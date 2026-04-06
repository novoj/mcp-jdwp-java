package io.mcp.jdwp.sandbox.bank;

/**
 * Transfers funds between accounts. Each individual operation is synchronized,
 * but the transfer as a whole is not atomic.
 */
public class TransferService {

	private final AuditService auditService;

	public TransferService(AuditService auditService) {
		this.auditService = auditService;
	}

	/**
	 * Transfers amount from one account to another.
	 * Snapshots balances for auditing between the two operations.
	 */
	public void transfer(BankAccount from, BankAccount to, int amount) {
		from.withdraw(amount);

		// Snapshot balances for audit trail — happens BETWEEN withdraw and deposit
		auditService.snapshotBalances(from, to);

		to.deposit(amount);
	}
}
