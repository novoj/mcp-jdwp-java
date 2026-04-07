# JDWP MCP Server — New Features Test Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Exercise all 8 newly implemented features against the deliberately broken sandbox scenarios.

**Features under test:**
1. `jdwp_evaluate_expression` — direct expression eval
2. `jdwp_set_logpoint` — non-stopping logging breakpoint
3. `jdwp_set_breakpoint` with `condition` — conditional breakpoint
4. `jdwp_set_exception_breakpoint` — break on exception throw
5. `jdwp_set_local` / `jdwp_set_field` — runtime variable mutation
6. `jdwp_to_string` — invoke `toString()` on cached objects
7. Dynamic compiler version — Java 14+ syntax in expressions
8. Event history (`jdwp_get_events`) — bounded ring buffer of all events

---

## Prerequisites

```bash
mvn clean package -DskipTests
```

The MCP server must be (re)connected after the JAR is rebuilt. Each scenario below assumes a fresh Maven debug fork on port 5005 and a fresh `jdwp_connect` call.

**Launch command for any scenario:** open a second shell and run:

```bash
mvn test -Dtest=<TestClass> -Dmaven.surefire.debug
```

Surefire will print `Listening for transport dt_socket at address: 5005` and wait. Then call `jdwp_connect` from the MCP side, set breakpoints, and call `jdwp_resume` to release the test JVM.

---

## Session 1 — Order Processor (truncation bug)

**Tests:** Feature 1 (eval), Feature 2 (logpoint), Feature 6 (toString), Feature 7 (Java 14+ syntax).

**Bug:** `AuditLogger.cacheFormattedTotal()` at `AuditLogger.java:30` casts `(double)(int)` and truncates cents.

**Launch:** `mvn test -Dtest=OrderProcessorTest -Dmaven.surefire.debug`

### Step 1.1 — Connect & set breakpoint

```
jdwp_connect()
jdwp_set_breakpoint(className="io.mcp.jdwp.sandbox.order.OrderProcessor", lineNumber=41, suspendPolicy="all")
```
Line 41 is the `auditLogger.log(order)` call — breakpoint will fire AFTER `setTotal(discountedTotal)` runs but BEFORE the auditor mutates it.

```
jdwp_resume()
```

### Step 1.2 — Feature 1: evaluate_expression (before truncation)

When the breakpoint hits, get the thread ID via `jdwp_get_current_thread()`, then:

```
jdwp_evaluate_expression(threadId=<id>, expression="order.getTotal()", frameIndex=0)
```
**Expected:** `Result: 71.982`

### Step 1.3 — Feature 6: toString on the items list

```
jdwp_get_locals(threadId=<id>, frameIndex=0)
```
Note the Object#id of `order`. Call `jdwp_get_fields(<order_id>)` to find the items list ID.

```
jdwp_to_string(objectId=<items_list_id>)
```
**Expected:** `Object#N (java.util.ImmutableCollections$List12).toString() = "[Widget, Gadget]"`

### Step 1.4 — Feature 7: Java 14+ syntax (switch expression)

```
jdwp_evaluate_expression(threadId=<id>, expression="(switch (order.getItems().size()) { case 2 -> \"two items\"; default -> \"other\"; })", frameIndex=0)
```
**Expected:** `Result: "two items"`. Confirms the dynamic compiler version is threading through (this expression would fail under hardcoded `-source 1.8`).

### Step 1.5 — Step into the audit logger to confirm truncation

```
jdwp_step_into(threadId=<id>)
jdwp_step_into(threadId=<id>)   // step until inside cacheFormattedTotal
jdwp_evaluate_expression(threadId=<id>, expression="order.getTotal()", frameIndex=0)
```
At the entry of `cacheFormattedTotal`, total is still `71.982`.

```
jdwp_step_over(threadId=<id>)   // execute the (double)(int) cast line
jdwp_evaluate_expression(threadId=<id>, expression="order.getTotal()", frameIndex=0)
```
**Expected:** `Result: 71.0` — the bug is reproduced live.

### Step 1.6 — Feature 2: logpoint (re-run trace)

