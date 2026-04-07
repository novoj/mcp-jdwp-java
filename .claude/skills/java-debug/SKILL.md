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

The target JVM must be running with the JDWP agent on port 5005. For Maven Surefire tests:

```bash
mvn test -Dtest=<TestClass> -Dmaven.surefire.debug
```

This forks the test JVM with `-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:5005`. The JVM blocks at startup waiting for the debugger to attach.

For other targets (web servers, standalone apps), launch with:
```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

## Core Workflow

1. **Launch the target** in a separate shell or background, with `suspend=y`. The JVM blocks until step 2.
2. **Connect:** `jdwp_connect()`
3. **Set breakpoints** at suspected bug locations. Default `suspendPolicy="all"`. Conditions and logpoints are optional.
4. **Resume:** `jdwp_resume()` — releases the target JVM.
5. **Wait for the breakpoint to hit:** call `jdwp_get_current_thread()`. If it returns a thread ID with `suspended=true`, the BP hit.
6. **Inspect** (see Tool Catalog below).
7. **Form a hypothesis,** test it: step through, eval at each step, mutate state to test "would it pass if X were Y?"
8. **Resume** to continue, or **disconnect** when done.

## Tool Catalog

### Setup / lifecycle
- **`jdwp_connect`** — attach to the configured JDWP port
- **`jdwp_disconnect`** — detach + clean up; auto-runs on reconnect to a new JVM
- **`jdwp_get_version`** — JVM info sanity check

### Inspection
- **`jdwp_get_threads`** — all threads with ID, name, status
- **`jdwp_get_current_thread`** — last thread that hit a breakpoint
- **`jdwp_get_stack(threadId)`** — full call stack
- **`jdwp_get_locals(threadId, frameIndex)`** — visible local variables (frame 0 = topmost)
- **`jdwp_get_fields(objectId)`** — fields of a cached object reference
- **`jdwp_to_string(objectId, threadId)`** — invoke `toString()` on a cached object
- **`jdwp_evaluate_expression(threadId, expression, frameIndex)`** — eval any Java expression in scope

### Mutation
- **`jdwp_set_local(threadId, frameIndex, varName, value)`** — mutate a local variable
- **`jdwp_set_field(objectId, fieldName, value)`** — mutate a field on a cached object

### Breakpoints
- **`jdwp_set_breakpoint(className, lineNumber, suspendPolicy, condition?)`** — line BP, optionally conditional
- **`jdwp_set_logpoint(className, lineNumber, expression)`** — non-stopping BP that records the expression result to event history
- **`jdwp_set_exception_breakpoint(exceptionClass, caught, uncaught)`** — catches an exception at the throw site, *before* any wrapping
- **`jdwp_clear_breakpoint_by_id(id)` / `jdwp_clear_exception_breakpoint(id)` / `jdwp_clear_all_breakpoints()`**
- **`jdwp_list_breakpoints()` / `jdwp_list_exception_breakpoints()`** — show active + pending

### Execution control
- **`jdwp_resume()` / `jdwp_resume_thread(threadId)` / `jdwp_suspend_thread(threadId)`**
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
2. `eval order.getTotal()` → `71.982` (correct).
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
2. At BP1: `eval session.hashCode()`, `eval store.contains(session)` → both correct.
3. At BP2: `eval session.hashCode()` → different value → bug found.
4. `to_string` on the internal `HashMap` to see the discrepancy: the entry exists but its key now has a different hash than `session.hashCode()` would compute.
5. Fix: use immutable keys, or `remove` + re-insert around the mutation.

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

## Gotchas

- **Always use `String.valueOf(...)` to print primitives.** `evaluate_expression` returns `Object` (the wrapper class autoboxes primitives). `eval order.getTotal()` shows `Object#N (java.lang.Double)`; `eval String.valueOf(order.getTotal())` shows `"71.982"`.
- **`evaluate_expression` cannot reference fields of `this`** when the enclosing class is package-private (the wrapper class can't see the type). Workarounds: (a) reference local variables only, or (b) `eval this` to get the cached object ID, then use `get_fields` on it.
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
- **Don't read a stack trace and stop at the first wrapped exception.** The original throw site is almost always more informative than what bubbled up. Set an exception BP on the inner type and re-run.
- **Don't forget cleanup.** `clear_all_breakpoints` between investigations. Auto-cleanup happens on disconnect/reconnect.
- **Don't use the debugger as a printf.** That's what logpoints are for. Save the suspend-and-inspect for genuinely interesting state.

## Common First Questions At a New Breakpoint

When you first land at a breakpoint and don't know what to look at:

1. `jdwp_get_current_thread()` — confirm which BP fired (the `breakpoint=N` field tells you the ID; `-1` = exception breakpoint)
2. `jdwp_get_stack(threadId)` — first 3-4 frames usually tell the story
3. `jdwp_get_locals(threadId, 0)` — what the current method has access to
4. For each Object reference of interest: `jdwp_get_fields(objectId)` then optionally `jdwp_to_string(objectId)`
5. `jdwp_evaluate_expression(threadId, "<some assertion you'd write>")` — a quick way to test "is the state what I expected?"

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
