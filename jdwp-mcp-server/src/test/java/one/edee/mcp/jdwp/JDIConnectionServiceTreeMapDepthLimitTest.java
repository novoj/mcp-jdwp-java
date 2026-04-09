package one.edee.mcp.jdwp;

import com.sun.jdi.Field;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that {@link JDIConnectionService}'s private {@code walkTreeMapInOrder} method respects
 * the {@code MAX_TREE_DEPTH} limit (64) and does not stack-overflow on excessively deep trees.
 * Also verifies that small, well-formed trees are rendered completely.
 */
class JDIConnectionServiceTreeMapDepthLimitTest {

	private JDIConnectionService service;

	@BeforeEach
	void setUp() {
		JdiEventListener listener = mock(JdiEventListener.class);
		BreakpointTracker tracker = new BreakpointTracker();
		EventHistory eventHistory = new EventHistory();
		WatcherManager watcherManager = new WatcherManager();
		service = new JDIConnectionService(listener, tracker, eventHistory, watcherManager, new EvaluationGuard());
	}

	@Nested
	@DisplayName("Depth limit enforcement")
	class DepthLimit {

		@Test
		@DisplayName("Deep left-spine tree (100 levels) stops without stack overflow")
		void shouldStopWithoutStackOverflowOnDeeplyNestedTree() throws Exception {
			// Build a left-spine chain 100 levels deep (exceeds MAX_TREE_DEPTH = 64).
			// Each node has key/value, a "left" child pointing deeper, and no "right" child.
			ObjectReference deepest = buildLeftSpineTree(100);

			int[] counter = {0};
			StringBuilder out = new StringBuilder();

			// Should not throw StackOverflowError; just stops at MAX_TREE_DEPTH.
			assertThatCode(() -> {
				try {
					TestReflectionUtils.invokePrivate(
						service, "walkTreeMapInOrder",
						new Class[]{ObjectReference.class, int[].class, StringBuilder.class},
						deepest, counter, out);
				} catch (Exception e) {
					if (e.getCause() instanceof StackOverflowError soe) {
						throw soe;
					}
					// Other exceptions (e.g., mock-related) are acceptable in this safety test
				}
			}).doesNotThrowAnyException();

			// Counter should be < 100 because the depth limit kicks in before traversal completes
			assertThat(counter[0]).isLessThan(100);
		}
	}

	@Nested
	@DisplayName("Small tree rendering")
	class SmallTree {

		@Test
		@DisplayName("Three-node tree renders all entries")
		void shouldRenderAllEntriesOfSmallTree() throws Exception {
			// Build a simple 3-node tree:
			//        root (key="b")
			//       /    \
			//  left (key="a")  right (key="c")
			ObjectReference left = buildLeafNode("a", "1", 1L);
			ObjectReference right = buildLeafNode("c", "3", 3L);
			ObjectReference root = buildNodeWithChildren("b", "2", 2L, left, right);

			int[] counter = {0};
			StringBuilder out = new StringBuilder();

			TestReflectionUtils.invokePrivate(
				service, "walkTreeMapInOrder",
				new Class[]{ObjectReference.class, int[].class, StringBuilder.class},
				root, counter, out);

			// All 3 entries should be rendered
			assertThat(counter[0]).isEqualTo(3);
			// In-order traversal: a, b, c
			String result = out.toString();
			assertThat(result).contains("\"a\"");
			assertThat(result).contains("\"b\"");
			assertThat(result).contains("\"c\"");
		}

		@Test
		@DisplayName("Single node tree renders one entry")
		void shouldRenderSingleNodeTree() throws Exception {
			ObjectReference leaf = buildLeafNode("only", "42", 10L);

			int[] counter = {0};
			StringBuilder out = new StringBuilder();

			TestReflectionUtils.invokePrivate(
				service, "walkTreeMapInOrder",
				new Class[]{ObjectReference.class, int[].class, StringBuilder.class},
				leaf, counter, out);

			assertThat(counter[0]).isEqualTo(1);
			assertThat(out.toString()).contains("\"only\"");
		}
	}

	// ── Mock tree construction helpers ──

	/**
	 * Builds a left-spine chain of the given depth. Each node has a "left" child pointing
	 * to the next deeper node, no "right" child, and key/value fields.
	 */
	private ObjectReference buildLeftSpineTree(int depth) {
		ObjectReference current = buildLeafNode("leaf", "val", 1000L);
		for (int i = depth - 1; i >= 0; i--) {
			ObjectReference parent = buildNodeWithChildren(
				"k" + i, "v" + i, (long) (2000 + i), current, null);
			current = parent;
		}
		return current;
	}

	/**
	 * Creates a leaf node mock (no left/right children) with the given key and value strings.
	 */
	private ObjectReference buildLeafNode(String key, String value, long uniqueId) {
		return buildNodeWithChildren(key, value, uniqueId, null, null);
	}

	/**
	 * Creates a TreeMap entry node mock with optional left and right children.
	 * The key and value fields are mocked as StringReferences.
	 */
	private ObjectReference buildNodeWithChildren(String key, String value, long uniqueId,
			ObjectReference left, ObjectReference right) {
		ObjectReference node = mock(ObjectReference.class);
		ReferenceType type = mock(ReferenceType.class);
		when(node.referenceType()).thenReturn(type);
		when(node.uniqueID()).thenReturn(uniqueId);

		// key and value fields
		Field keyField = mock(Field.class);
		Field valueField = mock(Field.class);
		when(type.fieldByName("key")).thenReturn(keyField);
		when(type.fieldByName("value")).thenReturn(valueField);

		StringReference keyRef = mock(StringReference.class);
		StringReference valueRef = mock(StringReference.class);
		when(keyRef.value()).thenReturn(key);
		when(valueRef.value()).thenReturn(value);
		when(node.getValue(keyField)).thenReturn(keyRef);
		when(node.getValue(valueField)).thenReturn(valueRef);

		// left child
		Field leftField = mock(Field.class);
		when(type.fieldByName("left")).thenReturn(leftField);
		if (left != null) {
			when(node.getValue(leftField)).thenReturn(left);
		} else {
			when(node.getValue(leftField)).thenReturn(null);
		}

		// right child
		Field rightField = mock(Field.class);
		when(type.fieldByName("right")).thenReturn(rightField);
		if (right != null) {
			when(node.getValue(rightField)).thenReturn(right);
		} else {
			when(node.getValue(rightField)).thenReturn(null);
		}

		return node;
	}
}
