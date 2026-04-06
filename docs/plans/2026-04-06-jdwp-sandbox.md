# JDWP Debugging Sandbox — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Create a sandbox package with deliberately buggy Java classes and tests that fail in ways only diagnosable through JDWP debugging — requiring breakpoints, expression evaluation, variable inspection, and thread-aware debugging.

**Architecture:** A `sandbox` package under `src/main/java/io/mcp/jdwp/sandbox/` with 5 self-contained scenarios. Each scenario has a buggy production class and a JUnit test that exposes the bug. The tests are designed so that reading the code alone is insufficient to diagnose the root cause — an agent must attach a debugger, place breakpoints, and inspect runtime state. Each scenario targets a specific JDWP debugging skill.

**Tech Stack:** Java 17, JUnit 5, AssertJ, no additional dependencies

---

## Scenario Design Philosophy

Each bug is crafted so that:
1. **The test fails** with a non-obvious assertion error or incorrect result
2. **Static code reading is misleading** — the code *looks* correct on the surface
3. **Only runtime inspection reveals the root cause** — you must hit a breakpoint and evaluate expressions or inspect variables to understand what's actually happening
4. **A specific JDWP skill is exercised**: breakpoints, stepping, expression evaluation, variable inspection, or thread-aware debugging

---

## Scenario 1: "The Phantom Update" — Variable Mutation via Shared Reference

**JDWP skills required:** Place breakpoint, inspect local variables, evaluate expression on field

**Bug:** An `OrderProcessor` computes a discount, applies it to an `Order`, then a background `AuditLogger` "reads" the order for logging — but silently mutates the order's total via a shared mutable reference. The test asserts the final total after processing, which is wrong because the audit step corrupted it.

**Why static reading fails:** The `AuditLogger.log()` method looks like a pure read — it calls `order.getTotal()` and formats a string. But buried in a private helper method, it recalculates the total using integer division (truncating cents), then *sets* it back "for caching." The mutation is in a method named `cacheFormattedTotal()` which sounds read-only.

**To diagnose:** Place a breakpoint after `processor.process(order)` and before the test assertion. Inspect `order.total` — it's already wrong. Then place a breakpoint inside `AuditLogger.cacheFormattedTotal()` and see the mutation happening. Evaluate `order.getTotal()` before and after the audit call.

**Classes:**
- `sandbox/order/Order.java` — mutable POJO (id, items, total)
- `sandbox/order/OrderProcessor.java` — calculates total with discount
- `sandbox/order/AuditLogger.java` — "reads" order but secretly mutates total

**Test:** `OrderProcessorTest.shouldApplyDiscountCorrectly` — asserts expected total after processing. Fails because audit logger truncates cents.

---

## Scenario 2: "The Race That Hides" — Thread-Timing-Dependent State Corruption

**JDWP skills required:** Suspend specific thread, inspect variables on multiple threads, evaluate expressions while thread is suspended

**Bug:** A `BankAccount` class has `deposit()` and `getBalance()` methods that look thread-safe (they use `synchronized`). A `TransferService` transfers between two accounts by withdrawing from one and depositing to another. But the `transfer()` method only synchronizes each operation individually, not the pair — creating a window where an `AuditService` thread reads intermediate state (after withdrawal, before deposit) and caches a "snapshot" that later corrupts a balance reconciliation.

**Why static reading fails:** Each individual method IS synchronized. The bug is in the *interleaving* — the transfer method's two calls are not atomic. The `AuditService` thread runs between them. The class looks correct method-by-method. But the test calls `reconcile()` which uses the audit snapshot, not the live balance.

**To diagnose:** Suspend the transfer thread between `withdraw()` and `deposit()` calls. While suspended, inspect what the audit thread sees. Evaluate `auditService.getLastSnapshot()` to see the intermediate (wrong) balance. Resume and observe the final reconciliation mismatch.

**Classes:**
- `sandbox/bank/BankAccount.java` — synchronized deposit/withdraw/getBalance
- `sandbox/bank/TransferService.java` — non-atomic two-step transfer
- `sandbox/bank/AuditService.java` — snapshots balances between operations

