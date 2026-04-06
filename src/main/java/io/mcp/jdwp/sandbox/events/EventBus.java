package io.mcp.jdwp.sandbox.events;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Dispatches order events to registered handlers.
 * Catches and stores errors, but only exposes top-level messages.
 */
public class EventBus {

	private final List<EventHandler> handlers = new ArrayList<>();
	private final List<CompletionException> errors = new ArrayList<>();

	public void register(EventHandler handler) {
		handlers.add(handler);
	}

	/**
	 * Dispatches an event to all registered handlers.
	 * Errors are caught and wrapped — root cause is buried.
	 */
	public void dispatch(OrderEvent event) {
		for (EventHandler handler : handlers) {
			try {
				handler.handle(event);
			} catch (Exception e) {
				errors.add(new CompletionException(
					"Async task failed",
					new EventHandlerException("Handler failed", e)
				));
			}
		}
	}

	/**
	 * Returns the raw error list.
	 */
	public List<CompletionException> getErrors() {
		return errors;
	}

	/**
	 * Returns only the top-level error messages — useless for root cause analysis.
	 */
	public List<String> getErrorSummary() {
		return errors.stream()
			.map(Throwable::getMessage)
			.collect(Collectors.toList());
	}
}