`jdwp_clear_all_breakpoints()`, then on a fresh `Order.java`:

```
jdwp_set_logpoint(className="io.mcp.jdwp.sandbox.order.Order", lineNumber=32, expression="\"setTotal called with: \" + total")
jdwp_resume()
```

After the test completes:

```
jdwp_get_events(count=20)
```
**Expected:** Two `[LOGPOINT]` entries:
- `setTotal called with: 71.982`
- `setTotal called with: 71.0`

The thread should NOT have been observably suspended — test completes normally.

### Step 1.7 — Disconnect

```
jdwp_disconnect()
```

---

## Session 2 — Bank Transfer (non-atomic snapshot)

**Tests:** Feature 3 (conditional breakpoint), Feature 5 (set_local), Feature 8 (event history).

**Bug:** `TransferService.transfer` at `TransferService.java:23` snapshots balances mid-transfer (after withdraw, before deposit), so the audit total is short by `amount`.

**Launch:** `mvn test -Dtest=TransferServiceTest -Dmaven.surefire.debug`

### Step 2.1 — Feature 3: conditional breakpoint

```
jdwp_connect()
jdwp_set_breakpoint(className="io.mcp.jdwp.sandbox.bank.TransferService", lineNumber=23, suspendPolicy="all", condition="amount > 1000")
jdwp_resume()
```

The test transfers 500, so the condition is **false** — the breakpoint should NOT fire and the test should run to completion (and fail on its assertion). Verify via `jdwp_get_events` that no BREAKPOINT entry was recorded for this line.

### Step 2.2 — Conditional breakpoint that DOES fire

```
jdwp_clear_all_breakpoints()
jdwp_set_breakpoint(className="io.mcp.jdwp.sandbox.bank.TransferService", lineNumber=23, suspendPolicy="all", condition="amount >= 500")
```

Re-launch the test. **Expected:** breakpoint hits this time. `jdwp_list_breakpoints()` should show the `Condition: amount >= 500` line.

### Step 2.3 — Feature 5: set_local (mutate `amount`)

While suspended at line 23:

```
jdwp_get_locals(threadId=<id>, frameIndex=0)
```
Confirm `amount = 500`.

```
jdwp_set_local(threadId=<id>, frameIndex=0, varName="amount", value="0")
jdwp_get_locals(threadId=<id>, frameIndex=0)
```
**Expected:** `amount = 0`. Resume; the snapshot will now match because the withdrawal was 0 (not really a fix, but proves the mutation took effect).

```
jdwp_resume()
```

### Step 2.4 — Feature 8: event history

```
jdwp_get_events(count=50)
```
**Expected:** A chronological list including `VM_START`, multiple `BREAKPOINT` entries (with the conditional breakpoint hit), and any `STEP` events from earlier sessions if the connection persisted. Confirm the bounded buffer is working: events are timestamped and ordered.

```
jdwp_clear_events()
jdwp_get_events(count=10)
```
**Expected:** `No events recorded yet.` confirms the clear worked.

```
jdwp_disconnect()
```

---

## Session 3 — Configuration Provider (partial init)

**Tests:** Feature 4 (exception breakpoint), Feature 5 (set_local fix at runtime).

**Bug:** `ServiceRunner.run` at `ServiceRunner.java:15` divides by `timeout`, which is 0 on a partially-initialized `Configuration`. Throws `ArithmeticException`.

We won't run `ConfigurationProviderTest` directly because it tests a thread race; instead exercise `ServiceRunner` via the MCP-side compiler if needed. For this session, use the existing test class which exercises the same code path.

**Launch:** `mvn test -Dtest=ConfigurationProviderTest -Dmaven.surefire.debug`

### Step 3.1 — Feature 4: exception breakpoint

```
jdwp_connect()
jdwp_set_exception_breakpoint(exceptionClass="java.lang.ArithmeticException", caught=true, uncaught=true)
jdwp_list_exception_breakpoints()
```
**Expected:** One entry showing the exception class and `caught: true, uncaught: true`.

```
jdwp_resume()
```