**Test:** `TransferServiceTest.shouldMaintainTotalBalance` — deposits 1000 to each of 2 accounts, transfers 500 from A to B, then asserts `reconcile()` returns 2000. Fails because the audit snapshot captures the intermediate state.

---

## Scenario 3: "The Identity Crisis" — HashMap Key Mutation

**JDWP skills required:** Place breakpoint, inspect object fields, evaluate `hashCode()` expression, step through `HashMap.get()`

**Bug:** A `UserSession` object is used as a `HashMap` key. After insertion, a seemingly unrelated operation mutates a field that participates in `hashCode()`/`equals()`. Subsequent `map.get(session)` returns `null` even though the object is visibly in the map — because it's now in the wrong hash bucket.

**Why static reading fails:** The `UserSession` class has `hashCode`/`equals` based on `userId` and `role`. The `upgradeRole()` method changes the role, which is an expected business operation. The HashMap usage in `SessionStore` looks correct — put, then get. The connection between `upgradeRole()` and HashMap corruption isn't obvious from reading.

**To diagnose:** Place a breakpoint at the `map.get(session)` call. Evaluate `session.hashCode()` — it returns a DIFFERENT value than when the session was inserted. Evaluate `map.containsKey(session)` — returns false. But evaluate `map.values().stream().findFirst()` — the session IS in the map. The hash bucket mismatch is the root cause.

**Classes:**
- `sandbox/session/UserSession.java` — mutable key with hashCode/equals on mutable fields
- `sandbox/session/SessionStore.java` — HashMap<UserSession, SessionData>

**Test:** `SessionStoreTest.shouldRetrieveSessionAfterRoleUpgrade` — stores session, upgrades role, tries to retrieve. Returns null.

---

## Scenario 4: "The Lazy Initialization Trap" — Double-Checked Locking Gone Wrong

**JDWP skills required:** Suspend one thread during initialization, inspect partially-constructed object from another thread, evaluate field values

**Bug:** A `ConfigurationProvider` uses double-checked locking to lazily initialize a `Configuration` object. But the `Configuration` constructor sets fields in a specific order, and without `volatile`, Thread B can see a partially-constructed `Configuration` (non-null reference, but with default/zero field values). A `ServiceRunner` thread reads the config and uses a zero value where it expects a positive timeout.

**Why static reading fails:** The double-checked locking pattern looks textbook-correct (null check, synchronized, null check again). The `Configuration` class has final fields... except one field (`timeout`) which is set in a post-construction `init()` method called by the provider. The field appears initialized in every code path — but instruction reordering means Thread B can see the reference before `init()` completes.

**To diagnose:** Suspend the initializer thread INSIDE the synchronized block after `new Configuration()` but before `init()`. From a second thread, evaluate `provider.getConfig()` — it returns a non-null Configuration. Evaluate `provider.getConfig().getTimeout()` — it returns 0 (not the expected 5000). This proves the partially-constructed object is visible.

**Classes:**
- `sandbox/config/Configuration.java` — timeout field set via init() after construction
- `sandbox/config/ConfigurationProvider.java` — broken double-checked locking (missing volatile)
- `sandbox/config/ServiceRunner.java` — reads config, divides by timeout (ArithmeticException when 0)

**Test:** `ConfigurationProviderTest.shouldProvideFullyInitializedConfig` — two threads access the provider concurrently. One initializes, the other reads. The reader gets timeout=0 and division fails.

---

## Scenario 5: "The Silent Swallower" — Exception Eaten in Async Callback

**JDWP skills required:** Place breakpoint in catch block, inspect exception message and stack trace, evaluate expressions to trace the original cause chain

**Bug:** An `EventBus` dispatches events to handlers asynchronously. One handler (`InventoryHandler`) throws an exception when processing an "OrderPlaced" event, but the `EventBus` catches it and stores it in an `errors` list. However, the error is wrapped three levels deep (`CompletionException` → `EventHandlerException` → `IllegalStateException`), and the `EventBus.getErrors()` method returns only the top-level exception's message ("Async task failed") — which is useless. The test checks for zero errors, but there IS an error buried inside. Meanwhile, the inventory was never updated, causing a downstream `StockValidator` to report wrong stock levels.

