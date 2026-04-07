# JDWP MCP Server — Test Flight Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Verify the JDWP MCP server works end-to-end with Claude Code by debugging a sandbox scenario.

**Architecture:** The MCP server runs as a separate process managed by CC (STDIO transport). A target JVM (Maven test) runs with JDWP agent enabled, listening on port 5005. CC uses MCP tools to connect to the target, place breakpoints, and inspect runtime state.

**Tech Stack:** Java 21, Maven, Spring Boot MCP Server, JDI/JDWP

---

## Prerequisites

> **Note on launch flags:** `-Dmaven.surefire.debug` is surefire's built-in JDWP attach (port 5005, `suspend=y`) and is appended on top of the pom's existing `--add-modules jdk.jdi` argLine. If you need to pass *additional* JVM args alongside the debug agent, use `-DargLine="-Xfoo -Xbar"` — since 2026-04-07 the pom interpolates `${argLine}` so this no longer overrides the JDI module flag.

### Build the MCP server JAR

```bash
cd /workspace
mvn clean package -DskipTests -o
```

Produces: `target/mcp-jdwp-java-1.0.0.jar`

### MCP server configuration

Already configured in `/workspace/.claude/settings.json`:

```json
{
  "mcpServers": {
    "jdwp-inspector": {
      "command": "java",
      "args": [
        "--add-modules", "jdk.jdi",
        "-DJVM_JDWP_PORT=5005",
        "-jar", "/workspace/target/mcp-jdwp-java-1.0.0.jar"
      ]
    }
  }
}
```

CC must be restarted after adding/changing this config so it picks up the new MCP server.

---

## Test Flight 1: The Phantom Update (Scenario 1)

**Bug:** `OrderProcessorTest.shouldApplyDiscountCorrectly` expects 71.982 but gets 71.0.
**Root cause:** `AuditLogger.cacheFormattedTotal()` truncates via `(double)(int)` cast.
**JDWP skills:** Breakpoint placement, variable inspection, expression evaluation.

### Step 1: Launch the debug target

In a **separate terminal** (not inside CC):

```bash
cd /workspace
mvn test -o -Dtest="io.mcp.jdwp.sandbox.order.OrderProcessorTest" -Dmaven.surefire.debug
```

Expected output: `Listening for transport dt_socket at address: 5005` — JVM is suspended, waiting for debugger.

### Step 2: Connect from CC

Ask CC:

> Connect to the debug target on localhost:5005.

CC should call `jdwp_connect`. Expected: "Connected to target VM."

### Step 3: Set a breakpoint

Ask CC:

> Set a breakpoint at `io.mcp.jdwp.sandbox.order.OrderProcessor` line where `auditLogger.log(order)` is called.

Or more directly:

> Set a breakpoint in `io.mcp.jdwp.sandbox.order.AuditLogger` at the `cacheFormattedTotal` method.

### Step 4: Resume and hit the breakpoint

Ask CC:

> Resume the VM so the test starts running.

CC calls `jdwp_resume_vm`. The test executes until it hits the breakpoint.

### Step 5: Inspect variables

Ask CC:

> Show me the local variables. What is `order.getTotal()` right now?

CC calls `jdwp_get_locals` and/or uses expression evaluation. This reveals the total before/after truncation.

### Step 6: Diagnose

Ask CC:

> Step through `cacheFormattedTotal` and watch how `order.total` changes.

CC uses `jdwp_step_into` / `jdwp_step_over` and `jdwp_get_locals` to observe the `(double)(int)` truncation.

### Expected diagnosis

CC identifies that `AuditLogger.cacheFormattedTotal()` casts the total to `int` (dropping decimal places: 71.982 → 71) then back to `double` (71.0), and sets it back on the order.

---

## Test Flight 2: The Identity Crisis (Scenario 3)

**Bug:** `SessionStoreTest.shouldRetrieveSessionAfterRoleUpgrade` expects non-null but gets null.
**Root cause:** `UserSession.upgradeRole()` mutates `role` field which participates in `hashCode()`/`equals()`, breaking the HashMap bucket lookup.
**JDWP skills:** Breakpoint, expression evaluation (hashCode comparison), object inspection.

### Step 1: Launch the debug target

```bash
cd /workspace
mvn test -o -Dtest="io.mcp.jdwp.sandbox.session.SessionStoreTest" -Dmaven.surefire.debug
```

### Step 2: Connect and set breakpoints

> Connect to localhost:5005. Set a breakpoint at `SessionStore.retrieve` method.

### Step 3: Resume, hit breakpoint, evaluate

> Resume the VM. When the breakpoint hits, evaluate `session.hashCode()`.
> Also evaluate the map's internal state — is the session still in the map?