The test's `ConfigurationProviderTest` does not actually call `ServiceRunner.run`, so this exception will not fire from that test. To exercise it, set a regular breakpoint at `ServiceRunner.java:15` instead and use `jdwp_evaluate_expression` plus `jdwp_set_local` to trigger and prevent the exception. **Alternative:** use `EventBusTest` for the exception breakpoint test (see Session 4).

### Step 3.2 — Feature 5 again: set_local to fix divide-by-zero

Cleaner alternative path. Set a regular breakpoint at `ServiceRunner.java:15`, run a small wrapper test, hit the breakpoint:

```
jdwp_evaluate_expression(threadId=<id>, expression="timeout", frameIndex=0)
```
**Expected:** `Result: 0`

```
jdwp_set_local(threadId=<id>, frameIndex=0, varName="timeout", value="5000")
jdwp_step_over(threadId=<id>)
```
**Expected:** No `ArithmeticException`. The method returns 2 instead of crashing. This proves `set_local` works for primitive ints.

```
jdwp_disconnect()
```

---

## Session 4 — Event Bus (silent exception swallower)

**Tests:** Feature 4 (exception breakpoint catches root cause), Feature 1 (eval byte arithmetic).

**Bug:** `OrderEvent` at `OrderEvent.java:21` casts raw int through `byte`, so `200 → -56`. `Inventory.reserve` at `Inventory.java:25` throws `IllegalStateException` for negative qty. The exception is then wrapped 3 times by `InventoryHandler` and `EventBus`, burying the root cause.

**Launch:** `mvn test -Dtest=EventBusTest -Dmaven.surefire.debug`

### Step 4.1 — Feature 4: catch the throw at the source

```
jdwp_connect()
jdwp_set_exception_breakpoint(exceptionClass="java.lang.IllegalStateException", caught=true, uncaught=false)
jdwp_set_breakpoint(className="io.mcp.jdwp.sandbox.events.EventBusTest", lineNumber=25, suspendPolicy="all", condition="")
jdwp_resume()
```

The exception bp will be reported as **deferred** (the bootstrap class is not loaded yet). The line bp gives the system a thread suspended at a method-invocation event so the deferred exception bp can self-promote via `Class.forName` force-load.

When the line bp hits, call any tool that uses `getVM()` (e.g., `jdwp_get_locals`) to trigger the retry, then resume. The exception bp now activates and catches the throw.

**Expected:** After resuming past the line bp, the exception breakpoint fires at `Inventory.java:25` — the **actual throw site**, not the wrapper layers above. This is the killer feature: without exception breakpoints, the wrapped exception's root cause is invisible.

```
jdwp_get_current_thread()
jdwp_get_stack(threadId=<id>)
```
**Expected:** Stack shows `Inventory.reserve → InventoryHandler.handle → EventBus.dispatch`. The frame at index 0 is inside `Inventory.reserve`.

### Step 4.2 — Feature 1: confirm the byte overflow

```
jdwp_get_locals(threadId=<id>, frameIndex=0)
```
**Expected:** `qty = -56` (the byte-truncated value).

```
jdwp_evaluate_expression(threadId=<id>, expression="(byte) 200", frameIndex=0)
```
**Expected:** `Result: -56` — confirms the cast that produced the bug.

```
jdwp_evaluate_expression(threadId=<id>, expression="qty + 256", frameIndex=0)
```
**Expected:** `Result: 200` — recovers the original value.

### Step 4.3 — Inspect the event in history

```
jdwp_get_events(count=10)
```
**Expected:** An `[EXCEPTION]` entry with summary referencing `IllegalStateException`, throw location `Inventory:25`, catch location `InventoryHandler:18`.

```
jdwp_clear_exception_breakpoint(breakpointId=<id from list>)
jdwp_disconnect()
```

---

## Session 5 — Session Store (HashMap key mutation)

**Tests:** Feature 6 (toString to inspect Map state), Feature 1 (eval `containsKey` before/after mutation).

**Bug:** `SessionStore.upgradeUserRole` at `SessionStore.java:25` mutates a field that participates in `hashCode()`/`equals()`, breaking the `HashMap` lookup.