**Why static reading fails:** The `EventBus.dispatch()` looks correct — it catches exceptions and stores them. `getErrors()` returns the list. The test checks `eventBus.getErrors().isEmpty()` which is FALSE — but the error message doesn't point to inventory. The `InventoryHandler` looks correct too — the bug is that it calls `inventory.reserve(quantity)` which throws when `quantity > available`, but the quantity comes from a field that was deserialized incorrectly (negative number interpreted as unsigned).

**To diagnose:** Place a breakpoint in the `EventBus` catch block. Inspect the caught exception. Evaluate `exception.getCause().getCause().getMessage()` to unwrap to the root cause. Then place a breakpoint in `InventoryHandler.handle()` and evaluate `event.getQuantity()` to see the wrong value. Trace back to `OrderEvent` construction to find the deserialization bug.

**Classes:**
- `sandbox/events/EventBus.java` — async dispatch with error swallowing
- `sandbox/events/OrderEvent.java` — event with quantity field
- `sandbox/events/InventoryHandler.java` — handler that throws on negative quantity
- `sandbox/events/Inventory.java` — stock tracking
- `sandbox/events/StockValidator.java` — checks stock consistency

**Test:** `EventBusTest.shouldDispatchAndUpdateInventory` — places an order event, then checks stock. Stock is wrong because the handler silently failed. The test sees errors in the bus but can't determine root cause from the top-level message.

---

## Implementation Tasks

### Task 1: Create Scenario 1 — The Phantom Update

**Files:**
- Create: `src/main/java/io/mcp/jdwp/sandbox/order/Order.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/order/OrderProcessor.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/order/AuditLogger.java`
- Create: `src/test/java/io/mcp/jdwp/sandbox/order/OrderProcessorTest.java`

**Step 1: Create Order.java**

A mutable POJO with id, item list (strings), and a total (double). Straightforward getters/setters.

**Step 2: Create OrderProcessor.java**

`process(Order order, double discountPercent)` — sums item prices from a hardcoded price map, applies discount, calls `order.setTotal(discountedTotal)`. Then calls `auditLogger.log(order)`.

**Step 3: Create AuditLogger.java**

`log(Order order)` — looks like a read operation. Internally calls `cacheFormattedTotal(order)` which recalculates using integer division (casting to int then back to double), then SETS the truncated value back: `order.setTotal((double)(int)(order.getTotal()))`. This silently drops cents.

**Step 4: Create OrderProcessorTest**

```java
@Test
void shouldApplyDiscountCorrectly() {
    Order order = new Order("ORD-1", List.of("Widget", "Gadget"));
    processor.process(order, 10.0); // 10% discount
    // Widget = 29.99, Gadget = 49.99 → subtotal = 79.98 → 10% off = 71.982
    assertThat(order.getTotal()).isEqualTo(71.982);
}
```

Fails because `AuditLogger.cacheFormattedTotal()` truncates 71.982 → 71.0.

**Step 5: Run test, verify it fails**

Run: `mvn test -o -pl . -Dtest=io.mcp.jdwp.sandbox.order.OrderProcessorTest`
Expected: FAIL — `expected: 71.982 but was: 71.0`

---

### Task 2: Create Scenario 2 — The Race That Hides

**Files:**
- Create: `src/main/java/io/mcp/jdwp/sandbox/bank/BankAccount.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/bank/TransferService.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/bank/AuditService.java`
- Create: `src/test/java/io/mcp/jdwp/sandbox/bank/TransferServiceTest.java`

**Step 1: Create BankAccount.java**

`synchronized deposit(int)`, `synchronized withdraw(int)`, `synchronized getBalance()`. Straightforward thread-safe account.

**Step 2: Create TransferService.java**

`transfer(BankAccount from, BankAccount to, int amount)` — calls `from.withdraw(amount)` then `to.deposit(amount)`. NOT atomic. Between these two calls, notifies the `AuditService` via `auditService.snapshotBalances(from, to)`.

**Step 3: Create AuditService.java**

`snapshotBalances(BankAccount a, BankAccount b)` — stores `a.getBalance() + b.getBalance()` as `lastTotalSnapshot`. `reconcile(BankAccount a, BankAccount b)` returns the DIFFERENCE between current total and snapshot — should be 0 if consistent, but snapshot was taken mid-transfer.

