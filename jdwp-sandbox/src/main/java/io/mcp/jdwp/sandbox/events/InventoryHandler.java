package io.mcp.jdwp.sandbox.events;

/**
 * Handles order events by reserving inventory.
 */
public class InventoryHandler implements EventHandler {

	private final Inventory inventory;

	public InventoryHandler(Inventory inventory) {
		this.inventory = inventory;
	}

	@Override
	public void handle(OrderEvent event) throws EventHandlerException {
		try {
			inventory.reserve(event.getProductId(), event.getQuantity());
		} catch (Exception e) {
			throw new EventHandlerException("Handler failed", e);
		}
	}
}
