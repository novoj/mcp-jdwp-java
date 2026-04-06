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

	private static final int JVM_JDWP_PORT = Integer.parseInt(
		System.getProperty("JVM_JDWP_PORT", "5005")
	);

	public JDWPTools(JDIConnectionService jdiService, BreakpointTracker breakpointTracker,
					 WatcherManager watcherManager, JdiExpressionEvaluator expressionEvaluator) {
		this.jdiService = jdiService;
		this.breakpointTracker = breakpointTracker;
		this.watcherManager = watcherManager;
		this.expressionEvaluator = expressionEvaluator;
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

	@McpTool(description = "Set a breakpoint at a specific line in a class. If the class is not yet loaded, the breakpoint is deferred and will activate automatically when the JVM loads the class.")
	public String jdwp_set_breakpoint(
			@McpToolParam(description = "Fully qualified class name (e.g. 'com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto')") String className,
			@McpToolParam(description = "Line number") int lineNumber,
			@McpToolParam(description = "Suspend policy: 'all' (default, suspends all threads), 'thread' (suspends only the thread that hit the breakpoint), 'none' (no suspension)") String suspendPolicy) {
		try {
			VirtualMachine vm = jdiService.getVM();
			com.sun.jdi.request.EventRequestManager erm = vm.eventRequestManager();

			// Parse suspend policy upfront
			int jdiPolicy = com.sun.jdi.request.EventRequest.SUSPEND_ALL;
			String policyLabel = "all";
			if (suspendPolicy != null) {
				switch (suspendPolicy.toLowerCase()) {
					case "thread" -> { jdiPolicy = com.sun.jdi.request.EventRequest.SUSPEND_EVENT_THREAD; policyLabel = "thread"; }
					case "none" -> { jdiPolicy = com.sun.jdi.request.EventRequest.SUSPEND_NONE; policyLabel = "none"; }
					case "all" -> { /* default */ }
					default -> {
						return String.format(
							"Error: Invalid suspend policy '%s'. Use 'all', 'thread', or 'none'.", suspendPolicy
						);
					}
				}
			}

			// Find the class
			List<ReferenceType> classes = vm.classesByName(className);

			if (classes.isEmpty()) {
				// Class not loaded yet — create a deferred breakpoint
				int pendingId = breakpointTracker.registerPendingBreakpoint(className, lineNumber, jdiPolicy, policyLabel);

				// Register a ClassPrepareRequest to be notified when the class loads
				if (!breakpointTracker.hasClassPrepareRequest(className)) {
					com.sun.jdi.request.ClassPrepareRequest cpr = erm.createClassPrepareRequest();
					cpr.addClassFilter(className);
					cpr.setSuspendPolicy(com.sun.jdi.request.EventRequest.SUSPEND_EVENT_THREAD);
					cpr.enable();
					breakpointTracker.registerClassPrepareRequest(className, cpr);
				}

				// Race condition mitigation: re-check if class loaded between our first check and CPR creation
				List<ReferenceType> recheck = vm.classesByName(className);
				if (!recheck.isEmpty()) {
					// Class loaded in the meantime — activate immediately
					ReferenceType refType = recheck.get(0);
					List<com.sun.jdi.Location> locations = refType.locationsOfLine(lineNumber);
					if (!locations.isEmpty()) {
						com.sun.jdi.request.BreakpointRequest bpRequest = erm.createBreakpointRequest(locations.get(0));
						bpRequest.setSuspendPolicy(jdiPolicy);
						bpRequest.enable();
						breakpointTracker.promotePendingToActive(pendingId, bpRequest);
						return String.format(
							"Breakpoint set at %s:%d (ID: %d, suspend: %s)",
							className, lineNumber, pendingId, policyLabel
						);
					}
				}

				return String.format(
					"Breakpoint deferred for %s:%d (ID: %d, suspend: %s). " +
					"Class not yet loaded — will activate automatically when the JVM loads it.",
					className, lineNumber, pendingId, policyLabel
				);
			}

			ReferenceType refType = classes.get(0);

			// Find location
			List<com.sun.jdi.Location> locations = refType.locationsOfLine(lineNumber);
			if (locations.isEmpty()) {
				return String.format("Error: No executable code found at line %d in class %s", lineNumber, className);
			}

			com.sun.jdi.Location location = locations.get(0);

			// Create breakpoint
			com.sun.jdi.request.BreakpointRequest bpRequest = erm.createBreakpointRequest(location);
			bpRequest.setSuspendPolicy(jdiPolicy);
			bpRequest.enable();

			int breakpointId = breakpointTracker.registerBreakpoint(bpRequest);

			return String.format(
				"Breakpoint set at %s:%d (ID: %d, suspend: %s)", className, lineNumber, breakpointId, policyLabel
			);
		} catch (AbsentInformationException e) {
			return "Error: No line number information available for this class. Compile with debug info (-g).";
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
					result.append(String.format("  Suspend: %s\n\n", policyStr));
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

	@McpTool(description = "Get recent JDWP events (breakpoints, steps, exceptions, etc.)")
	public String jdwp_get_events(@McpToolParam(description = "Number of recent events to retrieve (default: 20, max: 100)") Integer count) {
		try {
			if (count == null || count <= 0) {
				count = 20;
			}
			if (count > 100) {
				count = 100;
			}

			List<String> events = jdiService.getRecentEvents(count);

			if (events.isEmpty()) {
				return "No events recorded yet.\n\n" +
					   "The event listener captures all JDWP events including:\n" +
					   "  - Breakpoints (from IntelliJ or MCP)\n" +
					   "  - Steps (step over, step into, step out)\n" +
					   "  - Exceptions\n" +
					   "  - Method entries/exits (if enabled)\n\n" +
					   "Events are captured automatically when connected.";
			}

			StringBuilder result = new StringBuilder();
			result.append(String.format("Recent JDWP events (%d most recent):\n\n", events.size()));

			for (int i = 0; i < events.size(); i++) {
				result.append(String.format("%d. %s\n", i + 1, events.get(i)));
			}

			return result.toString();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Clear the JDWP event history")
	public String jdwp_clear_events() {
		try {
			jdiService.clearEvents();
			return "Event history cleared";
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Configure exception monitoring (enable/disable caught exceptions, set filters)")
	public String jdwp_configure_exception_monitoring(
		@McpToolParam(description = "Enable/disable capturing caught exceptions (true/false, optional)")
		Boolean captureCaught,

		@McpToolParam(description = "Comma-separated list of packages to monitor (e.g. 'com.axelor,org.myapp') - empty means all (optional)")
		String includePackages,

		@McpToolParam(description = "Comma-separated list of exception classes to exclude (e.g. 'java.lang.NumberFormatException,java.io.IOException') (optional)")
		String excludeClasses
	) {
		try {
			return jdiService.configureExceptionMonitoring(captureCaught, includePackages, excludeClasses);
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Get current exception monitoring configuration")
	public String jdwp_get_exception_config() {
		try {
			return jdiService.getExceptionConfig();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@McpTool(description = "Clear ALL breakpoints (active and pending) set by this MCP server")
	public String jdwp_clear_all_breakpoints() {
		try {
			int activeCount = breakpointTracker.getAllBreakpoints().size();
			int pendingCount = breakpointTracker.getAllPendingBreakpoints().size();
			int totalCount = activeCount + pendingCount;
			if (totalCount == 0) {
				return "No breakpoints to clear";
			}

			VirtualMachine vm = jdiService.getVM();
			breakpointTracker.clearAll(vm.eventRequestManager());
			watcherManager.clearAll();

			return String.format("Successfully cleared %d breakpoint(s) (%d active, %d pending) and all associated watchers.",
				totalCount, activeCount, pendingCount);
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

	/** Finds a thread by its unique ID. */
	private ThreadReference findThread(VirtualMachine vm, long threadId) {
		return vm.allThreads().stream()
			.filter(t -> t.uniqueID() == threadId)
			.findFirst()
			.orElse(null);
	}
}
