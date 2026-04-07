package io.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-thread reentrancy guard that tracks which target-VM threads are currently executing an
 * MCP-initiated {@code invokeMethod} chain. Used by {@link JdiEventListener} to suppress
 * breakpoint / exception / step events that fire on a thread that is already mid-evaluation —
 * without this suppression, the listener would try to suspend the very thread that the outer
 * {@code invokeMethod} is waiting on, producing a cross-thread deadlock (the MCP server freezes
 * until the evaluation times out).
 *
 * <p><b>Usage contract:</b> every MCP-driven {@code invokeMethod} path captures the
 * {@link ThreadReference#uniqueID()} of the invoking thread ONCE at the top of the call and
 * passes the resulting {@code long} to both {@link #enter(long)} and {@link #exit(long)} via a
 * try/finally. Capturing the id up front — rather than re-querying it from a {@code ThreadReference}
 * inside {@code exit} — is deliberate: if the target thread dies during the evaluation, its
 * {@code ThreadReference.uniqueID()} call throws {@code ObjectCollectedException}, which would
 * propagate out of the cleanup and leave a dangling entry in the depth map.
 *
 * <p>The guard is counted (not a boolean) so layered call sites — e.g. {@code configureCompilerClasspath}
 * calling {@code discoverClasspath} which itself issues {@code invokeMethod} calls nested inside
 * the outer {@code evaluate()} — all increment a shared depth and release the guard in reverse
 * order. An {@code exit} with no matching {@code enter} is a no-op (defensive) rather than an
 * error, because half-finished cleanup should never throw.
 *
 * <p><b>Why {@code long} for mutations but {@link ThreadReference} for reads:</b>
 * {@link #isEvaluating(ThreadReference)} is the listener's hot-path check, called on every
 * suspending JDI event. JDI guarantees that the {@code ThreadReference} carried by a live
 * {@code BreakpointEvent} / {@code StepEvent} / {@code ExceptionEvent} is itself live at the
 * moment of delivery, so {@code uniqueID()} is safe there. The mutation path lives across an
 * {@code invokeMethod} round-trip where the thread can die, so it takes a pre-captured id.
 *
 * <p>Thread-safety: backed by a {@link ConcurrentHashMap}; all mutations go through
 * {@code merge}/{@code compute} so enter and exit from different threads on the same key are
 * atomic. No locks are held across {@code invokeMethod} calls.
 */
@Slf4j
@Service
public class EvaluationGuard {

	/**
	 * Depth counter per target-VM thread. An entry is present iff the thread is currently inside
	 * at least one MCP-driven {@code invokeMethod} chain; the value is the current nesting depth.
	 * Absent entries mean "not evaluating" — we remove entries on the last {@code exit} to keep
	 * the map compact and {@link #isEvaluating} branch-free.
	 */
	private final ConcurrentMap<Long, Integer> depthByThreadId = new ConcurrentHashMap<>();

	/**
	 * Marks the thread with the given {@code threadUniqueId} as entering an MCP-driven
	 * evaluation. Safe to call repeatedly for nested invocations — the depth counter increases
	 * each time and the matching {@link #exit(long)} calls must balance the entries for the
	 * guard to clear.
	 *
	 * <p>Callers MUST capture {@link ThreadReference#uniqueID()} at the call site (before
	 * starting any {@code invokeMethod} work) and pass the resulting {@code long} to both
	 * {@code enter} and the paired {@link #exit(long)}. See the class JavaDoc for the rationale.
	 */
	public void enter(long threadUniqueId) {
		depthByThreadId.merge(threadUniqueId, 1, Integer::sum);
	}

	/**
	 * Marks the thread with the given {@code threadUniqueId} as leaving one level of an
	 * MCP-driven evaluation. When the depth reaches zero the entry is removed. An {@code exit}
	 * with no matching {@code enter} is a no-op so that defensive try/finally cleanup can never
	 * throw — this matters because the id may outlive the underlying thread if the target
	 * thread died during the invocation chain.
	 */
	public void exit(long threadUniqueId) {
		depthByThreadId.compute(threadUniqueId, (k, v) -> (v == null || v <= 1) ? null : v - 1);
	}

	/**
	 * Returns true iff {@code thread} is currently inside at least one MCP-driven evaluation.
	 * Queried by {@link JdiEventListener} on every suspending JDI event; must be O(1). JDI
	 * guarantees the {@code ThreadReference} on a live event is itself live, so calling
	 * {@code uniqueID()} here cannot throw {@code ObjectCollectedException}.
	 */
	public boolean isEvaluating(ThreadReference thread) {
		return depthByThreadId.containsKey(thread.uniqueID());
	}

	/**
	 * Current nesting depth for the thread with the given id; zero if not evaluating. Exposed
	 * for unit tests and defensive diagnostics — callers should prefer {@link #isEvaluating} for
	 * the hot-path check.
	 */
	int depth(long threadUniqueId) {
		Integer d = depthByThreadId.get(threadUniqueId);
		return d == null ? 0 : d;
	}
}
