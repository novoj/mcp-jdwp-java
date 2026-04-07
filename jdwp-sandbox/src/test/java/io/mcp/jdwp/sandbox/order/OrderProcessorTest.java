package io.mcp.jdwp.sandbox.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderProcessorTest {

	private OrderProcessor processor;

	@BeforeEach
	void setUp() {
		AuditLogger auditLogger = new AuditLogger();
		processor = new OrderProcessor(auditLogger);
	}

	@Test
	void shouldApplyDiscountCorrectly() {
		Order order = new Order("ORD-1", List.of("Widget", "Gadget"));
		processor.process(order, 10.0); // 10% discount
		// Widget = 29.99, Gadget = 49.99 -> subtotal = 79.98 -> 10% off = 71.982
		assertThat(order.getTotal()).isEqualTo(71.982);
	}
}
