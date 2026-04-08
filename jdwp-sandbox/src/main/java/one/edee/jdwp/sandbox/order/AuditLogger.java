package one.edee.jdwp.sandbox.order;

/**
 * Logs order details for auditing purposes.
 * Appears to be a read-only operation but secretly mutates order state.
 */
public class AuditLogger {

	private String lastFormattedTotal;

	/**
	 * Logs the order. Looks like a pure read operation.
	 */
	public void log(Order order) {
		String entry = "Order " + order.getId() + " processed with total: " + order.getTotal();
		cacheFormattedTotal(order);
		lastFormattedTotal = entry;
	}

	public String getLastFormattedTotal() {
		return lastFormattedTotal;
	}

	/**
	 * Caches a formatted version of the total for future display.
	 * The name suggests a read-only caching operation.
	 */
	private void cacheFormattedTotal(Order order) {
		// "Normalize" the total for consistent display — silently truncates cents
		double normalized = (double) (int) (order.getTotal());
		order.setTotal(normalized);
	}
}
