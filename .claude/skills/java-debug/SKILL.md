---
name: java-debug
description: Debug live Java applications via the jdwp-inspector MCP server — set breakpoints, inspect runtime state, evaluate expressions, mutate variables at runtime, catch exceptions at their throw site, and trace execution non-intrusively with logpoints. Use when investigating Java bugs, test failures, runtime exceptions, race conditions, or any JVM behavior that is hard to understand from reading code alone.
---

# Java Debug

## Overview

Live debugging of a running JVM via JDWP. Replaces "add a println, re-run, repeat" with: set breakpoint → hit it → inspect everything → mutate state → resume. Pairs with the `systematic-debugging` skill — that one is the *process*, this one is the *evidence-gathering and hypothesis-testing toolkit* for Java specifically.

**Use when:**
- A test fails and the assertion message doesn't tell you why
- An exception is buried under several layers of wrapping (root cause invisible)
- A value is "obviously wrong" but you can't tell where it changes
- A bug only happens under specific conditions (race, off-by-one, edge case)
- Stepping through code in your head doesn't match what the runtime does

**Don't use when:**
- The bug is clear from code review alone
- You already know the fix and just need to write it
- It's a build/compile failure (not a runtime bug)

## Prerequisites

The target JVM must be running with the JDWP agent on port 5005. The agent string this skill assumes is:

```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005
```

`suspend=y` makes the JVM block at startup until you `jdwp_connect` — use this for tests and for any case where you need the very first lines of execution. `suspend=n` lets the JVM run freely; attach whenever you want — use this for long-running services where you only care about specific events.

### Maven Surefire tests

```bash
mvn test -Dtest=<TestClass> -Dmaven.surefire.debug
```

This forks the test JVM with the agent string above (port 5005, `suspend=y`).

**Project poms must NOT hardcode `<argLine>` in the surefire plugin** — that pattern silently overrides any `-DargLine=` passed on the command line. If a pom has `<argLine>X</argLine>`, change it to `<argLine>X ${argLine}</argLine>` and add `<argLine></argLine>` to `<properties>` as the empty default. The mcp-jdwp-java pom uses this pattern as of 2026-04-07.

### Gradle tests

```bash
./gradlew test --tests "com.example.MyTest" --debug-jvm
```

`--debug-jvm` enables JDWP on port 5005 with `suspend=y` and disables the test timeout. Works with `test`, `integrationTest`, or any `Test`-typed task. Gradle's test worker daemon must be allowed to fork — if you have `maxParallelForks > 1`, only the first worker gets the port.

