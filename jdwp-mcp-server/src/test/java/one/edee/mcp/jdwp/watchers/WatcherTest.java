package one.edee.mcp.jdwp.watchers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WatcherTest {

	@Test
	void shouldGenerateUniqueId() {
		Watcher watcher = new Watcher("test", 1, "obj.field");
		assertThat(watcher.getId()).isNotNull().isNotEmpty();
	}

	@Test
	void shouldStoreLabel() {
		Watcher watcher = new Watcher("my label", 1, "expr");
		assertThat(watcher.getLabel()).isEqualTo("my label");
	}

	@Test
	void shouldStoreBreakpointId() {
		Watcher watcher = new Watcher("label", 42, "expr");
		assertThat(watcher.getBreakpointId()).isEqualTo(42);
	}

	@Test
	void shouldStoreExpression() {
		Watcher watcher = new Watcher("label", 1, "users.stream().count()");
		assertThat(watcher.getExpression()).isEqualTo("users.stream().count()");
	}

	@Test
	void shouldGenerateDistinctIdsForDifferentInstances() {
		Watcher first = new Watcher("a", 1, "x");
		Watcher second = new Watcher("b", 2, "y");
		assertThat(first.getId()).isNotEqualTo(second.getId());
	}

	@Test
	void shouldFormatToStringCorrectly() {
		Watcher watcher = new Watcher("trace entity", 5, "entity.getId()");
		String result = watcher.toString();

		assertThat(result)
			.contains(watcher.getId())
			.contains("trace entity")
			.contains("5")
			.contains("entity.getId()");
	}
}