Key expressions to evaluate:
- `session.hashCode()` — returns a DIFFERENT value than when it was stored
- `map.containsKey(session)` — returns `false`
- `map.size()` — returns `1` (the entry IS in the map, just unreachable)
- `map.values().iterator().next()` — returns the SessionData (proving it's there)

### Expected diagnosis

CC identifies that `hashCode()` changed after `upgradeRole()` was called, so `HashMap.get()` looks in the wrong bucket.

---

## Test Flight 3: The Race That Hides (Scenario 2)

**Bug:** `TransferServiceTest.shouldMaintainTotalBalance` expects discrepancy=0 but gets 500.
**Root cause:** `TransferService.transfer()` snapshots balances mid-transfer (after withdraw, before deposit).
**JDWP skills:** Thread inspection, suspend specific thread, evaluate from another thread's perspective.

### Step 1: Launch the debug target

```bash
cd /workspace
mvn test -o -Dtest="io.mcp.jdwp.sandbox.bank.TransferServiceTest" -Dmaven.surefire.debug
```

### Step 2: Connect and set breakpoint in transfer method

> Connect to localhost:5005. Set a breakpoint at `TransferService.transfer` after the `withdraw` call but before `deposit`.

### Step 3: Resume and inspect mid-transfer state

> Resume the VM. When the breakpoint hits, evaluate `from.getBalance()` and `to.getBalance()`.
> What does `auditService.getLastSnapshot()` return?

Key insight: `from.getBalance()` = 500 (withdrawn), `to.getBalance()` = 1000 (not yet deposited). Snapshot = 1500 (wrong). After deposit completes, real total = 2000 but snapshot still says 1500.

### Expected diagnosis

CC identifies that `snapshotBalances()` is called between `withdraw()` and `deposit()`, capturing an intermediate state.

---

## Test Flight 4: The Lazy Init Trap (Scenario 4)

**Bug:** `ConfigurationProviderTest.shouldProvideFullyInitializedConfig` expects timeout=5000 but gets 0.
**Root cause:** Provider publishes the `Configuration` instance before calling `init(5000)`.
**JDWP skills:** Multi-thread debugging, inspecting partially-constructed objects.

### Step 1: Launch

```bash
cd /workspace
mvn test -o -Dtest="io.mcp.jdwp.sandbox.config.ConfigurationProviderTest" -Dmaven.surefire.debug
```

### Step 2: Breakpoint inside the provider's initialization

> Set a breakpoint in `ConfigurationProvider` where `instance` is assigned but before `init()` is called.

### Step 3: Inspect from the reader thread

> When the breakpoint hits, list threads. Find the reader thread. Evaluate `provider.getConfig()` — it returns non-null. Evaluate `provider.getConfig().getTimeout()` — it returns 0.

### Expected diagnosis

CC identifies that `instance` is visible to the reader thread before `init()` runs, so `timeout` is still at its default value (0).

---

## Test Flight 5: The Silent Swallower (Scenario 5)

**Bug:** `EventBusTest.shouldDispatchAndUpdateInventory` — stock unchanged at 100, error summary says "Async task failed".
**Root cause:** `OrderEvent` casts quantity `200` to `byte` (overflows to -56), `Inventory.reserve()` throws on negative, exception wrapped 3 levels deep.
**JDWP skills:** Breakpoint in catch block, exception chain unwrapping via expression evaluation.

### Step 1: Launch

```bash
cd /workspace
mvn test -o -Dtest="io.mcp.jdwp.sandbox.events.EventBusTest" -Dmaven.surefire.debug
```

### Step 2: Breakpoint in EventBus catch block

> Set a breakpoint in `EventBus.dispatch` inside the catch block.

### Step 3: Unwrap the exception chain

> Evaluate `e.getMessage()` — "Async task failed" (useless).
> Evaluate `e.getCause().getMessage()` — "Handler failed" (still useless).
> Evaluate `e.getCause().getCause().getMessage()` — "Cannot reserve negative quantity: -56 for product WIDGET" (root cause!).

### Step 4: Trace the quantity

> Set a breakpoint in `InventoryHandler.handle`. Evaluate `event.getQuantity()` — returns -56.
> Set a breakpoint in `OrderEvent` constructor. Evaluate the `rawQuantity` parameter — it's 200, but `(byte) 200` overflows to -56.

### Expected diagnosis

CC identifies the byte-overflow deserialization bug in `OrderEvent` constructor.

---

## Troubleshooting

| Issue | Fix |
|---|---|
| CC doesn't see MCP tools | Restart CC after editing `.claude/settings.json` |
| "Connection refused" on connect | Ensure the `mvn test` is running and listening on port 5005 |
| MCP server crashes on startup | Check `--add-modules jdk.jdi` is in the args. Verify JAR exists at the configured path. |
| Test finishes before breakpoint hit | Use `suspend=y` in the JDWP agent args (test JVM waits for debugger) |
| "Address already in use" | Kill previous `mvn test` process: `pkill -f "jdwp.*5005"` |
| Breakpoint not hit | Verify class name is fully qualified, line number is on an executable statement |
