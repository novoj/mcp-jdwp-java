# JDWP MCP Server — Tool UX Improvements

> **Status:** COMPLETED 2026-04-07. All 15 tasks implemented, JAR rebuilt, end-to-end verified against the OrderProcessor and SessionStore sandbox flights. One follow-up bugfix to Task 8 was needed during verification — see "Completion notes" at the bottom of this file. The pre-execution sub-skill instruction below is left in place for historical traceability.

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Eliminate the friction points discovered during the 2026-04-07 test flight by reshaping existing tools and adding five new ones — making a typical "attach → break → inspect → form hypothesis" cycle take ~3 tool calls instead of ~10.

**Architecture:** All code changes live inside the existing Spring Boot MCP server. Most tasks are surgical edits to `JDWPTools.java` (the `@McpTool`-annotated tool surface), with smaller touches to `JDIConnectionService` (for thread filtering and object cache reset), `BreakpointTracker` (for the new "wait for next event" latch), and `JdiEventListener` (to fire that latch). One new internal helper class is introduced (`ThreadFormatting`). The `java-debug` skill doc is updated in lockstep so future Claude sessions discover the new tools and stop following the old workarounds. No new dependencies. No JUnit tests added — this project has none by design (see `CLAUDE.md`); verification happens by re-running the existing test flights against the rebuilt server after the final task.

**Tech Stack:** Java 17 source, Java 21 runtime against Spring Boot 4.0.5 + Spring AI MCP 2.0.0-M4 + JDI/JDWP. Indentation is **tabs**, line width is **120**. Lombok is on the classpath but only `@Slf4j` is in active use in the touched files.

---

## Background — what each improvement fixes

| # | Improvement | Pain point during 2026-04-07 test flight |
|---|---|---|
| 1 | Fix surefire pom `<argLine>` so `-DargLine=` extends it instead of being silently overridden | Wasted 3 maven runs before realising the JDWP agent was being dropped |
| 2 | Make `condition` and `suspendPolicy` params actually optional in `jdwp_set_breakpoint` | Had to pass `condition: ""` and `suspendPolicy: "all"` on every single call |
| 3 | Auto-unbox boxed primitives (`Integer`, `Double`, `Boolean`, …) in `formatFieldValue` | Had to wrap every `evaluate_expression` call in `String.valueOf(...)` to avoid `Object#N (java.lang.Double)` |
| 4 | Translate `ThreadReference.status()` ints to human labels | Status `1`, `4`, `5` are useless without looking up JDI constants |
| 5 | Filter JVM-internal/test-runner threads from `jdwp_get_threads` by default | 9-thread output where only 1 was the user thread |
| 6 | Truncate noisy junit/maven/reflection frames in `jdwp_get_stack` (default depth + prefix-based collapse) | 110-frame stack trace exploded my context window in Test Flight 4 |
| 7 | Include `this` automatically in `jdwp_get_locals` for instance methods | Always had to follow up with `evaluate_expression("this")` |
| 8 | Better error message + auto-rewrite for bare field references on `this` | "sessions cannot be resolved" — true cause is wrapper class can't see package-private types |
| 9 | New tool `jdwp_wait_for_attach(host, port, timeoutMs)` | Manual `/proc/net/tcp` polling to find when 5005 is listening |
| 10 | New tool `jdwp_resume_until_event(timeoutMs)` | The "resume → poll get_current_thread → repeat" choreography |
| 11 | New tool `jdwp_get_breakpoint_context()` | Same 4-call sequence (`get_current_thread` → `get_stack` → `get_locals` → `get_fields(this)`) at every BP hit |
| 12 | New tool `jdwp_assert_expression(expr, expected)` | One-shot hypothesis testing instead of eval-then-eyeball |
| 13 | New tool `jdwp_reset()` | Sequential test flights without re-attaching the MCP server |
| 14 | Update the `java-debug` skill doc — new tools, removed gotchas, refreshed recipes, new bootstrap recipe | Without this, future Claude sessions keep using the old patterns and never discover the new tools |

---

## Conventions for every task in this plan

- Tabs for indentation. Lines wrap at 120 chars.
- Imports go alphabetised at the top of the touched file. The plan assumes you'll add any new imports as needed without explicit instructions for each — JDI types like `ThreadReference`, `Field`, `Value`, etc. are already imported in the touched files.
- Do **not** run `mvn` between tasks. Do **not** create commits between tasks. The final task in this plan is the only place where you build, and Johnny commits manually after reviewing the diff.
- Do **not** add JUnit tests. The project has none by design.

---

## Task 1: Fix the surefire pom `<argLine>` extension

**Files:**
- Modify: `pom.xml:22-26` (add property default)
- Modify: `pom.xml:78-84` (extend surefire argLine)
- Modify: `docs/plans/2026-04-06-jdwp-test-flight.md` (rewrite the launch instructions to show the new working pattern)

**Why:** Today the pom hardcodes `<argLine>--add-modules jdk.jdi</argLine>` in the surefire plugin config, which silently clobbers any `-DargLine=...` passed on the command line. Users following the existing test-flight doc set the JDWP agent flags but they get dropped — the fork JVM runs without listening on 5005. The fix uses Maven's standard property-default + interpolation pattern so an externally-supplied `argLine` is appended to the hardcoded part instead of replacing it.

**Step 1: Add an empty `argLine` property default**

In `pom.xml` between line 22 and line 26, change:

```xml
<properties>
	<java.version>17</java.version>
	<spring-ai-mcp.version>2.0.0-M4</spring-ai-mcp.version>
	<ecj.version>3.45.0</ecj.version>
</properties>
```

to:

```xml
<properties>
	<java.version>17</java.version>
	<spring-ai-mcp.version>2.0.0-M4</spring-ai-mcp.version>
	<ecj.version>3.45.0</ecj.version>
	<!-- Default empty so surefire's argLine can be extended via -DargLine="..." on the command line -->
	<argLine></argLine>
</properties>
```

**Step 2: Reference the property in the surefire argLine**

In `pom.xml:78-84`, change:

```xml
<plugin>
	<groupId>org.apache.maven.plugins</groupId>
	<artifactId>maven-surefire-plugin</artifactId>
	<configuration>
		<argLine>--add-modules jdk.jdi</argLine>
	</configuration>
</plugin>
```

to:

```xml
<plugin>
	<groupId>org.apache.maven.plugins</groupId>
	<artifactId>maven-surefire-plugin</artifactId>
	<configuration>
		<!-- ${argLine} comes from <properties> default (empty) or from -DargLine on the command line.
				This pattern lets users append JVM args (e.g. JDWP agent) without losing --add-modules jdk.jdi. -->
		<argLine>--add-modules jdk.jdi ${argLine}</argLine>
	</configuration>
</plugin>
```

**Step 3: Update the test flight launch instructions**

In `docs/plans/2026-04-06-jdwp-test-flight.md`, find every block that looks like:

```bash
mvn test -o \
  -Dtest="..." \
  -DargLine="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
```

Replace each with:

```bash
mvn test -o -Dtest="..." -Dmaven.surefire.debug
```

…and add a one-line note at the top of the **Prerequisites** section explaining the choice:

```markdown
> **Note on launch flags:** `-Dmaven.surefire.debug` is surefire's built-in JDWP attach (port 5005, `suspend=y`) and is appended on top of the pom's existing `--add-modules jdk.jdi` argLine. If you need to pass *additional* JVM args alongside the debug agent, use `-DargLine="-Xfoo -Xbar"` — since 2026-04-07 the pom interpolates `${argLine}` so this no longer overrides the JDI module flag.
```

(Replace the surefire-debug runs in scenarios 1 through 5 — there are five `mvn test` blocks total.)

---

## Task 2: Make `condition` + `suspendPolicy` truly optional in the schema

