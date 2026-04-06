package io.mcp.jdwp.sandbox.bank;

/**
 * Captures balance snapshots for reconciliation.
 */
public class AuditService {

	private int lastTotalSnapshot;

	/**
	 * Takes a snapshot of the combined balance of two accounts.
	 */
	public void snapshotBalances(BankAccount a, BankAccount b) {
		lastTotalSnapshot = a.getBalance() + b.getBalance();
	}

	/**
	 * Returns the snapshot value captured during the last transfer.
	 */
	public int getLastSnapshot() {
		return lastTotalSnapshot;
	}

	/**
	 * Compares current total against the snapshot.
	 * Returns 0 if consistent, non-zero indicates discrepancy.
	 */
	public int reconcile(BankAccount a, BankAccount b) {
		int currentTotal = a.getBalance() + b.getBalance();
		return currentTotal - lastTotalSnapshot;
	}
}
