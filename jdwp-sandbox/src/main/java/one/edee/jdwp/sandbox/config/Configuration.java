package one.edee.jdwp.sandbox.config;

/**
 * Application configuration. The appName is set in the constructor,
 * but timeout requires a separate init() call.
 */
public class Configuration {

	private final String appName;
	private int timeout;

	public Configuration(String appName) {
		this.appName = appName;
		// timeout is NOT set here — requires init()
	}

	/**
	 * Initializes the timeout value. Must be called after construction.
	 */
	public void init(int timeout) {
		this.timeout = timeout;
	}

	public String getAppName() {
		return appName;
	}

	public int getTimeout() {
		return timeout;
	}
}
