package io.mcp.jdwp.sandbox.recursion;

/**
 * Naïve recursive Fibonacci used as the target surface for the "Echo Chamber" test flight.
 * The production code is intentionally correct — the scenario's debugger interaction is what
 * matters: set a breakpoint inside {@link #compute(int)} and, from inside the hit, evaluate
 * {@code this.compute(N)} to drive a controlled re-entry into the same breakpointed line.
 *
 * <p>See {@code package-info.java} for the full test flight script.
 */
public class RecursiveCalculator {

	/**
	 * Returns the n-th Fibonacci number using straight recursion. Set the breakpoint on the
	 * {@code int left = compute(n - 1);} line (the recursive call inside the non-base branch)
	 * so the test flight can trigger an expression-driven re-entry.
	 */
	public int compute(int n) {
		if (n <= 1) {
			return n;
		}
		int left = compute(n - 1);
		int right = compute(n - 2);
		return left + right;
	}
}
