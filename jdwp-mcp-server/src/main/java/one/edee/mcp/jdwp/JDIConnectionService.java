package one.edee.mcp.jdwp;

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import lombok.extern.slf4j.Slf4j;
import one.edee.mcp.jdwp.evaluation.InMemoryJavaCompiler;
import one.edee.mcp.jdwp.evaluation.JdkDiscoveryService;
import org.springframework.stereotype.Service;

import one.edee.mcp.jdwp.evaluation.ClasspathDiscoverer;
import one.edee.mcp.jdwp.evaluation.ClasspathDiscoverer.DiscoveryResult;
import one.edee.mcp.jdwp.evaluation.JdkDiscoveryService.JdkNotFoundException;
import one.edee.mcp.jdwp.watchers.WatcherManager;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static one.edee.mcp.jdwp.ThreadFormatting.isJvmInternalThread;

/**
 * Singleton service maintaining a persistent JDI connection to a JDWP-enabled target JVM.
 *
 * Responsibilities:
 * - Connect / disconnect / auto-reconnect on a dropped connection (see {@link #getVM()}).
 * - Object reference caching: every JDI {@link ObjectReference} returned via {@link #formatFieldValue}
 *   is stored in {@link #objectCache} so subsequent tool calls can navigate object graphs by ID.
 *   The cache is cleared on disconnect and `jdwp_reset`.
 * - Classpath discovery for expression evaluation: collaborates with {@link ClasspathDiscoverer}
 *   and {@link JdkDiscoveryService} on the first breakpoint hit and caches
 *   the result in {@link #cachedClasspath} / {@link #discoveredJdkPath} until disconnect.
 *
 * Thread-safety: all public mutators are `synchronized` on the service instance; the object cache
 * is a {@link ConcurrentHashMap}; the classpath/JDK fields are `volatile` for cross-thread reads.
 */
@Slf4j
@Service
public class JDIConnectionService {

	private final JdiEventListener eventListener;
	private final BreakpointTracker breakpointTracker;
	private final EventHistory eventHistory;
	private final WatcherManager watcherManager;
	private final EvaluationGuard evaluationGuard;

	private VirtualMachine vm;
	private String lastHost;
	private int lastPort = 0;

	/**
	 * Maps {@link ObjectReference#uniqueID()} to the live JDI mirror so MCP tools can reference
	 * objects by ID across multiple calls. Populated as a side effect of {@link #formatFieldValue}
	 * (and {@link #getArrayElements}); cleared on disconnect and on `jdwp_reset`.
	 */
	private final Map<Long, ObjectReference> objectCache = new ConcurrentHashMap<>();

	public JDIConnectionService(JdiEventListener eventListener, BreakpointTracker breakpointTracker,
								EventHistory eventHistory, WatcherManager watcherManager,
								EvaluationGuard evaluationGuard) {
		this.eventListener = eventListener;
		this.breakpointTracker = breakpointTracker;
		this.eventHistory = eventHistory;
		this.watcherManager = watcherManager;
		this.evaluationGuard = evaluationGuard;
	}

	/**
	 * Cached path-separated classpath of the target JVM. Populated by {@link #discoverClasspath}
	 * on the first successful call and reused thereafter; cleared on disconnect.
	 */
	private volatile String cachedClasspath;

	/**
	 * Filesystem path to a local JDK matching the target JVM version. Populated as a side effect of
	 * {@link #discoverClasspath} and consumed by {@link InMemoryJavaCompiler}
	 * for the `--system` argument; cleared on disconnect.
	 */
	private volatile String discoveredJdkPath;
	/**
	 * Major Java version of the target JVM (e.g., 8, 11, 17, 21); 0 until {@link #discoverClasspath}
	 * has been called. Used by the JDT compiler to pick the correct `-source`/`-target` strings.
	 */
	private volatile int targetMajorVersion = 0;

