package io.mcp.jdwp;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import io.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Listens to the JDI event queue on a daemon thread.
 * Updates BreakpointTracker when breakpoint events arrive.
 * Activates deferred breakpoints when ClassPrepareEvents fire.
 * Evaluates conditional breakpoints and logpoints automatically.
 */
@Slf4j
@Service
public class JdiEventListener {

	private final BreakpointTracker breakpointTracker;
	private final EventHistory eventHistory;
	private final JdiExpressionEvaluator expressionEvaluator;
	private volatile Thread listenerThread;
	private volatile boolean running;

	public JdiEventListener(BreakpointTracker breakpointTracker, EventHistory eventHistory,
							@Lazy JdiExpressionEvaluator expressionEvaluator) {
		this.breakpointTracker = breakpointTracker;
		this.eventHistory = eventHistory;
		this.expressionEvaluator = expressionEvaluator;
	}

	/**
	 * Start listening for JDI events from the given VM.
	 * Spawns a daemon thread that polls the event queue.
	 */
	public void start(VirtualMachine vm) {
		stop(); // clean up any previous listener

		running = true;
		listenerThread = new Thread(() -> listen(vm), "jdi-event-listener");
		listenerThread.setDaemon(true);
		listenerThread.start();
		log.info("[JDI] Event listener started");
	}

	/**
	 * Stop the event listener thread.
	 */
	public void stop() {
		running = false;
		if (listenerThread != null) {
			listenerThread.interrupt();
			listenerThread = null;
		}
	}

