package io.mcp.jdwp.sandbox.session;

/**
 * Simple holder for session metadata.
 */
public class SessionData {

	private final long loginTime;
	private final long lastAccess;

	public SessionData(long loginTime, long lastAccess) {
		this.loginTime = loginTime;
		this.lastAccess = lastAccess;
	}

	public long getLoginTime() {
		return loginTime;
	}

	public long getLastAccess() {
		return lastAccess;
	}
}