**Step 4: Create TransferServiceTest**

```java
@Test
void shouldMaintainTotalBalance() {
    BankAccount a = new BankAccount(1000);
    BankAccount b = new BankAccount(1000);
    transferService.transfer(a, b, 500);
    int discrepancy = auditService.reconcile(a, b);
    assertThat(discrepancy).isZero(); // Fails: snapshot captured mid-transfer
}
```

Fails because snapshot sees (500 + 1000 = 1500) during transfer, final total is 2000, discrepancy = 500.

**Step 5: Run test, verify it fails**

---

### Task 3: Create Scenario 3 — The Identity Crisis

**Files:**
- Create: `src/main/java/io/mcp/jdwp/sandbox/session/UserSession.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/session/SessionData.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/session/SessionStore.java`
- Create: `src/test/java/io/mcp/jdwp/sandbox/session/SessionStoreTest.java`

**Step 1: Create UserSession.java**

Fields: `userId` (String), `role` (String). `hashCode()`/`equals()` use BOTH fields. `upgradeRole(String newRole)` mutates role.

**Step 2: Create SessionData.java**

Simple data holder: `loginTime` (long), `lastAccess` (long).

**Step 3: Create SessionStore.java**

Wraps `HashMap<UserSession, SessionData>`. `store(UserSession, SessionData)`, `retrieve(UserSession)`, `upgradeUserRole(UserSession, String)` — calls `session.upgradeRole(newRole)`.

**Step 4: Create SessionStoreTest**

```java
@Test
void shouldRetrieveSessionAfterRoleUpgrade() {
    UserSession session = new UserSession("user-42", "BASIC");
    SessionData data = new SessionData(System.currentTimeMillis(), System.currentTimeMillis());
    store.store(session, data);

    store.upgradeUserRole(session, "PREMIUM");

    SessionData retrieved = store.retrieve(session);
    assertThat(retrieved).isNotNull(); // Fails: HashMap can't find it
}
```

Fails because `hashCode` changed after role mutation, so `HashMap.get()` looks in the wrong bucket.

**Step 5: Run test, verify it fails**

---

### Task 4: Create Scenario 4 — The Lazy Initialization Trap

**Files:**
- Create: `src/main/java/io/mcp/jdwp/sandbox/config/Configuration.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/config/ConfigurationProvider.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/config/ServiceRunner.java`
- Create: `src/test/java/io/mcp/jdwp/sandbox/config/ConfigurationProviderTest.java`

**Step 1: Create Configuration.java**

Fields: `appName` (String, set in constructor), `timeout` (int, set via `init()` method — NOT in constructor). `getTimeout()` returns the field.

**Step 2: Create ConfigurationProvider.java**

Broken double-checked locking: `instance` is NOT volatile. `getConfig()` does null check → synchronized → null check → `new Configuration("MyApp")` → `instance.init(5000)` → assign to `instance`. The `init()` call happens AFTER assignment to `instance` (deliberately — the assignment is done before init, simulating instruction reordering).

Actually, to make this deterministic in a test (real JMM reordering is non-deterministic), we'll simulate the bug differently: the provider assigns `instance` BEFORE calling `init()`, and a CountDownLatch allows the reader thread to read between assignment and init.

**Step 3: Create ServiceRunner.java**

`run(ConfigurationProvider provider)` — calls `provider.getConfig().getTimeout()`, divides `10000 / timeout` to compute a retry count. When timeout is 0, throws `ArithmeticException`.

**Step 4: Create ConfigurationProviderTest**

```java
@Test
void shouldProvideFullyInitializedConfig() throws Exception {
    ConfigurationProvider provider = new ConfigurationProvider();
    AtomicReference<Integer> timeout = new AtomicReference<>();

    // Reader thread
    Thread reader = new Thread(() -> {
        Configuration config = provider.getConfig();
        timeout.set(config.getTimeout());
    });

    // Provider exposes the partially-constructed object
    reader.start();
    reader.join(2000);

    assertThat(timeout.get())
        .describedAs("Timeout should be fully initialized")
        .isEqualTo(5000); // Fails: gets 0
}
```

