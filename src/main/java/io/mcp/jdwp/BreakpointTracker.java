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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks breakpoints set by this MCP server and the last thread that hit a breakpoint.
 * Supports both active (resolved) and pending (deferred) breakpoints for classes not yet loaded.
 */
@Slf4j
@Service
public class BreakpointTracker {

	private final AtomicInteger idCounter = new AtomicInteger(1);
	private final ConcurrentHashMap<Integer, BreakpointRequest> breakpointsById = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Integer, PendingBreakpoint> pendingBreakpointsById = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, ClassPrepareRequest> classPrepareRequests = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Integer, BreakpointMetadata> breakpointMetadata = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Integer, ExceptionBreakpointInfo> exceptionBreakpointsById = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Integer, PendingExceptionBreakpoint> pendingExceptionBreakpointsById = new ConcurrentHashMap<>();

	private volatile ThreadReference lastBreakpointThread;
	private volatile Integer lastBreakpointId;

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
	 * Remove a breakpoint by ID — checks active first, then pending.
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
			return true;
		}

		// Try pending breakpoints
		PendingBreakpoint pending = pendingBreakpointsById.remove(id);
		if (pending != null) {
			cleanupClassPrepareRequestIfNeeded(pending.getClassName());
			return true;
		}

		return false;
	}

	/**
	 * Remove a breakpoint by its JDI request reference (reverse lookup).
	 */
	public void unregisterByRequest(BreakpointRequest bp) {
		breakpointsById.entrySet().removeIf(e -> e.getValue() == bp);
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
	 * Clear all tracked breakpoints (active + pending) and delete their JDI event requests.
	 */
	public synchronized void clearAll(EventRequestManager erm) {
		for (BreakpointRequest bp : breakpointsById.values()) {
			try {
				erm.deleteEventRequest(bp);
			} catch (Exception e) {
				// VM may already be disconnected
			}
		}
		breakpointsById.clear();

		pendingBreakpointsById.clear();
		breakpointMetadata.clear();

		for (ClassPrepareRequest cpr : classPrepareRequests.values()) {
			try {
				erm.deleteEventRequest(cpr);
			} catch (Exception e) {
				// VM may already be disconnected
			}
		}
		classPrepareRequests.clear();

		for (ExceptionBreakpointInfo info : exceptionBreakpointsById.values()) {
			try {
				erm.deleteEventRequest(info.request);
			} catch (Exception e) {
				// VM may already be disconnected
			}
		}
		exceptionBreakpointsById.clear();
		pendingExceptionBreakpointsById.clear();

		lastBreakpointThread = null;
		lastBreakpointId = null;
		idCounter.set(1);
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

	public void setCondition(int breakpointId, String condition) {
		if (condition != null && !condition.isBlank()) {
			getOrCreateMetadata(breakpointId).condition = condition;
		}
	}

	public String getCondition(int breakpointId) {
		BreakpointMetadata meta = breakpointMetadata.get(breakpointId);
		return meta != null ? meta.condition : null;
	}

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

	public int registerExceptionBreakpoint(ExceptionRequest req, String exceptionClass, boolean caught, boolean uncaught) {
		int id = idCounter.getAndIncrement();
		exceptionBreakpointsById.put(id, new ExceptionBreakpointInfo(exceptionClass, caught, uncaught, req));
		return id;
	}

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

	public Map<Integer, ExceptionBreakpointInfo> getAllExceptionBreakpoints() {
		return Collections.unmodifiableMap(exceptionBreakpointsById);
	}

	public synchronized int registerPendingExceptionBreakpoint(String exceptionClass, boolean caught, boolean uncaught) {
		int id = idCounter.getAndIncrement();
		pendingExceptionBreakpointsById.put(id, new PendingExceptionBreakpoint(exceptionClass, caught, uncaught));
		return id;
	}

	public List<Map.Entry<Integer, PendingExceptionBreakpoint>> getPendingExceptionBreakpointsForClass(String exceptionClass) {
		return pendingExceptionBreakpointsById.entrySet().stream()
			.filter(e -> e.getValue().getExceptionClass().equals(exceptionClass))
			.toList();
	}

	public Map<Integer, PendingExceptionBreakpoint> getAllPendingExceptionBreakpoints() {
		return Collections.unmodifiableMap(pendingExceptionBreakpointsById);
	}

	public void promotePendingExceptionToActive(int id, ExceptionRequest req) {
		PendingExceptionBreakpoint pending = pendingExceptionBreakpointsById.remove(id);
		if (pending != null) {
			exceptionBreakpointsById.put(id, new ExceptionBreakpointInfo(
				pending.getExceptionClass(), pending.isCaught(), pending.isUncaught(), req));
		}
	}

	public void markPendingExceptionFailed(int id, String reason) {
		PendingExceptionBreakpoint pending = pendingExceptionBreakpointsById.get(id);
		if (pending != null) {
			pending.setFailureReason(reason);
		}
	}

	// ── Opportunistic promotion ──

	/**
	 * Re-attempts to promote every pending breakpoint and pending exception breakpoint by
	 * re-querying {@code vm.classesByName(...)}. This is the safety net for cases where
	 * {@link ClassPrepareRequest} does not fire — most notably bootstrap classes loaded by the JVM
	 * before any debugger event is delivered.
	 *
	 * <p>Called from {@link io.mcp.jdwp.JDIConnectionService#getVM()} (every MCP tool call) and
	 * from {@link io.mcp.jdwp.JdiEventListener} (after every JDI event), so any user interaction
	 * gives pending items another chance to bind. Best-effort; transient failures are logged at
	 * debug and the item stays pending for the next retry.
	 *
	 * @return number of items promoted in this call
	 */
	public synchronized int tryPromotePending(io.mcp.jdwp.JDIConnectionService jdiService, ThreadReference preferredThread) {
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
				new java.util.ArrayList<>(pendingBreakpointsById.entrySet())) {
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
				new java.util.ArrayList<>(pendingExceptionBreakpointsById.entrySet())) {
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
	 * Record which thread last hit a breakpoint.
	 */
	public void setLastBreakpointThread(ThreadReference thread, int breakpointId) {
		this.lastBreakpointThread = thread;
		this.lastBreakpointId = breakpointId;
	}

	public ThreadReference getLastBreakpointThread() {
		return lastBreakpointThread;
	}

	public Integer getLastBreakpointId() {
		return lastBreakpointId;
	}

	/**
	 * Reset all state (called on disconnect).
	 */
	public synchronized void reset() {
		breakpointsById.clear();
		pendingBreakpointsById.clear();
		breakpointMetadata.clear();
		classPrepareRequests.clear();
		exceptionBreakpointsById.clear();
		pendingExceptionBreakpointsById.clear();
		lastBreakpointThread = null;
		lastBreakpointId = null;
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
		volatile String condition;
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
		public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
	}
}
