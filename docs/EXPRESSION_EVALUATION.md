# Expression Evaluation — Developer Reference

How the JDWP MCP server compiles, injects, and executes arbitrary Java expressions against a live JVM suspended at a breakpoint. This document covers the design decisions, hard-won lessons, and non-obvious constraints that aren't self-evident from the code.

## Pipeline Overview

```
User expression (e.g. "order.getTotal()")
  │
  ▼
JdiExpressionEvaluator.evaluate(frame, expression)
  │  1. Build EvaluationContext from the frame (locals + this)
  │  2. Rewrite bare `this` keyword → `_this`
  │  3. Rewrite bare field references → `_this.field` (when safe)
  │  4. Cache lookup (key = context signature + "###" + expression)
  │  5. On miss: generate wrapper class, compile via InMemoryJavaCompiler
  │
  ▼
RemoteCodeExecutor.execute(vm, thread, classLoader, className, bytecode, args)
  │  1. defineClass() — inject bytecode into the target classloader
  │  2. Class.forName(name, true, classLoader) — force preparation
  │  3. invokeMethod(INVOKE_SINGLE_THREADED) — run the static evaluate()
  │
  ▼
JDI Value returned to MCP tool layer for formatting
```

## Component Responsibilities

### JdiExpressionEvaluator

Orchestrator. Builds the evaluation context from a stack frame, generates a wrapper class with a UUID-suffixed name, compiles it, and delegates execution. Owns the compilation cache and the `this`-rewriting logic.

The generated wrapper looks like:

```java
package mcp.jdi.evaluation;

public class ExpressionEvaluator_<UUID> {
    public static Object evaluate(MyService _this, Request request, int count) {
        return (Object) (request.getData());
    }
}
```

### InMemoryJavaCompiler

Wraps Eclipse JDT (ECJ) via JSR-199. Output is captured in memory through a custom `MemoryJavaFileManager`, but input source round-trips through a temp directory because JDT's `JavaFileObject` API requires a real path. The temp directory is deleted unconditionally in `finally`.

### RemoteCodeExecutor

Three-phase injection: `defineClass` → `Class.forName` → `invokeMethod`. Idempotent — checks `vm.classesByName()` before defining, so cached compilations that reuse the same class name skip the define step.

### ClasspathDiscoverer

Walks the target VM's classloader hierarchy to collect all JARs. The initial `java.class.path` system property is often incomplete (e.g. Tomcat only reports bootstrap JARs). Discovery aggregates from three sources:
1. `System.getProperty("java.class.path")`
2. `URLClassLoader.getURLs()` on each classloader in the chain
3. Tomcat `WebappClassLoaderBase.getURLs()` when present

### JdkDiscoveryService

Locates a local JDK matching the target JVM's major version. JDT needs `--system <jdkPath>` to resolve `java.*` system classes. Search strategy:
1. Target's own `java.home` if accessible from the MCP server's filesystem
2. Common per-OS install paths (Adoptium, Oracle, OpenJDK, Zulu on Windows; `/usr/lib/jvm`, `/opt` on Linux)
3. Directory scan of parent paths matching a version-suffix pattern

JDK validation checks: `jmods/` or `lib/jrt-fs.jar` (Java 9+), `lib/rt.jar` (Java 8), `jre/lib/rt.jar` (bundled-JRE layout).

### EvaluationGuard

Per-thread reentrancy guard. Tracks which target-VM threads are mid-evaluation so the JDI event listener can suppress recursive breakpoint/exception/step events. Without this, the listener would try to suspend a thread that the outer `invokeMethod` is waiting on, producing a cross-thread deadlock.

Counted (not boolean) so nested call sites — `configureCompilerClasspath` → `discoverClasspath` → `invokeMethod` inside an outer `evaluate()` — all stack correctly.

## Design Decisions and Rationale

### UUID-based class naming

Every generated wrapper class gets a fresh UUID suffix: `ExpressionEvaluator_<UUID>`.

**Why not a counter?** An `AtomicLong` counter resets to 0 on MCP server restart, but the previous classes persist in the target JVM's classloader. A second evaluation of the "same" expression after a restart would attempt `defineClass` with the same name and hit `LinkageError: duplicate class definition`. UUIDs eliminate this entirely — no collision across restarts, no cleanup needed.

