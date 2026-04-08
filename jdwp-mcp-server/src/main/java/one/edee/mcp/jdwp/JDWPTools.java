package one.edee.mcp.jdwp;

import com.sun.jdi.*;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.StepRequest;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.Watcher;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Single MCP-facing surface for the JDWP debugger. Each `@McpTool` method is auto-discovered by the
 * Spring AI MCP framework and exposed as an invocable tool; the per-tool contracts (parameters,
 * behaviour, error formats) live in the `@McpTool(description=...)` strings and are NOT duplicated
 * in this JavaDoc.
 *
 * Architecture: every tool method is a thin orchestration layer over the underlying services
 * ({@link JDIConnectionService}, {@link BreakpointTracker}, {@link JdiExpressionEvaluator},
 * {@link EventHistory}, {@link WatcherManager}). Methods run on the MCP server's worker threads,
 * never on the JDI event listener thread.
 *
 * Error convention: tool methods never throw — they catch every exception, format a human-readable
 * message starting with `Error:`, `[ERROR]`, `[TIMEOUT]`, or `[INTERRUPTED]`, and return it as a
 * `String`. The MCP client is expected to surface these messages verbatim.
 */
@Slf4j
@Service
public class JDWPTools {

	private final JDIConnectionService jdiService;
	private final BreakpointTracker breakpointTracker;
	private final WatcherManager watcherManager;
	private final JdiExpressionEvaluator expressionEvaluator;
	private final EventHistory eventHistory;
	private final EvaluationGuard evaluationGuard;

	/**
	 * Default JDWP port. Resolved at class load via the `-DJVM_JDWP_PORT` system property
	 * (typically passed by the MCP client through `.mcp.json`); falls back to 5005 if unset.
	 */
	private static final int JVM_JDWP_PORT = Integer.parseInt(
		System.getProperty("JVM_JDWP_PORT", "5005")
	);

	/**
	 * Allow-list of package prefixes treated as "noise" by `isNoiseFrame`. Stack frames whose
	 * declaring class starts with any of these are collapsed in `jdwp_get_stack` and
	 * `jdwp_get_breakpoint_context` unless the caller passes `includeNoise=true`. Adding or
	 * removing entries directly affects what users see in stack traces.
	 */
	private static final String[] NOISE_PACKAGE_PREFIXES = {
		"org.junit.",
		"org.apache.maven.surefire.",
		"jdk.internal.reflect.",
		"java.lang.reflect.",
		"java.lang.invoke.",
		"sun.reflect.",
		"jdk.internal.invoke."
	};

	public JDWPTools(JDIConnectionService jdiService, BreakpointTracker breakpointTracker,
					 WatcherManager watcherManager, JdiExpressionEvaluator expressionEvaluator,
					 EventHistory eventHistory, EvaluationGuard evaluationGuard) {
		this.jdiService = jdiService;
		this.breakpointTracker = breakpointTracker;
		this.watcherManager = watcherManager;
		this.expressionEvaluator = expressionEvaluator;
		this.eventHistory = eventHistory;
		this.evaluationGuard = evaluationGuard;
	}

	@McpTool(description = "Connect to the JDWP server using configuration from .mcp.json")
	public String jdwp_connect() {
		String host = "localhost";
		int port = JVM_JDWP_PORT;

		try {
			return jdiService.connect(host, port);
		} catch (Exception e) {
			return String.format(
				"[ERROR] Connection failed to %s:%d\n\n" +
				"Make sure your JVM is running with JDWP enabled:\n" +
				"  -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:%d\n\n" +
				"Original error: %s",
				host, port, port, e.getMessage()
			);
		}
	}

	@McpTool(description = "Wait until a JVM is listening for JDWP and attach. Polls every 200ms until timeout. Use this to bootstrap a debug session without manually polling for the listener — call this after launching the target with `mvn test -Dmaven.surefire.debug` (or any other JVM started with `-agentlib:jdwp=...,suspend=y`).")
	public String jdwp_wait_for_attach(
			@McpToolParam(required = false, description = "Hostname (default: localhost)") String host,
			@McpToolParam(required = false, description = "JDWP port (default: 5005 — overridable via -DJVM_JDWP_PORT)") Integer port,
			@McpToolParam(required = false, description = "Maximum wait time in milliseconds (default: 30000)") Integer timeoutMs) {
		String resolvedHost = (host == null || host.isBlank()) ? "localhost" : host;
		int resolvedPort = (port != null) ? port : JVM_JDWP_PORT;
		int deadlineMs = (timeoutMs != null && timeoutMs > 0) ? timeoutMs : 30_000;

		long deadline = System.currentTimeMillis() + deadlineMs;
		int attempts = 0;
		String lastError = "none";

		while (System.currentTimeMillis() < deadline) {
			attempts++;
			try {
				String result = jdiService.connect(resolvedHost, resolvedPort);
				return String.format("%s (attached after %d attempt(s))", result, attempts);
			} catch (IllegalConnectorArgumentsException e) {
				// Configuration error — retrying will never make this succeed. Fail fast.
				return String.format("[ERROR] Invalid connector arguments for %s:%d — %s",
					resolvedHost, resolvedPort, e.getMessage());
			} catch (IOException e) {
				// Connection refused or similar — JVM not listening yet, retry.
				lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			} catch (Exception e) {
				// Could be a transient handshake race during JVM startup; retry until deadline.
				lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			}
			try {
				Thread.sleep(200);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return String.format("[INTERRUPTED] After %d attempt(s) waiting for %s:%d. Last error: %s",
					attempts, resolvedHost, resolvedPort, lastError);
			}
		}

		return String.format("[TIMEOUT] No JVM listening on %s:%d after %d attempt(s) over %dms.\n" +
			"Last error: %s\n\n" +
			"Make sure the target JVM was launched with -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:%d\n" +
			"For Maven tests in this repo, use: mvn test -Dmaven.surefire.debug",
			resolvedHost, resolvedPort, attempts, deadlineMs, lastError, resolvedPort);
	}

	@McpTool(description = "Disconnect from the JDWP server")
	public String jdwp_disconnect() {
		return jdiService.disconnect();
	}