**Files:**
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java:430-451` (annotation tweaks on `jdwp_set_breakpoint`)
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java:776-784` (annotation tweaks on `jdwp_set_exception_breakpoint`)
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java:198-200` (annotation tweak on `jdwp_to_string`)
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java:245-248` (annotation tweak on `jdwp_evaluate_expression`)
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java:737` (annotation tweak on `jdwp_get_events`)

**Why:** `@McpToolParam.required` defaults to `true` (verified in `spring-ai-mcp-annotations 2.0.0-M4` source). The current code already handles null/blank for these fields, but the auto-generated JSON schema marks them required, forcing callers to pass `""`/`"all"` etc. on every call. Adding `required = false` to the existing annotations is one-line each and removes that ceremony.

**Step 1: Make `suspendPolicy` and `condition` optional on `jdwp_set_breakpoint`**

In `src/main/java/io/mcp/jdwp/JDWPTools.java` near line 430, change the parameter list:

```java
public String jdwp_set_breakpoint(
		@McpToolParam(description = "Fully qualified class name (e.g. 'com.example.MyClass')") String className,
		@McpToolParam(description = "Line number") int lineNumber,
		@McpToolParam(description = "Suspend policy: 'all' (default), 'thread', 'none'") String suspendPolicy,
		@McpToolParam(description = "Optional condition — only suspend when this evaluates to true (e.g., 'i > 100')") String condition) {
```

to:

```java
public String jdwp_set_breakpoint(
		@McpToolParam(description = "Fully qualified class name (e.g. 'com.example.MyClass')") String className,
		@McpToolParam(description = "Line number") int lineNumber,
		@McpToolParam(required = false, description = "Suspend policy: 'all' (default), 'thread', 'none'") String suspendPolicy,
		@McpToolParam(required = false, description = "Optional condition — only suspend when this evaluates to true (e.g., 'i > 100')") String condition) {
```

The body of the method already handles `suspendPolicy == null` (line 442) and `condition == null || condition.isBlank()` (lines 453, 461, 503). No body changes needed.

**Step 2: Make `caught` and `uncaught` optional on `jdwp_set_exception_breakpoint`**

In `src/main/java/io/mcp/jdwp/JDWPTools.java` near line 776, change:

```java
public String jdwp_set_exception_breakpoint(
		@McpToolParam(description = "Exception class name (e.g., 'java.lang.NullPointerException', 'java.lang.Exception' for all)") String exceptionClass,
		@McpToolParam(description = "Break on caught exceptions (default: true)") Boolean caught,
		@McpToolParam(description = "Break on uncaught exceptions (default: true)") Boolean uncaught) {
```

to:

```java
public String jdwp_set_exception_breakpoint(
		@McpToolParam(description = "Exception class name (e.g., 'java.lang.NullPointerException', 'java.lang.Exception' for all)") String exceptionClass,
		@McpToolParam(required = false, description = "Break on caught exceptions (default: true)") Boolean caught,
		@McpToolParam(required = false, description = "Break on uncaught exceptions (default: true)") Boolean uncaught) {
```

The body already handles null at lines 782-783.

**Step 3: Make `threadId` optional on `jdwp_to_string`**

In `src/main/java/io/mcp/jdwp/JDWPTools.java` near line 198, change:

```java
public String jdwp_to_string(
		@McpToolParam(description = "Object unique ID (from jdwp_get_locals or jdwp_get_fields)") long objectId,
		@McpToolParam(description = "Thread unique ID (must be suspended). If omitted, uses the last breakpoint thread.") Long threadId) {
```

to:

```java
public String jdwp_to_string(
		@McpToolParam(description = "Object unique ID (from jdwp_get_locals or jdwp_get_fields)") long objectId,
		@McpToolParam(required = false, description = "Thread unique ID (must be suspended). If omitted, uses the last breakpoint thread.") Long threadId) {
```

Body already handles null `threadId` at line 210.

**Step 4: Make `frameIndex` optional on `jdwp_evaluate_expression`**

In `src/main/java/io/mcp/jdwp/JDWPTools.java` near line 245, change:

```java
public String jdwp_evaluate_expression(
		@McpToolParam(description = "Thread unique ID") long threadId,
		@McpToolParam(description = "Java expression to evaluate (e.g., 'order.getTotal()', 'x + y', 'name.length()')") String expression,
		@McpToolParam(description = "Frame index (0 = current frame, default: 0)") Integer frameIndex) {
```

to:

```java
public String jdwp_evaluate_expression(
		@McpToolParam(description = "Thread unique ID") long threadId,
		@McpToolParam(description = "Java expression to evaluate (e.g., 'order.getTotal()', 'x + y', 'name.length()')") String expression,
		@McpToolParam(required = false, description = "Frame index (0 = current frame, default: 0)") Integer frameIndex) {
```

Body already handles null at line 255.

**Step 5: Make `count` optional on `jdwp_get_events`**

In `src/main/java/io/mcp/jdwp/JDWPTools.java` line 737, change:

```java
public String jdwp_get_events(@McpToolParam(description = "Number of recent events to retrieve (default: 20, max: 100)") Integer count) {
```

to:

```java
public String jdwp_get_events(@McpToolParam(required = false, description = "Number of recent events to retrieve (default: 20, max: 100)") Integer count) {
```

Body already handles null at line 739.

---

## Task 3: Auto-unbox boxed primitives in `formatFieldValue`

**Files:**
- Modify: `src/main/java/io/mcp/jdwp/JDIConnectionService.java:478-503` (`formatFieldValue` method)

**Why:** `jdwp_evaluate_expression` always returns a wrapper-typed result because the generated wrapper method's return type is `Object` (see `JdiExpressionEvaluator.generateSourceCode` line 196), so a primitive `int` becomes `Object#N (java.lang.Integer)`. Today users have to write `String.valueOf(order.getTotal())` for every primitive — annoying and easy to forget. Boxed primitives have a private `value` field accessible via JDI without `invokeMethod`, so we can unbox them in the formatter without needing a thread.

**Step 1: Add the boxed-type set and helper at the bottom of `JDIConnectionService`**

In `src/main/java/io/mcp/jdwp/JDIConnectionService.java`, near the existing `isJvmInternalThread` helper (around line 714), add the constant near the top of the class (next to `objectCache` declaration on line 37) and the helper method anywhere private:

Top of the class — add after line 37 (`private final Map<Long, ObjectReference> objectCache = ...`):

```java
private static final java.util.Set<String> BOXED_PRIMITIVE_TYPES = java.util.Set.of(
	"java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
	"java.lang.Boolean", "java.lang.Character", "java.lang.Byte", "java.lang.Short");
```

Helper method — add as a private method anywhere in the class (suggested: just below `formatFieldValue`):

```java
/**
 * If {@code obj} is a wrapper type for a Java primitive, reads its private {@code value} field
 * directly via JDI (no invocation needed) and returns the unboxed string form. Returns {@code null}
 * for any other type so the caller can fall through to the regular {@code Object#N (...)} rendering.
 */
private String tryUnboxPrimitive(ObjectReference obj) {
	String typeName = obj.referenceType().name();
	if (!BOXED_PRIMITIVE_TYPES.contains(typeName)) {
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
```

**Step 2: Use the helper from `formatFieldValue`**

In `src/main/java/io/mcp/jdwp/JDIConnectionService.java:497-503`, change the `ObjectReference` arm:

```java
if (value instanceof ObjectReference obj) {
	cacheObject(obj); // Store in cache for later inspection
	return String.format("Object#%d (%s)", obj.uniqueID(), obj.referenceType().name());
}

return value.toString();
```

to:

```java
if (value instanceof ObjectReference obj) {
	String unboxed = tryUnboxPrimitive(obj);
	if (unboxed != null) {
		return unboxed;
	}
	cacheObject(obj); // Store in cache for later inspection
	return String.format("Object#%d (%s)", obj.uniqueID(), obj.referenceType().name());
}

return value.toString();
```

Note: we still cache *non-boxed* objects but skip caching for the boxed primitives (no point — they're values not references). After this change `jdwp_evaluate_expression` returns `Result: 71.982` directly instead of `Result: Object#N (java.lang.Double)`.

---

## Task 4: Translate thread status numbers to readable names

**Files:**
- Create: `src/main/java/io/mcp/jdwp/ThreadFormatting.java`
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java:80-111` (`jdwp_get_threads`)

**Why:** `ThreadReference.status()` returns ints (0..6) and the current `jdwp_get_threads` output literally prints `Status: 4` — useless without `com.sun.jdi.ThreadReference` constant lookup. The new helper class also gives Task 5 a single home for the JVM-internal-thread name list (currently buried as `private` in `JDIConnectionService`).

**Step 1: Create the helper class**

Create `src/main/java/io/mcp/jdwp/ThreadFormatting.java`:

```java
package io.mcp.jdwp;

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
			String name = t.name();
			if (EXACT_INTERNAL_NAMES.contains(name)) {
				return true;
			}
			for (String prefix : INTERNAL_PREFIXES) {
				if (name.startsWith(prefix)) {
					return true;
				}
			}
			return false;
		} catch (Exception e) {
			return true;
		}
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
```

**Step 2: Use the new status formatter inside `jdwp_get_threads`**

In `src/main/java/io/mcp/jdwp/JDWPTools.java:94`, change:

```java
result.append(String.format("  Status: %d\n", thread.status()));
```

to:

```java
result.append(String.format("  Status: %s\n", ThreadFormatting.formatStatus(thread.status())));
```

(The full filtering change for this method comes in Task 5.)

**Step 3: Switch `JDIConnectionService.isJvmInternalThread` to delegate to the new helper**

In `src/main/java/io/mcp/jdwp/JDIConnectionService.java:714-728`, replace the entire `isJvmInternalThread` private method with a one-liner that delegates:

```java
private boolean isJvmInternalThread(ThreadReference t) {
	return ThreadFormatting.isJvmInternalThread(t);
}
```

Keep the method (don't inline at the call site on line 706) so the existing caller in `findSuspendedThread` keeps compiling without changes. The expanded prefix list now also catches surefire forks etc. — that's intentional and fine for the existing internal use too.

---

## Task 5: Filter system threads from `jdwp_get_threads` by default

**Files:**
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java:80-111` (`jdwp_get_threads` method)

**Why:** A typical sandbox test fork has 9 threads, 7 of which are JVM/JDK/surefire internals. Hiding them by default reduces visual noise; users who need them can pass `includeSystemThreads=true`. Reuses the helper from Task 4.

**Step 1: Add the optional parameter and the filter**

In `src/main/java/io/mcp/jdwp/JDWPTools.java:80-111`, replace the entire `jdwp_get_threads` method body with:

```java
@McpTool(description = "List user threads in the JVM (status, frame count). System/JVM-internal threads (Reference Handler, Finalizer, surefire workers, etc.) are hidden unless includeSystemThreads=true.")
public String jdwp_get_threads(
		@McpToolParam(required = false, description = "Include JVM/JDK/test-runner internal threads (default: false)") Boolean includeSystemThreads) {
	try {
		boolean includeSystem = includeSystemThreads != null && includeSystemThreads;
		VirtualMachine vm = jdiService.getVM();
		List<ThreadReference> all = vm.allThreads();
		List<ThreadReference> threads = includeSystem
			? all
			: all.stream().filter(t -> !ThreadFormatting.isJvmInternalThread(t)).toList();

		StringBuilder result = new StringBuilder();
		int hidden = all.size() - threads.size();
		result.append(String.format("Found %d thread(s)%s:\n\n",
			threads.size(),
			hidden > 0 ? String.format(" (%d system thread(s) hidden — pass includeSystemThreads=true to show)", hidden) : ""));

		for (int i = 0; i < threads.size(); i++) {
			ThreadReference thread = threads.get(i);
			result.append(String.format("Thread %d:\n", i));
			result.append(String.format("  ID: %d\n", thread.uniqueID()));
			result.append(String.format("  Name: %s\n", thread.name()));
			result.append(String.format("  Status: %s\n", ThreadFormatting.formatStatus(thread.status())));
			result.append(String.format("  Suspended: %s\n", thread.isSuspended()));

			if (thread.isSuspended()) {
				try {
					int frameCount = thread.frameCount();
					result.append(String.format("  Frames: %d\n", frameCount));
				} catch (IncompatibleThreadStateException ignored) {}
			}

			result.append("\n");
		}

		return result.toString();
	} catch (Exception e) {
		return "Error: " + e.getMessage();
	}
}
```

This both adds the new optional parameter and folds in the status formatter from Task 4 (the line you edited in Task 4 lives inside this rewritten body — that's fine, the edit becomes a no-op when this whole method is rewritten).

---

## Task 6: Truncate noisy frames in `jdwp_get_stack`

**Files:**
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java:113-153` (`jdwp_get_stack`)

**Why:** Test Flight 4 returned a 110-frame stack — 4 frames of user code wrapped in 106 frames of JUnit, surefire, reflection, and lambda metafactory machinery. We should default to a shallow window and collapse known-noise frames into a one-line summary, while still letting power users opt in to the raw view.

**Step 1: Replace the method body**

In `src/main/java/io/mcp/jdwp/JDWPTools.java:113-153`, replace the entire `jdwp_get_stack` method with:

```java
private static final String[] NOISE_PACKAGE_PREFIXES = {
	"org.junit.",
	"org.apache.maven.surefire.",
	"jdk.internal.reflect.",
	"java.lang.reflect.",
	"java.lang.invoke.",
	"sun.reflect.",
	"jdk.internal.invoke."
};

@McpTool(description = "Get the call stack for a specific thread. Defaults to top 10 user frames; junit/surefire/reflection internals are collapsed unless you pass includeNoise=true or raise maxFrames.")
public String jdwp_get_stack(
		@McpToolParam(description = "Thread unique ID") long threadId,
		@McpToolParam(required = false, description = "Maximum frames to render (default: 10). Higher values include deeper call sites.") Integer maxFrames,
		@McpToolParam(required = false, description = "If true, do not collapse junit/maven/reflection frames (default: false)") Boolean includeNoise) {
	try {
		int limit = (maxFrames != null && maxFrames > 0) ? maxFrames : 10;
		boolean includeNoiseFrames = includeNoise != null && includeNoise;

		VirtualMachine vm = jdiService.getVM();
		ThreadReference thread = findThread(vm, threadId);
		if (thread == null) {
			return "Error: Thread not found with ID " + threadId;
		}

		if (!thread.isSuspended()) {
			return "Error: Thread is not suspended. Thread must be stopped at a breakpoint.";
		}

		List<StackFrame> frames = thread.frames();
		StringBuilder result = new StringBuilder();
		result.append(String.format("Stack trace for thread %d (%s) - %d frame(s) total:\n\n",
			threadId, thread.name(), frames.size()));

		int rendered = 0;
		int collapsedNoise = 0;
		for (int i = 0; i < frames.size() && rendered < limit; i++) {
			StackFrame frame = frames.get(i);
			Location location = frame.location();
			String declaringType = location.declaringType().name();

			if (!includeNoiseFrames && isNoiseFrame(declaringType)) {
				collapsedNoise++;
				continue;
			}

			result.append(String.format("Frame %d:\n", i));
			result.append(String.format("  at %s.%s(", declaringType, location.method().name()));
			try {
				result.append(String.format("%s:%d)\n", location.sourceName(), location.lineNumber()));
			} catch (AbsentInformationException e) {
				result.append("Unknown Source)\n");
			}
			rendered++;
		}

		if (collapsedNoise > 0) {
			result.append(String.format("\n... %d junit/maven/reflection frame(s) collapsed (pass includeNoise=true to show)\n", collapsedNoise));
		}
		if (rendered >= limit && frames.size() > limit + collapsedNoise) {
			int remaining = frames.size() - limit - collapsedNoise;
			result.append(String.format("... %d more frame(s) hidden (raise maxFrames to see them)\n", remaining));
		}

		return result.toString();
	} catch (Exception e) {
		return "Error: " + e.getMessage();
	}
}

/** Returns true if the declaring class belongs to a known-noisy framework or JDK internal. */
private static boolean isNoiseFrame(String declaringType) {
	for (String prefix : NOISE_PACKAGE_PREFIXES) {
		if (declaringType.startsWith(prefix)) {
			return true;
		}
	}
	return false;
}
```

The `NOISE_PACKAGE_PREFIXES` constant goes immediately above the method (i.e., between the `formatValue` helper around line 112 and the `jdwp_get_stack` method at the original line 113).

---

## Task 7: Include `this` automatically in `jdwp_get_locals`

**Files:**
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java:155-186` (`jdwp_get_locals`)

**Why:** Almost every BP-hit workflow that needs to inspect object state begins with "find `this`". Today that's a separate `evaluate_expression("this")` call. Just include it in the locals output as a synthetic entry — it costs one extra line and saves a round trip.

**Step 1: Replace the method body**

In `src/main/java/io/mcp/jdwp/JDWPTools.java:155-186`, replace the entire `jdwp_get_locals` method with:

```java
@McpTool(description = "Get local variables for a specific frame in a thread. Also includes 'this' (cached as Object#N) for instance methods.")
public String jdwp_get_locals(
		@McpToolParam(description = "Thread unique ID") long threadId,
		@McpToolParam(description = "Frame index (0 = current frame)") int frameIndex) {
	try {
		VirtualMachine vm = jdiService.getVM();
		ThreadReference thread = findThread(vm, threadId);
		if (thread == null) {
			return "Error: Thread not found with ID " + threadId;
		}

		StackFrame frame = thread.frame(frameIndex);
		StringBuilder result = new StringBuilder();
		result.append(String.format("Local variables in frame %d:\n\n", frameIndex));

		// Synthetic 'this' entry for instance methods. Cached so the user can immediately call
		// jdwp_get_fields(<id>) without a separate eval round-trip.
		ObjectReference thisObj = frame.thisObject();
		if (thisObj != null) {
			result.append(String.format("this (%s) = %s\n",
				thisObj.referenceType().name(),
				formatValue(thisObj)));
		}

		Map<LocalVariable, Value> vars = frame.getValues(frame.visibleVariables());
		for (Map.Entry<LocalVariable, Value> entry : vars.entrySet()) {
			LocalVariable var = entry.getKey();
			Value value = entry.getValue();
			result.append(String.format("%s (%s) = %s\n",
				var.name(),
				var.typeName(),
				formatValue(value)));
		}

		return result.toString();
	} catch (Exception e) {
		return "Error: " + e.getMessage();
	}
}
```

`formatValue(thisObj)` calls `JDIConnectionService.formatFieldValue` which already caches the `ObjectReference` as a side effect (line 498), so the user can immediately call `jdwp_get_fields(<id>)` against it.

---

## Task 8: Smarter expression evaluator for bare `this`-field references

**Files:**
- Modify: `src/main/java/io/mcp/jdwp/evaluation/JdiExpressionEvaluator.java:53-132` (`evaluate` method) — pass `this`-type to source generation
- Modify: `src/main/java/io/mcp/jdwp/evaluation/JdiExpressionEvaluator.java:181-201` (`generateSourceCode` + new helper)
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java:244-266` (`jdwp_evaluate_expression`) — augment the error message when compilation fails on a name that matches a `this` field

**Why:** Two complementary improvements for the same gotcha:
1. **Auto-rewrite (when safe):** If the user writes `sessions.containsKey(session)` and `sessions` is a field on `this`'s declared type *and* there's no local with that name *and* the declared type is public, rewrite the expression to `_this.sessions.containsKey(session)` before compilation. This handles the common case automatically.
2. **Better error (when not):** If the rewrite isn't safe (declared type is package-private) or the rewrite still fails compilation, append a hint to the error explaining the package-private wrapper-class problem and pointing the user at `jdwp_get_fields(thisObjectId)`.

**Step 1: Add the rewrite helper to `JdiExpressionEvaluator`**

In `src/main/java/io/mcp/jdwp/evaluation/JdiExpressionEvaluator.java`, near the bottom of the class (just above `private static class EvaluationContext` around line 291), add:

```java
/**
 * Rewrites bare references to fields of {@code this} as {@code _this.field} so the wrapper class
 * can resolve them. Only safe to call when {@code this}'s declared type is public — for
 * package-private types the wrapper class still wouldn't be able to access the field even after
 * the rewrite, and we'd produce a misleading error. The caller decides whether the type is public.
 *
 * @param expression       the user-supplied Java expression
 * @param thisFieldNames   field names declared on {@code this}'s type (already filtered for publicness)
 * @param shadowingLocals  local variable names that shadow fields and must NOT be rewritten
 * @return the expression with bare field references prefixed by {@code _this.}
 */
private String rewriteThisFieldReferences(String expression, java.util.Set<String> thisFieldNames,
		java.util.Set<String> shadowingLocals) {
	if (thisFieldNames.isEmpty()) {
		return expression;
	}
	String result = expression;
	for (String fieldName : thisFieldNames) {
		if (shadowingLocals.contains(fieldName)) {
			continue;
		}
		// Match the bare identifier: not preceded by a word char or dot, not followed by a word char.
		// Example: matches `sessions` in `sessions.containsKey(...)` and `if (sessions != null)`.
		String pattern = "(?<![\\w.])" + java.util.regex.Pattern.quote(fieldName) + "(?!\\w)";
		result = result.replaceAll(pattern, "_this." + fieldName);
	}
	return result;
}
```

**Step 2: Wire the rewrite into `evaluate`**

In `src/main/java/io/mcp/jdwp/evaluation/JdiExpressionEvaluator.java:53-132`, modify the start of `evaluate(...)` so it consults the `this`-type and rewrites the expression *before* the cache key is built (otherwise the unrewritten and rewritten forms would clash on the same key):

Find this line (currently line 59):

```java
EvaluationContext context = buildContext(frame);
```

Replace with:

```java
EvaluationContext context = buildContext(frame);

// Auto-rewrite bare references to fields of `this` so users can write
// `sessions.containsKey(session)` instead of `_this.sessions.containsKey(session)`.
// Only safe when `this`'s declared type is public — otherwise the wrapper class can't
// access the field anyway and we'd just produce a misleading compile error.
ObjectReference thisObject = frame.thisObject();
if (thisObject != null && thisObject.referenceType() instanceof ClassType thisClass && thisClass.isPublic()) {
	java.util.Set<String> shadowingLocals = context.getVariables().stream()
		.map(v -> v.name)
		.collect(java.util.stream.Collectors.toSet());
	java.util.Set<String> publicFieldNames = thisClass.allFields().stream()
		.map(Field::name)
		.collect(java.util.stream.Collectors.toSet());
	expression = rewriteThisFieldReferences(expression, publicFieldNames, shadowingLocals);
}
```

(`Field` is in `com.sun.jdi` which is already wildcard-imported on line 5.)

**Step 3: Improve the error message in `jdwp_evaluate_expression`**

In `src/main/java/io/mcp/jdwp/JDWPTools.java:263-265`, change:

```java
} catch (Exception e) {
	return "Error evaluating expression: " + e.getMessage();
}
```

to:

```java
} catch (Exception e) {
	String msg = e.getMessage() != null ? e.getMessage() : e.toString();
	String enriched = enrichEvaluationError(msg, threadId, frameIndex);
	return "Error evaluating expression: " + enriched;
}
```

**Step 4: Add the `enrichEvaluationError` helper**

Add this private method anywhere in `JDWPTools` (suggested location: just below `jdwp_evaluate_expression`, before `jdwp_resume`):

```java
/**
 * If the evaluator's error message looks like "X cannot be resolved" and X matches a field on
 * {@code this}'s declared type, append a hint explaining the package-private wrapper-class
 * limitation and pointing the user at {@code jdwp_get_fields(thisObjectId)}.
 */
private String enrichEvaluationError(String originalMessage, long threadId, Integer frameIndex) {
	java.util.regex.Matcher m = java.util.regex.Pattern
		.compile("([A-Za-z_][A-Za-z_0-9]*)\\s+cannot be resolved")
		.matcher(originalMessage);
	if (!m.find()) {
		return originalMessage;
	}
	String unresolved = m.group(1);

	try {
		VirtualMachine vm = jdiService.getVM();
		ThreadReference thread = findThread(vm, threadId);
		if (thread == null || !thread.isSuspended()) {
			return originalMessage;
		}
		StackFrame frame = thread.frame(frameIndex != null ? frameIndex : 0);
		ObjectReference thisObj = frame.thisObject();
		if (thisObj == null) {
			return originalMessage;
		}
		boolean fieldExists = thisObj.referenceType().allFields().stream()
			.anyMatch(f -> f.name().equals(unresolved));
		if (!fieldExists) {
			return originalMessage;
		}
		jdiService.cacheObject(thisObj);
		String thisType = thisObj.referenceType().name();
		boolean isPublic = thisObj.referenceType() instanceof ClassType ct && ct.isPublic();
		StringBuilder hint = new StringBuilder(originalMessage);
		hint.append("\n\nHint: '").append(unresolved).append("' is a field on this (")
			.append(thisType).append(", Object#").append(thisObj.uniqueID()).append(").");
		if (isPublic) {
			hint.append(" Auto-rewrite should have handled this — please report the expression that triggered it.");
		} else {
			hint.append(" The enclosing class is package-private (or non-public) so the expression wrapper")
				.append(" cannot reference it directly. Workaround:")
				.append(" call jdwp_get_fields(").append(thisObj.uniqueID()).append(")")
				.append(" to inspect the field, or jdwp_to_string for a quick view.");
		}
		return hint.toString();
	} catch (Exception probeFailure) {
		return originalMessage;
	}
}
```

---

## Task 9: New tool — `jdwp_wait_for_attach(host, port, timeoutMs)`

**Files:**
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java` (add a new `@McpTool` method below `jdwp_connect`)

**Why:** Replaces the manual "launch JVM in background → poll `/proc/net/tcp` → call `jdwp_connect`" dance with a single call. Polls every 200 ms up to a deadline, attempting `jdiService.connect(host, port)` each iteration.

**Step 1: Add the tool method**

In `src/main/java/io/mcp/jdwp/JDWPTools.java`, immediately after `jdwp_connect` (around line 62, just before `jdwp_disconnect`), add:

```java
@McpTool(description = "Wait until a JVM is listening for JDWP and attach. Polls every 200ms until timeout. Use this to bootstrap a debug session without manually polling for the listener — call this after launching the target with `mvn test -Dmaven.surefire.debug` (or any other JVM started with `-agentlib:jdwp=...,suspend=y`).")
public String jdwp_wait_for_attach(
		@McpToolParam(required = false, description = "Hostname (default: localhost)") String host,
		@McpToolParam(required = false, description = "JDWP port (default: 5005 — overridable via -DJVM_JDWP_PORT)") Integer port,
		@McpToolParam(required = false, description = "Maximum wait time in milliseconds (default: 30000)") Integer timeoutMs) {
	String resolvedHost = (host == null || host.isBlank()) ? "localhost" : host;
	int resolvedPort = (port != null) ? port : JVM_JDWP_PORT;
	int deadlineMs = (timeoutMs != null && timeoutMs > 0) ? timeoutMs : 30_000;

	long deadline = System.currentTimeMillis() + deadlineMs;
	int attempts = 0;
	String lastError = "none";

	while (System.currentTimeMillis() < deadline) {
		attempts++;
		try {
			String result = jdiService.connect(resolvedHost, resolvedPort);
			return String.format("%s (attached after %d attempt(s))", result, attempts);
		} catch (java.io.IOException e) {
			// Connection refused or similar — JVM not listening yet, retry.
			lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
		} catch (Exception e) {
			// Could be a transient handshake race during JVM startup; retry until deadline.
			lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
		}
		try {
			Thread.sleep(200);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			return String.format("[INTERRUPTED] After %d attempt(s) waiting for %s:%d. Last error: %s",
				attempts, resolvedHost, resolvedPort, lastError);
		}
	}

	return String.format("[TIMEOUT] No JVM listening on %s:%d after %d attempt(s) over %dms.\n" +
		"Last error: %s\n\n" +
		"Make sure the target JVM was launched with -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:%d\n" +
		"For Maven tests in this repo, use: mvn test -Dmaven.surefire.debug",
		resolvedHost, resolvedPort, attempts, deadlineMs, lastError, resolvedPort);
}
```

---

## Task 10: New tool — `jdwp_resume_until_event(timeoutMs)`

**Files:**
- Modify: `src/main/java/io/mcp/jdwp/BreakpointTracker.java` (add the latch arming + signalling)
- Modify: `src/main/java/io/mcp/jdwp/JdiEventListener.java:130-178` (`handleBreakpointEvent`) to fire the latch
- Modify: `src/main/java/io/mcp/jdwp/JdiEventListener.java:180-196` (`handleStepEvent`) to fire the latch
- Modify: `src/main/java/io/mcp/jdwp/JdiEventListener.java:198-224` (`handleExceptionEvent`) to fire the latch
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java` (add a new `@McpTool` method near `jdwp_resume`)

**Why:** Replaces the "resume → poll `jdwp_get_current_thread` → maybe sleep → poll again" loop with a single synchronous call that returns when the next BP/step/exception event lands (or timeout). Implemented via a single-shot `CountDownLatch` armed before resume and counted down by the event listener.

**Step 1: Add the latch + arming/firing API to `BreakpointTracker`**

In `src/main/java/io/mcp/jdwp/BreakpointTracker.java`, just below the existing `lastBreakpointThread` / `lastBreakpointId` declarations (around line 40), add:

```java
private volatile java.util.concurrent.CountDownLatch nextEventLatch;
```

Add these public methods to the "Thread tracking" section near `setLastBreakpointThread` (around line 461):

```java
/**
 * Arms a fresh single-shot latch that will be released the next time {@link #fireNextEvent()}
 * is called. Returns the latch — callers should arm BEFORE resuming the VM and then await on
 * the returned latch to avoid the race where the event fires between resume and arm.
 *
 * <p>Used by {@link io.mcp.jdwp.JDWPTools#jdwp_resume_until_event(Integer)} to implement
 * synchronous "resume and wait for next stop". Replaces any previously-armed latch.
 */
public synchronized java.util.concurrent.CountDownLatch armNextEventLatch() {
	this.nextEventLatch = new java.util.concurrent.CountDownLatch(1);
	return this.nextEventLatch;
}

/**
 * Releases the currently-armed latch (if any) and clears it. Called by {@link io.mcp.jdwp.JdiEventListener}
 * after every BP/step/exception event so that {@code jdwp_resume_until_event} can return.
 */
public void fireNextEvent() {
	java.util.concurrent.CountDownLatch latch = this.nextEventLatch;
	if (latch != null) {
		latch.countDown();
		this.nextEventLatch = null;
	}
}
```

Also extend `reset()` (around line 477) to clear the latch — add this line at the end of the method body, just before `idCounter.set(1)`:

```java
nextEventLatch = null;
```

**Step 2: Fire the latch from each suspending event handler**

In `src/main/java/io/mcp/jdwp/JdiEventListener.java`, in `handleBreakpointEvent` (line 130), add a single line right before the `return true;` on line 172 (the "Normal breakpoint — record event and keep suspended" branch):

Find:

```java
log.info("[JDI] Breakpoint {} hit on thread {} at {}:{}", bpId, threadName, className, lineNumber);
return true;
```

Replace with:

```java
log.info("[JDI] Breakpoint {} hit on thread {} at {}:{}", bpId, threadName, className, lineNumber);
breakpointTracker.fireNextEvent();
return true;
```

In `handleStepEvent` (line 180), add `breakpointTracker.fireNextEvent();` as the **last** statement of the method (after the `eventHistory.record(...)` call inside the try block, on a new line right before the `} catch (Exception e) {` block):

Find:

```java
eventHistory.record(new EventHistory.DebugEvent("STEP",
	String.format("Step to %s:%d on thread %s", className, lineNumber, threadName),
	Map.of("class", className, "line", String.valueOf(lineNumber), "thread", threadName)));
} catch (Exception e) {
```

Replace with:

```java
eventHistory.record(new EventHistory.DebugEvent("STEP",
	String.format("Step to %s:%d on thread %s", className, lineNumber, threadName),
	Map.of("class", className, "line", String.valueOf(lineNumber), "thread", threadName)));
breakpointTracker.fireNextEvent();
} catch (Exception e) {
```

In `handleExceptionEvent` (line 198), add the same line just after `breakpointTracker.setLastBreakpointThread(event.thread(), -1);` (line 211). Find:

```java
breakpointTracker.setLastBreakpointThread(event.thread(), -1);

eventHistory.record(new EventHistory.DebugEvent("EXCEPTION",
```

Replace with:

```java
breakpointTracker.setLastBreakpointThread(event.thread(), -1);
breakpointTracker.fireNextEvent();

eventHistory.record(new EventHistory.DebugEvent("EXCEPTION",
```

**Step 3: Add the `jdwp_resume_until_event` tool**

In `src/main/java/io/mcp/jdwp/JDWPTools.java`, immediately after `jdwp_resume` (around line 277), add:

```java
@McpTool(description = "Resume the VM and BLOCK until the next breakpoint, step, or exception event fires (or timeout). Returns the same info as jdwp_get_current_thread on success. Replaces the manual 'resume → poll → poll' choreography.")
public String jdwp_resume_until_event(
		@McpToolParam(required = false, description = "Maximum wait time in milliseconds (default: 30000)") Integer timeoutMs) {
	int deadlineMs = (timeoutMs != null && timeoutMs > 0) ? timeoutMs : 30_000;
	try {
		VirtualMachine vm = jdiService.getVM();
		// Arm BEFORE resume so we don't race with a near-instant event firing.
		java.util.concurrent.CountDownLatch latch = breakpointTracker.armNextEventLatch();
		vm.resume();

		boolean fired = latch.await(deadlineMs, java.util.concurrent.TimeUnit.MILLISECONDS);
		if (!fired) {
			return String.format("[TIMEOUT] No event fired within %dms after resume.\n" +
				"The VM is still running. You can call jdwp_resume_until_event again with a larger timeout, " +
				"or use jdwp_get_threads to see live thread state.", deadlineMs);
		}

		ThreadReference thread = breakpointTracker.getLastBreakpointThread();
		if (thread == null) {
			return "Event fired but no breakpoint thread recorded (this should not happen — check the listener logs).";
		}
		Integer bpId = breakpointTracker.getLastBreakpointId();
		return String.format("Event fired. Thread: %s (ID=%d, suspended=%s, frames=%d, breakpoint=%s)",
			thread.name(), thread.uniqueID(), thread.isSuspended(),
			thread.isSuspended() ? thread.frameCount() : -1,
			bpId != null ? String.valueOf(bpId) : "unknown");
	} catch (InterruptedException ie) {
		Thread.currentThread().interrupt();
		return "Wait interrupted";
	} catch (Exception e) {
		return "Error: " + e.getMessage();
	}
}
```

---

## Task 11: New tool — `jdwp_get_breakpoint_context()`

**Files:**
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java` (add a new `@McpTool` method near `jdwp_get_current_thread`)

**Why:** Collapses the post-BP-hit standard sequence (`get_current_thread` → `get_stack` → `get_locals` → `get_fields(this)`) into one call. Returns a single composite report with: thread + bp ID, top N frames (using the noise filter from Task 6), frame-0 locals (using the `this` injection from Task 7), and `this` field dump if applicable.

**Step 1: Add the tool method**

In `src/main/java/io/mcp/jdwp/JDWPTools.java`, immediately after `jdwp_get_current_thread` (around line 922), add:

```java
@McpTool(description = "One-shot debugging context at the current breakpoint: thread, top frames, locals at frame 0, and 'this' field dump. Use this instead of the four-call sequence (get_current_thread → get_stack → get_locals → get_fields(this)) at every BP hit.")
public String jdwp_get_breakpoint_context(
		@McpToolParam(required = false, description = "Max stack frames to render (default: 5). Junit/maven/reflection frames are always collapsed.") Integer maxFrames,
		@McpToolParam(required = false, description = "Include the 'this' field dump (default: true)") Boolean includeThisFields) {
	int frameLimit = (maxFrames != null && maxFrames > 0) ? maxFrames : 5;
	boolean includeThis = includeThisFields == null || includeThisFields;

	try {
		ThreadReference thread = breakpointTracker.getLastBreakpointThread();
		if (thread == null) {
			return "No current breakpoint detected. Set a breakpoint and trigger it first.";
		}
		if (!thread.isSuspended()) {
			return String.format("Thread %s (ID=%d) is no longer suspended.", thread.name(), thread.uniqueID());
		}

		Integer bpId = breakpointTracker.getLastBreakpointId();
		StringBuilder sb = new StringBuilder();
		sb.append("=== Breakpoint Context ===\n");
		sb.append(String.format("Thread: %s (ID=%d, breakpoint=%s)\n\n",
			thread.name(), thread.uniqueID(),
			bpId != null ? String.valueOf(bpId) : "unknown"));

		// Top frames (junit/maven/reflection collapsed via the same noise list as jdwp_get_stack)
		List<StackFrame> frames = thread.frames();
		sb.append(String.format("--- Top frames (showing up to %d, %d total) ---\n", frameLimit, frames.size()));
		int rendered = 0;
		int collapsed = 0;
		for (int i = 0; i < frames.size() && rendered < frameLimit; i++) {
			StackFrame f = frames.get(i);
			Location loc = f.location();
			String declType = loc.declaringType().name();
			if (isNoiseFrame(declType)) {
				collapsed++;
				continue;
			}
			String src;
			try {
				src = loc.sourceName() + ":" + loc.lineNumber();
			} catch (AbsentInformationException e) {
				src = "Unknown Source";
			}
			sb.append(String.format("  #%d %s.%s (%s)\n", i, declType, loc.method().name(), src));
			rendered++;
		}
		if (collapsed > 0) {
			sb.append(String.format("  ... %d junit/maven/reflection frame(s) collapsed\n", collapsed));
		}
		sb.append("\n");

		// Locals at frame 0 (with synthetic 'this' the same way jdwp_get_locals does it)
		StackFrame frame0 = frames.get(0);
		sb.append("--- Locals at frame 0 ---\n");
		ObjectReference thisObj = frame0.thisObject();
		if (thisObj != null) {
			sb.append(String.format("  this (%s) = %s\n",
				thisObj.referenceType().name(), formatValue(thisObj)));
		}
		try {
			Map<LocalVariable, Value> vars = frame0.getValues(frame0.visibleVariables());
			if (vars.isEmpty() && thisObj == null) {
				sb.append("  (none)\n");
			}
			for (Map.Entry<LocalVariable, Value> e : vars.entrySet()) {
				sb.append(String.format("  %s (%s) = %s\n",
					e.getKey().name(), e.getKey().typeName(), formatValue(e.getValue())));
			}
		} catch (AbsentInformationException e) {
			sb.append("  (no debug info — compile with -g)\n");
		}
		sb.append("\n");

		// 'this' field dump
		if (includeThis && thisObj != null) {
			jdiService.cacheObject(thisObj);
			sb.append(String.format("--- this fields (Object#%d, %s) ---\n",
				thisObj.uniqueID(), thisObj.referenceType().name()));
			List<Field> fields = thisObj.referenceType().allFields();
			for (Field field : fields) {
				Value v = thisObj.getValue(field);
				sb.append(String.format("  %s %s = %s\n", field.typeName(), field.name(), formatValue(v)));
			}
		} else if (includeThis) {
			sb.append("--- this --- (static method, no this)\n");
		}

		return sb.toString();
	} catch (Exception e) {
		return "Error: " + e.getMessage();
	}
}
```

---

## Task 12: New tool — `jdwp_assert_expression(expression, expected, …)`

**Files:**
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java` (add a new `@McpTool` method near `jdwp_evaluate_expression`)

**Why:** Hypothesis-testing helper. Instead of "evaluate, eyeball the result, compare in your head", returns `OK` or `MISMATCH` with both values formatted. Especially nice for runtime invariants ("is `order.getTotal()` still 71.982 right now?"). Compares against the formatted form (so `String.valueOf(...)` gymnastics aren't needed thanks to Task 3).

**Step 1: Add the tool method**

In `src/main/java/io/mcp/jdwp/JDWPTools.java`, immediately after `jdwp_evaluate_expression` (around line 266, before the `enrichEvaluationError` helper added in Task 8), add:

```java
@McpTool(description = "Evaluate a Java expression and compare its result against an expected value. Returns 'OK' on match, 'MISMATCH' with actual vs expected on failure. Comparison is string-based against the same formatting jdwp_evaluate_expression uses (so primitives auto-unbox, strings strip surrounding quotes).")
public String jdwp_assert_expression(
		@McpToolParam(description = "Java expression (e.g., 'order.getTotal()', 'session.getRole()', 'list.size() == 5')") String expression,
		@McpToolParam(description = "Expected value (string-compared against the formatted expression result)") String expected,
		@McpToolParam(required = false, description = "Thread ID — defaults to the last breakpoint thread") Long threadId,
		@McpToolParam(required = false, description = "Frame index (default: 0)") Integer frameIndex) {
	try {
		int frame = (frameIndex != null) ? frameIndex : 0;
		ThreadReference thread;
		if (threadId != null) {
			thread = findThread(jdiService.getVM(), threadId);
			if (thread == null) {
				return "Error: Thread not found with ID " + threadId;
			}
		} else {
			thread = breakpointTracker.getLastBreakpointThread();
			if (thread == null) {
				return "Error: No current breakpoint thread. Pass threadId or hit a breakpoint first.";
			}
		}
		if (!thread.isSuspended()) {
			return "Error: Thread is not suspended.";
		}

		expressionEvaluator.configureCompilerClasspath(thread);
		StackFrame stackFrame = thread.frame(frame);
		Value result = expressionEvaluator.evaluate(stackFrame, expression);
		String actual = formatValue(result);

		// Strip wrapping quotes from formatted strings so users can pass `expected="hello"` or `expected=hello`.
		String compareActual = actual;
		if (compareActual.length() >= 2 && compareActual.startsWith("\"") && compareActual.endsWith("\"")) {
			compareActual = compareActual.substring(1, compareActual.length() - 1);
		}

		if (compareActual.equals(expected)) {
			return String.format("OK — %s = %s", expression, actual);
		}
		return String.format("MISMATCH — %s\n  expected: %s\n  actual:   %s", expression, expected, actual);
	} catch (Exception e) {
		return "Error: " + e.getMessage();
	}
}
```

---

## Task 13: New tool — `jdwp_reset()`

**Files:**
- Modify: `src/main/java/io/mcp/jdwp/JDIConnectionService.java` (add a public `clearObjectCache()` method)
- Modify: `src/main/java/io/mcp/jdwp/JDWPTools.java` (add a new `@McpTool` method near `jdwp_clear_all_breakpoints`)

**Why:** Sequential test flights against the same target VM benefit from a "wipe everything but keep the connection" call. Today the user has to either disconnect+reconnect (which kills the target VM session) or manually clear breakpoints, watchers, and the event log one by one. `jdwp_reset` does all four in one shot without touching the JDI VM reference.

**Step 1: Add `clearObjectCache()` to `JDIConnectionService`**

In `src/main/java/io/mcp/jdwp/JDIConnectionService.java`, just below `getCachedObject` (around line 597), add:

```java
/**
 * Clears the entire object reference cache. Called by {@code jdwp_reset} to wipe per-session
 * state without dropping the VM connection. Does NOT touch breakpoints, watchers, or event
 * history — those are owned by their respective services and reset separately.
 */
public void clearObjectCache() {
	objectCache.clear();
}
```

**Step 2: Add the `jdwp_reset` tool**

In `src/main/java/io/mcp/jdwp/JDWPTools.java`, immediately after `jdwp_clear_all_breakpoints` (around line 900), add:

```java
@McpTool(description = "Clear ALL session state (breakpoints, exception breakpoints, watchers, object cache, event history) WITHOUT disconnecting from the target VM. Use between sequential debugging scenarios against the same long-running target.")
public String jdwp_reset() {
	try {
		VirtualMachine vm = jdiService.getVM();

		int activeBp = breakpointTracker.getAllBreakpoints().size();
		int pendingBp = breakpointTracker.getAllPendingBreakpoints().size();
		int activeExBp = breakpointTracker.getAllExceptionBreakpoints().size();
		int pendingExBp = breakpointTracker.getAllPendingExceptionBreakpoints().size();
		int watchers = watcherManager.getAllWatchers().size();
		int events = eventHistory.size();

		breakpointTracker.clearAll(vm.eventRequestManager());
		watcherManager.clearAll();
		jdiService.clearObjectCache();
		eventHistory.clear();

		return String.format(
			"Reset complete (VM connection preserved).\n" +
			"  Breakpoints cleared:           %d active + %d pending\n" +
			"  Exception breakpoints cleared: %d active + %d pending\n" +
			"  Watchers cleared:              %d\n" +
			"  Event history cleared:         %d entries\n" +
			"  Object cache cleared.",
			activeBp, pendingBp, activeExBp, pendingExBp, watchers, events);
	} catch (Exception e) {
		return "Error: " + e.getMessage();
	}
}
```

---

## Task 14: Update the `java-debug` skill doc

**Files:**
- Modify: `.claude/skills/java-debug/SKILL.md` (multiple sections — see steps)

**Why:** The skill is the entry-point doc that future Claude sessions read every time they invoke `java-debug`. Today it tells future-me to wrap every primitive eval in `String.valueOf(...)`, warns about a `this`-field gotcha that's now auto-handled, and walks through a 4-call sequence at every BP that the new `jdwp_get_breakpoint_context` collapses into one. If we ship the code changes without updating the skill, the next session will keep using the old patterns and never discover the new tools. The doc must move in lockstep with the server.

The edits in this task are independent of whether the JAR has been rebuilt — the skill is pure prose. Do them before Task 15 so the diff Johnny reviews is internally consistent (server + skill).

**Step 1: Add the new tools to the Tool Catalog**

In `.claude/skills/java-debug/SKILL.md`, in the **Setup / lifecycle** section (around line 111-114), change:

```markdown
### Setup / lifecycle
- **`jdwp_connect`** — attach to the configured JDWP port
- **`jdwp_disconnect`** — detach + clean up; auto-runs on reconnect to a new JVM
- **`jdwp_get_version`** — JVM info sanity check
```

to:

```markdown
### Setup / lifecycle
- **`jdwp_wait_for_attach(host?, port?, timeoutMs?)`** — poll until the JVM is listening, then attach. Use this as the FIRST call after launching the target — replaces manual port-polling. Defaults: `localhost`, `5005`, `30000ms`.
- **`jdwp_connect`** — attach immediately (fails if no JVM is listening). Prefer `jdwp_wait_for_attach` for fresh sessions.
- **`jdwp_disconnect`** — detach + clean up; auto-runs on reconnect to a new JVM
- **`jdwp_reset`** — clear breakpoints, exception breakpoints, watchers, object cache, and event history WITHOUT disconnecting. Use between sequential test flights against the same long-running target.
- **`jdwp_get_version`** — JVM info sanity check
```

In the **Inspection** section (around line 116-123), change:

```markdown
### Inspection
- **`jdwp_get_threads`** — all threads with ID, name, status
- **`jdwp_get_current_thread`** — last thread that hit a breakpoint
- **`jdwp_get_stack(threadId)`** — full call stack
- **`jdwp_get_locals(threadId, frameIndex)`** — visible local variables (frame 0 = topmost)
- **`jdwp_get_fields(objectId)`** — fields of a cached object reference
- **`jdwp_to_string(objectId, threadId)`** — invoke `toString()` on a cached object
- **`jdwp_evaluate_expression(threadId, expression, frameIndex)`** — eval any Java expression in scope
```

to:

```markdown
### Inspection
- **`jdwp_get_breakpoint_context(maxFrames?, includeThisFields?)`** — one-shot context dump at the current BP: thread, top frames (junit/maven/reflection collapsed), frame-0 locals (with synthetic `this`), and the `this` field dump. **Use this instead of the four-call `get_current_thread → get_stack → get_locals → get_fields(this)` sequence.** Defaults: 5 frames, fields included.
- **`jdwp_get_threads(includeSystemThreads?)`** — user threads only by default. JVM/JDK/surefire internals (Reference Handler, Finalizer, GC threads, etc.) are hidden unless `includeSystemThreads=true`. Statuses are human-readable (`RUNNING`, `WAIT`, `SLEEPING`, …).
- **`jdwp_get_current_thread`** — last thread that hit a breakpoint (lower-level than `jdwp_get_breakpoint_context`)
- **`jdwp_get_stack(threadId, maxFrames?, includeNoise?)`** — call stack. Default 10 frames, junit/maven/reflection collapsed. Pass `includeNoise=true` for raw output.
- **`jdwp_get_locals(threadId, frameIndex)`** — visible local variables (frame 0 = topmost). Now also includes `this` as a synthetic local for instance methods — no need to `eval this` separately.
- **`jdwp_get_fields(objectId)`** — fields of a cached object reference
- **`jdwp_to_string(objectId, threadId?)`** — invoke `toString()` on a cached object (`threadId` optional — defaults to last BP thread)
- **`jdwp_evaluate_expression(threadId, expression, frameIndex?)`** — eval any Java expression in scope. Boxed primitives auto-unbox: `eval order.getTotal()` returns `71.982`, not `Object#N (java.lang.Double)`.
- **`jdwp_assert_expression(expression, expected, threadId?, frameIndex?)`** — eval and compare in one shot. Returns `OK — expr = value` or `MISMATCH — expr / expected: X / actual: Y`. String comparison strips wrapping quotes so `expected="hello"` and `expected=hello` both work.
```

In the **Breakpoints** section (around line 129-134), change:

```markdown
### Breakpoints
- **`jdwp_set_breakpoint(className, lineNumber, suspendPolicy, condition?)`** — line BP, optionally conditional
```

to:

```markdown
### Breakpoints
- **`jdwp_set_breakpoint(className, lineNumber, suspendPolicy?, condition?)`** — line BP. `suspendPolicy` defaults to `"all"`; `condition` optional. Both params can be omitted entirely now.
```

In the same section, change:

```markdown
- **`jdwp_set_exception_breakpoint(exceptionClass, caught, uncaught)`** — catches an exception at the throw site, *before* any wrapping
```

to:

```markdown
- **`jdwp_set_exception_breakpoint(exceptionClass, caught?, uncaught?)`** — catches an exception at the throw site, *before* any wrapping. Both flags default to `true`.
```

In the **Execution control** section (around line 136-140), change:

```markdown
### Execution control
- **`jdwp_resume()` / `jdwp_resume_thread(threadId)` / `jdwp_suspend_thread(threadId)`**
- **`jdwp_step_over(threadId)`** — next line in the same frame
- **`jdwp_step_into(threadId)`** — into the next method call
- **`jdwp_step_out(threadId)`** — out of the current method
```

to:

```markdown
### Execution control
- **`jdwp_resume_until_event(timeoutMs?)`** — resume the VM and BLOCK until the next BP/step/exception event fires (or timeout). Use this instead of `jdwp_resume` + polling `jdwp_get_current_thread`. Default timeout: 30000ms.
- **`jdwp_resume()`** — resume without waiting (fire-and-forget). Use only when you don't care about the next stop.
- **`jdwp_resume_thread(threadId)` / `jdwp_suspend_thread(threadId)`** — per-thread control
- **`jdwp_step_over(threadId)`** — next line in the same frame
- **`jdwp_step_into(threadId)`** — into the next method call
- **`jdwp_step_out(threadId)`** — out of the current method
```

**Step 2: Update the Core Workflow to use the new tools**

In the **Core Workflow** section (around line 98-107), replace the entire numbered list with:

```markdown
1. **Launch the target** in a separate shell or background, with `suspend=y`. The JVM blocks until step 2.
2. **Attach:** `jdwp_wait_for_attach()` — polls until the JVM is listening and attaches. Replaces manual "is the port open yet?" polling.
3. **Set breakpoints** at suspected bug locations. `jdwp_set_breakpoint(className, lineNumber)` — `suspendPolicy` and `condition` are both optional. Add logpoints/exception BPs as needed.
4. **Resume and wait:** `jdwp_resume_until_event()` — releases the target JVM and BLOCKS until the next BP/step/exception fires. Returns the suspended thread info.
5. **Inspect everything in one call:** `jdwp_get_breakpoint_context()` — returns thread, top frames, locals (incl. `this`), and `this` field dump. This replaces the old `get_current_thread → get_stack → get_locals → get_fields(this)` chain.
6. **Form a hypothesis,** test it: step through, `jdwp_assert_expression(...)` to check invariants, `jdwp_set_local`/`jdwp_set_field` to mutate state and ask "would the test pass if X were Y?"
7. **Resume to next event** (`jdwp_resume_until_event`) or **disconnect** when done. For sequential scenarios against the same target, use `jdwp_reset` between flights to clear state without dropping the connection.
```

**Step 3: Remove and update the gotchas that no longer apply**

In the **Gotchas** section (around line 220-233), DELETE this entire bullet (line 222):

```markdown
- **Always use `String.valueOf(...)` to print primitives.** `evaluate_expression` returns `Object` (the wrapper class autoboxes primitives). `eval order.getTotal()` shows `Object#N (java.lang.Double)`; `eval String.valueOf(order.getTotal())` shows `"71.982"`.
```

Change the package-private-`this`-field gotcha (line 223) from:

```markdown
- **`evaluate_expression` cannot reference fields of `this`** when the enclosing class is package-private (the wrapper class can't see the type). Workarounds: (a) reference local variables only, or (b) `eval this` to get the cached object ID, then use `get_fields` on it.
```

to:

```markdown
- **`evaluate_expression` auto-rewrites bare references to fields of `this`** (e.g. `sessions.containsKey(k)` → `_this.sessions.containsKey(k)`) when the enclosing class is public. For PACKAGE-PRIVATE enclosing classes the auto-rewrite is skipped (the wrapper class still couldn't see the type) — the error message will name the field and tell you to use `jdwp_get_fields(<thisObjectId>)` instead. The `this` object ID is now in the regular `jdwp_get_locals` output (synthetic `this` entry), so this is a one-call workaround.
```

**Step 4: Update Common First Questions to use the new aggregated tool**

Around line 245-253, replace the entire **Common First Questions At a New Breakpoint** section with:

```markdown
## Common First Questions At a New Breakpoint

When you first land at a breakpoint and don't know what to look at:

1. **`jdwp_get_breakpoint_context()`** — one-shot dump of thread + top frames + locals + `this` fields. 90% of the time this is all you need to form your next hypothesis.
2. For each interesting `Object#N` reference: `jdwp_get_fields(objectId)` to drill in, then `jdwp_to_string(objectId)` for a quick view.
3. `jdwp_assert_expression(<expression>, <expected>)` to test "is the state what I expected?" in one shot — much terser than `evaluate_expression` followed by eyeballing.

If you need more than the default 5 frames in the context dump, pass `maxFrames=20` (or whatever depth you need). Junit/maven/reflection frames are always collapsed; pass `includeNoise=true` to `jdwp_get_stack` if you genuinely need them.
```

**Step 5: Add a new bootstrap recipe**

Append a new recipe AFTER Recipe 6 (around line 219, just before the `## Gotchas` heading):

```markdown
### Recipe 7 — "Cold-start a fresh debug session"

**Smell:** You need to debug a test or service from scratch — the JVM isn't running yet, you're not attached, and the bug only reproduces from a clean run.

**Approach:**
1. Launch the target in a background shell with `suspend=y` (e.g., `mvn test -Dtest=MyTest -Dmaven.surefire.debug` for surefire, or `./gradlew test --debug-jvm`).
2. **`jdwp_wait_for_attach()`** — polls every 200ms until the JVM is listening, then attaches. No manual port-checking, no "is it ready yet?" loop.
3. `jdwp_set_breakpoint(className, lineNumber)` — set your trap. Schema params for `condition`/`suspendPolicy` are now optional.
4. **`jdwp_resume_until_event()`** — releases the JVM and blocks until the BP fires (or timeout). Returns the suspended thread info.
5. **`jdwp_get_breakpoint_context()`** — full state dump in one call.

The whole sequence takes ~5 tool calls instead of the old ~10-call dance with manual polling. For a follow-up scenario against the same target, finish step 5 with `jdwp_resume_until_event` (advance to the next BP) or `jdwp_reset` + new breakpoints (start a fresh investigation without re-attaching).
```

**Step 6: Update Recipe 4's hashCode comparison to use `jdwp_assert_expression`**

In **Recipe 4 — "Object inside a HashMap is no longer findable"** (around line 185-194), change the **Approach** list:

```markdown
**Approach:**
1. BP before AND after the suspected mutation point.
2. At BP1: `eval session.hashCode()`, `eval store.contains(session)` → both correct.
3. At BP2: `eval session.hashCode()` → different value → bug found.
4. `to_string` on the internal `HashMap` to see the discrepancy: the entry exists but its key now has a different hash than `session.hashCode()` would compute.
5. Fix: use immutable keys, or `remove` + re-insert around the mutation.
```

to:

```markdown
**Approach:**
1. BP before AND after the suspected mutation point.
2. At BP1: capture the hash with `jdwp_evaluate_expression("session.hashCode()")` and remember the value.
3. `jdwp_resume_until_event()` to advance to BP2.
4. At BP2: `jdwp_assert_expression("session.hashCode()", "<value-from-step-2>")` — `MISMATCH` confirms the hash drifted.
5. `jdwp_to_string` on the internal `HashMap` to see the discrepancy: the entry exists but its key now has a different hash than `session.hashCode()` would compute.
6. Fix: use immutable keys, or `remove` + re-insert around the mutation.
```

**Step 7: Update Recipe 1 to highlight the auto-unbox**

In **Recipe 1 — "The function shouldn't have changed that"** (around line 150-159), change step 2 and 4 from:

```markdown
2. `eval order.getTotal()` → `71.982` (correct).
...
4. `eval order.getTotal()` → `71.0` (truncated!).
```

to:

```markdown
2. `eval order.getTotal()` → `71.982` (correct — primitives auto-unbox now, no `String.valueOf` wrapper needed).
...
4. `eval order.getTotal()` → `71.0` (truncated!).
```

**Step 8: Update the Prerequisites section's Maven block**

In the **Maven Surefire tests** subsection (around line 34-40), change:

```markdown
### Maven Surefire tests

```bash
mvn test -Dtest=<TestClass> -Dmaven.surefire.debug
```

This forks the test JVM with the agent string above (port 5005, `suspend=y`).
```

to:

```markdown
### Maven Surefire tests

```bash
mvn test -Dtest=<TestClass> -Dmaven.surefire.debug
```

This forks the test JVM with the agent string above (port 5005, `suspend=y`).

**Project poms must NOT hardcode `<argLine>` in the surefire plugin** — that pattern silently overrides any `-DargLine=` passed on the command line. If a pom has `<argLine>X</argLine>`, change it to `<argLine>X ${argLine}</argLine>` and add `<argLine></argLine>` to `<properties>` as the empty default. The mcp-jdwp-java pom uses this pattern as of 2026-04-07.
```

---

## Task 15: Build the JAR and prepare for manual verification

**Files:** none

**Step 1: Clean build**

Run from `/workspace`:

```bash
mvn clean package -DskipTests -o
```

Expected: `BUILD SUCCESS`. The JAR `target/mcp-jdwp-java-1.0.0.jar` should be regenerated.

**Step 2: Reconnect the MCP server in Claude Code**

Inside the CC session that consumes this MCP server, run `/mcp` and reconnect `jdwp-inspector`. This forces CC to re-spawn the MCP subprocess with the new JAR. The new tools (`jdwp_wait_for_attach`, `jdwp_resume_until_event`, `jdwp_get_breakpoint_context`, `jdwp_assert_expression`, `jdwp_reset`) will appear in the tool list, and the changed schemas (optional params) will take effect.

**Step 3: Hand the diff back to Johnny**

Stop here. Johnny reviews the full diff (server source + pom + skill doc + test-flight doc) and commits manually. Do **not** create a commit, do **not** run any sandbox scenarios as automated verification — the test flights from `docs/plans/2026-04-06-jdwp-test-flight.md` already exist for that and Johnny will exercise them at his own pace.

---

## Completion notes (2026-04-07)

All 15 tasks shipped and the JAR rebuilt cleanly on the first attempt (after a one-character fix in the pom comment — XML doesn't allow `--` inside `<!-- -->`, so the inline justification was reworded to avoid the dash sequence).

Two test flights were executed against the rebuilt server to verify the new flow end-to-end:

- **Flight 1 (OrderProcessor — Task 1 truncation bug):** `wait_for_attach` → `set_breakpoint` (no optional params) → `resume_until_event` → `get_breakpoint_context` → `assert_expression(order.getTotal(), 71.982)` returned `OK`, then `step_over` + same assertion returned `MISMATCH actual: 71.0`. **5 tool calls** to fully diagnose vs ~10 in the old workflow. Top frames showed **104 of 108 frames collapsed** as junit/maven/reflection noise.
- **Flight 2 (SessionStore — Task 8 gotcha):** `wait_for_attach` → BP → `resume_until_event` → `evaluate_expression("sessions.containsKey(session)")`. This uncovered a bug in Task 8 (see below). After the workaround: `get_locals` showed the synthetic `this` entry; `eval session.hashCode()` returned `131836472` directly (no `String.valueOf` wrapping); `assert_expression(... == java.util.Objects.hash(...), "false")` returned `OK` — boolean auto-unbox confirmed. `jdwp_reset` cleared 1 BP + 2 events without disconnecting; `jdwp_get_threads` showed `1 thread(s) (7 system thread(s) hidden)` with `Status: RUNNING`.

### Task 8 follow-up bugfix

The original Task 8 implementation had two coupled bugs that surfaced during Flight 2:

1. **Auto-rewrite triggered for non-public fields.** `SessionStore` is `public` but its `sessions` field is `private`. The original `rewriteThisFieldReferences` filter only checked `thisClass.isPublic()`, so it rewrote `sessions.containsKey(session)` → `_this.sessions.containsKey(session)`. The wrapper class then compiled this and JDT reported `"The field io.mcp.jdwp.sandbox.session.SessionStore.sessions is not visible"` — different from `"X cannot be resolved"` and harder to act on than the original error would have been.
2. **`enrichEvaluationError` regex only matched `cannot be resolved`.** Because of bug 1 above, the error text was now `"is not visible"` and the enrichment never fired — the user got the bare compile error with no hint.

**Fix shipped (5 lines across 2 files):**

- `JdiExpressionEvaluator.java` — added `.filter(Field::isPublic)` to the field-name collection feeding `rewriteThisFieldReferences`. Non-public fields now bypass auto-rewrite entirely, so the wrapper produces the original `cannot be resolved` error and the enrichment hint can do its job.
- `JDWPTools.java` — broadened the regex in `enrichEvaluationError` to also match `field X.Y is not visible` and bare `Y is not visible` (preserved as a fallback for compiler versions that emit the latter form). The hint message branches on both class visibility and field visibility, so it correctly distinguishes:
  - "package-private enclosing class" (workaround: `jdwp_get_fields(thisObjectId)`)
  - "non-public field on a public class" (same workaround, different sentence)
  - "both public, rewrite should have handled this" (treat as a bug — please report)

**Re-test verification:** the same `evaluate_expression("sessions.containsKey(session)")` call now returns:

```
Error evaluating expression: Expression evaluation failed: Compilation failed:
Line 7 in /tmp/.../ExpressionEvaluator_xxx.java: sessions cannot be resolved

Hint: 'sessions' is a field on this (io.mcp.jdwp.sandbox.session.SessionStore, Object#2725).
The field is non-public, so the expression wrapper cannot reference it directly.
Workaround: call jdwp_get_fields(2725) to inspect the field, or jdwp_to_string for a quick view.
```

Following the suggested `jdwp_get_fields(2725)` returns the `sessions` HashMap as expected. The gotcha now costs **one extra tool call with a self-explanatory error**, vs. the previous "stare at compile error and guess".

### Files touched (final)

- `pom.xml` — argLine extension pattern, comment reworded to avoid `--`.
- `src/main/java/io/mcp/jdwp/JDWPTools.java` — 5 schema params optional; `jdwp_get_threads`/`jdwp_get_stack`/`jdwp_get_locals` rewritten; 5 new `@McpTool` methods added (`wait_for_attach`, `resume_until_event`, `get_breakpoint_context`, `assert_expression`, `reset`); `enrichEvaluationError` helper (with broadened regex per follow-up fix).
- `src/main/java/io/mcp/jdwp/JDIConnectionService.java` — boxed-primitive `tryUnboxPrimitive` helper wired into `formatFieldValue`; new public `clearObjectCache()`; `isJvmInternalThread` delegates to the new `ThreadFormatting` helper.
- `src/main/java/io/mcp/jdwp/BreakpointTracker.java` — `armNextEventLatch()` / `fireNextEvent()` for synchronous resume-until-event; `reset()` clears the latch too.
- `src/main/java/io/mcp/jdwp/JdiEventListener.java` — fires the next-event latch from BP/step/exception handlers.
- `src/main/java/io/mcp/jdwp/evaluation/JdiExpressionEvaluator.java` — auto-rewrites bare `this`-field references when both the enclosing class and the specific field are public (`Field::isPublic` filter added per follow-up fix); new `rewriteThisFieldReferences` helper.
- `src/main/java/io/mcp/jdwp/ThreadFormatting.java` — **new** static helper class with the JVM-internal-thread filter (extended with `surefire-forkedjvm-`, `Notification Thread`, `Service Thread`, JFR threads, etc.) and the JDI status int → readable label translation.
- `.claude/skills/java-debug/SKILL.md` — Tool Catalog refreshed (5 added + 7 updated entries), Core Workflow rewritten to use the new bootstrap chain, obsolete `String.valueOf` gotcha deleted, `this`-field gotcha rewritten, Common First Questions collapsed from 5 calls to 3, Recipe 7 (cold-start bootstrap) added, Recipes 1 + 4 refreshed, prerequisites note about pom argLine.
- `docs/plans/2026-04-06-jdwp-test-flight.md` — 5 launch blocks rewritten to use `-Dmaven.surefire.debug` (no more silently-clobbered `-DargLine`).

The original 13-improvement scope from the 2026-04-07 test-flight retrospective is fully delivered and field-tested.