### `Class.forName()` after `defineClass()`

`ClassLoader.defineClass()` loads the bytecode but does NOT prepare the class. JDI's `methodsByName()` throws `ClassNotPreparedException` (or returns empty) on an unprepared class. The fix:

```java
Class.forName(className, true, classLoader)
```

The `true` flag forces full initialization. This is the JVM's standard lifecycle mechanism and is robust across all JVM implementations.

**Alternatives tried and rejected:**
- `allMethods()` — accesses all inherited methods, inefficient and unreliable
- Busy-waiting on `isPrepared()` — race-prone, JDI doesn't guarantee timing

### Three-level classloader fallback

Finding the right classloader for injection matters — the wrapper class must see the same types the user expression references. The fallback chain:

1. `frame.thisObject().referenceType().classLoader()` — works for instance methods
2. `frame.location().declaringType().classLoader()` — works for static methods
3. `ClassLoader.getSystemClassLoader()` invoked in the target VM — last resort for bootstrap class contexts

### Dynamic proxy unwrapping

When `this` is a Guice/CGLIB/Spring AOP proxy, `thisObject.type().name()` returns something like `RestService$EnhancerByGuice$110706492`. The generated wrapper class can't reference this type — it's synthetic and runtime-generated.

Solution: detect the `$$` pattern and walk up the superclass chain to find the real class. Fallback: extract the name before `$$`.

### Non-public type visibility

The wrapper class lives in package `mcp.jdi.evaluation`. It can only reference public types. When `this` (or a local variable) has a package-private type, `getDeclaredType()` walks up the superclass chain to find the first public ancestor, falling back to `java.lang.Object`.

### `this` field auto-rewriting

Users naturally write `sessions.containsKey(k)` when they mean `this.sessions.containsKey(k)`. The evaluator auto-rewrites bare field references to `_this.field` when:
- The enclosing class (`this`) is public
- The specific field is public
- No local variable shadows the field name

The rewriter is a hand-rolled tokenizer (not regex) that correctly handles:
- String literals (`"name"` is not rewritten)
- Text blocks (`"""..."""`)
- Character literals
- Qualified references (`obj.field` is NOT rewritten — only bare identifiers)

**Why not regex?** A naive `\bfield\b` replacement corrupts string contents. `"name=" + name` with field `name` would become `"_this.name=" + _this.name`.

### `this` keyword rewriting

Same tokenizer technique for `this` → `_this`. The wrapper's `evaluate()` is a static method — it has no `this`. The original `this` is passed as a parameter named `_this`. The tokenizer ensures identifiers like `myThis` and `thisFoo` are untouched.

### Compilation cache

Key: `contextSignature + "###" + expression`, where `contextSignature` is the concatenated `type name` pairs of all visible variables. Two frames with the same local types/names sharing the same expression hit the same compiled class.

Eviction: full flush at 100 entries. LRU bookkeeping is not worth the complexity when the miss cost (compile + inject) dwarfs the cost of recompiling a few hot entries.

Cache is cleared on every `configureCompilerClasspath` call (new connections may invalidate old bytecode).

### `INVOKE_SINGLE_THREADED`

All JDI method invocations use `INVOKE_SINGLE_THREADED`. This requires the thread to be suspended at a method-invocation event (breakpoint/step) and prevents other threads from running during the invocation. Without it, concurrent thread execution during evaluation can mutate state out from under you.

### Compiler source/target version

Dynamically derived from the target JVM's major version: `1.8` for Java 8, the bare number for Java 9+. The `-g` flag is always passed to preserve local variable names in the compiled bytecode.

## Constraints and Edge Cases

### Thread must be suspended at an invocation event

`INVOKE_SINGLE_THREADED` requires a thread stopped at a breakpoint, step, or exception event. A thread suspended via `ThreadReference.suspend()` (manual suspension) is NOT at an invocation event — method calls will throw `IncompatibleThreadStateException`.

### Classpath configuration must precede evaluation

