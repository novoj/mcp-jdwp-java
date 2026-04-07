package io.mcp.jdwp.sandbox.order;

import java.util.List;

/**
 * Mutable order POJO with id, items list, and computed total.
 */
public class Order {

	private final String id;
	private final List<String> items;
	private double total;

	public Order(String id, List<String> items) {
		this.id = id;
		this.items = items;
	}

	public String getId() {
		return id;
	}

	public List<String> getItems() {
		return items;
	}

	public double getTotal() {
		return total;
	}

	public void setTotal(double total) {
		this.total = total;
	}
}
