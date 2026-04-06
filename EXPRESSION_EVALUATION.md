# Java Expression Evaluation via JDWP

## Overview

The JDWP MCP server allows evaluating arbitrary Java expressions in the context of a thread suspended at a breakpoint. This feature uses **JDI (Java Debug Interface)** to compile, inject, and execute code dynamically in the target JVM.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ 1. User attaches a watcher to a breakpoint                 │
│    Expression: "request.getData()"                          │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. Breakpoint triggered → Thread suspended                 │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. JdiExpressionEvaluator.evaluate()                        │
│    • Analyzes the stack frame (local variables + 'this')    │
│    • Generates wrapper code with a unique UUID              │
│    • Compiles with EclipseCompiler + discovered classpath   │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. RemoteCodeExecutor.execute()                             │
│    • Injects bytecode via ClassLoader.defineClass()         │
│    • Forces initialization with Class.forName()             │
│    • Invokes static method evaluate()                       │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│ 5. Result returned and formatted                            │
│    • Strings: "value"                                       │
│    • Primitives: 42, true                                   │
│    • Objects: Object#12345 (java.util.HashMap)              │
└─────────────────────────────────────────────────────────────┘
```

## Main Components

### 1. JdiExpressionEvaluator

**Role**: Main orchestrator for expression evaluation.

**File**: `src/main/java/io/mcp/jdwp/evaluation/JdiExpressionEvaluator.java`

**Responsibilities**:
- Extract the execution context (local variables, `this`)
- Generate the wrapper source code
- Manage compilation (with cache)
- Delegate execution to `RemoteCodeExecutor`

**Example of generated code**:
```java
package mcp.jdi.evaluation;

// Unique UUID to avoid class name collisions
public class ExpressionEvaluator_a1b2c3d4e5f6... {
    public static Object evaluate(RestService _this, Request request) {
        // User expression
        return (Object) (request.getData());
    }
}
```

**Key points**:
- **UUID in class name**: Avoids `LinkageError` when reloading the MCP server
- **Replacement of `this`**: The user expression uses `this`, but the parameter is `_this`
- **Compilation cache**: Avoids recompiling the same expression

### 2. InMemoryJavaCompiler

**Role**: Compiles Java code to bytecode without writing to disk.

**File**: `src/main/java/io/mcp/jdwp/evaluation/InMemoryJavaCompiler.java`

**Compiler used**: **Eclipse JDT Core Compiler** (no JDK needed at runtime)

**Configuration**:
- **JDK path**: Dynamically discovered via `JdkDiscoveryService`
- **Classpath**: 571 entries discovered via classloader exploration (Tomcat)
- **Options**: `-source 1.8 -target 1.8 -g --system <jdkPath>`

**Why temporary files?**
The Eclipse JDT compiler requires real files (not in-memory) to read the source via `JavaFileObject`. The files are created in `/tmp/mcp-compiler-*` and cleaned up automatically.

**`--system` option**:
Allows the compiler to resolve JDK classes (`java.lang.Object`, etc.) by pointing to the local JDK matching the target JVM version.

### 3. RemoteCodeExecutor

**Role**: Injects and executes the compiled bytecode in the target JVM.

**File**: `src/main/java/io/mcp/jdwp/evaluation/RemoteCodeExecutor.java`

**Execution steps**:

#### Step 1: Bytecode injection
```java
ClassLoader.defineClass(String name, byte[] b, int off, int len)
```
Loads the class into the target classloader, but **does not prepare it yet**.

#### Step 2: Forced initialization -- CRITICAL
```java
Class.forName(className, true, classLoader)
```

**Why is this necessary?**
- `defineClass()` loads the bytes but does not trigger class preparation
- Without preparation, `methodsByName()` throws `ClassNotPreparedException`
- `Class.forName()` forces the JVM to prepare and initialize the class

**This solution was identified after testing:**
- `allMethods()` -> Access to all inherited methods (inefficient)
- `Class.forName()` -> Forces preparation (standard and robust solution)

#### Step 3: Method invocation
```java
ClassType.invokeMethod(thread, method, args, INVOKE_SINGLE_THREADED)
```

**`INVOKE_SINGLE_THREADED` flag**:
- Requires the thread to be suspended at a "safepoint" (breakpoint)
- Avoids concurrency issues in the target VM

### 4. ClasspathDiscoverer

**Role**: Discovers the full application classpath (dynamically loaded JARs).

**File**: `src/main/java/io/mcp/jdwp/evaluation/ClasspathDiscoverer.java`

**Why is this necessary?**
In Tomcat, `System.getProperty("java.class.path")` returns only 2 JARs (bootstrap). The 500+ application JARs are dynamically loaded via `WebappClassLoader`.

**Exploration method**:
```java
// 1. Get the thread's context classloader
ClassLoaderReference contextCL = thread.getContextClassLoader();

