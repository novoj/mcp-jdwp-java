package io.mcp.jdwp;

import com.sun.jdi.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.StepRequest;
import io.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import io.mcp.jdwp.watchers.Watcher;
import io.mcp.jdwp.watchers.WatcherManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP tools for JDWP-based debugging. Each {@code @McpTool} method is auto-discovered by the
 * Spring AI MCP framework and exposed as an invocable tool. All tool methods return formatted String results.
 */
@Slf4j
@Service
public class JDWPTools {

	private final JDIConnectionService jdiService;
	private final BreakpointTracker breakpointTracker;
	private final WatcherManager watcherManager;
	private final JdiExpressionEvaluator expressionEvaluator;
	private final EventHistory eventHistory;

	private static final int JVM_JDWP_PORT = Integer.parseInt(
		System.getProperty("JVM_JDWP_PORT", "5005")
	);

	public JDWPTools(JDIConnectionService jdiService, BreakpointTracker breakpointTracker,
					 WatcherManager watcherManager, JdiExpressionEvaluator expressionEvaluator,
					 EventHistory eventHistory) {
		this.jdiService = jdiService;
		this.breakpointTracker = breakpointTracker;
		this.watcherManager = watcherManager;
		this.expressionEvaluator = expressionEvaluator;
		this.eventHistory = eventHistory;
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

	@McpTool(description = "List all threads in the JVM with their status and frame count")
	public String jdwp_get_threads() {
		try {
			VirtualMachine vm = jdiService.getVM();
			List<ThreadReference> threads = vm.allThreads();

			StringBuilder result = new StringBuilder();
			result.append(String.format("Found %d threads:\n\n", threads.size()));

			for (int i = 0; i < threads.size(); i++) {
				ThreadReference thread = threads.get(i);
				result.append(String.format("Thread %d:\n", i));
				result.append(String.format("  ID: %d\n", thread.uniqueID()));
				result.append(String.format("  Name: %s\n", thread.name()));
				result.append(String.format("  Status: %d\n", thread.status()));
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

	@McpTool(description = "Get the call stack for a specific thread (by thread ID)")
	public String jdwp_get_stack(@McpToolParam(description = "Thread unique ID") long threadId) {
		try {
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
			result.append(String.format("Stack trace for thread %d (%s) - %d frames:\n\n",
				threadId, thread.name(), frames.size()));

			for (int i = 0; i < frames.size(); i++) {
				StackFrame frame = frames.get(i);
				Location location = frame.location();

				result.append(String.format("Frame %d:\n", i));
				result.append(String.format("  at %s.%s(",
					location.declaringType().name(),
					location.method().name()));

				try {
					result.append(String.format("%s:%d)\n",
						location.sourceName(),
						location.lineNumber()));
				} catch (AbsentInformationException e) {
					result.append("Unknown Source)\n");
				}
			}

			return result.toString();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Get local variables for a specific frame in a thread")
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
			Map<LocalVariable, Value> vars = frame.getValues(frame.visibleVariables());

			StringBuilder result = new StringBuilder();
			result.append(String.format("Local variables in frame %d:\n\n", frameIndex));

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
			@McpToolParam(description = "Thread unique ID (must be suspended). If omitted, uses the last breakpoint thread.") Long threadId) {
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

			Value result = obj.invokeMethod(thread, toStringMethod, java.util.Collections.emptyList(),
				ObjectReference.INVOKE_SINGLE_THREADED);

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
			@McpToolParam(description = "Frame index (0 = current frame, default: 0)") Integer frameIndex) {
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
			return "Error evaluating expression: " + e.getMessage();
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
			if (localVar.typeName().equals("java.lang.String") && value.startsWith("\"") && value.endsWith("\"")) {
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
			if (field.typeName().equals("java.lang.String") && value.startsWith("\"") && value.endsWith("\"")) {
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

			com.sun.jdi.request.EventRequestManager erm = vm.eventRequestManager();

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
			@McpToolParam(description = "Suspend policy: 'all' (default), 'thread', 'none'") String suspendPolicy,
			@McpToolParam(description = "Optional condition — only suspend when this evaluates to true (e.g., 'i > 100')") String condition) {
		try {
			VirtualMachine vm = jdiService.getVM();
			com.sun.jdi.request.EventRequestManager erm = vm.eventRequestManager();

			int jdiPolicy = com.sun.jdi.request.EventRequest.SUSPEND_ALL;
			String policyLabel = "all";
			if (suspendPolicy != null) {
				switch (suspendPolicy.toLowerCase()) {
					case "thread" -> { jdiPolicy = com.sun.jdi.request.EventRequest.SUSPEND_EVENT_THREAD; policyLabel = "thread"; }
					case "none" -> { jdiPolicy = com.sun.jdi.request.EventRequest.SUSPEND_NONE; policyLabel = "none"; }
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
				if (condition != null && !condition.isBlank()) {
					breakpointTracker.setCondition(pendingId, condition);
				}

				if (!breakpointTracker.hasClassPrepareRequest(className)) {
					com.sun.jdi.request.ClassPrepareRequest cpr = erm.createClassPrepareRequest();
					cpr.addClassFilter(className);
					cpr.setSuspendPolicy(com.sun.jdi.request.EventRequest.SUSPEND_EVENT_THREAD);
					cpr.enable();
					breakpointTracker.registerClassPrepareRequest(className, cpr);
				}

				List<ReferenceType> recheck = vm.classesByName(className);
				if (!recheck.isEmpty()) {
					ReferenceType refType = recheck.get(0);
					List<com.sun.jdi.Location> locations = refType.locationsOfLine(lineNumber);
					if (!locations.isEmpty()) {
						com.sun.jdi.request.BreakpointRequest bpRequest = erm.createBreakpointRequest(locations.get(0));
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
			List<com.sun.jdi.Location> locations = refType.locationsOfLine(lineNumber);
			if (locations.isEmpty()) {
				return String.format("Error: No executable code found at line %d in class %s", lineNumber, className);
			}

			com.sun.jdi.request.BreakpointRequest bpRequest = erm.createBreakpointRequest(locations.get(0));
			bpRequest.setSuspendPolicy(jdiPolicy);
			bpRequest.enable();

			int breakpointId = breakpointTracker.registerBreakpoint(bpRequest);
			if (condition != null && !condition.isBlank()) {
				breakpointTracker.setCondition(breakpointId, condition);
			}

			return String.format("Breakpoint set at %s:%d (ID: %d, suspend: %s%s)",
				className, lineNumber, breakpointId, policyLabel, conditionInfo);
		} catch (AbsentInformationException e) {
			return "Error: No line number information available for this class. Compile with debug info (-g).";
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Set a logpoint (non-stopping breakpoint) that evaluates an expression and logs the result without pausing execution")
	public String jdwp_set_logpoint(
			@McpToolParam(description = "Fully qualified class name") String className,
			@McpToolParam(description = "Line number") int lineNumber,
			@McpToolParam(description = "Java expression to evaluate and log (e.g., '\"x=\" + x', 'order.getTotal()')") String expression) {
		try {
			VirtualMachine vm = jdiService.getVM();
			com.sun.jdi.request.EventRequestManager erm = vm.eventRequestManager();

			int jdiPolicy = com.sun.jdi.request.EventRequest.SUSPEND_EVENT_THREAD;

			ReferenceType eagerType = jdiService.findOrForceLoadClass(className);
			List<ReferenceType> classes = eagerType != null ? List.of(eagerType) : List.of();

			if (classes.isEmpty()) {
				int pendingId = breakpointTracker.registerPendingBreakpoint(className, lineNumber, jdiPolicy, "thread");
				breakpointTracker.setLogpointExpression(pendingId, expression);

				if (!breakpointTracker.hasClassPrepareRequest(className)) {
					com.sun.jdi.request.ClassPrepareRequest cpr = erm.createClassPrepareRequest();
					cpr.addClassFilter(className);
					cpr.setSuspendPolicy(com.sun.jdi.request.EventRequest.SUSPEND_EVENT_THREAD);
					cpr.enable();
					breakpointTracker.registerClassPrepareRequest(className, cpr);
				}

				List<ReferenceType> recheck = vm.classesByName(className);
				if (!recheck.isEmpty()) {
					ReferenceType refType = recheck.get(0);
					List<com.sun.jdi.Location> locations = refType.locationsOfLine(lineNumber);
					if (!locations.isEmpty()) {
						com.sun.jdi.request.BreakpointRequest bpRequest = erm.createBreakpointRequest(locations.get(0));
						bpRequest.setSuspendPolicy(jdiPolicy);
						bpRequest.enable();
						breakpointTracker.promotePendingToActive(pendingId, bpRequest);
						return String.format("Logpoint set at %s:%d (ID: %d, expression: %s)",
							className, lineNumber, pendingId, expression);
					}
				}

				return String.format("Logpoint deferred for %s:%d (ID: %d, expression: %s). " +
					"Class not yet loaded — will activate when the JVM loads it.",
					className, lineNumber, pendingId, expression);
			}

			ReferenceType refType = classes.get(0);
			List<com.sun.jdi.Location> locations = refType.locationsOfLine(lineNumber);
			if (locations.isEmpty()) {
				return String.format("Error: No executable code at line %d in class %s", lineNumber, className);
			}

			com.sun.jdi.request.BreakpointRequest bpRequest = erm.createBreakpointRequest(locations.get(0));
			bpRequest.setSuspendPolicy(jdiPolicy);
			bpRequest.enable();

			int breakpointId = breakpointTracker.registerBreakpoint(bpRequest);
			breakpointTracker.setLogpointExpression(breakpointId, expression);

			return String.format("Logpoint set at %s:%d (ID: %d, expression: %s)",
				className, lineNumber, breakpointId, expression);
		} catch (AbsentInformationException e) {
			return "Error: No line number information available. Compile with debug info (-g).";
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Remove a breakpoint at a specific line in a class")
	public String jdwp_clear_breakpoint(
			@McpToolParam(description = "Fully qualified class name") String className,
			@McpToolParam(description = "Line number") int lineNumber) {
		try {
			VirtualMachine vm = jdiService.getVM();
			com.sun.jdi.request.EventRequestManager erm = vm.eventRequestManager();

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
				return String.format("No breakpoint found at %s:%d (class not loaded)", className, lineNumber);
			}

			ReferenceType refType = classes.get(0);

			// Find location
			List<com.sun.jdi.Location> locations = refType.locationsOfLine(lineNumber);
			if (locations.isEmpty()) {
				return String.format("Error: No code at line %d in class %s", lineNumber, className);
			}

			com.sun.jdi.Location location = locations.get(0);

			// Find and delete matching breakpoint requests (copy list to avoid ConcurrentModificationException)
			List<com.sun.jdi.request.BreakpointRequest> breakpoints = new java.util.ArrayList<>(erm.breakpointRequests());
			int removed = 0;
			for (com.sun.jdi.request.BreakpointRequest bp : breakpoints) {
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
				return String.format("No breakpoint found at %s:%d", className, lineNumber);
			}

			return String.format("Removed %d breakpoint(s) at %s:%d", removed, className, lineNumber);
		} catch (AbsentInformationException e) {
			return "Error: No line number information available for this class";
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
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
						case com.sun.jdi.request.EventRequest.SUSPEND_ALL -> "all";
						case com.sun.jdi.request.EventRequest.SUSPEND_EVENT_THREAD -> "thread";
						case com.sun.jdi.request.EventRequest.SUSPEND_NONE -> "none";
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
	public String jdwp_get_events(@McpToolParam(description = "Number of recent events to retrieve (default: 20, max: 100)") Integer count) {
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
			@McpToolParam(description = "Break on caught exceptions (default: true)") Boolean caught,
			@McpToolParam(description = "Break on uncaught exceptions (default: true)") Boolean uncaught) {
		try {
			if (caught == null) caught = true;
			if (uncaught == null) uncaught = true;

			VirtualMachine vm = jdiService.getVM();
			com.sun.jdi.request.EventRequestManager erm = vm.eventRequestManager();

			// Try eager: check classesByName, and if empty, force-load via Class.forName
			ReferenceType eagerType = jdiService.findOrForceLoadClass(exceptionClass);

			if (eagerType == null) {
				// Class not loadable yet — defer
				int pendingId = breakpointTracker.registerPendingExceptionBreakpoint(exceptionClass, caught, uncaught);

				if (!breakpointTracker.hasClassPrepareRequest(exceptionClass)) {
					com.sun.jdi.request.ClassPrepareRequest cpr = erm.createClassPrepareRequest();
					cpr.addClassFilter(exceptionClass);
					cpr.setSuspendPolicy(com.sun.jdi.request.EventRequest.SUSPEND_EVENT_THREAD);
					cpr.enable();
					breakpointTracker.registerClassPrepareRequest(exceptionClass, cpr);
				}

				return String.format("Exception breakpoint deferred (ID: %d)\n  Exception: %s\n  Caught: %s\n  Uncaught: %s\n" +
					"Class not yet loaded — will activate automatically when the JVM loads it.",
					pendingId, exceptionClass, caught, uncaught);
			}

			ReferenceType refType = eagerType;
			com.sun.jdi.request.ExceptionRequest exReq = erm.createExceptionRequest(refType, caught, uncaught);
			exReq.setSuspendPolicy(com.sun.jdi.request.EventRequest.SUSPEND_EVENT_THREAD);
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

	@McpTool(description = "Get the thread ID of the current breakpoint")
	public String jdwp_get_current_thread() {
		try {
			ThreadReference thread = breakpointTracker.getLastBreakpointThread();
			if (thread == null) {
				return "No current breakpoint detected. Set a breakpoint and trigger it first.";
			}

			Integer bpId = breakpointTracker.getLastBreakpointId();
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
				stats.get("totalWatchers"), stats.get("breakpointsWithWatchers")));

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
	private Value createJdiValue(VirtualMachine vm, String valueStr, Type targetType) throws Exception {
		if ("null".equals(valueStr)) return null;

		String typeName = targetType.name();
		return switch (typeName) {
			case "int" -> vm.mirrorOf(Integer.parseInt(valueStr));
			case "long" -> vm.mirrorOf(Long.parseLong(valueStr.replace("L", "").replace("l", "")));
			case "double" -> vm.mirrorOf(Double.parseDouble(valueStr));
			case "float" -> vm.mirrorOf(Float.parseFloat(valueStr.replace("f", "").replace("F", "")));
			case "boolean" -> vm.mirrorOf(Boolean.parseBoolean(valueStr));
			case "char" -> vm.mirrorOf(valueStr.charAt(0));
			case "byte" -> vm.mirrorOf(Byte.parseByte(valueStr));
			case "short" -> vm.mirrorOf(Short.parseShort(valueStr));
			case "java.lang.String" -> vm.mirrorOf(valueStr);
			default -> throw new IllegalArgumentException(
				"Unsupported type: " + typeName + ". Only primitives, String, and null are supported.");
		};
	}

	/** Finds a thread by its unique ID. */
	private ThreadReference findThread(VirtualMachine vm, long threadId) {
		return vm.allThreads().stream()
			.filter(t -> t.uniqueID() == threadId)
			.findFirst()
			.orElse(null);
	}
}
