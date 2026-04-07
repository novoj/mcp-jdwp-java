package io.mcp.jdwp.sandbox.session;

import java.util.HashMap;
import java.util.Map;

/**
 * Stores session data keyed by UserSession objects.
 */
public class SessionStore {

	private final Map<UserSession, SessionData> sessions = new HashMap<>();

	public void store(UserSession session, SessionData data) {
		sessions.put(session, data);
	}

	public SessionData retrieve(UserSession session) {
		return sessions.get(session);
	}

	/**
	 * Upgrades the user's role. This mutates the key object while it's in the map.
	 */
	public void upgradeUserRole(UserSession session, String newRole) {
		session.upgradeRole(newRole);
	}

	public int size() {
		return sessions.size();
	}
}
