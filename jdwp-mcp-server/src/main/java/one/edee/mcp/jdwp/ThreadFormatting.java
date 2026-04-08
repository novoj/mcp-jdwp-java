package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;

import java.util.Set;

/**
 * Static utilities for human-friendly thread rendering. Lives in its own class so both
 * {@link JDWPTools} and {@link JDIConnectionService} can share the JVM-internal-thread filter
 * and the status-to-name translation.
 */
public final class ThreadFormatting {

	private ThreadFormatting() {
	}

	/**
	 * Names of threads that the JVM, the JDK, the test runner, and the JFR subsystem create
	 * for housekeeping. None of them are interesting for typical debugging sessions, so we
	 * filter them out of {@code jdwp_get_threads} unless the user explicitly opts in.
	 */
	private static final Set<String> EXACT_INTERNAL_NAMES = Set.of(
		"Reference Handler",
		"Finalizer",
		"Signal Dispatcher",
		"Common-Cleaner",
		"Attach Listener",
		"JFR Recorder Thread",
		"JFR Periodic Tasks",
		"JFR Shutdown Hook"
	);

	private static final String[] INTERNAL_PREFIXES = {
		"GC ",
		"G1 ",
		"Notification Thread",
		"Service Thread",
		"surefire-forkedjvm-",
		"process reaper"
	};

	/**
	 * @return {@code true} if the given thread is JVM/JDK/test-runner internal and should be
	 *         filtered out by default. Best-effort — any exception is treated as "internal" so
	 *         we never crash inspecting a half-dead thread.
	 */
	public static boolean isJvmInternalThread(ThreadReference t) {
		try {
			return isJvmInternalThreadName(t.name());
		} catch (Exception e) {
			return true;
		}
	}

	/**
	 * Pure-string variant of {@link #isJvmInternalThread(ThreadReference)} — checks the name
	 * against the exact-match set and the prefix list. Extracted as a separate method so it
	 * can be unit-tested without a live {@link ThreadReference}.
	 */
	static boolean isJvmInternalThreadName(String name) {
		if (name == null) {
			return true;
		}
		if (EXACT_INTERNAL_NAMES.contains(name)) {
			return true;
		}
		for (String prefix : INTERNAL_PREFIXES) {
			if (name.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Maps a JDI {@link ThreadReference#status() status} integer to a readable label.
	 */
	public static String formatStatus(int status) {
		return switch (status) {
			case ThreadReference.THREAD_STATUS_UNKNOWN -> "UNKNOWN";
			case ThreadReference.THREAD_STATUS_ZOMBIE -> "ZOMBIE";
			case ThreadReference.THREAD_STATUS_RUNNING -> "RUNNING";
			case ThreadReference.THREAD_STATUS_SLEEPING -> "SLEEPING";
			case ThreadReference.THREAD_STATUS_MONITOR -> "MONITOR";
			case ThreadReference.THREAD_STATUS_WAIT -> "WAIT";
			case ThreadReference.THREAD_STATUS_NOT_STARTED -> "NOT_STARTED";
			default -> "STATUS_" + status;
		};
	}
}
