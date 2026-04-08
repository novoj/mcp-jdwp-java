package one.edee.jdwp.sandbox.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventBusTest {

	private Inventory inventory;
	private EventBus eventBus;

	@BeforeEach
	void setUp() {
		inventory = new Inventory();
		eventBus = new EventBus();
		eventBus.register(new InventoryHandler(inventory));
	}

	@Test
	void shouldDispatchAndUpdateInventory() {
		inventory.restock("WIDGET", 100);
		OrderEvent event = new OrderEvent("ORD-1", "WIDGET", 200); // looks like qty=200

		eventBus.dispatch(event);

		// Expect stock to be reduced
		assertThat(inventory.getStock("WIDGET"))
			.describedAs("Stock should be 100 - 200 = -100... or should it?")
			.isLessThan(100); // Fails: stock is still 100 (handler threw, silently)

		// Even checking errors doesn't help much
		assertThat(eventBus.getErrorSummary())
			.describedAs("No errors should have occurred")
			.isEmpty(); // Also fails: there IS an error, but message is useless
	}
}
