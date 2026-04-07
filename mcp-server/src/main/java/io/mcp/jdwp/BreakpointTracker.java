package io.mcp.jdwp;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central registry for breakpoints owned by the MCP server and a parking spot for the last thread
 * that hit a suspending event. JDI's `BreakpointRequest` has no user-facing identifier, so this
 * service mints synthetic monotonic integer IDs and exposes them to the MCP client.
 *
 * Maintains four parallel state maps:
 * - `breakpointsById` — line breakpoints already bound to a JDI `BreakpointRequest`.
 * - `pendingBreakpointsById` — line breakpoints whose target class is not yet loaded.
 * - `exceptionBreakpointsById` — exception breakpoints bound to a JDI `ExceptionRequest`.
 * - `pendingExceptionBreakpointsById` — exception breakpoints whose exception class is not yet loaded.
 *
 * Pending entries are promoted to active either by `JdiEventListener.handleClassPrepareEvent` (the
 * normal path) or by the {@link #tryPromotePending} safety net (called from {@link JDIConnectionService#getVM()}
 * before every tool call) for classes loaded before any debugger event was delivered.
 *
 * Thread-safety: all public mutators are `synchronized`; the read-mostly state maps are
 * `ConcurrentHashMap` so listeners can iterate without contending with mutators. The `lastBreakpoint*`
 * fields are `volatile` for cross-thread visibility from the JDI listener thread to MCP worker threads.
 */
@Slf4j
@Service
public class BreakpointTracker {

	/** Monotonic synthetic ID source shared by every register-* call; reset by {@link #clearAll} / {@link #reset}. */
	private final AtomicInteger idCounter = new AtomicInteger(1);
	/** Active line breakpoints keyed by synthetic ID; populated by {@link #registerBreakpoint}. */
	private final ConcurrentHashMap<Integer, BreakpointRequest> breakpointsById = new ConcurrentHashMap<>();
	/** Line breakpoints awaiting class load; populated by {@link #registerPendingBreakpoint} via `jdwp_set_breakpoint`. */
	private final ConcurrentHashMap<Integer, PendingBreakpoint> pendingBreakpointsById = new ConcurrentHashMap<>();
	/** ClassPrepare requests keyed by class name; one per class with at least one pending BP referencing it. */
	private final ConcurrentHashMap<String, ClassPrepareRequest> classPrepareRequests = new ConcurrentHashMap<>();
	/** Optional condition / logpoint expression metadata indexed by breakpoint ID. */
	private final ConcurrentHashMap<Integer, BreakpointMetadata> breakpointMetadata = new ConcurrentHashMap<>();
	/** Active exception breakpoints keyed by synthetic ID; populated by {@link #registerExceptionBreakpoint}. */
	private final ConcurrentHashMap<Integer, ExceptionBreakpointInfo> exceptionBreakpointsById = new ConcurrentHashMap<>();
	/** Exception breakpoints awaiting class load; promoted via {@link #promotePendingExceptionToActive}. */
	private final ConcurrentHashMap<Integer, PendingExceptionBreakpoint> pendingExceptionBreakpointsById = new ConcurrentHashMap<>();

	/**
	 * Atomic snapshot of the last suspending JDI event: the firing {@link ThreadReference} paired
	 * with the synthetic breakpoint ID (or {@code -1} sentinel for non-breakpoint events such as
	 * exceptions). Stored in a single volatile field so readers cannot observe a torn pair from
	 * two different writes (FINDING-9). {@code null} until the first event lands or after a reset.
	 */
	private volatile LastBreakpoint lastBreakpoint;

	/**
	 * Single-shot latch backing {@link JDWPTools#jdwp_resume_until_event(Integer)}.
	 * Lifecycle: armed by {@link #armNextEventLatch()} immediately before `vm.resume()`,
	 * counted down by {@link #fireNextEvent()} from the JDI listener thread when the next
	 * suspending event (BP / step / exception) lands, and released-then-cleared by
	 * {@link #clearAll(EventRequestManager)} / {@link #reset()} so a `jdwp_reset` or
	 * `jdwp_disconnect` from another caller does not leave a waiter hanging.
	 *
	 * `volatile` for cross-thread visibility; mutating methods that touch the field are
	 * `synchronized` so the arm-then-fire ordering is atomic.
	 */
	private volatile CountDownLatch nextEventLatch;

