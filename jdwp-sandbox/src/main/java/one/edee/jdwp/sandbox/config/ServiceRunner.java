package one.edee.jdwp.sandbox.config;

/**
 * Uses the configuration to compute operational parameters.
 * Fails with ArithmeticException when timeout is 0.
 */
public class ServiceRunner {

	/**
	 * Computes a retry count based on the configured timeout.
	 * Throws ArithmeticException if timeout is 0 (partially initialized config).
	 */
	public int run(ConfigurationProvider provider) {
		int timeout = provider.getConfig().getTimeout();
		return 10000 / timeout; // ArithmeticException when timeout = 0
	}
}