	/**
	 * Main event loop. Events that require user inspection (breakpoints, steps, exceptions)
	 * keep the thread suspended. Logpoints and false conditions auto-resume.
	 */
	private void listen(VirtualMachine vm) {
		EventQueue queue = vm.eventQueue();

		while (running) {
			try {
				EventSet eventSet = queue.remove();
				boolean shouldSuspend = false;

				for (Event event : eventSet) {
					if (event instanceof BreakpointEvent bpEvent) {
						if (handleBreakpointEvent(bpEvent)) {
							shouldSuspend = true;
						}
					} else if (event instanceof StepEvent stepEvent) {
						handleStepEvent(stepEvent);
						shouldSuspend = true;
					} else if (event instanceof ExceptionEvent exEvent) {
						handleExceptionEvent(exEvent);
						shouldSuspend = true;
					} else if (event instanceof ClassPrepareEvent cpEvent) {
						handleClassPrepareEvent(cpEvent);
					} else if (event instanceof VMStartEvent) {
						log.info("[JDI] VM started — keeping suspended for breakpoint setup");
						eventHistory.record(new EventHistory.DebugEvent("VM_START", "VM started"));
						shouldSuspend = true;
					} else if (event instanceof VMDisconnectEvent || event instanceof VMDeathEvent) {
						log.info("[JDI] VM disconnected/died, stopping event listener");
						eventHistory.record(new EventHistory.DebugEvent("VM_DEATH", "VM disconnected/died"));
						running = false;
						return;
					}
				}

				if (!shouldSuspend) {
					eventSet.resume();
				}
				// NOTE: We deliberately do NOT call tryPromotePending from inside the event
				// listener thread. JDI forbids method invocations from within event handlers,
				// which would block the listener and crash the connection. Promotion happens via
				// JDIConnectionService.getVM() on the next MCP tool call, where invokeMethod is
				// safe because the listener is back at queue.remove().
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (com.sun.jdi.VMDisconnectedException e) {
				log.info("[JDI] VM disconnected, stopping event listener");
				break;
			} catch (Exception e) {
				if (running) {
					log.warn("[JDI] Error processing event: {}", e.getMessage());
				}
			}
		}
	}

	/**
	 * Handles a breakpoint event. Returns true if the thread should stay suspended.
	 * Returns false for logpoints (auto-resume after evaluation) and conditional breakpoints
	 * where the condition evaluates to false.
	 */
	private boolean handleBreakpointEvent(BreakpointEvent event) {
		try {
			BreakpointRequest request = (BreakpointRequest) event.request();
			Integer bpId = breakpointTracker.findIdByRequest(request);

			if (bpId == null) {
				log.warn("[JDI] Untracked breakpoint hit at {}:{}",
					event.location().declaringType().name(), event.location().lineNumber());
				return true;
			}

			breakpointTracker.setLastBreakpointThread(event.thread(), bpId);

			String className = event.location().declaringType().name();
			int lineNumber = event.location().lineNumber();
			String threadName = event.thread().name();

			// Check if this is a logpoint — evaluate expression, record output, auto-resume
			String logpointExpr = breakpointTracker.getLogpointExpression(bpId);
			if (logpointExpr != null) {
				evaluateLogpoint(event, bpId, logpointExpr, className, lineNumber, threadName);
				return false;
			}

			// Check if this has a condition — evaluate and resume if false
			String condition = breakpointTracker.getCondition(bpId);
			if (condition != null) {
				boolean conditionResult = evaluateCondition(event, condition);
				if (!conditionResult) {
					log.debug("[JDI] Conditional breakpoint {} at {}:{} — condition false, resuming",
						bpId, className, lineNumber);
					return false;
				}
			}

			// Normal breakpoint — record event and keep suspended
			eventHistory.record(new EventHistory.DebugEvent("BREAKPOINT",
				String.format("Breakpoint %d hit at %s:%d on thread %s", bpId, className, lineNumber, threadName),
				Map.of("breakpointId", String.valueOf(bpId), "class", className,
					"line", String.valueOf(lineNumber), "thread", threadName)));

			log.info("[JDI] Breakpoint {} hit on thread {} at {}:{}", bpId, threadName, className, lineNumber);
			return true;

		} catch (Exception e) {
			log.warn("[JDI] Error handling breakpoint event: {}", e.getMessage());
			return true;
		}
	}

	private void handleStepEvent(StepEvent event) {
		if (event.request() != null) {
			event.request().virtualMachine().eventRequestManager().deleteEventRequest(event.request());
		}

		try {
			String className = event.location().declaringType().name();
			int lineNumber = event.location().lineNumber();
			String threadName = event.thread().name();

			eventHistory.record(new EventHistory.DebugEvent("STEP",
				String.format("Step to %s:%d on thread %s", className, lineNumber, threadName),
				Map.of("class", className, "line", String.valueOf(lineNumber), "thread", threadName)));
		} catch (Exception e) {
			log.debug("[JDI] Error recording step event: {}", e.getMessage());
		}
	}

	private void handleExceptionEvent(ExceptionEvent event) {
		try {
			ObjectReference exception = event.exception();
			Location throwLocation = event.location();
			Location catchLocation = event.catchLocation();

			String exceptionType = exception.referenceType().name();
			String throwInfo = throwLocation != null
				? throwLocation.declaringType().name() + ":" + throwLocation.lineNumber() : "unknown";
			String catchInfo = catchLocation != null
				? catchLocation.declaringType().name() + ":" + catchLocation.lineNumber() : "uncaught";
			String threadName = event.thread().name();

			breakpointTracker.setLastBreakpointThread(event.thread(), -1);

			eventHistory.record(new EventHistory.DebugEvent("EXCEPTION",
				String.format("%s thrown at %s, caught at %s on thread %s",
					exceptionType, throwInfo, catchInfo, threadName),
				Map.of("exceptionType", exceptionType, "throwLocation", throwInfo,
					"catchLocation", catchInfo, "thread", threadName)));

			log.info("[JDI] Exception {} thrown at {}, caught at {} on thread {}",
				exceptionType, throwInfo, catchInfo, threadName);
		} catch (Exception e) {
			log.warn("[JDI] Error handling exception event: {}", e.getMessage());
		}
	}

	private void evaluateLogpoint(BreakpointEvent event, int bpId, String expression,
								  String className, int lineNumber, String threadName) {
		try {
			ThreadReference thread = event.thread();
			expressionEvaluator.configureCompilerClasspath(thread);
			StackFrame frame = thread.frame(0);
			Value result = expressionEvaluator.evaluate(frame, expression);

			String resultStr = formatLogpointResult(result);

			eventHistory.record(new EventHistory.DebugEvent("LOGPOINT",
				String.format("[Logpoint %d] %s = %s at %s:%d", bpId, expression, resultStr, className, lineNumber),
				Map.of("breakpointId", String.valueOf(bpId), "expression", expression,
					"result", resultStr, "class", className,
					"line", String.valueOf(lineNumber), "thread", threadName)));

			log.info("[JDI] Logpoint {} evaluated: {} = {}", bpId, expression, resultStr);
		} catch (Exception e) {
			eventHistory.record(new EventHistory.DebugEvent("LOGPOINT_ERROR",
				String.format("[Logpoint %d] Error evaluating '%s': %s", bpId, expression, e.getMessage())));
			log.warn("[JDI] Logpoint {} evaluation failed: {}", bpId, e.getMessage());
		}
	}

	private boolean evaluateCondition(BreakpointEvent event, String condition) {
		try {
			ThreadReference thread = event.thread();
			expressionEvaluator.configureCompilerClasspath(thread);
			StackFrame frame = thread.frame(0);
			Value result = expressionEvaluator.evaluate(frame, condition);

			// Direct boolean primitive (unlikely due to autoboxing in wrapper)
			if (result instanceof BooleanValue boolVal) {
				return boolVal.value();
			}

			// Boxed java.lang.Boolean — read internal 'value' field
			if (result instanceof ObjectReference objRef
					&& "java.lang.Boolean".equals(objRef.referenceType().name())) {
				Field valueField = objRef.referenceType().fieldByName("value");
				if (valueField != null) {
					Value innerValue = objRef.getValue(valueField);
					if (innerValue instanceof BooleanValue boolVal) {
						return boolVal.value();
					}
				}
			}

			log.warn("[JDI] Condition '{}' returned non-boolean: {}. Suspending.", condition, result);
			return true;
		} catch (Exception e) {
			log.warn("[JDI] Error evaluating condition '{}': {}. Suspending.", condition, e.getMessage());
			return true;
		}
	}

	private String formatLogpointResult(Value value) {
		if (value == null) return "null";
		if (value instanceof StringReference strRef) return strRef.value();
		if (value instanceof PrimitiveValue) return value.toString();
		if (value instanceof ObjectReference objRef) {
			return String.format("Object#%d (%s)", objRef.uniqueID(), objRef.referenceType().name());
		}
		return value.toString();
	}

	/**
	 * Activates deferred (pending) breakpoints when the target class is loaded by the JVM.
	 */
	private void handleClassPrepareEvent(ClassPrepareEvent event) {
		try {
			ReferenceType refType = event.referenceType();
			String className = refType.name();

			List<Map.Entry<Integer, BreakpointTracker.PendingBreakpoint>> pendingList =
				breakpointTracker.getPendingBreakpointsForClass(className);

			if (pendingList.isEmpty()) {
				return;
			}

			log.info("[JDI] ClassPrepareEvent for '{}', activating {} deferred breakpoint(s)", className, pendingList.size());

			VirtualMachine vm = event.virtualMachine();
			EventRequestManager erm = vm.eventRequestManager();

			for (Map.Entry<Integer, BreakpointTracker.PendingBreakpoint> entry : pendingList) {
				int id = entry.getKey();
				BreakpointTracker.PendingBreakpoint pending = entry.getValue();

				try {
					List<Location> locations = refType.locationsOfLine(pending.getLineNumber());
					if (locations.isEmpty()) {
						String reason = String.format("No executable code at line %d in %s", pending.getLineNumber(), className);
						breakpointTracker.markPendingFailed(id, reason);
						log.warn("[JDI] Deferred breakpoint {} failed: {}", id, reason);
						continue;
					}

					Location location = locations.get(0);
					BreakpointRequest bpRequest = erm.createBreakpointRequest(location);
					bpRequest.setSuspendPolicy(pending.getSuspendPolicy());
					bpRequest.enable();

					breakpointTracker.promotePendingToActive(id, bpRequest);
					log.info("[JDI] Deferred breakpoint {} activated at {}:{}", id, className, pending.getLineNumber());

				} catch (AbsentInformationException e) {
					breakpointTracker.markPendingFailed(id, "No debug info (compile with -g)");
					log.warn("[JDI] Deferred breakpoint {} failed: no debug info for {}", id, className);
				} catch (Exception e) {
					breakpointTracker.markPendingFailed(id, e.getMessage());
					log.warn("[JDI] Deferred breakpoint {} failed: {}", id, e.getMessage());
				}
			}

			// Also activate any deferred exception breakpoints for this class
			List<Map.Entry<Integer, BreakpointTracker.PendingExceptionBreakpoint>> pendingExList =
				breakpointTracker.getPendingExceptionBreakpointsForClass(className);

			for (Map.Entry<Integer, BreakpointTracker.PendingExceptionBreakpoint> entry : pendingExList) {
				int id = entry.getKey();
				BreakpointTracker.PendingExceptionBreakpoint pending = entry.getValue();
				try {
					ExceptionRequest exReq = erm.createExceptionRequest(refType, pending.isCaught(), pending.isUncaught());
					exReq.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
					exReq.enable();
					breakpointTracker.promotePendingExceptionToActive(id, exReq);
					log.info("[JDI] Deferred exception breakpoint {} activated for {}", id, className);
				} catch (Exception e) {
					breakpointTracker.markPendingExceptionFailed(id, e.getMessage());
					log.warn("[JDI] Deferred exception breakpoint {} failed: {}", id, e.getMessage());
				}
			}

			// Clean up ClassPrepareRequest if no more pending (line OR exception) BPs for this class
			if (breakpointTracker.getPendingBreakpointsForClass(className).isEmpty()
					&& breakpointTracker.getPendingExceptionBreakpointsForClass(className).isEmpty()) {
				ClassPrepareRequest cpr = breakpointTracker.removeClassPrepareRequest(className);
				if (cpr != null) {
					erm.deleteEventRequest(cpr);
				}
			}

		} catch (Exception e) {
			log.warn("[JDI] Error handling ClassPrepareEvent: {}", e.getMessage());
		}
	}
}
