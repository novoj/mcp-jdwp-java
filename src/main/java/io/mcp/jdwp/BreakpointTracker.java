package io.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.ThreadReference;
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
@Service
public class BreakpointTracker {

	private final AtomicInteger idCounter = new AtomicInteger(1);
	private final ConcurrentHashMap<Integer, BreakpointRequest> breakpointsById = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Integer, PendingBreakpoint> pendingBreakpointsById = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, ClassPrepareRequest> classPrepareRequests = new ConcurrentHashMap<>();

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

		for (ClassPrepareRequest cpr : classPrepareRequests.values()) {
			try {
				erm.deleteEventRequest(cpr);
			} catch (Exception e) {
				// VM may already be disconnected
			}
		}
		classPrepareRequests.clear();

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
			.anyMatch(p -> p.getClassName().equals(className));
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
		classPrepareRequests.clear();
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
}
