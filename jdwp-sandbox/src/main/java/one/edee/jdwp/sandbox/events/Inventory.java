package one.edee.jdwp.sandbox.events;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks available stock for products.
 */
public class Inventory {

	private final Map<String, Integer> available = new HashMap<>();

	/**
	 * Restocks a product with the given quantity.
	 */
	public void restock(String productId, int qty) {
		available.merge(productId, qty, Integer::sum);
	}

	/**
	 * Reserves stock for a product. Throws if quantity is negative.
	 */
	public void reserve(String productId, int qty) {
		if (qty < 0) {
			throw new IllegalStateException(
				"Cannot reserve negative quantity: " + qty + " for product " + productId
			);
		}
		available.merge(productId, -qty, Integer::sum);
	}

	/**
	 * Returns the current stock level for a product.
	 */
	public int getStock(String productId) {
		return available.getOrDefault(productId, 0);
	}
}