**Step 5: Run test, verify it fails**

---

### Task 5: Create Scenario 5 — The Silent Swallower

**Files:**
- Create: `src/main/java/io/mcp/jdwp/sandbox/events/EventBus.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/events/OrderEvent.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/events/EventHandler.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/events/EventHandlerException.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/events/InventoryHandler.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/events/Inventory.java`
- Create: `src/test/java/io/mcp/jdwp/sandbox/events/EventBusTest.java`

**Step 1: Create supporting classes**

- `OrderEvent`: `orderId` (String), `productId` (String), `quantity` (int). Constructor takes a "raw quantity" that's cast from byte (simulating deserialization): `this.quantity = (byte) rawQuantity`. For rawQuantity = 200, this overflows to -56.
- `EventHandler`: interface with `handle(OrderEvent)` throwing `EventHandlerException`
- `EventHandlerException`: extends Exception, wraps a cause
- `Inventory`: `available` map (String → int), `reserve(String productId, int qty)` throws `IllegalStateException` if qty < 0
- `InventoryHandler`: implements EventHandler, calls `inventory.reserve(event.getProductId(), event.getQuantity())`

**Step 2: Create EventBus.java**

`dispatch(OrderEvent event)` — iterates handlers, calls each in try-catch. Catches Exception, wraps in `CompletionException("Async task failed", new EventHandlerException("Handler failed", e))`, adds to `errors` list. `getErrors()` returns the list. `getErrorSummary()` returns only the top-level message: `"Async task failed"` — useless for diagnosis.

**Step 3: Create EventBusTest**

```java
@Test
void shouldDispatchAndUpdateInventory() {
    inventory.restock("WIDGET", 100);
    OrderEvent event = new OrderEvent("ORD-1", "WIDGET", 200); // looks like qty=200

    eventBus.dispatch(event);

    // Expect stock to be reduced
    assertThat(inventory.getStock("WIDGET"))
        .describedAs("Stock should be 100 - 200 = -100... or should it?")
        .isLessThan(100); // Fails: stock is still 100 (handler threw, silently)

    // Even checking errors doesn't help much
    assertThat(eventBus.getErrorSummary()).isEmpty(); // Also fails: there IS an error
}
```

The test has two assertions that both tell a confusing story. The stock didn't change (handler failed), and there IS an error but its message is "Async task failed" — meaningless. Only by setting a breakpoint in the catch block and evaluating `exception.getCause().getCause().getMessage()` do you find `"Cannot reserve negative quantity: -56 for product WIDGET"`, revealing the byte-overflow deserialization bug.

**Step 5: Run test, verify it fails**

---

### Task 6: Create package-info.java and README

**Files:**
- Create: `src/main/java/io/mcp/jdwp/sandbox/package-info.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/order/package-info.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/bank/package-info.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/session/package-info.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/config/package-info.java`
- Create: `src/main/java/io/mcp/jdwp/sandbox/events/package-info.java`

Each with a one-line description. No README file unless requested.

---

### Task 7: Verify all tests fail as expected

Run: `mvn test -o -Dtest="io.mcp.jdwp.sandbox.**"`
Expected: 5 test failures, one per scenario. The existing 63 tests must still pass.

---

## Debugging Cheat Sheet (for the agent)

| Scenario | Key Breakpoint Location | Expression to Evaluate | What It Reveals |
|---|---|---|---|
| 1. Phantom Update | After `auditLogger.log(order)` | `order.getTotal()` | Total was truncated from 71.982 to 71.0 |
| 2. Race That Hides | Inside `transfer()` between withdraw/deposit | `auditService.getLastSnapshot()` | Snapshot = 1500 (mid-transfer), not 2000 |
| 3. Identity Crisis | At `map.get(session)` in retrieve() | `session.hashCode()` vs stored hash | Hash changed after role upgrade |
| 4. Lazy Init Trap | Between `instance = new Config()` and `init()` | `provider.getConfig().getTimeout()` from reader thread | Returns 0 (partially constructed) |
| 5. Silent Swallower | In EventBus catch block | `e.getCause().getCause().getMessage()` | "Cannot reserve negative quantity: -56" |