	@McpTool(description = "Get JVM version information")
	public String jdwp_get_version() {
		try {
			VirtualMachine vm = jdiService.getVM();
			return String.format("VM: %s\nVersion: %s\nDescription: %s",
				vm.name(), vm.version(), vm.description());
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "List user threads in the JVM (status, frame count). System/JVM-internal threads (Reference Handler, Finalizer, surefire workers, etc.) are hidden unless includeSystemThreads=true.")
	public String jdwp_get_threads(
			@McpToolParam(required = false, description = "Include JVM/JDK/test-runner internal threads (default: false)") Boolean includeSystemThreads) {
		try {
			boolean includeSystem = includeSystemThreads != null && includeSystemThreads;
			VirtualMachine vm = jdiService.getVM();
			List<ThreadReference> all = vm.allThreads();
			List<ThreadReference> threads = includeSystem
				? all
				: all.stream().filter(t -> !ThreadFormatting.isJvmInternalThread(t)).toList();

			StringBuilder result = new StringBuilder();
			int hidden = all.size() - threads.size();
			result.append(String.format("Found %d thread(s)%s:\n\n",
				threads.size(),
				hidden > 0 ? String.format(" (%d system thread(s) hidden — pass includeSystemThreads=true to show)", hidden) : ""));

			for (int i = 0; i < threads.size(); i++) {
				ThreadReference thread = threads.get(i);
				result.append(String.format("Thread %d:\n", i));
				result.append(String.format("  ID: %d\n", thread.uniqueID()));
				result.append(String.format("  Name: %s\n", thread.name()));
				result.append(String.format("  Status: %s\n", ThreadFormatting.formatStatus(thread.status())));
				result.append(String.format("  Suspended: %s\n", thread.isSuspended()));

				if (thread.isSuspended()) {
					try {
						int frameCount = thread.frameCount();
						result.append(String.format("  Frames: %d\n", frameCount));
					} catch (IncompatibleThreadStateException ignored) {}
				}

				result.append("\n");
			}

			return result.toString();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Get the call stack for a specific thread. Defaults to top 10 user frames; junit/surefire/reflection internals are collapsed unless you pass includeNoise=true or raise maxFrames.")
	public String jdwp_get_stack(
			@McpToolParam(description = "Thread unique ID") long threadId,
			@McpToolParam(required = false, description = "Maximum frames to render (default: 10). Higher values include deeper call sites.") Integer maxFrames,
			@McpToolParam(required = false, description = "If true, do not collapse junit/maven/reflection frames (default: false)") Boolean includeNoise) {
		try {
			int limit = (maxFrames != null && maxFrames > 0) ? maxFrames : 10;
			boolean includeNoiseFrames = includeNoise != null && includeNoise;

			VirtualMachine vm = jdiService.getVM();
			ThreadReference thread = findThread(vm, threadId);
			if (thread == null) {
				return "Error: Thread not found with ID " + threadId;
			}

			if (!thread.isSuspended()) {
				return "Error: Thread is not suspended. Thread must be stopped at a breakpoint.";
			}

			List<StackFrame> frames = thread.frames();
			StringBuilder result = new StringBuilder();
			result.append(String.format("Stack trace for thread %d (%s) - %d frame(s) total:\n\n",
				threadId, thread.name(), frames.size()));

			appendUserFrames(result, frames, limit, includeNoiseFrames, "");

			return result.toString();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Appends a human-readable list of stack frames to {@code out}, collapsing
	 * junit/maven/reflection noise frames (unless {@code includeNoiseFrames} is true) and stopping
	 * after {@code limit} user frames have been rendered. Used by both {@link #jdwp_get_stack}
	 * and {@link #jdwp_get_breakpoint_context} to keep the rendering identical.
	 *
	 * @param out                 destination buffer
	 * @param frames              the full frame list (typically from {@link ThreadReference#frames()})
	 * @param limit               maximum number of user frames to render
	 * @param includeNoiseFrames  if true, render noise frames inline; if false, collapse them into a summary line
	 * @param indent              prefix prepended to each frame line (e.g. {@code "  "} for the breakpoint-context dump)
	 */
	private static void appendUserFrames(StringBuilder out, List<StackFrame> frames, int limit,
			boolean includeNoiseFrames, String indent) {
		int rendered = 0;
		int collapsedNoise = 0;
		for (int i = 0; i < frames.size() && rendered < limit; i++) {
			StackFrame frame = frames.get(i);
			Location location = frame.location();
			String declaringType = location.declaringType().name();

			if (!includeNoiseFrames && isNoiseFrame(declaringType)) {
				collapsedNoise++;
				continue;
			}

			String src;
			try {
				src = location.sourceName() + ":" + location.lineNumber();
			} catch (AbsentInformationException e) {
				src = "Unknown Source";
			}
			out.append(String.format("%s#%d %s.%s (%s)\n",
				indent, i, declaringType, location.method().name(), src));
			rendered++;
		}

		if (collapsedNoise > 0) {
			out.append(String.format("%s... %d junit/maven/reflection frame(s) collapsed (pass includeNoise=true to show)\n",
				indent, collapsedNoise));
		}
		if (rendered >= limit && frames.size() > limit + collapsedNoise) {
			int remaining = frames.size() - limit - collapsedNoise;
			out.append(String.format("%s... %d more frame(s) hidden (raise maxFrames to see them)\n",
				indent, remaining));
		}
	}

	/**
	 * Convenience overload that always collapses noise frames (used by
	 * {@code jdwp_get_breakpoint_context}, which never exposes the {@code includeNoise} option to
	 * the caller). See the 5-arg overload for the {@code indent} contract.
	 */
	private static void appendUserFrames(StringBuilder out, List<StackFrame> frames, int limit, String indent) {
		appendUserFrames(out, frames, limit, false, indent);
	}

	/** Returns true if the declaring class belongs to a known-noisy framework or JDK internal. */
	static boolean isNoiseFrame(String declaringType) {
		for (String prefix : NOISE_PACKAGE_PREFIXES) {
			if (declaringType.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	@McpTool(description = "Get local variables for a specific frame in a thread. Also includes 'this' (cached as Object#N) for instance methods.")
	public String jdwp_get_locals(
			@McpToolParam(description = "Thread unique ID") long threadId,
			@McpToolParam(description = "Frame index (0 = current frame)") int frameIndex) {
		try {
			VirtualMachine vm = jdiService.getVM();
			ThreadReference thread = findThread(vm, threadId);
			if (thread == null) {
				return "Error: Thread not found with ID " + threadId;
			}

			StackFrame frame = thread.frame(frameIndex);
			StringBuilder result = new StringBuilder();
			result.append(String.format("Local variables in frame %d:\n\n", frameIndex));

			// Synthetic 'this' entry for instance methods. Cached so the user can immediately call
			// jdwp_get_fields(<id>) without a separate eval round-trip.
			ObjectReference thisObj = frame.thisObject();
			if (thisObj != null) {
				result.append(String.format("this (%s) = %s\n",
					thisObj.referenceType().name(),
					formatValue(thisObj)));
			}

			Map<LocalVariable, Value> vars = frame.getValues(frame.visibleVariables());
			for (Map.Entry<LocalVariable, Value> entry : vars.entrySet()) {
				LocalVariable var = entry.getKey();
				Value value = entry.getValue();
				result.append(String.format("%s (%s) = %s\n",
					var.name(),
					var.typeName(),
					formatValue(value)));
			}

			return result.toString();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Get fields (properties) of an object by its object ID (obtained from jdwp_get_locals)")
	public String jdwp_get_fields(@McpToolParam(description = "Object unique ID") long objectId) {
		try {
			return jdiService.getObjectFields(objectId);
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Invoke toString() on a cached object to get its string representation")
	public String jdwp_to_string(
			@McpToolParam(description = "Object unique ID (from jdwp_get_locals or jdwp_get_fields)") long objectId,
			@McpToolParam(required = false, description = "Thread unique ID (must be suspended). If omitted, uses the last breakpoint thread.") Long threadId) {
		try {
			ObjectReference obj = jdiService.getCachedObject(objectId);
			if (obj == null) {
				return String.format("[ERROR] Object #%d not found in cache.\n" +
					"Use jdwp_get_locals() to discover objects in the current scope.", objectId);
			}

			VirtualMachine vm = jdiService.getVM();
			ThreadReference thread;
			if (threadId != null) {
				thread = findThread(vm, threadId);
				if (thread == null) return "Error: Thread not found with ID " + threadId;
			} else {
				thread = breakpointTracker.getLastBreakpointThread();
				if (thread == null) return "Error: No suspended thread available. Provide a threadId or hit a breakpoint first.";
			}

			if (!thread.isSuspended()) {
				return "Error: Thread is not suspended.";
			}

			Method toStringMethod = obj.referenceType()
				.methodsByName("toString", "()Ljava/lang/String;")
				.stream().findFirst().orElse(null);

			if (toStringMethod == null) {
				return String.format("Object #%d (%s): no toString() method found", objectId, obj.referenceType().name());
			}

			// Reentrancy guard: the invoked toString() may hit a user breakpoint. Mark the
			// thread as mid-evaluation so the listener suppresses the recursive hit rather
			// than re-suspending the very thread this invokeMethod is waiting on. Capture the
			// id up front so a thread death during toString() does not leak a guard entry.
			Value result;
			long guardedThreadId = thread.uniqueID();
			evaluationGuard.enter(guardedThreadId);
			try {
				result = obj.invokeMethod(thread, toStringMethod, Collections.emptyList(),
					ObjectReference.INVOKE_SINGLE_THREADED);
			} finally {
				evaluationGuard.exit(guardedThreadId);
			}

			if (result instanceof StringReference strRef) {
				return String.format("Object #%d (%s).toString() = \"%s\"",
					objectId, obj.referenceType().name(), strRef.value());
			}
			return String.format("Object #%d (%s).toString() = %s",
				objectId, obj.referenceType().name(), formatValue(result));
		} catch (Exception e) {
			return "Error invoking toString(): " + e.getMessage();
		}
	}

	@McpTool(description = "Evaluate a Java expression in the context of a suspended thread's stack frame")
	public String jdwp_evaluate_expression(
			@McpToolParam(description = "Thread unique ID") long threadId,
			@McpToolParam(description = "Java expression to evaluate (e.g., 'order.getTotal()', 'x + y', 'name.length()')") String expression,
			@McpToolParam(required = false, description = "Frame index (0 = current frame, default: 0)") Integer frameIndex) {
		try {
			VirtualMachine vm = jdiService.getVM();
			ThreadReference thread = findThread(vm, threadId);
			if (thread == null) return "Error: Thread not found with ID " + threadId;
			if (!thread.isSuspended()) return "Error: Thread is not suspended.";

			if (frameIndex == null) frameIndex = 0;

			expressionEvaluator.configureCompilerClasspath(thread);

			StackFrame frame = thread.frame(frameIndex);
			Value result = expressionEvaluator.evaluate(frame, expression);

			return String.format("Result: %s", formatValue(result));
		} catch (Exception e) {
			String msg = e.getMessage() != null ? e.getMessage() : e.toString();
			String enriched = enrichEvaluationError(msg, threadId, frameIndex);
			return "Error evaluating expression: " + enriched;
		}
	}

	@McpTool(description = "Evaluate a Java expression and compare its result against an expected value. Returns 'OK' on match, 'MISMATCH' with actual vs expected on failure. Comparison is string-based against the same formatting jdwp_evaluate_expression uses (so primitives auto-unbox, strings strip surrounding quotes).")
	public String jdwp_assert_expression(
			@McpToolParam(description = "Java expression (e.g., 'order.getTotal()', 'session.getRole()', 'list.size() == 5')") String expression,
			@McpToolParam(description = "Expected value (string-compared against the formatted expression result)") String expected,
			@McpToolParam(required = false, description = "Thread ID — defaults to the last breakpoint thread") Long threadId,
			@McpToolParam(required = false, description = "Frame index (default: 0)") Integer frameIndex) {
		try {
			int frame = (frameIndex != null) ? frameIndex : 0;
			ThreadReference thread;
			if (threadId != null) {
				thread = findThread(jdiService.getVM(), threadId);
				if (thread == null) {
					return "Error: Thread not found with ID " + threadId;
				}
			} else {
				thread = breakpointTracker.getLastBreakpointThread();
				if (thread == null) {
					return "Error: No current breakpoint thread. Pass threadId or hit a breakpoint first.";
				}
			}
			if (!thread.isSuspended()) {
				return "Error: Thread is not suspended.";
			}

			expressionEvaluator.configureCompilerClasspath(thread);
			StackFrame stackFrame = thread.frame(frame);
			Value result = expressionEvaluator.evaluate(stackFrame, expression);
			String actual = formatValue(result);

			// Strip wrapping quotes from formatted strings so users can pass `expected="hello"` or `expected=hello`.
			String compareActual = actual;
			if (compareActual.length() >= 2 && compareActual.startsWith("\"") && compareActual.endsWith("\"")) {
				compareActual = compareActual.substring(1, compareActual.length() - 1);
			}

			if (compareActual.equals(expected)) {
				return String.format("OK — %s = %s", expression, actual);
			}
			return String.format("MISMATCH — %s\n  expected: %s\n  actual:   %s", expression, expected, actual);
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Pure regex extraction of an unresolved/invisible field name from a JDT compile error message.
	 * Recognises three forms emitted by the Eclipse JDT compiler:
	 * <ul>
	 *   <li>{@code "X cannot be resolved"} — the wrapper class never saw the identifier</li>
	 *   <li>{@code "field a.b.c.X is not visible"} — the wrapper class saw the field but visibility failed</li>
	 *   <li>{@code "X is not visible"} — bare-name variant emitted by some compiler versions</li>
	 * </ul>
	 * Static + package-private so it can be unit-tested without a JDI connection.
	 *
	 * @param message the raw compiler error string
	 * @return the field/identifier name if any of the three patterns match, otherwise {@code null}
	 */
	@Nullable
	static String parseUnresolvedFieldName(@Nullable String message) {
		if (message == null) {
			return null;
		}
		Matcher m = Pattern
			.compile("([A-Za-z_][A-Za-z_0-9]*)\\s+cannot be resolved"
				+ "|field\\s+\\S*?\\.([A-Za-z_][A-Za-z_0-9]*)\\s+is not visible"
				+ "|([A-Za-z_][A-Za-z_0-9]*)\\s+is not visible")
			.matcher(message);
		if (!m.find()) {
			return null;
		}
		return m.group(1) != null ? m.group(1)
			: m.group(2) != null ? m.group(2)
			: m.group(3);
	}

	/**
	 * If the evaluator's error message looks like "X cannot be resolved" and X matches a field on
	 * {@code this}'s declared type, append a hint explaining the package-private wrapper-class
	 * limitation and pointing the user at {@code jdwp_get_fields(thisObjectId)}.
	 */
	private String enrichEvaluationError(String originalMessage, long threadId, Integer frameIndex) {
		String unresolved = parseUnresolvedFieldName(originalMessage);
		if (unresolved == null) {
			return originalMessage;
		}

		try {
			VirtualMachine vm = jdiService.getVM();
			ThreadReference thread = findThread(vm, threadId);
			if (thread == null || !thread.isSuspended()) {
				return originalMessage;
			}
			StackFrame frame = thread.frame(frameIndex != null ? frameIndex : 0);
			ObjectReference thisObj = frame.thisObject();
			if (thisObj == null) {
				return originalMessage;
			}
			Field field = thisObj.referenceType().allFields().stream()
				.filter(f -> f.name().equals(unresolved))
				.findFirst()
				.orElse(null);
			if (field == null) {
				return originalMessage;
			}
			jdiService.cacheObject(thisObj);
			String thisType = thisObj.referenceType().name();
			boolean classIsPublic = thisObj.referenceType() instanceof ClassType ct && ct.isPublic();
			boolean fieldIsPublic = field != null && field.isPublic();
			StringBuilder hint = new StringBuilder(originalMessage);
			hint.append("\n\nHint: '").append(unresolved).append("' is a field on this (")
				.append(thisType).append(", Object#").append(thisObj.uniqueID()).append(").");
			if (classIsPublic && fieldIsPublic) {
				hint.append(" Auto-rewrite should have handled this — please report the expression that triggered it.");
			} else {
				if (!classIsPublic) {
					hint.append(" The enclosing class is package-private (or non-public),");
				} else {
					hint.append(" The field is non-public,");
				}
				hint.append(" so the expression wrapper cannot reference it directly. Workaround:")
					.append(" call jdwp_get_fields(").append(thisObj.uniqueID()).append(")")
					.append(" to inspect the field, or jdwp_to_string for a quick view.");
			}
			return hint.toString();
		} catch (Exception probeFailure) {
			return originalMessage;
		}
	}

	@McpTool(description = "Resume execution of all threads in the VM")
	public String jdwp_resume() {
		try {
			VirtualMachine vm = jdiService.getVM();
			vm.resume();
			return "All threads resumed";
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Resume the VM and BLOCK until the next breakpoint, step, or exception event fires (or timeout). Returns the same info as jdwp_get_current_thread on success. Replaces the manual 'resume → poll → poll' choreography.")
	public String jdwp_resume_until_event(
			@McpToolParam(required = false, description = "Maximum wait time in milliseconds (default: 30000)") Integer timeoutMs) {
		int deadlineMs = (timeoutMs != null && timeoutMs > 0) ? timeoutMs : 30_000;
		try {
			VirtualMachine vm = jdiService.getVM();
			// Arm BEFORE resume so we don't race with a near-instant event firing.
			CountDownLatch latch = breakpointTracker.armNextEventLatch();
			vm.resume();

			boolean fired = latch.await(deadlineMs, TimeUnit.MILLISECONDS);
			if (!fired) {
				return String.format("[TIMEOUT] No event fired within %dms after resume.\n" +
					"The VM is still running. You can call jdwp_resume_until_event again with a larger timeout, " +
					"or use jdwp_get_threads to see live thread state.", deadlineMs);
			}

			BreakpointTracker.LastBreakpoint snapshot = breakpointTracker.getLastBreakpoint();
			if (snapshot == null || snapshot.thread() == null) {
				return "Event fired but no breakpoint thread recorded (this should not happen — check the listener logs).";
			}
			ThreadReference thread = snapshot.thread();
			Integer bpId = snapshot.id();
			return String.format("Event fired. Thread: %s (ID=%d, suspended=%s, frames=%d, breakpoint=%s)",
				thread.name(), thread.uniqueID(), thread.isSuspended(),
				thread.isSuspended() ? thread.frameCount() : -1,
				bpId != null ? String.valueOf(bpId) : "unknown");
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			return "Wait interrupted";
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Suspend a specific thread by its ID")
	public String jdwp_suspend_thread(@McpToolParam(description = "Thread unique ID") long threadId) {
		try {
			VirtualMachine vm = jdiService.getVM();
			ThreadReference thread = findThread(vm, threadId);
			if (thread == null) {
				return "Error: Thread not found with ID " + threadId;
			}

			if (thread.isSuspended()) {
				return String.format("Thread %d (%s) is already suspended", threadId, thread.name());
			}

			thread.suspend();
			return String.format("Thread %d (%s) suspended", threadId, thread.name());
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Resume a specific thread by its ID")
	public String jdwp_resume_thread(@McpToolParam(description = "Thread unique ID") long threadId) {
		try {
			VirtualMachine vm = jdiService.getVM();
			ThreadReference thread = findThread(vm, threadId);
			if (thread == null) {
				return "Error: Thread not found with ID " + threadId;
			}

			if (!thread.isSuspended()) {
				return String.format("Thread %d (%s) is not suspended", threadId, thread.name());
			}

			thread.resume();
			return String.format("Thread %d (%s) resumed", threadId, thread.name());
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Step over (execute current line and stop at next line)")
	public String jdwp_step_over(@McpToolParam(description = "Thread unique ID") long threadId) {
		return doStep(threadId, StepRequest.STEP_OVER, "over");
	}

	@McpTool(description = "Step into (enter method calls)")
	public String jdwp_step_into(@McpToolParam(description = "Thread unique ID") long threadId) {
		return doStep(threadId, StepRequest.STEP_INTO, "into");
	}

	@McpTool(description = "Step out (exit current method)")
	public String jdwp_step_out(@McpToolParam(description = "Thread unique ID") long threadId) {
		return doStep(threadId, StepRequest.STEP_OUT, "out");
	}

	@McpTool(description = "Set a local variable's value in a suspended thread's stack frame")
	public String jdwp_set_local(
			@McpToolParam(description = "Thread unique ID") long threadId,
			@McpToolParam(description = "Frame index (0 = current frame)") int frameIndex,
			@McpToolParam(description = "Variable name") String varName,
			@McpToolParam(description = "New value (e.g., '42', '3.14', 'true', '\"hello\"', 'null')") String value) {
		try {
			VirtualMachine vm = jdiService.getVM();
			ThreadReference thread = findThread(vm, threadId);
			if (thread == null) return "Error: Thread not found with ID " + threadId;
			if (!thread.isSuspended()) return "Error: Thread is not suspended.";

			StackFrame frame = thread.frame(frameIndex);
			LocalVariable localVar = frame.visibleVariableByName(varName);
			if (localVar == null) {
				return String.format("Error: Variable '%s' not found in frame %d", varName, frameIndex);
			}

			String parsedValue = value;
			if (localVar.typeName().equals("java.lang.String")
					&& value.length() >= 2
					&& value.startsWith("\"") && value.endsWith("\"")) {
				parsedValue = value.substring(1, value.length() - 1);
			}

			Value newValue = createJdiValue(vm, parsedValue, localVar.type());
			frame.setValue(localVar, newValue);

			return String.format("Variable '%s' set to %s in frame %d of thread %d", varName, value, frameIndex, threadId);
		} catch (Exception e) {
			return "Error setting variable: " + e.getMessage();
		}
	}

	@McpTool(description = "Set a field's value on a cached object")
	public String jdwp_set_field(
			@McpToolParam(description = "Object unique ID (from jdwp_get_locals or jdwp_get_fields)") long objectId,
			@McpToolParam(description = "Field name") String fieldName,
			@McpToolParam(description = "New value (e.g., '42', '3.14', 'true', '\"hello\"', 'null')") String value) {
		try {
			ObjectReference obj = jdiService.getCachedObject(objectId);
			if (obj == null) {
				return String.format("[ERROR] Object #%d not found in cache", objectId);
			}

			VirtualMachine vm = jdiService.getVM();
			Field field = obj.referenceType().fieldByName(fieldName);
			if (field == null) {
				return String.format("Error: Field '%s' not found on %s", fieldName, obj.referenceType().name());
			}

			String parsedValue = value;
			if (field.typeName().equals("java.lang.String")
					&& value.length() >= 2
					&& value.startsWith("\"") && value.endsWith("\"")) {
				parsedValue = value.substring(1, value.length() - 1);
			}

			Value newValue = createJdiValue(vm, parsedValue, field.type());
			obj.setValue(field, newValue);

			return String.format("Field '%s.%s' set to %s", obj.referenceType().name(), fieldName, value);
		} catch (Exception e) {
			return "Error setting field: " + e.getMessage();
		}
	}

	/** Performs a single-line step operation (over/into/out) on the given thread. */
	private String doStep(long threadId, int stepDepth, String label) {
		try {
			VirtualMachine vm = jdiService.getVM();
			ThreadReference thread = findThread(vm, threadId);
			if (thread == null) {
				return "Error: Thread not found with ID " + threadId;
			}

			if (!thread.isSuspended()) {
				return "Error: Thread is not suspended. Cannot step.";
			}

			EventRequestManager erm = vm.eventRequestManager();

			// Delete any existing StepRequests for this thread — JDI allows only one per thread
			erm.stepRequests().stream()
				.filter(sr -> sr.thread().equals(thread))
				.toList()
				.forEach(erm::deleteEventRequest);

			StepRequest stepRequest = erm.createStepRequest(thread, StepRequest.STEP_LINE, stepDepth);
			stepRequest.addCountFilter(1);
			stepRequest.enable();

			thread.resume();

			return String.format("Step %s executed on thread %d (%s)", label, threadId, thread.name());
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Set a breakpoint at a specific line in a class. Supports conditional breakpoints. If the class is not yet loaded, the breakpoint is deferred.")
	public String jdwp_set_breakpoint(
			@McpToolParam(description = "Fully qualified class name (e.g. 'com.example.MyClass')") String className,
			@McpToolParam(description = "Line number") int lineNumber,
			@McpToolParam(required = false, description = "Suspend policy: 'all' (default), 'thread', 'none'") String suspendPolicy,
			@McpToolParam(required = false, description = "Optional condition — only suspend when this evaluates to true (e.g., 'i > 100')") String condition) {
		// Track the pending ID outside the try so the catch can clean it up if locationsOfLine
		// (or any later step) throws after the pending entry has already been registered (Tier 1B).
		Integer pendingIdForCleanup = null;
		try {
			VirtualMachine vm = jdiService.getVM();
			EventRequestManager erm = vm.eventRequestManager();

			int jdiPolicy = EventRequest.SUSPEND_ALL;
			String policyLabel = "all";
			if (suspendPolicy != null) {
				switch (suspendPolicy.toLowerCase()) {
					case "thread" -> { jdiPolicy = EventRequest.SUSPEND_EVENT_THREAD; policyLabel = "thread"; }
					case "none" -> { jdiPolicy = EventRequest.SUSPEND_NONE; policyLabel = "none"; }
					case "all" -> { /* default */ }
					default -> {
						return String.format("Error: Invalid suspend policy '%s'. Use 'all', 'thread', or 'none'.", suspendPolicy);
					}
				}
			}

			String conditionInfo = (condition != null && !condition.isBlank())
				? String.format(", condition: %s", condition) : "";

			ReferenceType eagerType = jdiService.findOrForceLoadClass(className);
			List<ReferenceType> classes = eagerType != null ? List.of(eagerType) : List.of();

			if (classes.isEmpty()) {
				int pendingId = breakpointTracker.registerPendingBreakpoint(className, lineNumber, jdiPolicy, policyLabel);
				pendingIdForCleanup = pendingId;
				if (condition != null && !condition.isBlank()) {
					breakpointTracker.setCondition(pendingId, condition);
				}

				if (!breakpointTracker.hasClassPrepareRequest(className)) {
					ClassPrepareRequest cpr = erm.createClassPrepareRequest();
					cpr.addClassFilter(className);
					cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
					cpr.enable();
					breakpointTracker.registerClassPrepareRequest(className, cpr);
				}

				List<ReferenceType> recheck = vm.classesByName(className);
				if (!recheck.isEmpty()) {
					ReferenceType refType = recheck.get(0);
					List<Location> locations = refType.locationsOfLine(lineNumber);
					if (!locations.isEmpty()) {
						BreakpointRequest bpRequest = erm.createBreakpointRequest(locations.get(0));
						bpRequest.setSuspendPolicy(jdiPolicy);
						bpRequest.enable();
						breakpointTracker.promotePendingToActive(pendingId, bpRequest);
						return String.format("Breakpoint set at %s:%d (ID: %d, suspend: %s%s)",
							className, lineNumber, pendingId, policyLabel, conditionInfo);
					}
				}

				return String.format("Breakpoint deferred for %s:%d (ID: %d, suspend: %s%s). " +
					"Class not yet loaded — will activate automatically when the JVM loads it.",
					className, lineNumber, pendingId, policyLabel, conditionInfo);
			}

			ReferenceType refType = classes.get(0);
			List<Location> locations = refType.locationsOfLine(lineNumber);
			if (locations.isEmpty()) {
				return String.format("Error: No executable code found at line %d in class %s", lineNumber, className);
			}

			BreakpointRequest bpRequest = erm.createBreakpointRequest(locations.get(0));
			bpRequest.setSuspendPolicy(jdiPolicy);
			bpRequest.enable();

			int breakpointId = breakpointTracker.registerBreakpoint(bpRequest);
			if (condition != null && !condition.isBlank()) {
				breakpointTracker.setCondition(breakpointId, condition);
			}

			return String.format("Breakpoint set at %s:%d (ID: %d, suspend: %s%s)",
				className, lineNumber, breakpointId, policyLabel, conditionInfo);
		} catch (AbsentInformationException e) {
			cleanupOrphanPendingBreakpoint(pendingIdForCleanup);
			return "Error: No line number information available for this class. Compile with debug info (-g).";
		} catch (Exception e) {
			cleanupOrphanPendingBreakpoint(pendingIdForCleanup);
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Set a logpoint (non-stopping breakpoint) that evaluates an expression and logs the result without pausing execution. Supports an optional condition — the expression is only logged when the condition evaluates to true.")
	public String jdwp_set_logpoint(
			@McpToolParam(description = "Fully qualified class name") String className,
			@McpToolParam(description = "Line number") int lineNumber,
			@McpToolParam(description = "Java expression to evaluate and log (e.g., '\"x=\" + x', 'order.getTotal()')") String expression,
			@McpToolParam(required = false, description = "Optional condition — only log when this evaluates to true (e.g., 'i > 100')") String condition) {
		// Track the pending ID outside the try so the catch can clean it up if locationsOfLine
		// (or any later step) throws after the pending entry has already been registered (Tier 1B).
		Integer pendingIdForCleanup = null;
		try {
			VirtualMachine vm = jdiService.getVM();
			EventRequestManager erm = vm.eventRequestManager();

			int jdiPolicy = EventRequest.SUSPEND_EVENT_THREAD;

			String conditionInfo = (condition != null && !condition.isBlank())
				? String.format(", condition: %s", condition) : "";

			ReferenceType eagerType = jdiService.findOrForceLoadClass(className);
			List<ReferenceType> classes = eagerType != null ? List.of(eagerType) : List.of();

			if (classes.isEmpty()) {
				int pendingId = breakpointTracker.registerPendingBreakpoint(className, lineNumber, jdiPolicy, "thread");
				pendingIdForCleanup = pendingId;
				breakpointTracker.setLogpointExpression(pendingId, expression);
				if (condition != null && !condition.isBlank()) {
					breakpointTracker.setCondition(pendingId, condition);
				}

				if (!breakpointTracker.hasClassPrepareRequest(className)) {
					ClassPrepareRequest cpr = erm.createClassPrepareRequest();
					cpr.addClassFilter(className);
					cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
					cpr.enable();
					breakpointTracker.registerClassPrepareRequest(className, cpr);
				}

				List<ReferenceType> recheck = vm.classesByName(className);
				if (!recheck.isEmpty()) {
					ReferenceType refType = recheck.get(0);
					List<Location> locations = refType.locationsOfLine(lineNumber);
					if (!locations.isEmpty()) {
						BreakpointRequest bpRequest = erm.createBreakpointRequest(locations.get(0));
						bpRequest.setSuspendPolicy(jdiPolicy);
						bpRequest.enable();
						breakpointTracker.promotePendingToActive(pendingId, bpRequest);
						return String.format("Logpoint set at %s:%d (ID: %d, expression: %s%s)",
							className, lineNumber, pendingId, expression, conditionInfo);
					}
				}

				return String.format("Logpoint deferred for %s:%d (ID: %d, expression: %s%s). " +
					"Class not yet loaded — will activate when the JVM loads it.",
					className, lineNumber, pendingId, expression, conditionInfo);
			}

			ReferenceType refType = classes.get(0);
			List<Location> locations = refType.locationsOfLine(lineNumber);
			if (locations.isEmpty()) {
				return String.format("Error: No executable code at line %d in class %s", lineNumber, className);
			}

			BreakpointRequest bpRequest = erm.createBreakpointRequest(locations.get(0));
			bpRequest.setSuspendPolicy(jdiPolicy);
			bpRequest.enable();

			int breakpointId = breakpointTracker.registerBreakpoint(bpRequest);
			breakpointTracker.setLogpointExpression(breakpointId, expression);
			if (condition != null && !condition.isBlank()) {
				breakpointTracker.setCondition(breakpointId, condition);
			}

			return String.format("Logpoint set at %s:%d (ID: %d, expression: %s%s)",
				className, lineNumber, breakpointId, expression, conditionInfo);
		} catch (AbsentInformationException e) {
			cleanupOrphanPendingBreakpoint(pendingIdForCleanup);
			return "Error: No line number information available. Compile with debug info (-g).";
		} catch (Exception e) {
			cleanupOrphanPendingBreakpoint(pendingIdForCleanup);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Removes a pending breakpoint that was registered before a downstream JDI call threw.
	 * No-op when {@code pendingId} is null (no pending entry to clean up). Used by
	 * {@link #jdwp_set_breakpoint} and {@link #jdwp_set_logpoint} to avoid orphaning
	 * pending entries on {@code AbsentInformationException} from {@code locationsOfLine}.
	 */
	private void cleanupOrphanPendingBreakpoint(@Nullable Integer pendingId) {
		if (pendingId != null) {
			breakpointTracker.removePendingBreakpoint(pendingId);
		}
	}

	@McpTool(description = "Remove a breakpoint at a specific line in a class")
	public String jdwp_clear_breakpoint(
			@McpToolParam(description = "Fully qualified class name") String className,
			@McpToolParam(description = "Line number") int lineNumber) {
		try {
			VirtualMachine vm = jdiService.getVM();
			EventRequestManager erm = vm.eventRequestManager();

			// Find the class
			List<ReferenceType> classes = vm.classesByName(className);

			if (classes.isEmpty()) {
				// Class not loaded — check pending breakpoints
				int removedPending = 0;
				for (Map.Entry<Integer, BreakpointTracker.PendingBreakpoint> entry :
						breakpointTracker.getAllPendingBreakpoints().entrySet()) {
					BreakpointTracker.PendingBreakpoint pb = entry.getValue();
					if (pb.getClassName().equals(className) && pb.getLineNumber() == lineNumber) {
						breakpointTracker.removePendingBreakpoint(entry.getKey());
						removedPending++;
					}
				}
				if (removedPending > 0) {
					return String.format("Removed %d pending breakpoint(s) at %s:%d", removedPending, className, lineNumber);
				}
				return String.format("No breakpoint found at %s:%d (class not loaded)%s",
					className, lineNumber, exceptionBreakpointHint(className));
			}

			ReferenceType refType = classes.get(0);

			// Find location
			List<Location> locations = refType.locationsOfLine(lineNumber);
			if (locations.isEmpty()) {
				return String.format("Error: No code at line %d in class %s%s",
					lineNumber, className, exceptionBreakpointHint(className));
			}

			Location location = locations.get(0);

			// Find and delete matching breakpoint requests (copy list to avoid ConcurrentModificationException)
			List<BreakpointRequest> breakpoints = new ArrayList<>(erm.breakpointRequests());
			int removed = 0;
			for (BreakpointRequest bp : breakpoints) {
				if (bp.location().equals(location)) {
					Integer bpId = breakpointTracker.findIdByRequest(bp);
					if (bpId != null) {
						watcherManager.deleteWatchersForBreakpoint(bpId);
					}
					breakpointTracker.unregisterByRequest(bp);
					erm.deleteEventRequest(bp);
					removed++;
				}
			}

			if (removed == 0) {
				return String.format("No breakpoint found at %s:%d%s",
					className, lineNumber, exceptionBreakpointHint(className));
			}

			return String.format("Removed %d breakpoint(s) at %s:%d", removed, className, lineNumber);
		} catch (AbsentInformationException e) {
			return "Error: No line number information available for this class";
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Returns a UX hint pointing the user at {@link #jdwp_clear_exception_breakpoint} when the
	 * given class name matches an active or pending exception breakpoint, otherwise an empty
	 * string. Used by {@link #jdwp_clear_breakpoint} to disambiguate the "no breakpoint found"
	 * message when the user has confused the two clear tools.
	 */
	private String exceptionBreakpointHint(String className) {
		boolean matchesActive = breakpointTracker.getAllExceptionBreakpoints().values().stream()
			.anyMatch(info -> className.equals(info.getExceptionClass()));
		boolean matchesPending = breakpointTracker.getAllPendingExceptionBreakpoints().values().stream()
			.anyMatch(p -> className.equals(p.getExceptionClass()));
		if (matchesActive || matchesPending) {
			return " — for exception breakpoints, use jdwp_clear_exception_breakpoint(id) "
				+ "(see jdwp_list_exception_breakpoints)";
		}
		return "";
	}

	@McpTool(description = "List all breakpoints (active, pending, and failed) set by this MCP server")
	public String jdwp_list_breakpoints() {
		try {
			Map<Integer, BreakpointRequest> active = breakpointTracker.getAllBreakpoints();
			Map<Integer, BreakpointTracker.PendingBreakpoint> pending = breakpointTracker.getAllPendingBreakpoints();

			if (active.isEmpty() && pending.isEmpty()) {
				return "No breakpoints set";
			}

			StringBuilder result = new StringBuilder();
			int i = 1;

			if (!active.isEmpty()) {
				result.append(String.format("Active breakpoints: %d\n\n", active.size()));

				for (Map.Entry<Integer, BreakpointRequest> entry : active.entrySet()) {
					int id = entry.getKey();
					BreakpointRequest bp = entry.getValue();
					Location loc = bp.location();

					String policyStr = switch (bp.suspendPolicy()) {
						case EventRequest.SUSPEND_ALL -> "all";
						case EventRequest.SUSPEND_EVENT_THREAD -> "thread";
						case EventRequest.SUSPEND_NONE -> "none";
						default -> "unknown";
					};
					result.append(String.format("Breakpoint %d (ID: %d):\n", i++, id));
					result.append(String.format("  Class: %s\n", loc.declaringType().name()));
					result.append(String.format("  Method: %s\n", loc.method().name()));
					result.append(String.format("  Line: %d\n", loc.lineNumber()));
					result.append(String.format("  Enabled: %s\n", bp.isEnabled()));
					result.append(String.format("  Suspend: %s\n", policyStr));
					String cond = breakpointTracker.getCondition(id);
					if (cond != null) {
						result.append(String.format("  Condition: %s\n", cond));
					}
					String logExpr = breakpointTracker.getLogpointExpression(id);
					if (logExpr != null) {
						result.append(String.format("  Type: LOGPOINT\n  Expression: %s\n", logExpr));
					}
					result.append("\n");
				}
			}

			if (!pending.isEmpty()) {
				result.append(String.format("Pending breakpoints: %d\n\n", pending.size()));

				for (Map.Entry<Integer, BreakpointTracker.PendingBreakpoint> entry : pending.entrySet()) {
					int id = entry.getKey();
					BreakpointTracker.PendingBreakpoint pb = entry.getValue();
					result.append(String.format("Breakpoint %d (ID: %d):\n", i++, id));
					result.append(String.format("  Class: %s\n", pb.getClassName()));
					result.append(String.format("  Line: %d\n", pb.getLineNumber()));
					result.append(String.format("  Suspend: %s\n", pb.getSuspendPolicyLabel()));
					if (pb.getFailureReason() != null) {
						result.append(String.format("  Status: FAILED (%s)\n\n", pb.getFailureReason()));
					} else {
						result.append("  Status: PENDING (class not yet loaded)\n\n");
					}
				}
			}

			return result.toString();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Clear a specific breakpoint by its ID (from jdwp_list_breakpoints)")
	public String jdwp_clear_breakpoint_by_id(@McpToolParam(description = "Breakpoint ID to clear") int breakpointId) {
		try {
			boolean removed = breakpointTracker.removeBreakpoint(breakpointId);
			if (!removed) {
				return String.format("Breakpoint %d not found", breakpointId);
			}

			int watchersRemoved = watcherManager.deleteWatchersForBreakpoint(breakpointId);
			String msg = String.format("Breakpoint %d cleared successfully", breakpointId);
			if (watchersRemoved > 0) {
				msg += String.format(" (%d associated watcher(s) also removed)", watchersRemoved);
			}
			return msg;
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Get recent JDWP events (breakpoints, steps, exceptions, logpoints, etc.)")
	public String jdwp_get_events(@McpToolParam(required = false, description = "Number of recent events to retrieve (default: 20, max: 100)") Integer count) {
		try {
			if (count == null || count <= 0) count = 20;
			if (count > 100) count = 100;

			List<EventHistory.DebugEvent> events = eventHistory.getRecent(count);

			if (events.isEmpty()) {
				return "No events recorded yet.\n\n" +
					"Events are captured automatically when connected:\n" +
					"  - Breakpoint hits\n" +
					"  - Step completions\n" +
					"  - Exception throws\n" +
					"  - Logpoint evaluations\n" +
					"  - VM lifecycle events";
			}

			StringBuilder result = new StringBuilder();
			result.append(String.format("Recent events (%d of %d total):\n\n", events.size(), eventHistory.size()));

			for (int i = 0; i < events.size(); i++) {
				EventHistory.DebugEvent event = events.get(i);
				result.append(String.format("%d. [%s] %s (%s)\n",
					i + 1, event.type(), event.summary(),
					event.timestamp().toString().substring(11, 23)));
			}

			return result.toString();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Clear the JDWP event history")
	public String jdwp_clear_events() {
		eventHistory.clear();
		return "Event history cleared";
	}

	@McpTool(description = "Set a breakpoint that triggers when a specific exception is thrown. If the exception class is not yet loaded, the breakpoint is deferred and will activate automatically when the JVM loads it.")
	public String jdwp_set_exception_breakpoint(
			@McpToolParam(description = "Exception class name (e.g., 'java.lang.NullPointerException', 'java.lang.Exception' for all)") String exceptionClass,
			@McpToolParam(required = false, description = "Break on caught exceptions (default: true)") Boolean caught,
			@McpToolParam(required = false, description = "Break on uncaught exceptions (default: true)") Boolean uncaught) {
		try {
			if (caught == null) caught = true;
			if (uncaught == null) uncaught = true;

			VirtualMachine vm = jdiService.getVM();
			EventRequestManager erm = vm.eventRequestManager();

			// Try eager: check classesByName, and if empty, force-load via Class.forName
			ReferenceType eagerType = jdiService.findOrForceLoadClass(exceptionClass);

			if (eagerType == null) {
				// Class not loadable yet — defer
				int pendingId = breakpointTracker.registerPendingExceptionBreakpoint(exceptionClass, caught, uncaught);

				if (!breakpointTracker.hasClassPrepareRequest(exceptionClass)) {
					ClassPrepareRequest cpr = erm.createClassPrepareRequest();
					cpr.addClassFilter(exceptionClass);
					cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
					cpr.enable();
					breakpointTracker.registerClassPrepareRequest(exceptionClass, cpr);
				}

				return String.format("Exception breakpoint deferred (ID: %d)\n  Exception: %s\n  Caught: %s\n  Uncaught: %s\n" +
					"Class not yet loaded — will activate automatically when the JVM loads it.",
					pendingId, exceptionClass, caught, uncaught);
			}

			ReferenceType refType = eagerType;
			ExceptionRequest exReq = erm.createExceptionRequest(refType, caught, uncaught);
			exReq.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
			exReq.enable();

			int id = breakpointTracker.registerExceptionBreakpoint(exReq, exceptionClass, caught, uncaught);

			return String.format("Exception breakpoint set (ID: %d)\n  Exception: %s\n  Caught: %s\n  Uncaught: %s",
				id, exceptionClass, caught, uncaught);
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Remove an exception breakpoint by its ID")
	public String jdwp_clear_exception_breakpoint(@McpToolParam(description = "Exception breakpoint ID") int breakpointId) {
		try {
			boolean removed = breakpointTracker.removeExceptionBreakpoint(breakpointId);
			if (!removed) {
				return String.format("Exception breakpoint %d not found", breakpointId);
			}
			return String.format("Exception breakpoint %d cleared", breakpointId);
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "List all exception breakpoints (active and pending)")
	public String jdwp_list_exception_breakpoints() {
		try {
			Map<Integer, BreakpointTracker.ExceptionBreakpointInfo> active = breakpointTracker.getAllExceptionBreakpoints();
			Map<Integer, BreakpointTracker.PendingExceptionBreakpoint> pending = breakpointTracker.getAllPendingExceptionBreakpoints();

			if (active.isEmpty() && pending.isEmpty()) {
				return "No exception breakpoints set.\n\nUse jdwp_set_exception_breakpoint() to catch exceptions.";
			}

			StringBuilder result = new StringBuilder();
			int i = 1;

			if (!active.isEmpty()) {
				result.append(String.format("Active exception breakpoints: %d\n\n", active.size()));
				for (Map.Entry<Integer, BreakpointTracker.ExceptionBreakpointInfo> entry : active.entrySet()) {
					BreakpointTracker.ExceptionBreakpointInfo info = entry.getValue();
					result.append(String.format("%d. (ID: %d) %s — caught: %s, uncaught: %s\n",
						i++, entry.getKey(), info.getExceptionClass(), info.isCaught(), info.isUncaught()));
				}
			}

			if (!pending.isEmpty()) {
				if (!active.isEmpty()) result.append("\n");
				result.append(String.format("Pending exception breakpoints: %d\n\n", pending.size()));
				for (Map.Entry<Integer, BreakpointTracker.PendingExceptionBreakpoint> entry : pending.entrySet()) {
					BreakpointTracker.PendingExceptionBreakpoint pb = entry.getValue();
					String status = pb.getFailureReason() != null
						? " [FAILED: " + pb.getFailureReason() + "]" : " [PENDING]";
					result.append(String.format("%d. (ID: %d) %s — caught: %s, uncaught: %s%s\n",
						i++, entry.getKey(), pb.getExceptionClass(), pb.isCaught(), pb.isUncaught(), status));
				}
			}

			return result.toString();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Clear ALL session state (breakpoints, exception breakpoints, watchers, object cache, event history) WITHOUT disconnecting from the target VM. Use between sequential debugging scenarios against the same long-running target.")
	public String jdwp_reset() {
		int activeBp = breakpointTracker.getAllBreakpoints().size();
		int pendingBp = breakpointTracker.getAllPendingBreakpoints().size();
		int activeExBp = breakpointTracker.getAllExceptionBreakpoints().size();
		int pendingExBp = breakpointTracker.getAllPendingExceptionBreakpoints().size();
		int watchers = watcherManager.getAllWatchers().size();
		int events = eventHistory.size();

		// VM-dependent path first: try to delete the live JDI requests via the EventRequestManager.
		// If the VM is unreachable (external disconnect, crash) we fall back to a pure in-memory
		// reset so the server-local state still gets cleared (FINDING-10).
		boolean vmCleared = false;
		try {
			VirtualMachine vm = jdiService.getVM();
			breakpointTracker.clearAll(vm.eventRequestManager());
			vmCleared = true;
		} catch (Exception e) {
			breakpointTracker.reset();
		}

		// These clears are server-local and must happen regardless of VM liveness.
		watcherManager.clearAll();
		jdiService.clearObjectCache();
		eventHistory.clear();

		String header = vmCleared
			? "Reset complete (VM connection preserved)."
			: "Reset complete (VM unreachable — server-local state cleared).";
		return String.format(
			"%s\n" +
			"  Breakpoints cleared:           %d active + %d pending\n" +
			"  Exception breakpoints cleared: %d active + %d pending\n" +
			"  Watchers cleared:              %d\n" +
			"  Event history cleared:         %d entries\n" +
			"  Object cache cleared.",
			header, activeBp, pendingBp, activeExBp, pendingExBp, watchers, events);
	}

	@McpTool(description = "Clear ALL breakpoints (active and pending) set by this MCP server")
	public String jdwp_clear_all_breakpoints() {
		try {
			int activeCount = breakpointTracker.getAllBreakpoints().size();
			int pendingCount = breakpointTracker.getAllPendingBreakpoints().size();
			int exceptionCount = breakpointTracker.getAllExceptionBreakpoints().size()
				+ breakpointTracker.getAllPendingExceptionBreakpoints().size();
			int totalCount = activeCount + pendingCount;
			if (totalCount == 0 && exceptionCount == 0) {
				return "No breakpoints to clear";
			}

			VirtualMachine vm = jdiService.getVM();
			breakpointTracker.clearAll(vm.eventRequestManager());
			watcherManager.clearAll();

			String msg = String.format("Cleared %d breakpoint(s) (%d active, %d pending) and all associated watchers.",
				totalCount, activeCount, pendingCount);
			if (exceptionCount > 0) {
				msg += String.format(" Also cleared %d exception breakpoint(s).", exceptionCount);
			}
			return msg;
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	private String formatValue(Value value) {
		return jdiService.formatFieldValue(value);
	}

	@McpTool(description = "One-shot debugging context at the current breakpoint: thread, top frames, locals at frame 0, and 'this' field dump. Use this instead of the four-call sequence (get_current_thread → get_stack → get_locals → get_fields(this)) at every BP hit.")
	public String jdwp_get_breakpoint_context(
			@McpToolParam(required = false, description = "Max stack frames to render (default: 5). Junit/maven/reflection frames are always collapsed.") Integer maxFrames,
			@McpToolParam(required = false, description = "Include the 'this' field dump (default: true)") Boolean includeThisFields) {
		int frameLimit = (maxFrames != null && maxFrames > 0) ? maxFrames : 5;
		boolean includeThis = includeThisFields == null || includeThisFields;

		try {
			BreakpointTracker.LastBreakpoint snapshot = breakpointTracker.getLastBreakpoint();
			if (snapshot == null || snapshot.thread() == null) {
				return "No current breakpoint detected. Set a breakpoint and trigger it first.";
			}
			ThreadReference thread = snapshot.thread();
			if (!thread.isSuspended()) {
				return String.format("Thread %s (ID=%d) is no longer suspended.", thread.name(), thread.uniqueID());
			}

			Integer bpId = snapshot.id();
			StringBuilder sb = new StringBuilder();
			sb.append("=== Breakpoint Context ===\n");
			sb.append(String.format("Thread: %s (ID=%d, breakpoint=%s)\n\n",
				thread.name(), thread.uniqueID(),
				bpId != null ? String.valueOf(bpId) : "unknown"));

			// Top frames (junit/maven/reflection collapsed via the same noise list as jdwp_get_stack)
			List<StackFrame> frames = thread.frames();
			sb.append(String.format("--- Top frames (showing up to %d, %d total) ---\n", frameLimit, frames.size()));
			appendUserFrames(sb, frames, frameLimit, "  ");

			if (frames.isEmpty()) {
				sb.append("\n(thread has no frames — possibly suspended at VM startup before any user code)\n");
				return sb.toString();
			}
			sb.append("\n");

			// Locals at frame 0 (with synthetic 'this' the same way jdwp_get_locals does it)
			StackFrame frame0 = frames.get(0);
			sb.append("--- Locals at frame 0 ---\n");
			ObjectReference thisObj = frame0.thisObject();
			if (thisObj != null) {
				sb.append(String.format("  this (%s) = %s\n",
					thisObj.referenceType().name(), formatValue(thisObj)));
			}
			try {
				Map<LocalVariable, Value> vars = frame0.getValues(frame0.visibleVariables());
				if (vars.isEmpty() && thisObj == null) {
					sb.append("  (none)\n");
				}
				for (Map.Entry<LocalVariable, Value> e : vars.entrySet()) {
					sb.append(String.format("  %s (%s) = %s\n",
						e.getKey().name(), e.getKey().typeName(), formatValue(e.getValue())));
				}
			} catch (AbsentInformationException e) {
				sb.append("  (no debug info — compile with -g)\n");
			}
			sb.append("\n");

			// 'this' field dump — instance fields only. Static fields are class-level state and
			// would clutter the dump (e.g. constant tables like PRICE_CATALOG showing up under
			// every instance) without telling the user anything about THIS object.
			if (includeThis && thisObj != null) {
				jdiService.cacheObject(thisObj);
				sb.append(String.format("--- this fields (Object#%d, %s) ---\n",
					thisObj.uniqueID(), thisObj.referenceType().name()));
				List<Field> instanceFields = thisObj.referenceType().allFields().stream()
					.filter(f -> !f.isStatic())
					.toList();
				if (instanceFields.isEmpty()) {
					sb.append("  (no instance fields)\n");
				} else {
					for (Field field : instanceFields) {
						Value v = thisObj.getValue(field);
						sb.append(String.format("  %s %s = %s\n", field.typeName(), field.name(), formatValue(v)));
					}
				}
			} else if (includeThis) {
				sb.append("--- this --- (static method, no this)\n");
			}

			return sb.toString();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Get the thread ID of the current breakpoint")
	public String jdwp_get_current_thread() {
		try {
			BreakpointTracker.LastBreakpoint snapshot = breakpointTracker.getLastBreakpoint();
			if (snapshot == null || snapshot.thread() == null) {
				return "No current breakpoint detected. Set a breakpoint and trigger it first.";
			}
			ThreadReference thread = snapshot.thread();
			Integer bpId = snapshot.id();
			return String.format("Current thread: %s (ID=%d, suspended=%s, frames=%d, breakpoint=%s)",
				thread.name(), thread.uniqueID(), thread.isSuspended(),
				thread.isSuspended() ? thread.frameCount() : -1,
				bpId != null ? String.valueOf(bpId) : "unknown");
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	// ========================================
	// Watcher Management Tools
	// ========================================

	/**
	 * Attach a watcher to a breakpoint to evaluate a single expression when the breakpoint is hit
	 */
	@McpTool(description = "Attach a watcher to a breakpoint to evaluate a Java expression when hit. Returns the watcher ID.")
	public String jdwp_attach_watcher(
			@McpToolParam(description = "Breakpoint request ID (from jdwp_list_breakpoints)") int breakpointId,
			@McpToolParam(description = "Descriptive label for this watcher (e.g., 'Trace entity ID', 'Check user name')") String label,
			@McpToolParam(description = "Java expression to evaluate (e.g., 'entity.id', 'user.name', 'items.size()')") String expression) {
		try {
			if (expression == null || expression.trim().isEmpty()) {
				return "Error: No expression provided";
			}

			// Create the watcher
			String watcherId = watcherManager.createWatcher(label, breakpointId, expression.trim());

			return String.format(
				"✓ Watcher attached successfully\n\n" +
				"  Watcher ID: %s\n" +
				"  Label: %s\n" +
				"  Breakpoint: %d\n" +
				"  Expression: %s\n\n" +
				"The watcher will evaluate this expression when breakpoint %d is hit.\n" +
				"Use jdwp_detach_watcher(watcherId) to remove it.",
				watcherId, label, breakpointId, expression.trim(), breakpointId
			);

		} catch (Exception e) {
			log.error("[Watcher] Error attaching watcher", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Detach a watcher by its ID
	 */
	@McpTool(description = "Detach a watcher from its breakpoint using the watcher ID")
	public String jdwp_detach_watcher(@McpToolParam(description = "Watcher ID (UUID returned by jdwp_attach_watcher)") String watcherId) {
		try {
			Watcher watcher = watcherManager.getWatcher(watcherId);
			if (watcher == null) {
				return String.format(
					"Error: Watcher '%s' not found.\n\nUse jdwp_list_all_watchers() to see active watchers.", watcherId
				);
			}

			String label = watcher.getLabel();
			int breakpointId = watcher.getBreakpointId();

			boolean deleted = watcherManager.deleteWatcher(watcherId);
			if (deleted) {
				return String.format("✓ Watcher detached: '%s' (ID: %s, Breakpoint: %d)", label, watcherId, breakpointId);
			} else {
				return "Error: Failed to detach watcher";
			}

		} catch (Exception e) {
			log.error("[Watcher] Error detaching watcher", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * List all watchers attached to a specific breakpoint
	 */
	@McpTool(description = "List all watchers attached to a specific breakpoint")
	public String jdwp_list_watchers_for_breakpoint(@McpToolParam(description = "Breakpoint request ID") int breakpointId) {
		try {
			List<Watcher> watchers = watcherManager.getWatchersForBreakpoint(breakpointId);

			if (watchers.isEmpty()) {
				return String.format("No watchers attached to breakpoint %d.\n\n" +
					"Use jdwp_attach_watcher(%d, \"label\", \"expression\") to attach a watcher.", breakpointId, breakpointId);
			}

			StringBuilder result = new StringBuilder();
			result.append(String.format("Watchers for breakpoint %d (%d total):\n\n", breakpointId, watchers.size()));

			for (int i = 0; i < watchers.size(); i++) {
				Watcher w = watchers.get(i);
				result.append(String.format("%d. [%s] %s\n", i + 1, w.getId().substring(0, 8), w.getLabel()));
				result.append(String.format("   Expression: %s\n\n", w.getExpression()));
			}

			return result.toString();

		} catch (Exception e) {
			log.error("[Watcher] Error listing watchers for breakpoint", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * List all active watchers
	 */
	@McpTool(description = "List all active watchers across all breakpoints")
	public String jdwp_list_all_watchers() {
		try {
			List<Watcher> watchers = watcherManager.getAllWatchers();

			if (watchers.isEmpty()) {
				return "No watchers configured.\n\n" +
					"Use jdwp_attach_watcher(breakpointId, label, expression) to create a watcher.";
			}

			Map<String, Object> stats = watcherManager.getStats();
			StringBuilder result = new StringBuilder();
			result.append(String.format("Active watchers: %d across %d breakpoints\n\n",
				(Integer) stats.get("totalWatchers"), (Integer) stats.get("breakpointsWithWatchers")));

			// Group by breakpoint
			Map<Integer, List<Watcher>> grouped = watchers.stream()
				.collect(Collectors.groupingBy(Watcher::getBreakpointId));

			for (Map.Entry<Integer, List<Watcher>> entry : grouped.entrySet()) {
				result.append(String.format("Breakpoint %d (%d watchers):\n", entry.getKey(), entry.getValue().size()));
				for (Watcher w : entry.getValue()) {
					result.append(String.format("  • [%s] %s\n", w.getId().substring(0, 8), w.getLabel()));
					result.append(String.format("    Expression: %s\n", w.getExpression()));
				}
				result.append("\n");
			}

			return result.toString();

		} catch (Exception e) {
			log.error("[Watcher] Error listing all watchers", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Clear all watchers
	 */
	@McpTool(description = "Clear all watchers from all breakpoints")
	public String jdwp_clear_all_watchers() {
		try {
			int count = watcherManager.getAllWatchers().size();
			watcherManager.clearAll();
			return String.format("✓ Cleared %d watcher(s)", count);

		} catch (Exception e) {
			log.error("[Watcher] Error clearing watchers", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Evaluate watchers on a suspended thread's stack.
	 * Can operate in two scopes:
	 * - 'current_frame': (Default & Recommended) Evaluates watchers only for the breakpoint
	 *   that caused the suspension. Fast and precise.
	 * - 'full_stack': Scans every frame of the stack to find any location matching any breakpoint
	 *   with a watcher. Powerful but slower.
	 */
	@McpTool(description = "Evaluate watchers on a suspended thread's stack based on a scope")
	public String jdwp_evaluate_watchers(
			@McpToolParam(description = "Thread unique ID") long threadId,
			@McpToolParam(description = "Evaluation scope: 'current_frame' (default) or 'full_stack'") String scope,
			@McpToolParam(description = "Optional: The specific breakpoint ID that was hit. If provided, evaluation is much faster for 'current_frame' scope") Integer breakpointId) {
		try {
			VirtualMachine vm = jdiService.getVM();
			ThreadReference thread = findThread(vm, threadId);
			if (thread == null) {
				return "Error: Thread not found with ID " + threadId;
			}

			if (!thread.isSuspended()) {
				return String.format("[ERROR] Thread %d is NOT suspended\n\n" +
					"Thread must be stopped at a breakpoint to evaluate watchers.", threadId);
			}

			// CRITICAL: Configure compiler classpath BEFORE any expression evaluation
			// This must be done here (not inside evaluate()) to avoid nested JDI calls
			expressionEvaluator.configureCompilerClasspath(thread);

			if (scope == null || scope.isBlank()) {
				scope = "current_frame";
			}

			StringBuilder result = new StringBuilder();
			result.append(String.format("=== Watcher Evaluation for Thread %d (Scope: %s) ===\n\n", threadId, scope));
			result.append(String.format("Thread: %s (frames: %d)\n\n", thread.name(), thread.frameCount()));

			int watchersEvaluated;
			if ("full_stack".equalsIgnoreCase(scope)) {
				watchersEvaluated = evaluateWatchersFullStack(thread, result);
			} else {
				watchersEvaluated = evaluateWatchersCurrentFrame(thread, breakpointId, result);
			}

			if (watchersEvaluated == 0) {
				result.append("No watchers found or evaluated for the given scope.\n");
			} else {
				result.append(String.format("Total: Evaluated %d expression(s)\n", watchersEvaluated));
			}

			return result.toString();

		} catch (Exception e) {
			log.error("[Watcher] Error evaluating watchers", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Evaluates watchers for the current (topmost) stack frame only. If the breakpoint ID is not provided,
	 * it is resolved by matching the frame's location against the breakpoint location map.
	 *
	 * @param thread        the suspended thread whose frame 0 will be inspected
	 * @param breakpointId  the breakpoint ID to look up watchers for, or null to resolve from location
	 * @param result        accumulator for formatted evaluation output
	 * @return number of watchers successfully evaluated
	 */
	private int evaluateWatchersCurrentFrame(
			ThreadReference thread, Integer breakpointId, StringBuilder result) throws Exception {
		if (thread.frameCount() == 0) return 0;

		StackFrame frame = thread.frame(0);
		Location location = frame.location();
		int watchersEvaluated = 0;

		// If breakpointId is not provided, we must resolve it from location
		if (breakpointId == null) {
			Map<String, Integer> locationMap = breakpointTracker.getBreakpointLocationMap();
			String locationKey = location.declaringType().name() + ":" + location.lineNumber();
			breakpointId = locationMap.get(locationKey);
		}

		if (breakpointId == null) {
			result.append("Could not find a matching breakpoint for the current location.\n");
			result.append(String.format("Current location: %s:%d\n", location.declaringType().name(), location.lineNumber()));
			return 0;
		}

		List<Watcher> watchers = watcherManager.getWatchersForBreakpoint(breakpointId);
		if (watchers.isEmpty()) {
			return 0;
		}

		result.append(String.format("─── Current Frame #0: %s:%d (Breakpoint ID: %d) ───\n\n",
			location.declaringType().name(), location.lineNumber(), breakpointId));

		for (Watcher watcher : watchers) {
			result.append(String.format("  • [%s] %s\n", watcher.getId().substring(0, 8), watcher.getLabel()));
			try {
				Value value = expressionEvaluator.evaluate(frame, watcher.getExpression());
				result.append(String.format("    %s = %s\n\n", watcher.getExpression(), formatValue(value)));
				watchersEvaluated++;
			} catch (Exception e) {
				result.append(String.format("    %s = [ERROR: %s]\n\n", watcher.getExpression(), e.getMessage()));
			}
		}
		return watchersEvaluated;
	}

	/**
	 * Evaluates watchers across the entire call stack by scanning each frame's location against
	 * the breakpoint location map. Only frames that match a known breakpoint have their watchers evaluated.
	 *
	 * @param thread the suspended thread whose full stack will be scanned
	 * @param result accumulator for formatted evaluation output
	 * @return total number of watchers successfully evaluated across all matching frames
	 */
	private int evaluateWatchersFullStack(ThreadReference thread, StringBuilder result) throws Exception {
		Map<String, Integer> locationToBreakpointId = breakpointTracker.getBreakpointLocationMap();
		if (locationToBreakpointId.isEmpty()) {
			result.append("No breakpoints found. Cannot evaluate watchers.\n");
			return 0;
		}

		int watchersEvaluated = 0;
		List<StackFrame> frames = thread.frames();

		for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
			StackFrame frame = frames.get(frameIndex);
			Location location = frame.location();
			String locationKey = location.declaringType().name() + ":" + location.lineNumber();

			Integer breakpointId = locationToBreakpointId.get(locationKey);
			if (breakpointId == null) continue;

			List<Watcher> watchers = watcherManager.getWatchersForBreakpoint(breakpointId);
			if (watchers.isEmpty()) continue;

			result.append(String.format("─── Frame #%d: %s:%d (Breakpoint ID: %d) ───\n\n",
				frameIndex, location.declaringType().name(), location.lineNumber(), breakpointId));

			for (Watcher watcher : watchers) {
				result.append(String.format("  • [%s] %s\n", watcher.getId().substring(0, 8), watcher.getLabel()));
				try {
					Value value = expressionEvaluator.evaluate(frame, watcher.getExpression());
					result.append(String.format("    %s = %s\n\n", watcher.getExpression(), formatValue(value)));
					watchersEvaluated++;
				} catch (Exception e) {
					result.append(String.format("    %s = [ERROR: %s]\n\n", watcher.getExpression(), e.getMessage()));
				}
			}
		}
		return watchersEvaluated;
	}

	/** Parses a string value into a JDI Value matching the target type. Supports primitives, String, and null. */
	@Nullable
	private Value createJdiValue(VirtualMachine vm, String valueStr, Type targetType) throws Exception {
		if ("null".equals(valueStr)) return null;

		String typeName = targetType.name();
		return switch (typeName) {
			case "int" -> vm.mirrorOf(Integer.parseInt(valueStr));
			case "long" -> vm.mirrorOf(Long.parseLong(valueStr.replace("L", "").replace("l", "")));
			case "double" -> vm.mirrorOf(Double.parseDouble(valueStr));
			case "float" -> vm.mirrorOf(Float.parseFloat(valueStr.replace("f", "").replace("F", "")));
			case "boolean" -> vm.mirrorOf(Boolean.parseBoolean(valueStr));
			case "char" -> vm.mirrorOf(parseCharInput(valueStr));
			case "byte" -> vm.mirrorOf(Byte.parseByte(valueStr));
			case "short" -> vm.mirrorOf(Short.parseShort(valueStr));
			case "java.lang.String" -> vm.mirrorOf(valueStr);
			default -> throw new IllegalArgumentException(
				"Unsupported type: " + typeName + ". Only primitives, String, and null are supported.");
		};
	}

	/**
	 * Parses a user-supplied char literal. Strips surrounding {@code '...'} when present so the
	 * documented {@code 'a'} input form yields the character {@code a}, not the apostrophe. Throws
	 * {@link IllegalArgumentException} when the resulting payload is not exactly one character.
	 */
	static char parseCharInput(String valueStr) {
		if (valueStr == null) {
			throw new IllegalArgumentException("char value cannot be null");
		}
		String stripped = valueStr;
		if (stripped.length() >= 2 && stripped.startsWith("'") && stripped.endsWith("'")) {
			stripped = stripped.substring(1, stripped.length() - 1);
		}
		if (stripped.length() != 1) {
			throw new IllegalArgumentException(
				"char value must be exactly one character (optionally wrapped in single quotes), got: " + valueStr);
		}
		return stripped.charAt(0);
	}

	/** Finds a thread by its unique ID. */
	@Nullable
	private ThreadReference findThread(VirtualMachine vm, long threadId) {
		return vm.allThreads().stream()
			.filter(t -> t.uniqueID() == threadId)
			.findFirst()
			.orElse(null);
	}
}
