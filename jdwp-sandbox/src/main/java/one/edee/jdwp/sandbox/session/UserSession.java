package one.edee.jdwp.sandbox.session;

import java.util.Objects;

/**
 * Represents a user session with a userId and role.
 * Used as a HashMap key — hashCode/equals depend on both fields.
 */
public class UserSession {

	private final String userId;
	private String role;

	public UserSession(String userId, String role) {
		this.userId = userId;
		this.role = role;
	}

	public String getUserId() {
		return userId;
	}

	public String getRole() {
		return role;
	}

	/**
	 * Upgrades the user's role. This mutates a field that participates in hashCode/equals.
	 */
	public void upgradeRole(String newRole) {
		this.role = newRole;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		UserSession that = (UserSession) o;
		return Objects.equals(userId, that.userId) && Objects.equals(role, that.role);
	}

	@Override
	public int hashCode() {
		return Objects.hash(userId, role);
	}
}
