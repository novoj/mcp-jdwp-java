package io.mcp.jdwp;

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import io.mcp.jdwp.evaluation.ClasspathDiscoverer;
import io.mcp.jdwp.evaluation.ClasspathDiscoverer.DiscoveryResult;
import io.mcp.jdwp.evaluation.JdkDiscoveryService.JdkNotFoundException;
import io.mcp.jdwp.watchers.WatcherManager;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton service maintaining a persistent JDI connection to a remote JDWP-enabled JVM.
 * Provides auto-reconnect, object reference caching for cross-call object graph navigation,
 * and classpath discovery for expression evaluation.
 */
@Slf4j
@Service
public class JDIConnectionService {

	private final JdiEventListener eventListener;
	private final BreakpointTracker breakpointTracker;
	private final EventHistory eventHistory;
	private final WatcherManager watcherManager;

	private VirtualMachine vm = null;
	private String lastHost = null;
	private int lastPort = 0;

	// Cache to store encountered ObjectReferences
	private final Map<Long, ObjectReference> objectCache = new ConcurrentHashMap<>();

	public JDIConnectionService(JdiEventListener eventListener, BreakpointTracker breakpointTracker,
								EventHistory eventHistory, WatcherManager watcherManager) {
		this.eventListener = eventListener;
		this.breakpointTracker = breakpointTracker;
		this.eventHistory = eventHistory;
		this.watcherManager = watcherManager;
	}

	// Cached classpath from target JVM (discovered once)
	private volatile String cachedClasspath = null;

	// Discovered local JDK path matching target JVM version
	private volatile String discoveredJdkPath = null;
	private volatile int targetMajorVersion = 0;

	/**
	 * Check if VM connection is alive
	 */
	private boolean isVMAlive() {
		if (vm == null) {
			return false;
		}
		try {
			// Try to call a simple method to check if connection is alive
			vm.name();
			return true;
		} catch (Exception e) {
			// Connection is dead
			vm = null;
			return false;
		}
	}

	/**
	 * Connects to a JDWP server. Idempotent if already connected to a live VM.
	 *
	 * @param host hostname of the target JVM
	 * @param port JDWP debug port
	 * @return status message indicating connection result
	 */
	public synchronized String connect(String host, int port) throws Exception {
		// Check if already connected and alive
		if (vm != null && isVMAlive()) {
			return "Already connected to " + vm.name();
		}

		// Stale state from a dead VM (or first connection): wipe everything before reconnecting.
		// This is what prevents memory leaks across many test-run sessions.
		if (vm != null) {
			log.info("[JDI] Clearing stale state from previous (dead) VM connection");
			cleanupSessionState();
		}

		// Find SocketAttachingConnector
		VirtualMachineManager vmm = Bootstrap.virtualMachineManager();
		AttachingConnector connector = null;

		for (AttachingConnector ac : vmm.attachingConnectors()) {
			if ("com.sun.jdi.SocketAttach".equals(ac.name())) {
				connector = ac;
				break;
			}
		}

		if (connector == null) {
			throw new RuntimeException("SocketAttach connector not found");
		}

		// Set connection arguments
		Map<String, Connector.Argument> args = connector.defaultArguments();
		args.get("hostname").setValue(host);
		args.get("port").setValue(String.valueOf(port));

		// Attach
		vm = connector.attach(args);
		lastHost = host;
		lastPort = port;

		// Start listening for JDI events (breakpoints, steps, etc.)
		eventListener.start(vm);

		return String.format("Connected to %s (version %s)", vm.name(), vm.version());
	}

	/**
	 * Ensure connection is alive, reconnect if needed
	 */
	private synchronized void ensureConnected() throws Exception {
		if (vm == null || !isVMAlive()) {
			if (lastHost != null && lastPort != 0) {
				// Try to reconnect
				connect(lastHost, lastPort);
			} else {
				throw new Exception("Not connected to JDWP server. Use jdwp_connect first.");
			}
		}
	}

