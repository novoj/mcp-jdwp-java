# MCP JDWP Inspector

MCP server that gives AI agents full debugger control over running Java applications — inspect state, set breakpoints, evaluate expressions, and mutate values at runtime via JDWP/JDI.

**Built on the foundations of [mcp-jdwp-java](https://github.com/NicolasVautrin/mcp-jdwp-java) by [Nicolas Vautrin](https://github.com/NicolasVautrin)** — the original project that provided core JDI connectivity, thread/stack/variable inspection, stepping, and basic breakpoint management. Everything described below as "beyond standard JDWP" was built on top of that base.

## Why this exists

Raw JDWP/JDI gives you threads, stack frames, and variables. That's enough for a human with IntelliJ — but an AI agent needs more:

- **Conditional breakpoints** — stop only when a Java expression is true, so the agent isn't flooded with irrelevant hits
- **Logpoints** — evaluate an expression and log the result *without* stopping, for non-intrusive tracing
- **Deferred breakpoints** — set breakpoints on classes that haven't loaded yet; they activate automatically
- **Exception breakpoints** — catch exceptions at the throw site, not after they've unwound the stack
- **Expression evaluation** — compile and execute arbitrary Java at any breakpoint, with full classpath
- **Value mutation** — change local variables and object fields at runtime to test hypotheses
- **Recursive breakpoint protection** — expression evaluation is safe even when it re-enters the breakpointed line
- **Smart filtering** — JVM internal threads and framework noise frames are hidden by default
- **Blocking resume** — `resume_until_event` eliminates the "resume → poll → poll" dance

40 MCP tools, exposed over STDIO. Your agent gets the same power as IntelliJ's debugger.

## Quick start

### Prerequisites

- **JDK 17+** (must be a JDK, not a JRE — JDI lives in `jdk.jdi`)
- **Maven 3.8+** (wrapper included)

### 1. Build

```bash
cd mcp-jdwp-java
mvn clean package -DskipTests
```

Produces: `jdwp-mcp-server/target/mcp-jdwp-java-1.0.0.jar`

### 2. Register with Claude Code

**Option A: CLI (recommended)**

```bash
claude mcp add jdwp-inspector -s user \
  -e MCP_TIMEOUT=30000 \
  -e MCP_TOOL_TIMEOUT=120000 \
  -- java --add-modules jdk.jdi -jar /path/to/mcp-jdwp-java-1.0.0.jar
```

To change the JDWP port (default 5005):

```bash
claude mcp add jdwp-inspector -s user \
  -e MCP_TIMEOUT=30000 \
  -e MCP_TOOL_TIMEOUT=120000 \
  -- java --add-modules jdk.jdi -DJVM_JDWP_PORT=12345 -jar /path/to/mcp-jdwp-java-1.0.0.jar
```

Re-installing requires removing first: `claude mcp remove jdwp-inspector -s user`

Drop `-s user` to scope to the current project only.

**Option B: `.mcp.json`**

```json
{
  "mcpServers": {
    "jdwp-inspector": {
      "command": "java",
      "args": [
        "--add-modules", "jdk.jdi",
        "-jar", "/path/to/mcp-jdwp-java-1.0.0.jar"
      ],
      "env": {
        "MCP_TIMEOUT": "30000",
        "MCP_TOOL_TIMEOUT": "120000"
      }
    }
  }
}
```

### 3. Launch your Java application with JDWP

**Maven Surefire (test debugging):**

```bash
mvn test -Dmaven.surefire.debug
```

Starts the JVM with JDWP on port 5005, suspended until a debugger connects.

**Any Java application:**

```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

### 4. Restart Claude Code

To pick up the new MCP configuration.

## Features beyond standard JDWP

These are the capabilities this server adds on top of raw JDI — the reason to use it instead of writing JDI calls directly.

### Conditional breakpoints

```
jdwp_set_breakpoint(
  className="com.example.OrderService",
  lineNumber=42,
  condition="order.getTotal() > 1000"
)
```

The breakpoint fires on every hit, but the thread is only suspended when the condition evaluates to `true`. False hits are auto-resumed transparently. This is essential for AI agents — without conditions, a breakpoint in a hot loop would generate thousands of useless stops.

### Logpoints (non-stopping breakpoints)

```
jdwp_set_logpoint(
  className="com.example.OrderService",
  lineNumber=42,
  expression="\"Processing order \" + order.getId() + \" total=\" + order.getTotal()",
  condition="order.getTotal() > 1000"
)
```

A logpoint evaluates an expression every time the line is hit, logs the result to the event history, and **never suspends the thread**. Combined with an optional condition, this gives the agent non-intrusive tracing without stopping execution. View results with `jdwp_get_events()`.

### Deferred breakpoints

```
jdwp_set_breakpoint(className="com.example.LazyService", lineNumber=15)
→ "Breakpoint deferred — class com.example.LazyService not yet loaded. Will activate on class load."
```

If the target class isn't loaded when the breakpoint is set, the server registers a `ClassPrepareRequest` and automatically promotes the breakpoint to active when the JVM loads the class. Works for line breakpoints, logpoints, and exception breakpoints. `jdwp_list_breakpoints()` shows pending breakpoints with their status.

### Exception breakpoints

```
jdwp_set_exception_breakpoint(
  exceptionClass="java.lang.NullPointerException",
  caught=true,
  uncaught=true
)
```

Catch exceptions at the throw site — before the stack unwinds. Supports deferred activation (if the exception class isn't loaded yet). Use `jdwp_list_exception_breakpoints()` to see active and pending exception breakpoints.

### Expression evaluation

```
jdwp_evaluate_expression(
  threadId=25,
  expression="request.getData().get(\"_domain\")",
  frameIndex=0
)
→ "self.operationTypeSelect = 3"
```

Compiles arbitrary Java expressions to bytecode using Eclipse JDT, injects them into the target JVM via `ClassLoader.defineClass()`, and executes them in the context of the suspended frame. Full classpath is discovered automatically (including container classloaders like Tomcat). Results are cached for performance. Handles Guice/CGLIB proxies automatically.

See [EXPRESSION_EVALUATION.md](EXPRESSION_EVALUATION.md) for the compilation pipeline details.

### Assertions

```
jdwp_assert_expression(
  expression="order.getStatus()",
  expected="CONFIRMED",
  threadId=25
)
→ "OK" or "MISMATCH: actual='PENDING', expected='CONFIRMED'"
```

Evaluate and compare in one call — useful for agents running verification sequences.

### Value mutation

```
jdwp_set_local(threadId=25, frameIndex=0, varName="retryCount", value="3")
jdwp_set_field(objectId=26886, fieldName="limit", value="100")
```

Change local variables and object fields at runtime. The agent can test hypotheses ("what if this value were different?") without restarting the application.

### Watchers

Attach persistent expressions to breakpoints that are evaluated automatically on every hit:

```
jdwp_attach_watcher(breakpointId=27, label="request data", expression="request.getData()")
jdwp_attach_watcher(breakpointId=27, label="user context", expression="request.getUser().getName()")

# Later, when breakpoint 27 fires:
jdwp_evaluate_watchers(threadId=25, scope="current_frame", breakpointId=27)
```

Watchers are MCP-side state — they survive across breakpoint hits and are cleaned up when the breakpoint is deleted.

### Recursive breakpoint protection

When an expression evaluation at a breakpoint re-enters the breakpointed line (e.g., `this.compute(n - 1)` evaluated inside `compute`), JDI would re-suspend the thread and deadlock the server. This server wraps every `invokeMethod` chain in a per-thread reentrancy guard:

1. Recursive breakpoint/exception/step events are **auto-resumed** instead of suspending
2. A `BREAKPOINT_SUPPRESSED` / `EXCEPTION_SUPPRESSED` / `STEP_SUPPRESSED` entry is recorded in event history
3. The outer breakpoint context is preserved

**Covered invocation sites:** `jdwp_evaluate_expression`, `jdwp_assert_expression`, `jdwp_evaluate_watchers`, logpoint evaluation, conditional breakpoint evaluation, `jdwp_to_string`, classpath discovery, and deferred class loading via `Class.forName`.

### Smart filtering

**Threads:** `jdwp_get_threads()` hides JVM internals (Reference Handler, Finalizer, surefire workers) by default. Pass `includeSystemThreads=true` to see everything.

**Stack frames:** `jdwp_get_stack()` collapses junit/surefire/reflection noise frames by default. Pass `includeNoise=true` to see the full stack.

### Blocking resume

```
jdwp_resume_until_event(timeoutMs=30000)
→ blocks until next breakpoint/step/exception, returns context immediately
```

Replaces the manual "resume → poll events → poll events" pattern. The agent resumes and gets the next stop in one synchronous call.

### One-shot breakpoint context

```
jdwp_get_breakpoint_context(maxFrames=5, includeThisFields=true)
```

Returns thread info, top stack frames, locals at frame 0, and `this` fields in a single call — replaces the four-call sequence `get_current_thread → get_stack → get_locals → get_fields(this)` that an agent would otherwise need at every breakpoint hit.

## Tool reference (40 tools)

### Connection (3)

| Tool | Parameters | Description |
|------|-----------|-------------|
| `jdwp_connect` | — | Connect to JDWP on configured host:port |
| `jdwp_disconnect` | — | Disconnect (sends JDWP Dispose) |
| `jdwp_wait_for_attach` | `host?`, `port?`, `timeoutMs?` | Poll until JVM is listening, then attach |

### Inspection (8)

| Tool | Parameters | Description |
|------|-----------|-------------|
| `jdwp_get_version` | — | JVM version info |
| `jdwp_get_threads` | `includeSystemThreads?` | List threads with status and frame counts |
| `jdwp_get_stack` | `threadId`, `maxFrames?`, `includeNoise?` | Stack trace (noise frames collapsed by default) |
| `jdwp_get_locals` | `threadId`, `frameIndex` | Local variables at a frame (includes `this`) |
| `jdwp_get_fields` | `objectId` | Object fields, collection elements, or array contents |
| `jdwp_to_string` | `objectId`, `threadId` | Invoke `toString()` on a cached object |
| `jdwp_get_breakpoint_context` | `maxFrames?`, `includeThisFields?` | One-shot context dump at current breakpoint |
| `jdwp_get_current_thread` | — | Thread ID of the last breakpoint hit |

### Execution control (7)

| Tool | Parameters | Description |
|------|-----------|-------------|
| `jdwp_resume` | — | Resume all threads |
| `jdwp_resume_thread` | `threadId` | Resume a specific thread |
| `jdwp_suspend_thread` | `threadId` | Suspend a specific thread |
| `jdwp_resume_until_event` | `timeoutMs?` | Resume and block until next breakpoint/step/exception |
| `jdwp_step_over` | `threadId` | Step over (F6) |
| `jdwp_step_into` | `threadId` | Step into (F7) |
| `jdwp_step_out` | `threadId` | Step out (Shift+F8) |

### Breakpoints (9)

| Tool | Parameters | Description |
|------|-----------|-------------|
| `jdwp_set_breakpoint` | `className`, `lineNumber`, `suspendPolicy?`, `condition?` | Set breakpoint (supports conditions and deferred) |
| `jdwp_set_logpoint` | `className`, `lineNumber`, `expression`, `condition?` | Non-stopping breakpoint that logs expression result |
| `jdwp_clear_breakpoint` | `className`, `lineNumber` | Remove breakpoint by location |
| `jdwp_clear_breakpoint_by_id` | `breakpointId` | Remove breakpoint by ID |
| `jdwp_list_breakpoints` | — | List all breakpoints (active, pending, failed) |
| `jdwp_clear_all_breakpoints` | — | Remove all breakpoints |
| `jdwp_set_exception_breakpoint` | `exceptionClass`, `caught?`, `uncaught?` | Break on exception throw (supports deferred) |
| `jdwp_clear_exception_breakpoint` | `breakpointId` | Remove exception breakpoint |
| `jdwp_list_exception_breakpoints` | — | List exception breakpoints (active and pending) |

### Expression evaluation and mutation (4)

| Tool | Parameters | Description |
|------|-----------|-------------|
| `jdwp_evaluate_expression` | `threadId`, `expression`, `frameIndex?` | Evaluate Java expression at suspended frame |
| `jdwp_assert_expression` | `expression`, `expected`, `threadId`, `frameIndex?` | Evaluate and compare against expected value |
| `jdwp_set_local` | `threadId`, `frameIndex`, `varName`, `value` | Set a local variable's value |
| `jdwp_set_field` | `objectId`, `fieldName`, `value` | Set a field's value on a cached object |

### Events (2)

| Tool | Parameters | Description |
|------|-----------|-------------|
| `jdwp_get_events` | `count?` | Recent events (breakpoints, steps, exceptions, logpoints) |
| `jdwp_clear_events` | — | Clear event history |

### Watchers (6)

| Tool | Parameters | Description |
|------|-----------|-------------|
| `jdwp_attach_watcher` | `breakpointId`, `label`, `expression` | Attach expression watcher to a breakpoint |
| `jdwp_detach_watcher` | `watcherId` | Remove a watcher |
| `jdwp_list_watchers_for_breakpoint` | `breakpointId` | List watchers on a breakpoint |
| `jdwp_list_all_watchers` | — | List all watchers across all breakpoints |
| `jdwp_evaluate_watchers` | `threadId`, `scope`, `breakpointId?` | Evaluate watchers (`current_frame` or `full_stack`) |
| `jdwp_clear_all_watchers` | — | Remove all watchers |

### Session (1)

| Tool | Parameters | Description |
|------|-----------|-------------|
| `jdwp_reset` | — | Clear all state (breakpoints, watchers, cache, events) without disconnecting |

## Usage workflows

### Debugging a REST request

```
1. Launch your app with JDWP enabled
2. In Claude Code:
   "Set a breakpoint in OrderService.createOrder line 42 and wait for a hit"

3. Claude:
   jdwp_connect()
   jdwp_set_breakpoint("com.example.OrderService", 42)
   jdwp_resume_until_event(timeoutMs=60000)
   jdwp_get_breakpoint_context()
   → "Thread http-nio-8080-exec-3 stopped at OrderService:42.
      Local 'order' has total=0.0, status=null — looks like
      the order wasn't initialized before reaching this line."
```

### Non-intrusive tracing with logpoints

```
1. "Add a logpoint to trace every order over $1000"

2. Claude:
   jdwp_set_logpoint(
     "com.example.OrderService", 42,
     "\"order=\" + order.getId() + \" total=\" + order.getTotal()",
     "order.getTotal() > 1000"
   )
   jdwp_resume()

3. Later:
   jdwp_get_events(count=20)
   → Shows LOGPOINT entries with evaluated expressions, no thread was ever stopped
```

### Catching exceptions at the throw site

```
1. "I'm getting a NullPointerException somewhere in the order flow"

2. Claude:
   jdwp_set_exception_breakpoint("java.lang.NullPointerException", caught=true, uncaught=true)
   jdwp_resume_until_event(timeoutMs=60000)
   jdwp_get_breakpoint_context()
   → "NullPointerException thrown at OrderValidator:87.
      Local 'customer' is null — the order was submitted without a customer reference."
```

### Test flight: recursive breakpoint scenario

The `jdwp-sandbox` module includes a deterministic scenario (`one.edee.jdwp.sandbox.recursion` package) that reproduces the recursive breakpoint case:

```bash
# Terminal 1 — launch sandbox, suspended on port 5005
mvn -pl jdwp-sandbox test -Dtest=RecursiveCalculatorTest -DskipTests=false -Dmaven.surefire.debug

# From Claude Code:
jdwp_wait_for_attach()
jdwp_set_breakpoint("one.edee.jdwp.sandbox.recursion.RecursiveCalculator", 22)
jdwp_resume_until_event()
# → BP fires inside compute(5)
jdwp_evaluate_expression(threadId, "this.compute(3)")
# → returns 2 without deadlock
jdwp_get_events()
# → shows BREAKPOINT_SUPPRESSED entries for each recursive hit
```

## Architecture

```
Claude Code ──MCP/STDIO──> Spring Boot MCP Server ──JDI──> Target JVM (port 5005)
```

The server is `SYNC` mode, `web-application-type=none` — JSON over STDIO, no HTTP.

### Core components

| Component | Role |
|-----------|------|
| **JDWPTools** | 40 `@McpTool` methods — the MCP surface. Thin orchestration over services below. |
| **JDIConnectionService** | Singleton `VirtualMachine` connection. Object cache (`ConcurrentHashMap<Long, ObjectReference>`), smart collection rendering, classpath discovery. |
| **BreakpointTracker** | Breakpoint registry with synthetic IDs. Tracks pending/deferred state, conditions, logpoint expressions, exception breakpoints. |
| **JdiEventListener** | Daemon thread consuming the JDI event queue. Routes events, evaluates conditions/logpoints, handles recursive suppression. |
| **EvaluationGuard** | Per-thread reentrancy guard preventing deadlocks during expression evaluation. |
| **EventHistory** | Ring buffer of the last 100 JDWP events (including suppressed). |

### Expression evaluation pipeline (`evaluation/`)

1. **JdiExpressionEvaluator** — Analyzes the stack frame, generates a wrapper class with a UUID name, delegates compilation, caches results.
2. **ClasspathDiscoverer** — Walks target JVM classloader hierarchy (including Tomcat/container) to find all JARs. Uses **JdkDiscoveryService** to locate a local JDK matching the target version.
3. **InMemoryJavaCompiler** — Compiles Java source to bytecode using Eclipse JDT (ECJ), entirely in memory.
4. **RemoteCodeExecutor** — Injects bytecode via `ClassLoader.defineClass()` and invokes it.

### Watcher system (`watchers/`)

- **WatcherManager** — CRUD, dual-indexed by watcher UUID and breakpoint ID. Auto-cleans when breakpoint is deleted.
- **Watcher** — Immutable model: id, label, breakpointId, expression.

## Project structure

```
mcp-jdwp-java/
├── pom.xml                              # Parent POM (reactor)
├── mvnw / mvnw.cmd                      # Maven wrapper
├── README.md
├── WORKFLOW.md                          # Development guide
├── EXPRESSION_EVALUATION.md             # Expression evaluation pipeline docs
│
├── jdwp-mcp-server/                     # The MCP server
│   ├── pom.xml
│   └── src/main/java/one/edee/mcp/jdwp/
│       ├── JDWPMcpServerApplication.java
│       ├── JDWPTools.java               # 40 @McpTool methods
│       ├── JDIConnectionService.java    # JDI connection + object cache
│       ├── BreakpointTracker.java       # Breakpoint registry + deferred state
│       ├── JdiEventListener.java        # JDI event consumer
│       ├── EvaluationGuard.java         # Recursive breakpoint protection
│       ├── EventHistory.java            # Event ring buffer
│       ├── ThreadFormatting.java        # Thread/frame noise filtering
│       ├── evaluation/
│       │   ├── JdiExpressionEvaluator.java
│       │   ├── RemoteCodeExecutor.java
│       │   ├── InMemoryJavaCompiler.java
│       │   ├── ClasspathDiscoverer.java
│       │   └── JdkDiscoveryService.java
│       └── watchers/
│           ├── WatcherManager.java
│           └── Watcher.java
│
└── jdwp-sandbox/                        # Debugging targets (test flights)
    ├── pom.xml                          # Tests skipped by default
    └── src/                             # Deliberately broken scenarios
```

## Dependencies

- **Spring Boot 4.0** — Framework
- **Spring AI MCP 2.0.0-M4** — MCP protocol integration
- **JDI** (`jdk.jdi` module) — Java Debug Interface
- **Eclipse JDT Compiler (ECJ)** — In-memory expression compilation
- **JSpecify + NullAway** — Compile-time nullness enforcement

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `tools.jar not found` / `jdk.jdi not available` | Ensure `JAVA_HOME` points to a JDK, not a JRE. Launch with `--add-modules jdk.jdi`. |
| Connection refused | Verify target JVM has `-agentlib:jdwp=...address=*:5005`. Check port matches `-DJVM_JDWP_PORT`. |
| MCP server doesn't respond | Rebuild: `mvn clean package -DskipTests`. Check jar path. Restart Claude Code. |
| "Thread is not suspended" | The thread must be stopped at a breakpoint for stack/locals/expression tools. |
| Expression evaluation timeout | First evaluation is slow (classpath discovery). Increase `MCP_TOOL_TIMEOUT`. Subsequent evaluations use cache. |

## License

MIT
