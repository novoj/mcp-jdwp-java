package one.edee.jdwp.sandbox.events;

/**
 * Represents an order event with product and quantity information.
 * The quantity field undergoes a byte cast during construction, simulating
 * a deserialization bug that causes overflow for values above 127.
 */
public class OrderEvent {

	private final String orderId;
	private final String productId;
	private final int quantity;

	/**
	 * Creates an order event. The rawQuantity is cast through byte,
	 * causing overflow for values greater than 127.
	 */
	public OrderEvent(String orderId, String productId, int rawQuantity) {
		this.orderId = orderId;
		this.productId = productId;
		this.quantity = (byte) rawQuantity; // Overflow: 200 becomes -56
	}

	public String getOrderId() {
		return orderId;
	}

	public String getProductId() {
		return productId;
	}

	public int getQuantity() {
		return quantity;
	}
}