	/**
	 * Cheap liveness probe — issues `vm.name()` and treats any exception as a dead connection.
	 *
	 * Side effect: clears the {@link #vm} reference on failure so the next call to {@link #ensureConnected}
	 * triggers a fresh attach via the cached host/port.
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
	 * Verifies the VM connection is alive, attempting a single reconnect using the host/port from the
	 * last successful {@link #connect}. Throws with a "use jdwp_connect first" hint if no prior
	 * connection was ever attempted (cached host/port are null/0).
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
	 * tolerates a dead VM (uses `reset()` as a fallback when JDI calls would fail).
	 *
	 * Called from both {@link #disconnect()} (clean shutdown) and {@link #connect(String, int)}
	 * when a stale connection is detected (target VM died but the user reconnects). Without this,
	 * the MCP server would accumulate breakpoints, watchers, and ObjectReferences across sessions.
	 */
	private void cleanupSessionState() {
		eventListener.stop();

		if (vm == null) {
			breakpointTracker.reset();
		} else {
			try {
				breakpointTracker.clearAll(vm.eventRequestManager());
			} catch (Exception e) {
				// "VM died mid-session" path — JDI calls would fail anyway, so we zero the in-memory
				// state instead. The breakpoint requests will be GC'd along with the dead VM.
				breakpointTracker.reset();
			}
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
	 * Renders the fields/elements of a previously cached object. The rendering branches on type:
	 * - Arrays: first 100 elements via {@link #getArrayElements}.
	 * - Recognised `java.util` collections (`ArrayList`, `LinkedList`, `HashMap`, `LinkedHashMap`,
	 *   `HashSet`, `TreeMap`, `TreeSet`): "smart view" with size, first 50 elements/entries, and the
	 *   raw internal fields, via {@link #getCollectionView}.
	 * - Anything else: a flat list of all fields (including inherited).
	 *
	 * Reads only — does not mutate target VM state. Relies on JDI's own thread-safety for
	 * concurrent frame inspection.
	 *
	 * @param objectId unique ID of a previously cached {@link ObjectReference}
	 * @return formatted string listing fields/elements, or an `[ERROR]` message if the object is not in cache
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
	 * Allow-list of {@code java.util} collection types whose internal field layout is known and
	 * stable enough for the smart-view rendering. Anything not on this list falls through to the
	 * generic field dump in {@link #getObjectFields}.
	 */
	private boolean isCollection(String typeName) {
		return collectionKind(typeName) != CollectionKind.UNKNOWN;
	}

	/**
	 * Classifies a JDK collection type by its concrete class name. Uses explicit equality checks
	 * rather than substring matching so future additions like {@code ConcurrentSkipListMap}
	 * (which contains both "List" and "Map") cannot accidentally route to the wrong branch
	 * (FINDING-13).
	 */
	private CollectionKind collectionKind(String typeName) {
		return switch (typeName) {
			case "java.util.ArrayList", "java.util.LinkedList" -> CollectionKind.LIST;
			case "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap" -> CollectionKind.MAP;
			case "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet" -> CollectionKind.SET;
			default -> CollectionKind.UNKNOWN;
		};
	}

	/** Smart-collection-view dispatch tag — see {@link #collectionKind(String)}. */
	private enum CollectionKind { LIST, MAP, SET, UNKNOWN }

	/**
	 * Renders a "smart view" for one of the supported collection types: prints the {@code size}
	 * field, dispatches to the type-specific element/entry walker, and then dumps the raw
	 * internal fields for completeness. Dispatch is by exact-name classification via
	 * {@link #collectionKind(String)}.
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

				switch (collectionKind(typeName)) {
					case LIST -> result.append(getListElements(obj, size));
					case MAP -> result.append(getMapEntries(obj, size));
					case SET -> result.append(getSetElements(obj, size));
					case UNKNOWN -> { /* allow-list filtered by isCollection — unreachable */ }
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

	/** Maximum number of entries/elements to render in any smart-collection view. */
	private static final int COLLECTION_VIEW_LIMIT = 50;

	/**
	 * Renders the first 50 elements of a List. Handles {@link java.util.ArrayList} via its internal
	 * {@code elementData} Object[] and {@link java.util.LinkedList} via its {@code first}/{@code next}
	 * node chain (reading each node's {@code item} field). Limited to 50 elements for performance
	 * and human readability.
	 */
	private String getListElements(ObjectReference list, int size) {
		StringBuilder result = new StringBuilder(128);
		result.append("Elements:\n");

		try {
			// ArrayList stores elements in an internal Object[] named "elementData".
			Field elementDataField = list.referenceType().fieldByName("elementData");
			if (elementDataField != null) {
				ArrayReference array = (ArrayReference) list.getValue(elementDataField);
				if (array != null) {
					int limit = Math.min(size, COLLECTION_VIEW_LIMIT);
					for (int i = 0; i < limit; i++) {
						Value value = array.getValue(i);
						result.append(String.format("  [%d] = %s\n", i, formatFieldValue(value)));
					}
					if (size > COLLECTION_VIEW_LIMIT) {
						result.append(String.format("  ... (%d more elements)\n", size - COLLECTION_VIEW_LIMIT));
					}
					return result.toString();
				}
			}

			// LinkedList stores elements in a "first" → "next" Node chain. Each Node has an "item" field.
			Field firstField = list.referenceType().fieldByName("first");
			if (firstField != null) {
				ObjectReference node = (ObjectReference) list.getValue(firstField);
				int index = 0;
				while (node != null && index < COLLECTION_VIEW_LIMIT) {
					Field itemField = node.referenceType().fieldByName("item");
					if (itemField != null) {
						Value item = node.getValue(itemField);
						result.append(String.format("  [%d] = %s\n", index, formatFieldValue(item)));
					}
					Field nextField = node.referenceType().fieldByName("next");
					if (nextField == null) break;
					node = (ObjectReference) node.getValue(nextField);
					index++;
				}
				if (size > COLLECTION_VIEW_LIMIT) {
					result.append(String.format("  ... (%d more elements)\n", size - COLLECTION_VIEW_LIMIT));
				}
			}
		} catch (Exception e) {
			result.append("  Error: ").append(e.getMessage()).append("\n");
		}

		return result.toString();
	}

	/**
	 * Renders the first 50 entries of a Map. Handles three layouts:
	 * <ul>
	 *   <li>{@link java.util.LinkedHashMap} — walks the doubly-linked {@code head} → {@code after} chain.</li>
	 *   <li>{@link java.util.HashMap} — walks the {@code table[]} bucket array following each
	 *       {@code Node.next} chain.</li>
	 *   <li>{@link java.util.TreeMap} — walks the red-black tree rooted at {@code root} via
	 *       in-order {@code left}/{@code right} traversal.</li>
	 * </ul>
	 * Limited to 50 entries for performance.
	 */
	private String getMapEntries(ObjectReference map, int size) {
		StringBuilder result = new StringBuilder(256);
		result.append("Entries:\n");

		try {
			ReferenceType mapType = map.referenceType();

			// LinkedHashMap path: doubly-linked "head" → "after" insertion-order chain.
			Field headField = mapType.fieldByName("head");
			if (headField != null) {
				ObjectReference entry = (ObjectReference) map.getValue(headField);
				int count = 0;
				while (entry != null && count < COLLECTION_VIEW_LIMIT) {
					appendMapEntry(result, entry);
					Field nextField = entry.referenceType().fieldByName("after");
					if (nextField == null) {
						nextField = entry.referenceType().fieldByName("next");
					}
					if (nextField == null) break;
					entry = (ObjectReference) entry.getValue(nextField);
					count++;
				}
				appendOverflowFooter(result, size, "entries");
				return result.toString();
			}

			// HashMap path: table[] of Node buckets, each bucket is a "next" chain.
			Field tableField = mapType.fieldByName("table");
			if (tableField != null) {
				ArrayReference table = (ArrayReference) map.getValue(tableField);
				if (table != null) {
					int rendered = 0;
					int length = table.length();
					for (int i = 0; i < length && rendered < COLLECTION_VIEW_LIMIT; i++) {
						ObjectReference bucket = (ObjectReference) table.getValue(i);
						while (bucket != null && rendered < COLLECTION_VIEW_LIMIT) {
							appendMapEntry(result, bucket);
							rendered++;
							Field nextField = bucket.referenceType().fieldByName("next");
							if (nextField == null) break;
							bucket = (ObjectReference) bucket.getValue(nextField);
						}
					}
					appendOverflowFooter(result, size, "entries");
					return result.toString();
				}
			}

			// TreeMap path: in-order traversal from "root" using "left"/"right" children.
			Field rootField = mapType.fieldByName("root");
			if (rootField != null) {
				ObjectReference root = (ObjectReference) map.getValue(rootField);
				int[] counter = new int[]{0};
				walkTreeMapInOrder(root, counter, result);
				appendOverflowFooter(result, size, "entries");
			}
		} catch (Exception e) {
			result.append("  Error: ").append(e.getMessage()).append("\n");
		}

		return result.toString();
	}

	/**
	 * In-order traversal of a TreeMap entry tree. Stops once {@link #COLLECTION_VIEW_LIMIT} entries
	 * have been rendered to bound the output.
	 */
	private void walkTreeMapInOrder(ObjectReference node, int[] counter, StringBuilder out) {
		if (node == null || counter[0] >= COLLECTION_VIEW_LIMIT) return;
		ReferenceType type = node.referenceType();
		Field leftField = type.fieldByName("left");
		Field rightField = type.fieldByName("right");

		if (leftField != null) {
			ObjectReference left = (ObjectReference) node.getValue(leftField);
			walkTreeMapInOrder(left, counter, out);
		}
		if (counter[0] >= COLLECTION_VIEW_LIMIT) return;

		appendMapEntry(out, node);
		counter[0]++;

		if (rightField != null) {
			ObjectReference right = (ObjectReference) node.getValue(rightField);
			walkTreeMapInOrder(right, counter, out);
		}
	}

	/** Appends a single {@code key = value} row to {@code out} for the given map entry node. */
	private void appendMapEntry(StringBuilder out, ObjectReference entry) {
		ReferenceType type = entry.referenceType();
		Field keyField = type.fieldByName("key");
		Field valueField = type.fieldByName("value");
		if (keyField == null || valueField == null) return;
		Value key = entry.getValue(keyField);
		Value value = entry.getValue(valueField);
		out.append(String.format("  %s = %s\n", formatFieldValue(key), formatFieldValue(value)));
	}

	/**
	 * Appends a {@code "... (N more …)"} footer when the collection's reported size exceeds the
	 * smart-view limit. {@code label} is typically {@code "entries"} or {@code "elements"}.
	 */
	private void appendOverflowFooter(StringBuilder out, int size, String label) {
		if (size > COLLECTION_VIEW_LIMIT) {
			out.append(String.format("  ... (%d more %s)\n", size - COLLECTION_VIEW_LIMIT, label));
		}
	}

	/**
	 * Renders the elements of a Set by following its internal backing-map field. {@link java.util.HashSet}
	 * uses {@code map}; {@link java.util.TreeSet} uses {@code m}. Once the backing map is located the
	 * set elements are read from its keys via {@link #getMapEntries}.
	 */
	private String getSetElements(ObjectReference set, int size) {
		StringBuilder result = new StringBuilder(128);
		result.append("Elements:\n");

		try {
			// HashSet delegates to an internal HashMap stored in a field named "map".
			// TreeSet delegates to a TreeMap stored in a field named "m" (single letter).
			Field mapField = set.referenceType().fieldByName("map");
			if (mapField == null) {
				mapField = set.referenceType().fieldByName("m");
			}
			if (mapField != null) {
				ObjectReference map = (ObjectReference) set.getValue(mapField);
				if (map != null) {
					// Extract keys from the map (values are dummy PRESENT objects for HashSet).
					result.append(getMapEntries(map, size));
				}
			}
		} catch (Exception e) {
			result.append("  Error: ").append(e.getMessage()).append("\n");
		}

		return result.toString();
	}

	/**
	 * Renders the first 100 elements of a JDI array reference. Limit is hardcoded to keep responses
	 * bounded for the MCP client; longer arrays are summarised with a "more elements" footer.
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
			String unboxed = tryUnboxPrimitive(obj);
			if (unboxed != null) {
				return unboxed;
			}
			cacheObject(obj); // Store in cache for later inspection
			return String.format("Object#%d (%s)", obj.uniqueID(), obj.referenceType().name());
		}

		return value.toString();
	}

	/**
	 * If {@code obj} is a wrapper type for a Java primitive, reads its private {@code value} field
	 * directly via JDI (no invocation needed) and returns the unboxed string form. Returns {@code null}
	 * for any other type so the caller can fall through to the regular {@code Object#N (...)} rendering.
	 */
	private String tryUnboxPrimitive(ObjectReference obj) {
		String typeName = obj.referenceType().name();
		if (!isBoxedPrimitiveType(typeName)) {
			return null;
		}
		Field valueField = obj.referenceType().fieldByName("value");
		if (valueField == null) {
			return null;
		}
		Value inner = obj.getValue(valueField);
		if (inner instanceof PrimitiveValue) {
			return inner.toString();
		}
		return null;
	}

	/** Names of the eight Java primitive wrapper types — gates {@link #tryUnboxPrimitive} fast-path. */
	private static final Set<String> BOXED_PRIMITIVE_TYPES = Set.of(
		"java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
		"java.lang.Boolean", "java.lang.Character", "java.lang.Byte", "java.lang.Short");

	/**
	 * Pure type-name check for the eight Java primitive wrapper classes. Extracted as a separate
	 * static so it can be unit-tested without a {@link ObjectReference}.
	 */
	static boolean isBoxedPrimitiveType(String typeName) {
		return typeName != null && BOXED_PRIMITIVE_TYPES.contains(typeName);
	}

	/**
	 * Discovers and caches the full classpath of the target JVM, including JARs loaded dynamically
	 * by Tomcat / container classloaders that don't appear in `java.class.path`. Result is cached
	 * after the first successful call and reused until {@link #cleanupSessionState}.
	 *
	 * Side effects: also populates {@link #discoveredJdkPath} and {@link #targetMajorVersion} via
	 * {@link JdkDiscoveryService} so the JDT compiler can be configured.
	 *
	 * Returns `null` (and logs at error level) on any failure, including when no matching local JDK
	 * can be found — the {@link JdkDiscoveryService.JdkNotFoundException} is
	 * caught here and never bubbles out.
	 *
	 * @param suspendedThread thread already suspended at a JDI method-invocation event (breakpoint
	 *                        or step); plain `vm.suspend()` is not enough because the discovery uses
	 *                        `INVOKE_SINGLE_THREADED` which requires a usable invocation thread
	 * @return classpath string (separator inferred from the first entry), or `null` on any failure
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
	 * Clears the entire object reference cache. Called by {@code jdwp_reset} to wipe per-session
	 * state without dropping the VM connection. Does NOT touch breakpoints, watchers, or event
	 * history — those are owned by their respective services and reset separately.
	 */
	public void clearObjectCache() {
		objectCache.clear();
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
			// Reentrancy guard: forcing Class.forName runs the target class's <clinit>, which
			// may hit a user breakpoint. Without the guard the listener would re-suspend the
			// thread we are driving and the outer invokeMethod would hang. Capture the id up
			// front so a thread death during <clinit> does not leak a guard entry.
			long guardedThreadId = thread.uniqueID();
			evaluationGuard.enter(guardedThreadId);
			try {
				classClass.invokeMethod(thread, forName, java.util.List.of(nameRef), ClassType.INVOKE_SINGLE_THREADED);
			} finally {
				evaluationGuard.exit(guardedThreadId);
			}
			log.info("[JDI] Force-loaded class '{}' via Class.forName", className);

			List<ReferenceType> retry = vm.classesByName(className);
			return retry.isEmpty() ? null : retry.get(0);
		} catch (Exception e) {
			log.warn("[JDI] Could not force-load class '{}': {} ({})",
				className, e.getMessage(), e.getClass().getSimpleName());
			return null;
		}
	}

	/**
	 * Defensive frame count probe — returns `-1` if the thread is in a state where `frameCount()`
	 * throws (e.g., not suspended, or suspended in a state JDI cannot inspect).
	 */
	private int tryFrameCount(ThreadReference thread) {
		try {
			return thread.frameCount();
		} catch (Exception e) {
			return -1;
		}
	}

	/**
	 * Checks whether a thread satisfies JDI's preconditions for `invokeMethod`: must be suspended
	 * AND have at least one stack frame. JDI rejects invocations on threads suspended via
	 * `vm.suspend()` (no frames yet) or threads suspended in native waits.
	 */
	private boolean isUsableForInvoke(ThreadReference t) {
		try {
			return t.isSuspended() && t.frameCount() > 0;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Picks any thread suitable for JDI `invokeMethod` (force-load, watcher evaluation, classpath
	 * discovery). Two-step fallback:
	 * 1. The thread that most recently fired a suspending event (recorded by {@link BreakpointTracker});
	 *    this is the preferred choice because it's known to be at a JDI method-invocation event.
	 * 2. Any other suspended thread with frames, excluding JVM-internal threads (Reference Handler,
	 *    Finalizer, etc.) which are suspended in native waits and would fail JDI's invoke check.
	 */
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

}