**Launch:** `mvn test -Dtest=SessionStoreTest -Dmaven.surefire.debug`

### Step 5.1 — Set breakpoint inside the test, before and after the upgrade

```
jdwp_connect()
jdwp_set_breakpoint(className="io.mcp.jdwp.sandbox.session.SessionStoreTest", lineNumber=23, suspendPolicy="all")
jdwp_set_breakpoint(className="io.mcp.jdwp.sandbox.session.SessionStoreTest", lineNumber=25, suspendPolicy="all")
jdwp_resume()
```

### Step 5.2 — Feature 1: hashCode before mutation (line 23)

```
jdwp_evaluate_expression(threadId=<id>, expression="session.hashCode()", frameIndex=0)
jdwp_evaluate_expression(threadId=<id>, expression="store.retrieve(session) != null", frameIndex=0)
```
**Expected:** A hash value, then `Result: true`.

### Step 5.3 — Feature 6: toString on the internal map

```
jdwp_get_locals(threadId=<id>, frameIndex=0)
jdwp_get_fields(objectId=<store_id>)
```
Find the internal `sessions` HashMap object ID. Then:

```
jdwp_to_string(objectId=<sessions_map_id>)
```
**Expected:** A formatted map representation like `{io.mcp.jdwp.sandbox.session.UserSession@xxx=io.mcp.jdwp.sandbox.session.SessionData@yyy}`.

### Step 5.4 — Resume to second breakpoint (after upgrade)

```
jdwp_resume()
```

At line 25, after `upgradeUserRole`:

```
jdwp_evaluate_expression(threadId=<id>, expression="session.hashCode()", frameIndex=0)
jdwp_evaluate_expression(threadId=<id>, expression="store.retrieve(session) != null", frameIndex=0)
```
**Expected:** A **different** hash value, then `Result: false`. The mutation broke the lookup.

```
jdwp_to_string(objectId=<sessions_map_id>)
```
**Expected:** Still the same map content shown — but the key's hash bucket is now stale. This shows the discrepancy live.

```
jdwp_disconnect()
```

---

## Coverage Matrix

| Feature                                | Session(s) exercising it |
|----------------------------------------|--------------------------|
| 1. `jdwp_evaluate_expression`          | 1, 3, 4, 5               |
| 2. `jdwp_set_logpoint`                 | 1                        |
| 3. `jdwp_set_breakpoint` w/ condition  | 2                        |
| 4. `jdwp_set_exception_breakpoint`     | 4                        |
| 5. `jdwp_set_local` / `jdwp_set_field` | 2, 3                     |
| 6. `jdwp_to_string`                    | 1, 5                     |
| 7. Dynamic compiler version            | 1 (switch expression)    |
| 8. Event history                       | 2, 4                     |

---

## Known limitations / gotchas

- **Logpoint expressions** must compile against the target's classpath; first hit incurs the one-time classpath discovery cost (~1s).
- **`jdwp_set_local`** only supports primitives, `String`, and `null` — not arbitrary object construction. Setting a complex object requires a different approach (e.g., evaluate an expression that produces the value, then assign — not yet supported).
- **`jdwp_to_string`** requires a suspended thread. If `threadId` is omitted, falls back to the last breakpoint thread; if no breakpoint has been hit since connection, the call errors.
- **Conditional breakpoint evaluation errors** (e.g., a `NullPointerException` inside the condition expression) cause the thread to suspend (safe default) — the user sees the breakpoint hit and can investigate.
- **Exception breakpoints on bootstrap classes** (e.g., `java.lang.IllegalStateException`) are loaded lazily by the JVM, so `vm.classesByName()` returns empty at VMStart. The MCP server handles this automatically: it falls back to `vm.allClasses()` and then to a `Class.forName(name)` invocation in the target VM via a suspended thread. If no thread is yet suspended at a method-invocation event (e.g., right after `jdwp_connect`), the breakpoint is registered as deferred and will self-promote on the next `getVM()`-driven tool call after any breakpoint hit. **No manual workaround needed.**