If you need to control the agent string yourself (e.g. different port, `suspend=n`, or to debug a specific test task without `--debug-jvm`'s side effects), add to `build.gradle(.kts)`:

```kotlin
tasks.test {
    jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005")
    // Required when suspending — otherwise Gradle kills the worker after 10 min idle
    timeout.set(Duration.ofHours(1))
}
```

### Gradle — running the project (live debugging, not tests)

For Spring Boot apps:
```bash
./gradlew bootRun --debug-jvm
```

For the `application` plugin (`run` task):
```bash
./gradlew run --debug-jvm
```

`--debug-jvm` again pins port 5005 with `suspend=y`. The app blocks at JVM start until you `jdwp_connect`, then runs normally — set your breakpoints *before* resume if you need to catch early-startup code.

For a long-running service where you want to attach later (no startup pause), configure the run task explicitly and use `suspend=n`:

```kotlin
tasks.named<JavaExec>("bootRun") {  // or "run"
    jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005")
}
```

Then `./gradlew bootRun` starts the app immediately, and you can `jdwp_connect` whenever a bug reproduces.

### Other targets (standalone JARs, app servers)

Launch the JVM yourself with the agent string. Use `suspend=n` for normal "attach when needed" debugging:

```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar app.jar
```

Or set `JAVA_TOOL_OPTIONS` so any child JVM picks it up automatically:

```bash
export JAVA_TOOL_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
```

## Core Workflow

1. **Launch the target** in a separate shell or background, with `suspend=y`. The JVM blocks until step 2.
2. **Attach:** `jdwp_wait_for_attach()` — polls until the JVM is listening and attaches. Replaces manual "is the port open yet?" polling.
3. **Set breakpoints** at suspected bug locations. `jdwp_set_breakpoint(className, lineNumber)` — `suspendPolicy` and `condition` are both optional. Add logpoints/exception BPs as needed.
4. **Resume and wait:** `jdwp_resume_until_event()` — releases the target JVM and BLOCKS until the next BP/step/exception fires. Returns the suspended thread info.
5. **Inspect everything in one call:** `jdwp_get_breakpoint_context()` — returns thread, top frames, locals (incl. `this`), and `this` field dump. This replaces the old `get_current_thread → get_stack → get_locals → get_fields(this)` chain.
6. **Form a hypothesis,** test it: step through, `jdwp_assert_expression(...)` to check invariants, `jdwp_set_local`/`jdwp_set_field` to mutate state and ask "would the test pass if X were Y?"
7. **Resume to next event** (`jdwp_resume_until_event`) or **disconnect** when done. For sequential scenarios against the same target, use `jdwp_reset` between flights to clear state without dropping the connection.

## Tool Catalog

### Setup / lifecycle
- **`jdwp_wait_for_attach(host?, port?, timeoutMs?)`** — poll until the JVM is listening, then attach. Use this as the FIRST call after launching the target — replaces manual port-polling. Defaults: `localhost`, `5005`, `30000ms`.
- **`jdwp_connect`** — attach immediately (fails if no JVM is listening). Prefer `jdwp_wait_for_attach` for fresh sessions.
- **`jdwp_disconnect`** — detach + clean up; auto-runs on reconnect to a new JVM
- **`jdwp_reset`** — clear breakpoints, exception breakpoints, watchers, object cache, and event history WITHOUT disconnecting. Use between sequential test flights against the same long-running target.
- **`jdwp_get_version`** — JVM info sanity check

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

### Mutation
- **`jdwp_set_local(threadId, frameIndex, varName, value)`** — mutate a local variable
- **`jdwp_set_field(objectId, fieldName, value)`** — mutate a field on a cached object

### Breakpoints
- **`jdwp_set_breakpoint(className, lineNumber, suspendPolicy?, condition?)`** — line BP. `suspendPolicy` defaults to `"all"`; `condition` optional. Both params can be omitted entirely now.
- **`jdwp_set_logpoint(className, lineNumber, expression)`** — non-stopping BP that records the expression result to event history
- **`jdwp_set_exception_breakpoint(exceptionClass, caught?, uncaught?)`** — catches an exception at the throw site, *before* any wrapping. Both flags default to `true`.
- **`jdwp_clear_breakpoint_by_id(id)` / `jdwp_clear_exception_breakpoint(id)` / `jdwp_clear_all_breakpoints()`**
- **`jdwp_list_breakpoints()` / `jdwp_list_exception_breakpoints()`** — show active + pending

### Execution control
- **`jdwp_resume_until_event(timeoutMs?)`** — resume the VM and BLOCK until the next BP/step/exception event fires (or timeout). Use this instead of `jdwp_resume` + polling `jdwp_get_current_thread`. Default timeout: 30000ms.
- **`jdwp_resume()`** — resume without waiting (fire-and-forget). Use only when you don't care about the next stop.
- **`jdwp_resume_thread(threadId)` / `jdwp_suspend_thread(threadId)`** — per-thread control
- **`jdwp_step_over(threadId)`** — next line in the same frame
- **`jdwp_step_into(threadId)`** — into the next method call
- **`jdwp_step_out(threadId)`** — out of the current method

### Event history
- **`jdwp_get_events(count)`** — recent events: `BREAKPOINT`, `STEP`, `EXCEPTION`, `LOGPOINT`, `LOGPOINT_ERROR`, `VM_START`, `VM_DEATH`
- **`jdwp_clear_events()`**

## Debugging Recipes

Each recipe matches a class of bug. They are proven against real bugs from the JDWP sandbox test flight.

### Recipe 1 — "The function shouldn't have changed that"

**Smell:** A value looks correct before a method call and wrong after. The method appears to only do reads.

**Approach:**
1. BP at the call site, *before* the suspicious call.
2. `eval order.getTotal()` → `71.982` (correct — primitives auto-unbox now, no `String.valueOf` wrapper needed).
3. `step_over` past the call.
4. `eval order.getTotal()` → `71.0` (truncated!).
5. The call mutates. Restart, BP at the same site, **`step_into`** to land in the suspicious method, then `step_over` line by line and eval after each — find the exact mutation point.

### Recipe 2 — "Race / partial init / observable intermediate state"

**Smell:** A field has a value at one read site that doesn't match what was written, OR a thread reads a half-built object.

**Approach:**
1. BP at the read site (where the wrong value is observed).
2. `get_locals` → find the broken object's ID.
3. `get_fields(<id>)` → see the partially-initialized state.
4. **`set_field(<id>, "timeout", "5000")`** to fix it at runtime.
5. `resume` → if the test passes now, the root cause is confirmed: write order in the constructor.
6. Find and fix the actual write order in the source.

### Recipe 3 — "Exception is buried under wrappers"

**Smell:** Test failure shows `CompletionException("Async task failed")`, but the real cause is something else 3 frames deeper.

**Approach:**
1. `set_exception_breakpoint("java.lang.IllegalStateException", caught=true, uncaught=false)`.
2. If it returns "deferred" (bootstrap class not yet loaded), **also set a regular line BP somewhere upstream** of the throw (anywhere the test will hit before the throw). Both BPs need to be in place before resuming.
3. `resume`.
4. The line BP hits → call `jdwp_get_locals` (any tool that uses `getVM()`) → triggers force-load → exception BP self-promotes from `[PENDING]` to active.
5. `resume` past the line BP.
6. The exception BP catches the throw → `get_stack` shows the **real** root frame, not the wrapper.

### Recipe 4 — "Object inside a HashMap is no longer findable"

**Smell:** `map.put(k, v)` then `map.get(k) → null` even though `k` looks identical.

**Approach:**
1. BP before AND after the suspected mutation point.
2. At BP1: capture the hash with `jdwp_evaluate_expression("session.hashCode()")` and remember the value.
3. `jdwp_resume_until_event()` to advance to BP2.
4. At BP2: `jdwp_assert_expression("session.hashCode()", "<value-from-step-2>")` — `MISMATCH` confirms the hash drifted.
5. `jdwp_to_string` on the internal `HashMap` to see the discrepancy: the entry exists but its key now has a different hash than `session.hashCode()` would compute.
6. Fix: use immutable keys, or `remove` + re-insert around the mutation.

### Recipe 5 — "Bug only at large input / specific value"

**Smell:** Test fails at one input, passes at another. Stepping through every iteration is impractical.

**Approach A — conditional breakpoint:**
- `set_breakpoint("MyClass", 42, "all", condition="i > 100 && items.size() > 50")`
- BP only fires when both are true.

**Approach B — logpoint then conditional:**
- `set_logpoint("MyClass", 42, "\"i=\" + i + \" v=\" + value")`
- Run the test uninterrupted.
- `get_events(50)` → find the FIRST iteration where the value goes bad.
- Set a conditional BP for that exact iteration.

### Recipe 6 — "Trace many call sites without stopping"

**Smell:** A value gets set in many places and you want to know which write produced the bad value.

**Approach:**
- `set_logpoint(<setter class>, <setter line>, "\"set called with: \" + value")`
- Run the test
- `get_events(50)` → all logpoint hits in chronological order
- The last entry before the test fails is the culprit

### Recipe 7 — "Cold-start a fresh debug session"

**Smell:** You need to debug a test or service from scratch — the JVM isn't running yet, you're not attached, and the bug only reproduces from a clean run.

**Approach:**
1. Launch the target in a background shell with `suspend=y` (e.g., `mvn test -Dtest=MyTest -Dmaven.surefire.debug` for surefire, or `./gradlew test --debug-jvm`).
2. **`jdwp_wait_for_attach()`** — polls every 200ms until the JVM is listening, then attaches. No manual port-checking, no "is it ready yet?" loop.
3. `jdwp_set_breakpoint(className, lineNumber)` — set your trap. Schema params for `condition`/`suspendPolicy` are now optional.
4. **`jdwp_resume_until_event()`** — releases the JVM and blocks until the BP fires (or timeout). Returns the suspended thread info.
5. **`jdwp_get_breakpoint_context()`** — full state dump in one call.

The whole sequence takes ~5 tool calls instead of the old ~10-call dance with manual polling. For a follow-up scenario against the same target, finish step 5 with `jdwp_resume_until_event` (advance to the next BP) or `jdwp_reset` + new breakpoints (start a fresh investigation without re-attaching).

## Gotchas

- **`evaluate_expression` auto-rewrites bare references to fields of `this`** (e.g. `sessions.containsKey(k)` → `_this.sessions.containsKey(k)`) when the enclosing class is public. For PACKAGE-PRIVATE enclosing classes the auto-rewrite is skipped (the wrapper class still couldn't see the type) — the error message will name the field and tell you to use `jdwp_get_fields(<thisObjectId>)` instead. The `this` object ID is now in the regular `jdwp_get_locals` output (synthetic `this` entry), so this is a one-call workaround.
- **`set_local` / `set_field` only support** primitives, `String`, and `null`. To mutate a complex object, mutate its individual fields.
- **`to_string` requires a suspended thread.** If `threadId` is omitted it uses the last breakpoint thread.
- **Exception breakpoints on bootstrap classes** (`java.lang.IllegalStateException`, `NullPointerException`, etc.) start as `[PENDING]`. They auto-promote when ANY tool that calls `getVM()` runs while a thread is suspended at a method-invocation event. Pair the exception BP with at least one regular line BP upstream of the throw — see Recipe 3.
- **Suspend policy `all`** is the safest default. `thread` only suspends the BP-hitting thread; in multi-threaded apps the rest keep running and may move state out from under you.
- **Frame index 0 is the topmost** (most recent) frame. Higher indices are callers.
- **The expression compiler discovers the classpath lazily** on the first `evaluate_expression` after a fresh connect. The first eval can take ~1s; subsequent evals are fast (cached).
- **Logpoints still cost time** — each one fires the expression evaluator. Don't put a logpoint inside a tight loop with millions of iterations.
- **Object IDs are session-scoped.** They become invalid after `disconnect`, and may also become invalid if the GC collects the object. If you see "Object not found in cache", re-fetch via `get_locals`.
- **VMStart suspension is special.** When you connect to a JVM started with `suspend=y`, all threads are suspended at VMStart but no thread is yet at a method-invocation event — so `evaluate_expression`, `to_string`, and `set_exception_breakpoint` force-load all need at least one BP hit before they can do invocations. Set your breakpoints first, then `resume`, then inspect after the first BP hit.
- **Reconnecting to a new JVM auto-cleans state.** Breakpoints, watchers, object cache, event history — all wiped on the next `connect` after the previous target died. No need to manually `disconnect` first.

## Anti-patterns

- **Don't add `System.out.println`.** Use logpoints. They don't require recompiling.
- **Don't restart the test for every hypothesis.** Use `set_local` / `set_field` to mutate state in place and resume. If the test passes, your hypothesis is confirmed.
- **Don't catch with the wrong exception type "to be safe".** If you want to catch `NullPointerException`, target it specifically. Targeting `Throwable` or `Exception` will fire on every JVM exception including JDK internals — extremely noisy and slow.
- **Don't step through 50 loop iterations manually.** Use a conditional breakpoint or logpoint to jump straight to the iteration that matters.
- **Don't read a stack trace and stop at the first wrapped exception.** The original throw site is almost always more informative than what bubbled up. Set an exception BP on the inner type and re-run.Y
- **Don't forget cleanup.** `clear_all_breakpoints` between investigations. Auto-cleanup happens on disconnect/reconnect.
- **Don't use the debugger as a printf.** That's what logpoints are for. Save the suspend-and-inspect for genuinely interesting state.

## Common First Questions At a New Breakpoint

When you first land at a breakpoint and don't know what to look at:

1. **`jdwp_get_breakpoint_context()`** — one-shot dump of thread + top frames + locals + `this` fields. 90% of the time this is all you need to form your next hypothesis.
2. For each interesting `Object#N` reference: `jdwp_get_fields(objectId)` to drill in, then `jdwp_to_string(objectId)` for a quick view.
3. `jdwp_assert_expression(<expression>, <expected>)` to test "is the state what I expected?" in one shot — much terser than `evaluate_expression` followed by eyeballing.

If you need more than the default 5 frames in the context dump, pass `maxFrames=20` (or whatever depth you need). Junit/maven/reflection frames are always collapsed; pass `includeNoise=true` to `jdwp_get_stack` if you genuinely need them.

## When the MCP Server Misbehaves

If a tool returns an unexpected error or the server seems stuck:

1. **Check the server log:** `tail -50 mcp-jdwp-inspector.log` in the project root. The server logs all JDI operations and errors.
2. **Reconnect:** Run `/mcp` in CC and reconnect `jdwp-inspector`. This spawns a fresh subprocess with the current JAR.
3. **Rebuild if you changed the server source:** `mvn clean package -DskipTests`, then `/mcp` reconnect.
4. **Free port 5005:** orphaned test JVMs can hold the JDWP port. `ps -ef | grep jdwp=transport` then `kill <pid>`.
5. **Both processes gone after a crash:** the test JVM exits when its debugger detaches; the MCP server may also have crashed. Reconnect MCP and relaunch the test JVM from scratch.

## Related Skills

- **`systematic-debugging`** — the process for any bug investigation; this skill provides the Java-specific evidence-gathering tools that go in Phase 1 (Root Cause Investigation) and Phase 3 (Hypothesis Testing).
- **`test-driven-development`** — for writing the failing test in Phase 4 once the root cause is identified.
