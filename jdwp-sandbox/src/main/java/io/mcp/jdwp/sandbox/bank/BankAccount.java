package io.mcp.jdwp.sandbox.bank;

/**
 * Thread-safe bank account with synchronized individual operations.
 */
public class BankAccount {

	private int balance;

	public BankAccount(int initialBalance) {
		this.balance = initialBalance;
	}

	public synchronized void deposit(int amount) {
		balance += amount;
	}

	public synchronized void withdraw(int amount) {
		balance -= amount;
	}

	public synchronized int getBalance() {
		return balance;
	}
}