	/**
	 * Disconnects from the JDWP server. Also stops the event listener and resets the breakpoint tracker.
	 *
	 * @return status message ("Disconnected" or "Not connected")
	 */
	public synchronized String disconnect() {
		if (vm == null) {
			return "Not connected";
		}
		cleanupSessionState();
		return "Disconnected";
	}

	/**
	 * Releases all session-bound state held by the MCP server: JDI event requests, object cache,
	 * watchers, classpath cache, event history, and the VM reference itself. Best-effort —
	 * tolerates a dead VM (uses {@code reset()} as a fallback when JDI calls would fail).
	 *
	 * <p>Called from both {@link #disconnect()} (clean shutdown) and {@link #connect(String, int)}
	 * when a stale connection is detected (target VM died but the user reconnects). Without this,
	 * the MCP server would accumulate breakpoints, watchers, and ObjectReferences across sessions.
	 */
	private void cleanupSessionState() {
		eventListener.stop();

		if (vm != null) {
			try {
				breakpointTracker.clearAll(vm.eventRequestManager());
			} catch (Exception e) {
				// VM may already be dead — fall back to in-memory reset
				breakpointTracker.reset();
			}
		} else {
			breakpointTracker.reset();
		}

		watcherManager.clearAll();
		objectCache.clear();
		cachedClasspath = null;
		discoveredJdkPath = null;
		targetMajorVersion = 0;
		eventHistory.clear();

		if (vm != null) {
			try {
				vm.dispose();
			} catch (Exception e) {
				// VM may already be disconnected
			}
			vm = null;
		}
	}

	/**
	 * Returns the connected VirtualMachine, auto-reconnecting if the connection has dropped.
	 *
	 * @return the live {@link VirtualMachine} instance
	 * @throws Exception if not connected and reconnection fails
	 */
	public synchronized VirtualMachine getVM() throws Exception {
		ensureConnected();
		// Opportunistically retry pending breakpoints — handles bootstrap classes that don't fire
		// ClassPrepareEvent and any other deferred items whose target class became visible since
		// the last check. Best-effort; failures are swallowed.
		try {
			breakpointTracker.tryPromotePending(this, null);
		} catch (Exception e) {
			log.debug("[JDI] Pending promotion failed: {}", e.getMessage());
		}
		return vm;
	}

	/**
	 * Returns the raw VM reference without triggering opportunistic promotion. Used by
	 * {@link BreakpointTracker#tryPromotePending(JDIConnectionService, ThreadReference)} to avoid
	 * recursion. Callers must already hold the connection service monitor.
	 */
	VirtualMachine getRawVM() {
		return vm;
	}

	/**
	 * Stores an ObjectReference in the cache for later cross-call inspection. Thread-safe, null-safe.
	 */
	public void cacheObject(ObjectReference obj) {
		if (obj != null) {
			objectCache.put(obj.uniqueID(), obj);
		}
	}

