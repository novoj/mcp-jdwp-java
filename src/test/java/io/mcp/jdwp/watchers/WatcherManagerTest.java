package io.mcp.jdwp.watchers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class WatcherManagerTest {

	private WatcherManager manager;

	@BeforeEach
	void setUp() {
		manager = new WatcherManager();
	}

	@Test
	void shouldCreateWatcherAndReturnId() {
		String id = manager.createWatcher("label", 1, "expr");
		assertThat(id).isNotNull().isNotEmpty();
	}

	@Test
	void shouldRetrieveWatcherById() {
		String id = manager.createWatcher("my watcher", 10, "obj.field");
		Watcher watcher = manager.getWatcher(id);

		assertThat(watcher).isNotNull();
		assertThat(watcher.getId()).isEqualTo(id);
		assertThat(watcher.getLabel()).isEqualTo("my watcher");
		assertThat(watcher.getBreakpointId()).isEqualTo(10);
		assertThat(watcher.getExpression()).isEqualTo("obj.field");
	}

	@Test
	void shouldReturnNullForUnknownWatcherId() {
		assertThat(manager.getWatcher("nonexistent")).isNull();
	}

	@Test
	void shouldRetrieveWatchersByBreakpointId() {
		manager.createWatcher("w1", 5, "a");
		manager.createWatcher("w2", 5, "b");
		manager.createWatcher("w3", 99, "c");

		List<Watcher> watchers = manager.getWatchersForBreakpoint(5);
		assertThat(watchers).hasSize(2);
		assertThat(watchers).extracting(Watcher::getLabel).containsExactlyInAnyOrder("w1", "w2");
	}

	@Test
	void shouldReturnEmptyListForBreakpointWithNoWatchers() {
		assertThat(manager.getWatchersForBreakpoint(999)).isEmpty();
	}

	@Test
	void shouldReturnAllWatchers() {
		manager.createWatcher("a", 1, "x");
		manager.createWatcher("b", 2, "y");
		manager.createWatcher("c", 3, "z");

		assertThat(manager.getAllWatchers()).hasSize(3);
	}

	@Test
	void shouldDeleteWatcherById() {
		String id = manager.createWatcher("to delete", 1, "expr");
		assertThat(manager.deleteWatcher(id)).isTrue();
		assertThat(manager.getWatcher(id)).isNull();
	}

	@Test
	void shouldReturnFalseWhenDeletingNonexistentWatcher() {
		assertThat(manager.deleteWatcher("fake-id")).isFalse();
	}

	@Test
	void shouldRemoveFromBothIndexesOnDelete() {
		String id = manager.createWatcher("w", 7, "expr");
		manager.deleteWatcher(id);

		assertThat(manager.getWatcher(id)).isNull();
		assertThat(manager.getWatchersForBreakpoint(7)).isEmpty();
	}

	@Test
	void shouldCleanupBreakpointIndexWhenLastWatcherRemoved() {
		String id1 = manager.createWatcher("w1", 10, "a");
		String id2 = manager.createWatcher("w2", 10, "b");

		manager.deleteWatcher(id1);
		assertThat(manager.getWatchersForBreakpoint(10)).hasSize(1);

		manager.deleteWatcher(id2);
		assertThat(manager.getWatchersForBreakpoint(10)).isEmpty();
	}

	@Test
	void shouldDeleteAllWatchersForBreakpoint() {
		manager.createWatcher("w1", 3, "a");
		manager.createWatcher("w2", 3, "b");
		manager.createWatcher("w3", 3, "c");
		manager.createWatcher("other", 99, "d");

		int deleted = manager.deleteWatchersForBreakpoint(3);
		assertThat(deleted).isEqualTo(3);
		assertThat(manager.getWatchersForBreakpoint(3)).isEmpty();
		assertThat(manager.getAllWatchers()).hasSize(1);
	}

	@Test
	void shouldReturnZeroWhenDeletingWatchersForBreakpointWithNone() {
		assertThat(manager.deleteWatchersForBreakpoint(999)).isZero();
	}

	@Test
	void shouldClearAll() {
		manager.createWatcher("a", 1, "x");
		manager.createWatcher("b", 2, "y");
		manager.clearAll();

		assertThat(manager.getAllWatchers()).isEmpty();
		assertThat(manager.getWatchersForBreakpoint(1)).isEmpty();
		assertThat(manager.getWatchersForBreakpoint(2)).isEmpty();
	}

	@Test
	void shouldReturnCorrectStats() {
		manager.createWatcher("w1", 1, "a");
		manager.createWatcher("w2", 1, "b");
		manager.createWatcher("w3", 2, "c");

		Map<String, Object> stats = manager.getStats();
		assertThat(stats.get("totalWatchers")).isEqualTo(3);
		assertThat(stats.get("breakpointsWithWatchers")).isEqualTo(2);
		assertThat((double) stats.get("avgWatchersPerBreakpoint")).isEqualTo(1.5);
	}

	@Test
	void shouldReturnZeroStatsWhenEmpty() {
		Map<String, Object> stats = manager.getStats();
		assertThat(stats.get("totalWatchers")).isEqualTo(0);
		assertThat(stats.get("breakpointsWithWatchers")).isEqualTo(0);
		assertThat((double) stats.get("avgWatchersPerBreakpoint")).isEqualTo(0.0);
	}

	@Test
	void shouldHandleConcurrentCreateAndDelete() throws Exception {
		int threadCount = 8;
		int operationsPerThread = 100;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		AtomicBoolean failed = new AtomicBoolean(false);
		CyclicBarrier barrier = new CyclicBarrier(threadCount);

		List<Future<?>> futures = new ArrayList<>();
		for (int t = 0; t < threadCount; t++) {
			final int threadIndex = t;
			futures.add(executor.submit(() -> {
				try {
					barrier.await(5, TimeUnit.SECONDS);
					List<String> createdIds = new ArrayList<>();
					for (int i = 0; i < operationsPerThread; i++) {
						String id = manager.createWatcher(
							"thread-" + threadIndex + "-" + i,
							threadIndex,
							"expr" + i
						);
						createdIds.add(id);

						// Concurrently read watchers for this breakpoint — must not throw
						List<Watcher> snapshot = manager.getWatchersForBreakpoint(threadIndex);
						assertThat(snapshot).isNotNull();
					}
					// Delete half of the watchers we created
					for (int i = 0; i < createdIds.size() / 2; i++) {
						manager.deleteWatcher(createdIds.get(i));
						// Read again after delete — defensive copy must be safe
						List<Watcher> snapshot = manager.getWatchersForBreakpoint(threadIndex);
						assertThat(snapshot).isNotNull();
					}
				} catch (Exception e) {
					failed.set(true);
				}
			}));
		}

		for (Future<?> future : futures) {
			future.get(10, TimeUnit.SECONDS);
		}
		executor.shutdown();

		assertThat(failed).as("No thread should have thrown an exception").isFalse();
		assertThat(manager.getAllWatchers()).isNotEmpty();
	}
}