	// ── Active breakpoint operations ──

	/**
	 * Register a breakpoint and return a synthetic integer ID.
	 */
	public int registerBreakpoint(BreakpointRequest bp) {
		int id = idCounter.getAndIncrement();
		breakpointsById.put(id, bp);
		return id;
	}

	/**
	 * Lookup a breakpoint by its synthetic ID.
	 */
	public BreakpointRequest getBreakpoint(int id) {
		return breakpointsById.get(id);
	}

	/**
	 * Remove a breakpoint by ID — checks active first, then pending. Also clears any condition or
	 * logpoint metadata associated with the synthetic ID so it does not leak after removal
	 * (FINDING-8).
	 *
	 * @return true if found and removed
	 */
	public synchronized boolean removeBreakpoint(int id) {
		// Try active breakpoints first
		BreakpointRequest bp = breakpointsById.remove(id);
		if (bp != null) {
			try {
				bp.virtualMachine().eventRequestManager().deleteEventRequest(bp);
			} catch (Exception e) {
				// VM may already be disconnected
			}
			breakpointMetadata.remove(id);
			return true;
		}

		// Try pending breakpoints
		PendingBreakpoint pending = pendingBreakpointsById.remove(id);
		if (pending != null) {
			cleanupClassPrepareRequestIfNeeded(pending.getClassName());
			breakpointMetadata.remove(id);
			return true;
		}

		return false;
	}

	/**
	 * Removes the in-memory tracking entry for the given JDI request via identity comparison and
	 * clears any condition / logpoint metadata associated with its synthetic ID (FINDING-8).
	 *
	 * Does NOT call `EventRequestManager.deleteEventRequest`. Callers (currently
	 * {@link JDWPTools#jdwp_clear_breakpoint}) are responsible for deleting the underlying JDI request
	 * before invoking this method, otherwise the request stays alive in the target VM.
	 */
	public void unregisterByRequest(BreakpointRequest bp) {
		Integer id = findIdByRequest(bp);
		breakpointsById.entrySet().removeIf(e -> e.getValue() == bp);
		if (id != null) {
			breakpointMetadata.remove(id);
		}
	}

	/**
	 * Find the synthetic ID for a given JDI BreakpointRequest.
	 *
	 * @return the ID, or null if not tracked
	 */
	public Integer findIdByRequest(BreakpointRequest bp) {
		for (Map.Entry<Integer, BreakpointRequest> entry : breakpointsById.entrySet()) {
			if (entry.getValue() == bp) {
				return entry.getKey();
			}
		}
		return null;
	}

	/**
	 * Return all tracked active breakpoints (unmodifiable view).
	 */
	public Map<Integer, BreakpointRequest> getAllBreakpoints() {
		return Collections.unmodifiableMap(breakpointsById);
	}

	/**
	 * Build a location map: "className:lineNumber" -> breakpoint ID (active only).
	 */
	public Map<String, Integer> getBreakpointLocationMap() {
		Map<String, Integer> map = new HashMap<>();
		for (Map.Entry<Integer, BreakpointRequest> entry : breakpointsById.entrySet()) {
			Location loc = entry.getValue().location();
			String key = loc.declaringType().name() + ":" + loc.lineNumber();
			map.put(key, entry.getKey());
		}
		return map;
	}

	/**
	 * Clean shutdown variant: clears every state map (active line BPs, pending line BPs, exception BPs,
	 * pending exception BPs, breakpoint metadata, ClassPrepare requests) and deletes the underlying
	 * JDI event requests via `erm`. Also releases any pending `resume_until_event` waiter so a
	 * `jdwp_reset` or `jdwp_disconnect` from another caller does not leave it hanging until timeout.
	 *
	 * Counterpart of {@link #reset()}, which performs the same in-memory cleanup but skips the JDI
	 * calls — used when the target VM is already gone.
	 */
	public synchronized void clearAll(EventRequestManager erm) {
		// Release any awaiter BEFORE we touch state — see fireNextEvent() for why this matters.
		fireNextEvent();
		for (BreakpointRequest bp : breakpointsById.values()) {
			deleteQuietly(erm, bp);
		}
		breakpointsById.clear();

		pendingBreakpointsById.clear();
		breakpointMetadata.clear();

		for (ClassPrepareRequest cpr : classPrepareRequests.values()) {
			deleteQuietly(erm, cpr);
		}
		classPrepareRequests.clear();

		for (ExceptionBreakpointInfo info : exceptionBreakpointsById.values()) {
			deleteQuietly(erm, info.request);
		}
		exceptionBreakpointsById.clear();
		pendingExceptionBreakpointsById.clear();

		lastBreakpoint = null;
		idCounter.set(1);
	}