// 2. Walk up the classloader hierarchy
while (currentCL != null) {
    if (currentCL instanceof URLClassLoader) {
        // 3. Extract URLs via getURLs()
        URL[] urls = currentCL.getURLs();
        // Add to classpath
    }
    currentCL = currentCL.getParent();
}
```

**Result**: 571 classpath entries discovered
- 535 JARs from `ParallelWebappClassLoader` (Tomcat)
- 34 JARs from `URLClassLoader` (system)
- 2 initial entries from `java.class.path`

### 5. JdkDiscoveryService

**Role**: Finds the local JDK matching the target JVM version.

**File**: `src/main/java/io/mcp/jdwp/evaluation/JdkDiscoveryService.java`

**Discovery steps**:
1. Retrieve `java.version` and `java.home` from the target JVM via JDI
2. Check if `java.home` is locally accessible (same machine)
3. Search in standard locations:
   - `C:\Program Files\Eclipse Adoptium\jdk-*`
   - `C:\Program Files\Java\jdk-*`
   - `JAVA_HOME` environment variable

**JDK validation**:
- Java 9+: Presence of the `jmods/` directory or `lib/jrt-fs.jar`
- Java 8: Presence of `lib/rt.jar`

**On failure**:
Throws a `JdkNotFoundException` with installation instructions.

## Resolved Issues

### Issue 1: LinkageError on class names

**Symptom**:
```
java.lang.LinkageError: duplicate class definition: mcp.jdi.evaluation.ExpressionEvaluator_0
```

**Cause**:
The class counter (`AtomicLong`) resets to 0 on each MCP server restart, but the previous classes remain in the target JVM.

**Solution**:
Use a **UUID** instead of a counter:
```java
String uniqueId = UUID.randomUUID().toString().replace("-", "");
String className = "mcp.jdi.evaluation.ExpressionEvaluator_" + uniqueId;
```

### Issue 2: ClassNotPreparedException

**Symptom**:
```
com.sun.jdi.ClassNotPreparedException
    at ReferenceTypeImpl.methodsByName(ReferenceTypeImpl.java:570)
```

**Cause**:
`defineClass()` loads the bytecode but does not prepare the class. The `methodsByName()` method requires the class to be in the "prepared" state.

**Solution**:
Force initialization with `Class.forName()`:
```java
ClassType classClass = (ClassType) vm.classesByName("java.lang.Class").get(0);
Method forNameMethod = classClass.methodsByName(
    "forName",
    "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"
).get(0);

StringReference classNameRef = vm.mirrorOf(className);
BooleanValue initializeRef = vm.mirrorOf(true);
List<Value> args = List.of(classNameRef, initializeRef, classLoader);

