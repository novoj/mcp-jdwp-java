# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Run from /workspace (the reactor root). Builds both modules.
mvn compile                                    # Compile only
mvn clean package -DskipTests                  # Full build (produces mcp-server/target/mcp-jdwp-java-1.0.0.jar)

# Build only the MCP server (skips the sandbox module entirely):
mvn -pl mcp-server -am clean package -DskipTests
```

The repository is a 2-module Maven reactor:

- **`mcp-server/`** — the real MCP server. Contains all genuine unit tests; `mvn -pl mcp-server test` runs them.
- **`jdwp-sandbox/`** — deliberately broken Java classes used as JDWP debugging targets ("test flights"). Sandbox tests are expected-to-fail by design and are **skipped by default** so a normal `mvn test` stays green. Run them explicitly during a test flight with: `mvn -pl jdwp-sandbox test -DskipTests=false`.

The compiler requires `--add-modules jdk.jdi` (configured in `mcp-server/pom.xml`). At runtime, the JAR must also be launched with `--add-modules jdk.jdi`.

## Architecture

This is a **Spring Boot MCP Server** that lets Claude Code inspect and control running Java applications via JDWP (Java Debug Wire Protocol). It connects directly to a target JVM's JDWP port (default 5005).

```
Claude Code ──MCP/STDIO──> Spring Boot MCP Server ──JDI──> Target JVM (port 5005)
```

### Core Components

- **JDWPTools** — Exposes ~30 MCP tools via `@McpTool` annotations. This is the main interface: connection, thread/stack/variable inspection, breakpoints, stepping, and watcher evaluation. All tool methods return formatted `String` responses.
- **JDIConnectionService** — Singleton holding the persistent `VirtualMachine` (JDI) connection. Manages object caching (`ConcurrentHashMap<Long, ObjectReference>`) and smart collection rendering (ArrayList, HashMap, HashSet internals). Also drives classpath discovery for expression evaluation.
- **BreakpointTracker** — Maintains a registry of breakpoints with synthetic integer IDs (since JDI `BreakpointRequest` has no user-facing ID). Tracks the last thread that hit a breakpoint.
- **JdiEventListener** — Daemon thread consuming the JDI event queue. Updates `BreakpointTracker` on breakpoint/step events. Breakpoint events do NOT auto-resume — threads stay suspended for user inspection.

### Expression Evaluation Pipeline (`evaluation/` package)

Evaluates arbitrary Java expressions at breakpoints by compiling and injecting bytecode into the target JVM:

1. **JdiExpressionEvaluator** — Orchestrator. Analyzes the stack frame, generates a wrapper class with a unique UUID name, delegates compilation, and caches results.
2. **ClasspathDiscoverer** — Walks the target JVM's classloader hierarchy (including Tomcat/container classloaders) to discover all JARs. Also uses **JdkDiscoveryService** to find a local JDK matching the target JVM version.
3. **InMemoryJavaCompiler** — Compiles Java source to bytecode using Eclipse JDT (ECJ), entirely in memory.
4. **RemoteCodeExecutor** — Injects compiled bytecode into the target JVM via `ClassLoader.defineClass()` and invokes it.

### Watcher System (`watchers/` package)

- **WatcherManager** — CRUD for watchers, dual-indexed by watcher UUID and breakpoint ID.
- **Watcher** — Immutable model: id, label, breakpointId, expression.

Watchers are MCP-side only — they store expressions that get evaluated via the expression pipeline when a breakpoint is hit.

## Key Design Decisions

- MCP server type is `SYNC` and `web-application-type=none` — communication is JSON over STDIO, no HTTP server.
- `JDIConnectionService.disconnect()` calls `vm.dispose()` for clean JDWP teardown.
- Object references are cached globally so users can navigate object graphs across multiple tool calls.
- Expression wrapper classes use UUID-based naming to avoid `LinkageError` across server restarts.
- The JDI event listener thread must consume all events to prevent the target JVM from blocking.