	/** Best-effort delete of a JDI event request — swallows any exception (e.g., VM already disconnected). */
	private static void deleteQuietly(EventRequestManager erm, EventRequest req) {
		try {
			erm.deleteEventRequest(req);
		} catch (Exception e) {
			// VM may already be disconnected
		}
	}

	// ── Pending breakpoint operations ──

	/**
	 * Register a pending (deferred) breakpoint for a class not yet loaded.
	 */
	public synchronized int registerPendingBreakpoint(
			String className, int lineNumber, int suspendPolicy, String suspendPolicyLabel) {
		int id = idCounter.getAndIncrement();
		pendingBreakpointsById.put(id, new PendingBreakpoint(className, lineNumber, suspendPolicy, suspendPolicyLabel));
		return id;
	}

	/**
	 * Looks up a pending breakpoint by its synthetic ID.
	 */
	public PendingBreakpoint getPendingBreakpoint(int id) {
		return pendingBreakpointsById.get(id);
	}

	/**
	 * Removes a pending breakpoint by ID and cleans up its ClassPrepareRequest if no longer needed.
	 *
	 * @return true if found and removed
	 */
	public synchronized boolean removePendingBreakpoint(int id) {
		PendingBreakpoint removed = pendingBreakpointsById.remove(id);
		if (removed != null) {
			cleanupClassPrepareRequestIfNeeded(removed.getClassName());
			return true;
		}
		return false;
	}

	/**
	 * Get all pending breakpoints for a given class name.
	 */
	public List<Map.Entry<Integer, PendingBreakpoint>> getPendingBreakpointsForClass(String className) {
		return pendingBreakpointsById.entrySet().stream()
			.filter(e -> e.getValue().getClassName().equals(className))
			.toList();
	}

	/**
	 * Returns all pending breakpoints (unmodifiable view).
	 */
	public Map<Integer, PendingBreakpoint> getAllPendingBreakpoints() {
		return Collections.unmodifiableMap(pendingBreakpointsById);
	}

	/**
	 * Promote a pending breakpoint to active: remove from pending, add to active with the same ID.
	 */
	public void promotePendingToActive(int id, BreakpointRequest bp) {
		pendingBreakpointsById.remove(id);
		breakpointsById.put(id, bp);
	}

	/**
	 * Mark a pending breakpoint as failed (e.g., no executable code at line).
	 */
	public void markPendingFailed(int id, String reason) {
		PendingBreakpoint pending = pendingBreakpointsById.get(id);
		if (pending != null) {
			pending.setFailureReason(reason);
		}
	}

	// ── ClassPrepareRequest tracking ──

	/**
	 * Registers a ClassPrepareRequest so deferred breakpoints can be activated when the class loads.
	 */
	public void registerClassPrepareRequest(String className, ClassPrepareRequest cpr) {
		classPrepareRequests.put(className, cpr);
	}

	/**
	 * Checks whether a ClassPrepareRequest is already registered for the given class.
	 */
	public boolean hasClassPrepareRequest(String className) {
		return classPrepareRequests.containsKey(className);
	}

	/**
	 * Removes and returns the ClassPrepareRequest for the given class, or null if none exists.
	 */
	public ClassPrepareRequest removeClassPrepareRequest(String className) {
		return classPrepareRequests.remove(className);
	}

	/**
	 * If no more pending breakpoints reference this class, delete the ClassPrepareRequest.
	 */
	private void cleanupClassPrepareRequestIfNeeded(String className) {
		boolean hasOthers = pendingBreakpointsById.values().stream()
				.anyMatch(p -> p.getClassName().equals(className))
			|| pendingExceptionBreakpointsById.values().stream()
				.anyMatch(p -> p.getExceptionClass().equals(className));
		if (!hasOthers) {
			ClassPrepareRequest cpr = classPrepareRequests.remove(className);
			if (cpr != null) {
				try {
					cpr.virtualMachine().eventRequestManager().deleteEventRequest(cpr);
				} catch (Exception e) {
					// VM may already be disconnected
				}
			}
		}
	}