classClass.invokeMethod(thread, forNameMethod, args, INVOKE_SINGLE_THREADED);
```

This approach is **robust** because it relies on the JVM's standard mechanism for the class lifecycle.

### Issue 3: Dynamic proxies (Guice, CGLIB)

**Symptom**:
```
Compilation failed: RestService$EnhancerByGuice$110706492 cannot be resolved to a type
```

**Cause**:
`thisObject.type().name()` returns the runtime proxy name (`RestService$EnhancerByGuice$...`), not the declared class.

**Solution**:
Extract the base class by walking up the class hierarchy:
```java
private String getDeclaredType(ReferenceType type) {
    String typeName = type.name();

    // Detect proxies (contain $$)
    if (typeName.contains("$$")) {
        if (type instanceof ClassType) {
            ClassType superclass = ((ClassType) type).superclass();
            if (superclass != null && !superclass.name().equals("java.lang.Object")) {
                return getDeclaredType(superclass); // Recursive
            }
        }

        // Fallback: extract name before $$
        return typeName.substring(0, typeName.indexOf("$$"));
    }

    return typeName;
}
```

### Issue 4: JDK class resolution

**Symptom**:
```
The type java.lang.Object cannot be resolved
```

**Cause**:
The compiler cannot find the base JDK classes.

**Solution**:
Eclipse compiler `--system <jdkPath>` option:
```java
options.addAll(Arrays.asList("--system", this.jdkPath));
```

Points to the dynamically discovered local JDK, allowing the compiler to resolve all system classes.

## Using Watchers

### Attaching a watcher

```bash
jdwp_attach_watcher(
    breakpointId=27,
    label="Test request data",
    expression="request.getData()"
)
```

**Parameters**:
- `breakpointId`: Breakpoint ID (obtained via `jdwp_list_breakpoints`)
- `label`: Human-readable description of the watcher
- `expression`: Java expression to evaluate

### Evaluating watchers

```bash
jdwp_evaluate_watchers(
    threadId=26162,
    scope="current_frame",
    breakpointId=27  # Optional but recommended for performance
)
```

**Parameters**:
- `threadId`: Thread ID (obtained via `jdwp_get_current_thread`)
- `scope`:
  - `"current_frame"`: Evaluates only in the current frame
  - `"full_stack"`: Searches for the breakpoint across the entire stack
- `breakpointId`: Optimization to avoid traversing the entire stack

### Result format

**Strings**:
```
"Hello World" = "Hello World"
```

**Primitives**:
```
42 = 42
true = true
```

**Objects**:
```
request.getData() = Object#33761 (java.util.LinkedHashMap)
```

The object is cached and can be inspected with `jdwp_get_fields(33761)`.

**Arrays**:
```
items = Array#12345 (java.lang.String[10])
```

## Constraints and Limitations

### 1. Thread must be suspended

Evaluation requires a thread suspended at a breakpoint because:
- `INVOKE_SINGLE_THREADED` requires a "safepoint"
- Local variables are only accessible when the thread is stopped

### 2. No nested JDI calls

**Critical**: Never call `discoverClasspath()` or other JDI methods during an evaluation.

**Why?**
JDI invocations can trigger other JDI calls, causing deadlocks or `IncompatibleThreadStateException`.

**Solution**:
Configure the classpath **before** evaluation:
```java
expressionEvaluator.configureCompilerClasspath(suspendedThread);
Value result = expressionEvaluator.evaluate(frame, expression);
```

### 3. Expressions limited to context

Expressions have access only to:
- Local variables visible in the frame
- `this` (if available)
- Classes from the discovered classpath

**No access to**:
- Variables from other threads
- Private methods via reflection (possible but not implemented)

### 4. No persistent side effects

Injected classes are loaded into the target classloader but:
- Exist only for the duration of the evaluation
- Cannot permanently modify the application state
- Static initializers (`<clinit>`) are executed only once

## Performance

### Typical evaluation time

**First evaluation** (with classpath discovery):
```
JDK discovery:        ~140ms
Classpath discovery:  ~850ms
Compilation:          ~1900ms
Injection + execution: ~750ms
──────────────────────────────
Total:                ~3640ms
```

**Subsequent evaluations** (cache enabled):
```
Compilation (cache hit): ~0ms
Injection + execution:   ~750ms
─────────────────────────────
Total:                   ~750ms
```

### Optimizations

**Compilation cache**:
Cache key = `context signature + expression`
```java
String cacheKey = context.getSignature() + "###" + expression;
// Signature = "RestService _this,Request request"
```

**No recompilation** if:
- Same expression
- Same parameter types
- Same parameter order

**Cache invalidated** if:
- Different expression
- Different parameter types (e.g., proxy vs base class)

## Log Files

All logs are in `C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\mcp-jdwp-inspector.log`

### Useful logs for debugging

**Classpath discovery**:
```
[JDI] Discovering full classpath using breakpoint thread 'http-nio-8080-exec-3'
[JDK Discovery] ✓ Found matching JDK: C:\Program Files\Eclipse Adoptium\jdk-11.0.21.9-hotspot
[Discoverer] Application classpath discovered in 629ms (571 entries)
```

**Compilation**:
```
[Compiler] Configured with JDK at C:\...\jdk-11.0.21.9-hotspot and 571 classpath entries
[Compiler] Compilation successful in 1934ms (736 bytes generated for 1 class(es))
```

**Execution**:
```
[Executor] Loading class mcp.jdi.evaluation.ExpressionEvaluator_a1b2c3d4...
[Executor] Forcing initialization of class mcp.jdi.evaluation.ExpressionEvaluator_a1b2c3d4...
[Executor] Class initialization completed
[Executor] Found static method evaluate
[Executor] Remote method invoked successfully in 756ms, returned: java.util.LinkedHashMap
```

## Tests and Validation

### Basic test

```java
// Simple expression
"Hello World"
// Expected result: "Hello World"

// Primitive
42 + 10
// Expected result: 52

// Local variable
request
// Expected result: Object#12345 (com.axelor.rpc.Request)
```

### Test with methods

```java
// Method call
request.getData()
// Expected result: Object#33761 (java.util.LinkedHashMap)

// Navigation
request.getData().size()
// Expected result: 5
```

### Test with 'this'

```java
// Using 'this'
this.getClass().getName()
// Expected result: "com.axelor.web.service.RestService"
```

## References

- [JDI Specification](https://docs.oracle.com/en/java/javase/11/docs/api/jdk.jdi/com/sun/jdi/package-summary.html)
- [Eclipse JDT Core Compiler](https://www.eclipse.org/jdt/core/)
- [Java Class Loading](https://docs.oracle.com/javase/specs/jvms/se11/html/jvms-5.html)
- [JDWP Protocol](https://docs.oracle.com/en/java/javase/11/docs/specs/jdwp/jdwp-protocol.html)
