package io.mcp.jdwp.sandbox.order;

import java.util.Map;

/**
 * Processes orders by computing totals from a price catalog and applying discounts.
 */
public class OrderProcessor {

	private static final Map<String, Double> PRICE_CATALOG = Map.of(
		"Widget", 29.99,
		"Gadget", 49.99,
		"Doohickey", 14.50,
		"Thingamajig", 99.95
	);

	private final AuditLogger auditLogger;

	public OrderProcessor(AuditLogger auditLogger) {
		this.auditLogger = auditLogger;
	}

	/**
	 * Calculates the order total with a discount percentage, then logs it.
	 *
	 * @param order the order to process
	 * @param discountPercent discount percentage (e.g. 10.0 for 10%)
	 */
	public void process(Order order, double discountPercent) {
		double subtotal = 0.0;
		for (String item : order.getItems()) {
			Double price = PRICE_CATALOG.get(item);
			if (price != null) {
				subtotal += price;
			}
		}
		double discountedTotal = subtotal * (1.0 - discountPercent / 100.0);
		order.setTotal(discountedTotal);

		// Log the order — looks harmless
		auditLogger.log(order);
	}
}