	/**
	 * Returns the fields of a cached object, or its elements if it is an array or collection.
	 *
	 * @param objectId unique ID of a previously cached {@link ObjectReference}
	 * @return formatted string listing fields/elements, or an error message if the object is not in cache
	 */
	public synchronized String getObjectFields(long objectId) throws Exception {
		ensureConnected();

		ObjectReference obj = objectCache.get(objectId);
		if (obj == null) {
			return String.format("[ERROR] Object #%d not found in cache\n\n" +
				"This object was not previously discovered.\n" +
				"Use jdwp_get_locals() to discover objects in the current scope.",
				objectId);
		}

		try {
			// Check if it's an array
			if (obj instanceof ArrayReference arr) {
				return getArrayElements(arr, objectId);
			}

			ReferenceType refType = obj.referenceType();
			String typeName = refType.name();

			// Check for common Java collections and provide smart views
			if (typeName.startsWith("java.util.") && isCollection(typeName)) {
				return getCollectionView(obj, objectId, typeName);
			}

			// Regular object - get fields
			StringBuilder result = new StringBuilder();
			result.append(String.format("Object #%d (%s):\n\n", objectId, refType.name()));

			// Get all fields (including inherited)
			List<Field> fields = refType.allFields();

			for (Field field : fields) {
				Value value = obj.getValue(field);
				String valueStr = formatFieldValue(value);

				result.append(String.format("%s %s = %s\n",
					field.typeName(),
					field.name(),
					valueStr));
			}

			return result.toString();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Check if type is a known collection
	 */
	private boolean isCollection(String typeName) {
		return typeName.startsWith("java.util.ArrayList") ||
			   typeName.startsWith("java.util.LinkedList") ||
			   typeName.startsWith("java.util.HashMap") ||
			   typeName.startsWith("java.util.LinkedHashMap") ||
			   typeName.startsWith("java.util.HashSet") ||
			   typeName.startsWith("java.util.TreeSet") ||
			   typeName.startsWith("java.util.TreeMap");
	}

	/**
	 * Provide smart view for collections
	 */
	private String getCollectionView(ObjectReference obj, long objectId, String typeName) {
		StringBuilder result = new StringBuilder();
		result.append(String.format("Object #%d (%s):\n\n", objectId, typeName));

		try {
			// Get size
			Field sizeField = obj.referenceType().fieldByName("size");
			if (sizeField != null) {
				Value sizeValue = obj.getValue(sizeField);
				int size = ((IntegerValue) sizeValue).value();
				result.append(String.format("Size: %d\n\n", size));

				// For List types
				if (typeName.contains("List")) {
					result.append(getListElements(obj, size));
				}
				// For Map types
				else if (typeName.contains("Map")) {
					result.append(getMapEntries(obj, size));
				}
				// For Set types
				else if (typeName.contains("Set")) {
					result.append(getSetElements(obj, size));
				}
			}

			result.append("\n--- Internal fields ---\n\n");
			// Also show internal fields
			for (Field field : obj.referenceType().allFields()) {
				Value value = obj.getValue(field);
				String valueStr = formatFieldValue(value);
				result.append(String.format("%s %s = %s\n",
					field.typeName(), field.name(), valueStr));
			}

		} catch (Exception e) {
			result.append("Error inspecting collection: ").append(e.getMessage());
		}

		return result.toString();
	}

	/**
	 * Get elements from a List (ArrayList, LinkedList)
	 */
	private String getListElements(ObjectReference list, int size) {
		StringBuilder result = new StringBuilder();
		result.append("Elements:\n");

		try {
			// ArrayList stores elements in an internal Object[] named "elementData" — this is a JDK implementation detail
			Field elementDataField = list.referenceType().fieldByName("elementData");
			if (elementDataField != null) {
				ArrayReference array = (ArrayReference) list.getValue(elementDataField);
				if (array != null) {
					int limit = Math.min(size, 50); // Limit to 50 elements
					for (int i = 0; i < limit; i++) {
						Value value = array.getValue(i);
						result.append(String.format("  [%d] = %s\n", i, formatFieldValue(value)));
					}
					if (size > 50) {
						result.append(String.format("  ... (%d more elements)\n", size - 50));
					}
				}
			}
		} catch (Exception e) {
			result.append("  Error: ").append(e.getMessage()).append("\n");
		}

		return result.toString();
	}

	/**
	 * Get entries from a Map (HashMap, LinkedHashMap, TreeMap)
	 */
	private String getMapEntries(ObjectReference map, int size) {
		StringBuilder result = new StringBuilder();
		result.append("Entries:\n");

		try {
			// LinkedHashMap maintains a doubly-linked list via "head"/"after" fields for insertion-order iteration.
			// Falls back to HashMap's "table"/"next" bucket chain. These are JDK-internal field names.
			Field headField = map.referenceType().fieldByName("head");
			if (headField != null) {
				ObjectReference entry = (ObjectReference) map.getValue(headField);
				int count = 0;
				int limit = 50;

				while (entry != null && count < limit) {
					// Get key and value from entry
					Field keyField = entry.referenceType().fieldByName("key");
					Field valueField = entry.referenceType().fieldByName("value");

					if (keyField != null && valueField != null) {
						Value key = entry.getValue(keyField);
						Value value = entry.getValue(valueField);
						result.append(String.format("  %s = %s\n",
							formatFieldValue(key), formatFieldValue(value)));
					}

					// Move to next entry
					Field nextField = entry.referenceType().fieldByName("after");
					if (nextField == null) {
						nextField = entry.referenceType().fieldByName("next");
					}
					if (nextField != null) {
						entry = (ObjectReference) entry.getValue(nextField);
					} else {
						break;
					}
					count++;
				}

				if (size > limit) {
					result.append(String.format("  ... (%d more entries)\n", size - limit));
				}
			}
		} catch (Exception e) {
			result.append("  Error: ").append(e.getMessage()).append("\n");
		}

		return result.toString();
	}

	/**
	 * Get elements from a Set (HashSet, TreeSet)
	 */
	private String getSetElements(ObjectReference set, int size) {
		StringBuilder result = new StringBuilder();
		result.append("Elements:\n");

		try {
			// HashSet delegates to an internal HashMap stored in a field named "map" — JDK implementation detail
			Field mapField = set.referenceType().fieldByName("map");
			if (mapField != null) {
				ObjectReference map = (ObjectReference) set.getValue(mapField);
				if (map != null) {
					// Extract keys from the map (values are dummy PRESENT objects)
					result.append(getMapEntries(map, size));
				}
			}
		} catch (Exception e) {
			result.append("  Error: ").append(e.getMessage()).append("\n");
		}

		return result.toString();
	}

	/**
	 * Get elements of an array
	 */
	private String getArrayElements(ArrayReference array, long arrayId) {
		StringBuilder result = new StringBuilder();
		int length = array.length();
		String typeName = array.type().name();

		result.append(String.format("Array #%d (%s) - %d elements:\n\n", arrayId, typeName, length));

		// Limit to first 100 elements for performance
		int limit = Math.min(length, 100);

		for (int i = 0; i < limit; i++) {
			Value value = array.getValue(i);
			String valueStr = formatFieldValue(value);
			result.append(String.format("[%d] = %s\n", i, valueStr));
		}

		if (length > 100) {
			result.append(String.format("\n... (%d more elements)\n", length - 100));
		}

		return result.toString();
	}

	/**
	 * Formats a JDI {@link Value} for human-readable display. Caches any encountered
	 * {@link ObjectReference} as a side effect so it can be inspected in subsequent calls.
	 *
	 * @param value the JDI value to format (may be null)
	 * @return formatted string representation
	 */
	public String formatFieldValue(Value value) {
		if (value == null) {
			return "null";
		}

		if (value instanceof StringReference strRef) {
			return "\"" + strRef.value() + "\"";
		}

		if (value instanceof PrimitiveValue) {
			return value.toString();
		}

		if (value instanceof ArrayReference arr) {
			cacheObject(arr);
			return String.format("Array#%d (%s[%d])",
				arr.uniqueID(), arr.type().name(), arr.length());
		}

		if (value instanceof ObjectReference obj) {
			cacheObject(obj); // Store in cache for later inspection
			return String.format("Object#%d (%s)", obj.uniqueID(), obj.referenceType().name());
		}

		return value.toString();
	}

	/**
	 * Discover the full classpath of the target JVM by exploring its classloader hierarchy.
	 * Results are cached after first call.
	 *
	 * This method explores the context classloader hierarchy to find all dynamically loaded JARs,
	 * which is essential for Tomcat/container applications where most JARs are not in java.class.path.
	 *
	 * MUST be called with a thread that is already suspended at a breakpoint.
	 * This ensures the thread is in a compatible state for INVOKE_SINGLE_THREADED.
	 *
	 * @param suspendedThread A thread already suspended at a breakpoint (REQUIRED)
	 * @return Classpath string (colon or semicolon separated depending on OS), or null if unavailable
	 */
	public String discoverClasspath(ThreadReference suspendedThread) {
		if (cachedClasspath != null) {
			return cachedClasspath;
		}

		if (suspendedThread == null) {
			log.error("[JDI] discoverClasspath() requires a suspended thread from a breakpoint");
			return null;
		}

		try {
			// Capture vm reference via synchronized getVM() to avoid race with disconnect()
			VirtualMachine currentVm = getVM();

			log.info("[JDI] Discovering full classpath using breakpoint thread '{}'", suspendedThread.name());

			// Use ClasspathDiscoverer to explore classloader hierarchy
			ClasspathDiscoverer discoverer = new ClasspathDiscoverer(currentVm);

			// Discover both JDK path and application classpath
			DiscoveryResult result = discoverer.discoverFullClasspath(suspendedThread);

			// Store discovered JDK path for later use by JDT compiler
			discoveredJdkPath = result.getLocalJdkPath();
			targetMajorVersion = result.getTargetMajorVersion();
			log.info("[JDI] Using local JDK: {} (Java {})", discoveredJdkPath, targetMajorVersion);

			Set<String> classpathEntries = result.getApplicationClasspath();

			if (classpathEntries.isEmpty()) {
				log.warn("[JDI] No classpath entries discovered");
				return null;
			}

			// Determine separator based on first entry (Windows uses backslash, Unix forward slash)
			String separator = classpathEntries.stream()
				.findFirst()
				.map(path -> path.contains("\\") ? ";" : ":")
				.orElse(System.getProperty("path.separator"));

			// Join all entries into a single classpath string
			cachedClasspath = String.join(separator, classpathEntries);

			log.info("[JDI] Full classpath discovered ({} entries)", classpathEntries.size());

			return cachedClasspath;

		} catch (JdkNotFoundException e) {
			// Critical error: No matching JDK found locally
			log.error("[JDI] {}", e.getMessage());
			return null;
		} catch (Exception e) {
			log.error("[JDI] Failed to discover classpath", e);
			return null;
		}
	}

	/**
	 * Get the discovered local JDK path matching the target JVM version.
	 * This path is discovered during classpath discovery and can be used by the JDT compiler.
	 *
	 * @return Local JDK path, or null if not yet discovered
	 */
	public String getDiscoveredJdkPath() {
		return discoveredJdkPath;
	}

	/**
	 * Returns the target JVM's major Java version (e.g., 8, 11, 17, 21).
	 */
	public int getTargetMajorVersion() {
		return targetMajorVersion;
	}

	/**
	 * Returns a previously cached ObjectReference, or null if not in cache.
	 */
	public ObjectReference getCachedObject(long objectId) {
		return objectCache.get(objectId);
	}

	/**
	 * Locates a class in the target VM, force-loading it via {@code Class.forName(name)} if not
	 * yet visible. The force-load attempt requires a suspended thread (typically the main thread
	 * paused at VMStart, or any thread suspended at a breakpoint). Returns null if the class
	 * cannot be found or force-loaded.
	 *
	 * <p>This solves the bootstrap-class problem: classes like {@code java.lang.IllegalStateException}
	 * are not visible to {@link VirtualMachine#classesByName(String)} until first referenced, and
	 * their {@link com.sun.jdi.event.ClassPrepareEvent} is not delivered to JDI clients. Forcing
	 * the load via {@code Class.forName} bypasses both issues.
	 */
	public synchronized ReferenceType findOrForceLoadClass(String className) {
		return findOrForceLoadClass(className, null);
	}

	/**
	 * Same as {@link #findOrForceLoadClass(String)} but allows passing a preferred thread for the
	 * force-load step. This must be a thread that is suspended at a JDI method-invocation event
	 * (breakpoint, step, exception, class prepare) — JDI cannot invoke methods on threads
	 * suspended via vm.suspend() (e.g., the VMStart-suspended state).
	 */
	public synchronized ReferenceType findOrForceLoadClass(String className, ThreadReference preferredThread) {
		if (vm == null) return null;

		// Fast path: already visible via the indexed lookup
		List<ReferenceType> existing = vm.classesByName(className);
		if (!existing.isEmpty()) {
			return existing.get(0);
		}

		// Fallback 1: full scan of allClasses() — sometimes bootstrap classes appear here
		// even when classesByName misses them.
		ReferenceType scanned = vm.allClasses().stream()
			.filter(rt -> rt.name().equals(className))
			.findFirst()
			.orElse(null);
		if (scanned != null) {
			log.info("[JDI] Found '{}' via allClasses() scan (not in classesByName index)", className);
			return scanned;
		}

		// Fallback 2: invoke Class.forName(name) in the target VM to force a load.
		// JDI requires the thread to be suspended at a method-invocation event AND have frames.
		ThreadReference thread = preferredThread != null && isUsableForInvoke(preferredThread)
			? preferredThread : findSuspendedThread();
		if (thread == null) {
			log.debug("[JDI] Cannot force-load '{}' — no thread suspended at a method-invocation event", className);
			return null;
		}

		try {
			log.info("[JDI] Attempting to force-load '{}' via thread '{}' (suspended={}, frames={})",
				className, thread.name(), thread.isSuspended(), tryFrameCount(thread));

			List<ReferenceType> classClassList = vm.classesByName("java.lang.Class");
			if (classClassList.isEmpty()) {
				log.warn("[JDI] java.lang.Class not visible in target VM — cannot force-load");
				return null;
			}
			ClassType classClass = (ClassType) classClassList.get(0);
			Method forName = classClass.concreteMethodByName("forName", "(Ljava/lang/String;)Ljava/lang/Class;");
			if (forName == null) {
				log.warn("[JDI] Class.forName(String) method not found");
				return null;
			}

			StringReference nameRef = vm.mirrorOf(className);
			classClass.invokeMethod(thread, forName, java.util.List.of(nameRef), ClassType.INVOKE_SINGLE_THREADED);
			log.info("[JDI] Force-loaded class '{}' via Class.forName", className);

			List<ReferenceType> retry = vm.classesByName(className);
			return retry.isEmpty() ? null : retry.get(0);
		} catch (Exception e) {
			log.warn("[JDI] Could not force-load class '{}': {} ({})",
				className, e.getMessage(), e.getClass().getSimpleName());
			return null;
		}
	}

	private int tryFrameCount(ThreadReference thread) {
		try {
			return thread.frameCount();
		} catch (Exception e) {
			return -1;
		}
	}

	private boolean isUsableForInvoke(ThreadReference t) {
		try {
			return t.isSuspended() && t.frameCount() > 0;
		} catch (Exception e) {
			return false;
		}
	}

	private ThreadReference findSuspendedThread() {
		if (vm == null) return null;
		try {
			// First preference: the thread that most recently hit a breakpoint — known to be
			// suspended at a method-invocation event, which is what JDI requires for invokeMethod.
			ThreadReference lastBp = breakpointTracker.getLastBreakpointThread();
			if (lastBp != null && isUsableForInvoke(lastBp)) {
				return lastBp;
			}
			// Fallback: any suspended thread with frames. Skip JVM-internal threads (Reference
			// Handler, Finalizer, etc.) which are suspended in native waits, not at JDI events.
			return vm.allThreads().stream()
				.filter(t -> isUsableForInvoke(t) && !isJvmInternalThread(t))
				.findFirst()
				.orElse(null);
		} catch (Exception e) {
			return null;
		}
	}

	private boolean isJvmInternalThread(ThreadReference t) {
		try {
			String name = t.name();
			return name.equals("Reference Handler")
				|| name.equals("Finalizer")
				|| name.equals("Signal Dispatcher")
				|| name.equals("Common-Cleaner")
				|| name.startsWith("GC ")
				|| name.startsWith("G1 ")
				|| name.startsWith("Notification Thread")
				|| name.startsWith("Service Thread");
		} catch (Exception e) {
			return true; // err on the side of skipping
		}
	}

}
