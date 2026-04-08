package one.edee.jdwp.sandbox.events;

/**
 * Handler interface for processing order events.
 */
public interface EventHandler {

	void handle(OrderEvent event) throws EventHandlerException;
}