	// ── Breakpoint metadata (conditions, logpoints) ──

	/**
	 * Records a condition expression for the given breakpoint. Blank/null conditions are silently
	 * ignored — no metadata row is created. Conditions are evaluated against frame 0 by
	 * {@link JdiEventListener#evaluateCondition} on every BP hit; if the expression evaluates to
	 * false the listener auto-resumes without notifying the user.
	 */
	public void setCondition(int breakpointId, String condition) {
		if (condition != null && !condition.isBlank()) {
			getOrCreateMetadata(breakpointId).condition = condition;
		}
	}

	public String getCondition(int breakpointId) {
		BreakpointMetadata meta = breakpointMetadata.get(breakpointId);
		return meta != null ? meta.condition : null;
	}

	/**
	 * Marks the breakpoint as a logpoint by attaching an expression to evaluate on each hit.
	 * Blank/null expressions are silently ignored — no metadata row is created. A non-null logpoint
	 * expression flips the breakpoint's behaviour: {@link JdiEventListener#handleBreakpointEvent}
	 * evaluates the expression via {@link io.mcp.jdwp.evaluation.JdiExpressionEvaluator}, records a
	 * `LOGPOINT` (or `LOGPOINT_ERROR`) entry in {@link EventHistory}, and auto-resumes the thread.
	 */
	public void setLogpointExpression(int breakpointId, String expression) {
		if (expression != null && !expression.isBlank()) {
			getOrCreateMetadata(breakpointId).logpointExpression = expression;
		}
	}

	public String getLogpointExpression(int breakpointId) {
		BreakpointMetadata meta = breakpointMetadata.get(breakpointId);
		return meta != null ? meta.logpointExpression : null;
	}

	public boolean isLogpoint(int breakpointId) {
		return getLogpointExpression(breakpointId) != null;
	}

	private BreakpointMetadata getOrCreateMetadata(int breakpointId) {
		return breakpointMetadata.computeIfAbsent(breakpointId, k -> new BreakpointMetadata());
	}

	// ── Exception breakpoint operations ──

	/**
	 * Registers an active exception breakpoint and returns its synthetic ID. Twin of
	 * {@link #registerBreakpoint} for the exception side of the state machine.
	 */
	public int registerExceptionBreakpoint(ExceptionRequest req, String exceptionClass, boolean caught, boolean uncaught) {
		int id = idCounter.getAndIncrement();
		exceptionBreakpointsById.put(id, new ExceptionBreakpointInfo(exceptionClass, caught, uncaught, req));
		return id;
	}

	/**
	 * Removes an exception breakpoint by ID — checks active first, then pending. Active removals
	 * also delete the underlying JDI `ExceptionRequest`; pending removals additionally clean up the
	 * `ClassPrepareRequest` if no other pending item still references the same exception class.
	 *
	 * @return `true` if found and removed, `false` if no entry exists for the given ID
	 */
	public synchronized boolean removeExceptionBreakpoint(int id) {
		ExceptionBreakpointInfo info = exceptionBreakpointsById.remove(id);
		if (info != null) {
			try {
				info.request.virtualMachine().eventRequestManager().deleteEventRequest(info.request);
			} catch (Exception e) {
				// VM may already be disconnected
			}
			return true;
		}
		PendingExceptionBreakpoint pending = pendingExceptionBreakpointsById.remove(id);
		if (pending != null) {
			cleanupClassPrepareRequestIfNeeded(pending.getExceptionClass());
			return true;
		}
		return false;
	}

	/** Returns an unmodifiable snapshot of all currently-active exception breakpoints. */
	public Map<Integer, ExceptionBreakpointInfo> getAllExceptionBreakpoints() {
		return Collections.unmodifiableMap(exceptionBreakpointsById);
	}

