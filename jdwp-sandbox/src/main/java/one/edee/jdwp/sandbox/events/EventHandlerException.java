package one.edee.jdwp.sandbox.events;

/**
 * Checked exception for event handler failures.
 */
public class EventHandlerException extends Exception {

	public EventHandlerException(String message, Throwable cause) {
		super(message, cause);
	}
}
