package io.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ThreadFormatting}. Covers the JDI status integer translation and the
 * pure-string variant of the JVM-internal-thread filter. The {@code ThreadReference} overload
 * is exercised indirectly through {@link ThreadFormatting#isJvmInternalThreadName(String)}; we
 * deliberately do not stand up a real JDI connection here.
 */
class ThreadFormattingTest {

	// ── formatStatus ─────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("formatStatus maps RUNNING to RUNNING")
	void shouldFormatRunningStatus() {
		assertThat(ThreadFormatting.formatStatus(ThreadReference.THREAD_STATUS_RUNNING)).isEqualTo("RUNNING");
	}

	@Test
	@DisplayName("formatStatus maps every JDI status constant to a non-fallback label")
	void shouldFormatAllKnownJdiStatuses() {
		assertThat(ThreadFormatting.formatStatus(ThreadReference.THREAD_STATUS_UNKNOWN)).isEqualTo("UNKNOWN");
		assertThat(ThreadFormatting.formatStatus(ThreadReference.THREAD_STATUS_ZOMBIE)).isEqualTo("ZOMBIE");
		assertThat(ThreadFormatting.formatStatus(ThreadReference.THREAD_STATUS_SLEEPING)).isEqualTo("SLEEPING");
		assertThat(ThreadFormatting.formatStatus(ThreadReference.THREAD_STATUS_MONITOR)).isEqualTo("MONITOR");
		assertThat(ThreadFormatting.formatStatus(ThreadReference.THREAD_STATUS_WAIT)).isEqualTo("WAIT");
		assertThat(ThreadFormatting.formatStatus(ThreadReference.THREAD_STATUS_NOT_STARTED)).isEqualTo("NOT_STARTED");
	}

	@Test
	@DisplayName("formatStatus falls back to STATUS_<n> for values outside the known JDI constants")
	void shouldFallBackForUnknownStatus() {
		// The JDI constants cover -1 (UNKNOWN), 0 (ZOMBIE), 1 (RUNNING), 2 (SLEEPING),
		// 3 (MONITOR), 4 (WAIT), 5 (NOT_STARTED). Anything outside that range hits the fallback.
		assertThat(ThreadFormatting.formatStatus(99)).isEqualTo("STATUS_99");
		assertThat(ThreadFormatting.formatStatus(42)).isEqualTo("STATUS_42");
	}

	// ── isJvmInternalThreadName: exact-match list ────────────────────────────────────────

	@Test
	@DisplayName("isJvmInternalThreadName flags every exact-match JVM thread name")
	void shouldFlagExactInternalNames() {
		assertThat(ThreadFormatting.isJvmInternalThreadName("Reference Handler")).isTrue();
		assertThat(ThreadFormatting.isJvmInternalThreadName("Finalizer")).isTrue();
		assertThat(ThreadFormatting.isJvmInternalThreadName("Signal Dispatcher")).isTrue();
		assertThat(ThreadFormatting.isJvmInternalThreadName("Common-Cleaner")).isTrue();
		assertThat(ThreadFormatting.isJvmInternalThreadName("Attach Listener")).isTrue();
		assertThat(ThreadFormatting.isJvmInternalThreadName("JFR Recorder Thread")).isTrue();
		assertThat(ThreadFormatting.isJvmInternalThreadName("JFR Periodic Tasks")).isTrue();
		assertThat(ThreadFormatting.isJvmInternalThreadName("JFR Shutdown Hook")).isTrue();
	}

	// ── isJvmInternalThreadName: prefix list ─────────────────────────────────────────────

	@Test
	@DisplayName("isJvmInternalThreadName flags GC threads")
	void shouldFlagGcPrefixes() {
		assertThat(ThreadFormatting.isJvmInternalThreadName("GC Thread#0")).isTrue();
		assertThat(ThreadFormatting.isJvmInternalThreadName("G1 Main Marker")).isTrue();
	}

	@Test
	@DisplayName("isJvmInternalThreadName flags Notification/Service/surefire threads")
	void shouldFlagFrameworkPrefixes() {
		assertThat(ThreadFormatting.isJvmInternalThreadName("Notification Thread")).isTrue();
		assertThat(ThreadFormatting.isJvmInternalThreadName("Service Thread")).isTrue();
		assertThat(ThreadFormatting.isJvmInternalThreadName("surefire-forkedjvm-stream-flusher")).isTrue();
		assertThat(ThreadFormatting.isJvmInternalThreadName("surefire-forkedjvm-command-thread")).isTrue();
		assertThat(ThreadFormatting.isJvmInternalThreadName("process reaper")).isTrue();
	}

	// ── isJvmInternalThreadName: negatives ───────────────────────────────────────────────

	@Test
	@DisplayName("isJvmInternalThreadName does NOT flag user threads")
	void shouldNotFlagUserThreads() {
		assertThat(ThreadFormatting.isJvmInternalThreadName("main")).isFalse();
		assertThat(ThreadFormatting.isJvmInternalThreadName("Thread-0")).isFalse();
		assertThat(ThreadFormatting.isJvmInternalThreadName("worker-1")).isFalse();
		assertThat(ThreadFormatting.isJvmInternalThreadName("http-nio-8080-exec-1")).isFalse();
	}

	@Test
	@DisplayName("isJvmInternalThreadName does not match prefix-substring threads")
	void shouldNotMatchPartialPrefixes() {
		// "GCWorker" lacks the trailing space that the prefix "GC " requires
		assertThat(ThreadFormatting.isJvmInternalThreadName("GCWorker")).isFalse();
		// "G1Internal" lacks the trailing space that the prefix "G1 " requires
		assertThat(ThreadFormatting.isJvmInternalThreadName("G1Internal")).isFalse();
	}

	@Test
	@DisplayName("isJvmInternalThreadName treats null as internal (defensive)")
	void shouldTreatNullAsInternal() {
		assertThat(ThreadFormatting.isJvmInternalThreadName(null)).isTrue();
	}
}