	/**
	 * Registers a pending exception breakpoint for a class not yet loaded. Twin of
	 * {@link #registerPendingBreakpoint} for the exception side of the state machine. The caller
	 * is expected to also register a `ClassPrepareRequest` so {@link JdiEventListener#handleClassPrepareEvent}
	 * can promote it via {@link #promotePendingExceptionToActive}.
	 */
	public synchronized int registerPendingExceptionBreakpoint(String exceptionClass, boolean caught, boolean uncaught) {
		int id = idCounter.getAndIncrement();
		pendingExceptionBreakpointsById.put(id, new PendingExceptionBreakpoint(exceptionClass, caught, uncaught));
		return id;
	}

	/**
	 * Returns every pending exception breakpoint that targets the given class name. Used by the
	 * class-prepare handler to know which entries to promote when the class loads.
	 */
	public List<Map.Entry<Integer, PendingExceptionBreakpoint>> getPendingExceptionBreakpointsForClass(String exceptionClass) {
		return pendingExceptionBreakpointsById.entrySet().stream()
			.filter(e -> e.getValue().getExceptionClass().equals(exceptionClass))
			.toList();
	}

	/** Returns an unmodifiable snapshot of all currently-pending exception breakpoints. */
	public Map<Integer, PendingExceptionBreakpoint> getAllPendingExceptionBreakpoints() {
		return Collections.unmodifiableMap(pendingExceptionBreakpointsById);
	}

	/**
	 * Promotes a pending exception breakpoint to active by removing it from the pending map and
	 * inserting an {@link ExceptionBreakpointInfo} under the same synthetic ID. No-op if the ID is
	 * unknown (e.g., the user removed it between class-prepare and promotion).
	 */
	public void promotePendingExceptionToActive(int id, ExceptionRequest req) {
		PendingExceptionBreakpoint pending = pendingExceptionBreakpointsById.remove(id);
		if (pending != null) {
			exceptionBreakpointsById.put(id, new ExceptionBreakpointInfo(
				pending.getExceptionClass(), pending.isCaught(), pending.isUncaught(), req));
		}
	}

	/**
	 * Records why a pending exception breakpoint could not be activated (e.g., the exception class
	 * exists but cannot be force-loaded). The pending entry stays in the map so the failure reason
	 * is visible to `jdwp_list_exception_breakpoints`.
	 */
	public void markPendingExceptionFailed(int id, String reason) {
		PendingExceptionBreakpoint pending = pendingExceptionBreakpointsById.get(id);
		if (pending != null) {
			pending.setFailureReason(reason);
		}
	}

	// ── Opportunistic promotion ──

	/**
	 * Re-attempts to promote every pending breakpoint and pending exception breakpoint by
	 * re-querying `vm.classesByName(...)`. This is the safety net for cases where
	 * {@link ClassPrepareRequest} does not fire — most notably bootstrap classes loaded by the JVM
	 * before any debugger event is delivered.
	 *
	 * Called from {@link JDIConnectionService#getVM()} (every MCP tool call) and
	 * from {@link JdiEventListener} (after every JDI event), so any user interaction
	 * gives pending items another chance to bind. Best-effort; transient failures are logged at
	 * debug and the item stays pending for the next retry.
	 *
	 * @return number of items promoted in this call
	 */
	public synchronized int tryPromotePending(JDIConnectionService jdiService, ThreadReference preferredThread) {
		if (jdiService == null) return 0;

		VirtualMachine vm;
		EventRequestManager erm;
		try {
			vm = jdiService.getRawVM();
			if (vm == null) return 0;
			erm = vm.eventRequestManager();
		} catch (Exception e) {
			return 0;
		}

		int promoted = 0;

		// Promote pending line breakpoints
		for (Map.Entry<Integer, PendingBreakpoint> entry :
				new ArrayList<>(pendingBreakpointsById.entrySet())) {
			int id = entry.getKey();
			PendingBreakpoint pending = entry.getValue();
			if (pending.getFailureReason() != null) continue;

			try {
				ReferenceType refType = jdiService.findOrForceLoadClass(pending.getClassName(), preferredThread);
				if (refType == null) continue;

				List<Location> locations = refType.locationsOfLine(pending.getLineNumber());
				if (locations.isEmpty()) continue;

				BreakpointRequest bp = erm.createBreakpointRequest(locations.get(0));
				bp.setSuspendPolicy(pending.getSuspendPolicy());
				bp.enable();
				promotePendingToActive(id, bp);
				promoted++;
				log.info("[Tracker] Opportunistically promoted pending breakpoint {} for {}:{}",
					id, pending.getClassName(), pending.getLineNumber());
			} catch (AbsentInformationException e) {
				// Class loaded but no debug info — try again later in case a different version arrives
			} catch (Exception e) {
				log.debug("[Tracker] Failed to promote pending breakpoint {}: {}", id, e.getMessage());
			}
		}

		// Promote pending exception breakpoints
		for (Map.Entry<Integer, PendingExceptionBreakpoint> entry :
				new ArrayList<>(pendingExceptionBreakpointsById.entrySet())) {
			int id = entry.getKey();
			PendingExceptionBreakpoint pending = entry.getValue();
			if (pending.getFailureReason() != null) continue;

			try {
				ReferenceType refType = jdiService.findOrForceLoadClass(pending.getExceptionClass(), preferredThread);
				if (refType == null) continue;

				ExceptionRequest exReq = erm.createExceptionRequest(
					refType, pending.isCaught(), pending.isUncaught());
				exReq.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
				exReq.enable();
				promotePendingExceptionToActive(id, exReq);
				promoted++;
				log.info("[Tracker] Opportunistically promoted pending exception breakpoint {} for {}",
					id, pending.getExceptionClass());
			} catch (Exception e) {
				log.debug("[Tracker] Failed to promote pending exception breakpoint {}: {}", id, e.getMessage());
			}
		}

		return promoted;
	}

