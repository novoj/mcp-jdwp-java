package io.mcp.jdwp;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Listens to the JDI event queue on a daemon thread.
 * Updates BreakpointTracker when breakpoint events arrive.
 * Activates deferred breakpoints when ClassPrepareEvents fire.
 */
@Slf4j
@Service
public class JdiEventListener {

	private final BreakpointTracker breakpointTracker;
	private volatile Thread listenerThread;
	private volatile boolean running;

	public JdiEventListener(BreakpointTracker breakpointTracker) {
		this.breakpointTracker = breakpointTracker;
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
	 * Main event loop. BreakpointEvent and StepEvent do NOT auto-resume (thread stays suspended
	 * for user inspection). All other events auto-resume.
	 *
	 * <p>NOTE on mixed EventSets: All events in a JDI EventSet share the same suspend policy.
	 * If a BreakpointEvent (SUSPEND_EVENT_THREAD/SUSPEND_ALL) coexists with a ClassPrepareEvent,
	 * the breakpoint's suspend policy governs the entire set. The ClassPrepareEvent handler
	 * completes its work (activating deferred breakpoints) regardless of whether we resume —
	 * the class is already loaded, the event merely notifies us. Not resuming is correct here:
	 * the BreakpointEvent dictates suspension for user inspection.</p>
	 */
	private void listen(VirtualMachine vm) {
		EventQueue queue = vm.eventQueue();

		while (running) {
			try {
				EventSet eventSet = queue.remove(); // blocks until event arrives
				boolean hasBreakpointOrStep = false;

				for (Event event : eventSet) {
					if (event instanceof BreakpointEvent bpEvent) {
						handleBreakpointEvent(bpEvent);
						// Don't auto-resume — thread stays suspended for user inspection
						hasBreakpointOrStep = true;
					} else if (event instanceof StepEvent stepEvent) {
						// Delete the completed StepRequest to allow future steps on this thread
						if (stepEvent.request() != null) {
							stepEvent.request().virtualMachine().eventRequestManager()
								.deleteEventRequest(stepEvent.request());
						}
						// Step events: thread stays suspended for user inspection
						hasBreakpointOrStep = true;
					} else if (event instanceof ClassPrepareEvent cpEvent) {
						handleClassPrepareEvent(cpEvent);
						// Always resume — don't block class loading
					} else if (event instanceof VMStartEvent) {
						log.info("[JDI] VM started — keeping suspended for breakpoint setup");
						hasBreakpointOrStep = true; // don't auto-resume
					} else if (event instanceof VMDisconnectEvent || event instanceof VMDeathEvent) {
						log.info("[JDI] VM disconnected/died, stopping event listener");
						running = false;
						return;
					}
					// All other events (ThreadStart, etc.): resume
				}

				// Resume the EventSet unless a BreakpointEvent or StepEvent requires suspension
				if (!hasBreakpointOrStep) {
					eventSet.resume();
				}
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
	 * Updates the tracker with the thread and breakpoint ID that triggered the event.
	 */
	private void handleBreakpointEvent(BreakpointEvent event) {
		try {
			BreakpointRequest request = (BreakpointRequest) event.request();
			Integer bpId = breakpointTracker.findIdByRequest(request);

			if (bpId != null) {
				breakpointTracker.setLastBreakpointThread(event.thread(), bpId);
				log.info("[JDI] Breakpoint {} hit on thread {} at {}:{}",
					bpId, event.thread().name(),
					event.location().declaringType().name(),
					event.location().lineNumber());
			} else {
				log.warn("[JDI] Untracked breakpoint hit at {}:{}",
					event.location().declaringType().name(),
					event.location().lineNumber());
			}
		} catch (Exception e) {
			log.warn("[JDI] Error handling breakpoint event: {}", e.getMessage());
		}
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

			// Clean up ClassPrepareRequest if no more pending BPs for this class
			if (breakpointTracker.getPendingBreakpointsForClass(className).isEmpty()) {
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