`configureCompilerClasspath(thread)` issues its own `invokeMethod` calls (to discover JARs via the classloader hierarchy). Calling it from inside `evaluate()` would nest JDI invocations, risking deadlocks or `IncompatibleThreadStateException`. The caller is responsible for calling configure BEFORE evaluate.

### Injected classes persist in the target JVM

Classes loaded via `defineClass()` remain in the target classloader for the lifetime of that classloader. They are NOT cleaned up per evaluation. The idempotent check in `loadClass` (checking `vm.classesByName` before defining) is what makes cache reuse safe — it returns the existing definition instead of double-defining.

### Byte array mirroring is expensive

`RemoteCodeExecutor.createRemoteByteArray()` mirrors bytecode into the target VM element by element — one JDWP round-trip per `vm.mirrorOf(byte)` call. This O(n) cost is unavoidable without cooperating native code in the target VM, since `ArrayReference.setValues` requires already-mirrored values.

### Package-private enclosing class blocks field rewriting

When `this`'s declared type is package-private, the wrapper class (in `mcp.jdi.evaluation`) can't reference the type at all. In this case:
- The `this` field auto-rewrite is skipped entirely
- The `this` parameter is typed as the first public ancestor (or `Object`)
- Users must use `jdwp_get_fields(<thisObjectId>)` to inspect fields instead

The error message names the field and suggests the workaround.

### VMStart suspension is special

When connecting to a JVM started with `suspend=y`, all threads are suspended at VMStart but no thread is at a method-invocation event yet. This means `evaluate_expression`, `to_string`, and `set_exception_breakpoint` (which use `invokeMethod`) cannot work until at least one breakpoint has been hit. Set breakpoints first, then resume, then inspect.

### Bootstrap class exception breakpoints start as PENDING

Exception breakpoints on classes like `java.lang.NullPointerException` may return as `[PENDING]` if the class isn't loaded yet. They auto-promote when any tool that calls `getVM()` runs while a thread is suspended at a method-invocation event. Pair with a regular line breakpoint upstream to ensure promotion.

## Watcher Integration

Watchers are MCP-side expression bookmarks attached to breakpoints. They use the same evaluation pipeline:

1. `jdwp_attach_watcher(breakpointId, label, expression)` — registers an expression to evaluate when a specific breakpoint hits
2. `jdwp_evaluate_watchers(threadId, scope, breakpointId?)` — evaluates all watchers for the current context
3. The watcher's expression goes through the same `JdiExpressionEvaluator.evaluate()` pipeline

Watchers are dual-indexed by watcher UUID and breakpoint ID via `WatcherManager`. They are cleared on `jdwp_reset()` and on disconnect/reconnect.

## Logpoint and Conditional Breakpoint Evaluation

Logpoints (`jdwp_set_logpoint`) and conditional breakpoints both use the expression evaluation pipeline:

- **Logpoints**: evaluate the expression on every hit, record the result to event history, and auto-resume. Support an optional condition expression that gates whether the log fires.
- **Conditional breakpoints**: evaluate the condition expression on every hit; the thread stays suspended only if the condition evaluates to `true`.

Both are subject to the same constraints: the expression must compile against the frame's visible types, and each evaluation incurs the `invokeMethod` cost. Placing a logpoint inside a tight loop with millions of iterations will be expensive.

## Reentrancy Protection

The `EvaluationGuard` is the key mechanism preventing deadlocks during evaluation. The problem:

1. MCP server calls `invokeMethod()` on thread T to evaluate an expression
2. The evaluation triggers code that hits a breakpointed line
3. `JdiEventListener` sees the breakpoint event on thread T and tries to suspend it
4. Thread T is already waiting for `invokeMethod` to return — deadlock

The guard prevents step 3: the listener checks `evaluationGuard.isEvaluating(thread)` before processing any suspending event, and suppresses events on guarded threads.

The guard ID is captured once at the call site (`frame.thread().uniqueID()`) and passed to both `enter()` and `exit()`. Re-querying the ID from the `ThreadReference` inside `exit()` would throw `ObjectCollectedException` if the target thread died during evaluation, leaking a dangling entry.