	// ── Thread tracking ──

	/**
	 * Records which thread last fired a suspending event so {@link #getLastBreakpointThread} and
	 * `jdwp_get_current_thread` can resolve a default thread when none is supplied. Pass `-1` for
	 * `breakpointId` for non-breakpoint events (currently used by exception events).
	 *
	 * Publishes the {@code (thread, id)} pair atomically via a single volatile reference so
	 * concurrent readers cannot observe a crossed pair (FINDING-9).
	 */
	public void setLastBreakpointThread(ThreadReference thread, int breakpointId) {
		this.lastBreakpoint = new LastBreakpoint(thread, breakpointId);
	}

	public ThreadReference getLastBreakpointThread() {
		LastBreakpoint snapshot = lastBreakpoint;
		return snapshot != null ? snapshot.thread() : null;
	}

	public Integer getLastBreakpointId() {
		LastBreakpoint snapshot = lastBreakpoint;
		return snapshot != null ? snapshot.id() : null;
	}

	/**
	 * Atomic snapshot of the last suspending JDI event. Callers that need a consistent
	 * {@code (thread, id)} pair (e.g. {@code jdwp_get_current_thread}) must use this method
	 * rather than the individual getters, which can otherwise observe values from two different
	 * writes when called consecutively.
	 *
	 * @return the most recent {@link LastBreakpoint} snapshot, or {@code null} if no event has
	 *         fired since the last reset
	 */
	public LastBreakpoint getLastBreakpoint() {
		return lastBreakpoint;
	}

	/**
	 * Immutable {@code (thread, id)} pair published atomically in {@link #lastBreakpoint}. Using a
	 * record means readers either see a complete pair from one write or no pair at all — never a
	 * crossed pair from two different writes.
	 */
	public record LastBreakpoint(ThreadReference thread, Integer id) {}

	/**
	 * Arms a fresh single-shot latch that will be released the next time {@link #fireNextEvent()}
	 * is called. Returns the latch — callers should arm BEFORE resuming the VM and then await on
	 * the returned latch to avoid the race where the event fires between resume and arm.
	 *
	 * Used by {@link JDWPTools#jdwp_resume_until_event(Integer)} to implement synchronous
	 * "resume and wait for next stop". Replaces any previously-armed latch.
	 */
	public synchronized CountDownLatch armNextEventLatch() {
		this.nextEventLatch = new CountDownLatch(1);
		return this.nextEventLatch;
	}

