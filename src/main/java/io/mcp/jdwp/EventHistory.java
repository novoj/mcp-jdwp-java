package io.mcp.jdwp;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Bounded ring buffer of debug events. Records all JDI events (breakpoints, steps, exceptions,
 * logpoints, etc.) for post-mortem inspection via the jdwp_get_events tool.
 */
@Service
public class EventHistory {

	private static final int MAX_EVENTS = 500;
	private final Deque<DebugEvent> events = new ConcurrentLinkedDeque<>();

	public void record(DebugEvent event) {
		events.addLast(event);
		while (events.size() > MAX_EVENTS) {
			events.pollFirst();
		}
	}

	public List<DebugEvent> getRecent(int count) {
		List<DebugEvent> all = new ArrayList<>(events);
		int from = Math.max(0, all.size() - count);
		return all.subList(from, all.size());
	}

	public void clear() {
		events.clear();
	}

	public int size() {
		return events.size();
	}

	/**
	 * A single debug event captured from the JDI event queue or logpoint evaluation.
	 */
	public record DebugEvent(Instant timestamp, String type, String summary, Map<String, String> details) {
		public DebugEvent(String type, String summary) {
			this(Instant.now(), type, summary, Map.of());
		}

		public DebugEvent(String type, String summary, Map<String, String> details) {
			this(Instant.now(), type, summary, details);
		}
	}
}
