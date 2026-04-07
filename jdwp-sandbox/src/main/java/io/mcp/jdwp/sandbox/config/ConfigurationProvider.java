package io.mcp.jdwp.sandbox.config;

import java.util.concurrent.CountDownLatch;

/**
 * Provides a lazily-initialized Configuration singleton.
 * Contains a deliberate initialization ordering bug: the instance reference
 * is published before init() completes.
 */
public class ConfigurationProvider {

	private Configuration instance;
	private final CountDownLatch assignedLatch = new CountDownLatch(1);
	private final CountDownLatch initCompleteLatch = new CountDownLatch(1);

	/**
	 * Returns the configuration instance. A reader thread may see
	 * a partially-initialized Configuration (timeout = 0) because
	 * the instance is published before init() is called.
	 */
	public Configuration getConfig() {
		if (instance != null) {
			return instance;
		}
		synchronized (this) {
			if (instance != null) {
				return instance;
			}
			// Assign instance BEFORE calling init() — deliberate bug
			instance = new Configuration("MyApp");
			// Signal that the reference is now visible
			assignedLatch.countDown();
			try {
				// Wait for the reader thread to observe the partial state
				initCompleteLatch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			instance.init(5000);
			return instance;
		}
	}

	/**
	 * Waits until the instance reference has been assigned (but not yet initialized).
	 */
	public void awaitAssignment() throws InterruptedException {
		assignedLatch.await();
	}

	/**
	 * Signals that the reader has observed the partial state and init() can proceed.
	 */
	public void releaseInit() {
		initCompleteLatch.countDown();
	}
}