	/**
	 * Releases the currently-armed latch (if any) and clears it. Called by {@link JdiEventListener}
	 * after every BP/step/exception event so that `jdwp_resume_until_event` can return.
	 *
	 * `synchronized` so an arm + fire pair is atomic — without this lock, the JDI listener thread
	 * could read a stale `nextEventLatch`, count down the wrong latch, and leave a fresh awaiter
	 * hanging.
	 */
	public synchronized void fireNextEvent() {
		CountDownLatch latch = this.nextEventLatch;
		if (latch != null) {
			latch.countDown();
			this.nextEventLatch = null;
		}
	}

	/**
	 * Best-effort, in-memory-only state wipe. Counterpart to {@link #clearAll(EventRequestManager)}
	 * for the "VM is dead" path: skips the JDI `deleteEventRequest` calls because they would fail
	 * anyway when the target VM is unreachable. Called from {@link JDIConnectionService#cleanupSessionState}
	 * during disconnect cleanup.
	 */
	public synchronized void reset() {
		// Release any awaiter BEFORE we touch state — see fireNextEvent() for why this matters.
		fireNextEvent();
		breakpointsById.clear();
		pendingBreakpointsById.clear();
		breakpointMetadata.clear();
		classPrepareRequests.clear();
		exceptionBreakpointsById.clear();
		pendingExceptionBreakpointsById.clear();
		lastBreakpoint = null;
		idCounter.set(1);
	}

	// ── Inner class ──

	/**
	 * A breakpoint registered for a class that is not yet loaded by the JVM.
	 * Will be promoted to an active breakpoint when the class loads.
	 */
	public static class PendingBreakpoint {
		private final String className;
		private final int lineNumber;
		private final int suspendPolicy;
		private final String suspendPolicyLabel;
		private volatile String failureReason;

		public PendingBreakpoint(String className, int lineNumber, int suspendPolicy, String suspendPolicyLabel) {
			this.className = className;
			this.lineNumber = lineNumber;
			this.suspendPolicy = suspendPolicy;
			this.suspendPolicyLabel = suspendPolicyLabel;
		}

		public String getClassName() { return className; }
		public int getLineNumber() { return lineNumber; }
		public int getSuspendPolicy() { return suspendPolicy; }
		public String getSuspendPolicyLabel() { return suspendPolicyLabel; }
		public String getFailureReason() { return failureReason; }
		/** Records why this pending breakpoint could not be activated (e.g., no executable code at line). */
		public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
	}

	/**
	 * Metadata for a breakpoint: optional condition expression and/or logpoint expression.
	 */
	public static class BreakpointMetadata {
		/** Boolean expression evaluated against frame 0 by {@link JdiEventListener#evaluateCondition}; `null` until set. */
		volatile String condition;
		/** Logpoint expression — when non-null, the breakpoint auto-resumes and records the result to {@link EventHistory}. */
		volatile String logpointExpression;
	}

	/**
	 * Tracks an exception breakpoint created via JDI ExceptionRequest.
	 */
	public static class ExceptionBreakpointInfo {
		private final String exceptionClass;
		private final boolean caught;
		private final boolean uncaught;
		final ExceptionRequest request;

		public ExceptionBreakpointInfo(String exceptionClass, boolean caught, boolean uncaught, ExceptionRequest request) {
			this.exceptionClass = exceptionClass;
			this.caught = caught;
			this.uncaught = uncaught;
			this.request = request;
		}

		public String getExceptionClass() { return exceptionClass; }
		public boolean isCaught() { return caught; }
		public boolean isUncaught() { return uncaught; }
		public ExceptionRequest getRequest() { return request; }
	}

	/**
	 * An exception breakpoint registered for a class that is not yet loaded by the JVM.
	 * Will be promoted to an active exception breakpoint when the class loads.
	 */
	public static class PendingExceptionBreakpoint {
		private final String exceptionClass;
		private final boolean caught;
		private final boolean uncaught;
		private volatile String failureReason;

		public PendingExceptionBreakpoint(String exceptionClass, boolean caught, boolean uncaught) {
			this.exceptionClass = exceptionClass;
			this.caught = caught;
			this.uncaught = uncaught;
		}

		public String getExceptionClass() { return exceptionClass; }
		public boolean isCaught() { return caught; }
		public boolean isUncaught() { return uncaught; }
		public String getFailureReason() { return failureReason; }
		/** Records why this pending exception breakpoint could not be activated (mirrors {@link PendingBreakpoint#setFailureReason}). */
		public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
	}
}
