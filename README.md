# MCP JDWP Inspector

MCP (Model Context Protocol) server for inspecting and **controlling** Java applications in real time via JDWP using JDI (Java Debug Interface).

Allows Claude Code to inspect the state of a JVM during execution AND control execution (resume, step over, step into, step out).

## Architecture

```
Claude Code
    ↓ (MCP Protocol via STDIO)
Spring Boot MCP Server
    ↓ (JDI - Java Debug Interface)
JDWP Protocol
    ↓
Tomcat/Application Java (port JVM_JDWP_PORT=5005)
```

**Configurable port:**
- `JVM_JDWP_PORT` (default: 5005) - JVM JDWP port

## Features

✅ **Complete inspection**
- Thread listing with status
- Full stack traces
- Local variables at each frame
- Object fields with recursive navigation

✅ **Smart collections**
- Optimized views for ArrayList, LinkedHashMap, HashSet
- Content display (elements, key=value entries)
- Array navigation

✅ **Execution control**
- Resume/Suspend threads
- Step Over, Step Into, Step Out
- Breakpoint management (set/clear/list)
- Full debugger control via AI

✅ **Event monitoring**
- Captures all JDWP events in real time
- Breakpoint detection
- Step, exception, and thread modification monitoring
- History of the last 100 events

✅ **Expression evaluation (Watchers)**
- Evaluation of arbitrary Java expressions at breakpoints
- Dynamic compilation with full classpath (571 entries)
- Support for strings, primitives, objects, and methods
- Compilation cache for performance
- Automatic proxy handling (Guice, CGLIB)

✅ **Recursive breakpoint protection**
- Expression evaluation is safe even when the expression re-enters the breakpointed line
- Recursive breakpoint / exception / step events are auto-resumed and recorded as `*_SUPPRESSED` entries in the event history instead of deadlocking the server
- See [Recursive breakpoint protection](#recursive-breakpoint-protection) below for details


## Prerequisites

- **JDK 17+** (with `tools.jar` for JDI)
- **Maven 3.8+** (included via wrapper)
- **Java application in JDWP debug mode**

## Installation

### 1. Build the project

```bash
cd mcp-jdwp-java
mvn clean package -DskipTests
```

This creates: `mcp-server/target/mcp-jdwp-java-1.0.0.jar`

### 2. Claude Code configuration

#### Option A: `claude mcp add` CLI (recommended)

Register the server once, globally (`-s user`), with generous startup and per-tool timeouts:

```bash
claude mcp add jdwp-inspector -s user \
  -e MCP_TIMEOUT=30000 \
  -e MCP_TOOL_TIMEOUT=120000 \
  -- java --add-modules jdk.jdi -jar /path/to/mcp-jdwp-java-1.0.0.jar
```

- `MCP_TIMEOUT` — server startup timeout in ms (default is too short for cold JVM starts)
- `MCP_TOOL_TIMEOUT` — per-tool-call timeout in ms (expression evaluation and classpath discovery can take a while on first call)
- `--add-modules jdk.jdi` is **required** at runtime for JDI to be visible
- Drop `-s user` to scope the registration to the current project only

To change the JVM port or other system properties, add them before `-jar`:

```bash
claude mcp add jdwp-inspector -s user \
  -e MCP_TIMEOUT=30000 \
  -e MCP_TOOL_TIMEOUT=120000 \
  -- java --add-modules jdk.jdi -DJVM_JDWP_PORT=12345 -jar /path/to/mcp-jdwp-java-1.0.0.jar
```

**Re-installing:** `claude mcp add` refuses to overwrite an existing entry. Remove it first:

```bash
claude mcp remove jdwp-inspector -s user
```

Or edit `~/.claude.json` directly under the `mcpServers` key.

#### Option B: Manual `.mcp.json`

At the root of your project:

```json
{
  "mcpServers": {
    "jdwp-inspector": {
      "command": "java",
      "args": [
        "--add-modules", "jdk.jdi",
        "-jar",
        "/path/to/mcp-jdwp-java-1.0.0.jar"
      ],
      "env": {
        "MCP_TIMEOUT": "30000",
        "MCP_TOOL_TIMEOUT": "120000"
      }
    }
  }
}
```

**Configurable parameters:**
- `-DJVM_JDWP_PORT` : Port where the JVM listens (default: 5005)

**Note:** Exception capture (caught/uncaught) and filters are configured dynamically via the `jdwp_configure_exception_monitoring` tool.

### 3. Restart Claude Code

To apply the new MCP configuration.

## Usage

### Step 1: Launch your Java application with JDWP enabled

**Option A: Maven Surefire (recommended for testing)**

Add the debug flag to your Maven test command:
```bash
mvn test -Dmaven.surefire.debug
```

This automatically starts the JVM with JDWP listening on port **5005** and suspends execution until a debugger connects. To use a different port or non-suspending mode:
```bash
mvn test -Dmaven.surefire.debug="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
```

**Option B: JVM argument (any Java application)**

Add the following VM option to your application launch configuration:
```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

The application starts normally and listens on port `JVM_JDWP_PORT=5005`.

### Step 2: Use the MCP JDWP Inspector in Claude Code

```
Me: "Connect to the inspector"

Claude: jdwp_connect()
→ Connects to JDWP on localhost:5005
→ Ready to inspect!

Me: "I have an active breakpoint, can you analyze the request?"

Claude:
1. Lists threads
2. Finds the suspended thread
3. Inspects the stack
4. Retrieves local variables
5. Navigates through objects
6. Analyzes the problem
```

## Available MCP tools (30)

### 1. `jdwp_connect`
Connect to the JDWP server using the configuration from `.mcp.json`.

**Parameters:** None (automatically uses the ports configured in `.mcp.json`)

**Behavior:**
- Automatically reads `JVM_JDWP_PORT` from system properties
- Connects to `localhost` on the configured port (default: 5005)

**Example:**
```
jdwp_connect()
```

### 2. `jdwp_disconnect`
Disconnect from the JDWP server.

**Note:** Sends a Dispose command to the JDWP server for a clean disconnection.

### 3. `jdwp_get_version`
Get information about the connected JVM.

**Returns:**
```
VM: OpenJDK 64-Bit Server VM
Version: 11.0.21
Description: Java Debug Interface...
```

### 4. `jdwp_get_threads`
List all JVM threads with their status.

**Returns:** For each thread:
- Unique ID
- Name
- Status (1=RUNNING, 4=WAITING, etc.)
- Suspended (true/false)
- Number of frames (if suspended)

**Example output:**
```
Found 42 threads:

Thread 0:
  ID: 1
  Name: main
  Status: 1
  Suspended: true
  Frames: 14

Thread 14:
  ID: 15
  Name: http-nio-8080-exec-1
  Status: 1
  Suspended: true
  Frames: 93  ← Thread with active breakpoint
```

### 5. `jdwp_get_stack`
Get the full call stack of a thread (must be suspended).

**Parameters:**
- `threadId` (long) : Thread ID (obtained via get_threads)

**Example:**
```
jdwp_get_stack(threadId=15)
```

**Returns:**
```
Stack trace for thread 15 (http-nio-8080-exec-1) - 93 frames:

Frame 0:
  at com.axelor.web.service.RestService.find(RestService.java:186)
Frame 1:
  at com.axelor.web.service.RestService$$EnhancerByGuice...
...
```

### 6. `jdwp_get_locals`
Get local variables of a specific frame.

**Parameters:**
- `threadId` (long) : Thread ID
- `frameIndex` (int) : Frame index (0 = current frame, 1 = caller, etc.)

**Example:**
```
jdwp_get_locals(threadId=15, frameIndex=0)
```

**Returns:**
```
Local variables in frame 0:

request (com.axelor.rpc.Request) = Object#26886 (com.axelor.rpc.Request)
```

All objects are automatically cached for later inspection.

### 7. `jdwp_get_fields`
Get the fields of an object (or the elements of a collection/array).

**Parameters:**
- `objectId` (long) : Object ID (obtained via get_locals or get_fields)

**Example:**
```
jdwp_get_fields(objectId=26886)  # request object
```

**Returns for an object:**
```
Object #26886 (com.axelor.rpc.Request):

int limit = 40
int offset = 0
java.util.List sortBy = Object#26935 (java.util.ArrayList)
java.util.Map data = Object#26936 (java.util.LinkedHashMap)
...
```

**Returns for an ArrayList:**
```
Object #26935 (java.util.ArrayList):

Size: 1

Elements:
  [0] = "-invoiceDate"

--- Internal fields ---
...
```

**Returns for a LinkedHashMap:**
```
Object #26936 (java.util.LinkedHashMap):

Size: 5

Entries:
  "_domain" = "self.operationTypeSelect = 3"
  "_domainContext" = Object#26951 (LinkedHashMap)
  "operator" = "and"
  "criteria" = Object#26959 (ArrayList)
...
```

**Returns for an array:**
```
Array #26944 (java.lang.Object[]) - 10 elements:

[0] = "-invoiceDate"
[1] = null
[2] = null
...
```

**Supported collections:**
- `ArrayList`, `LinkedList`
- `HashMap`, `LinkedHashMap`, `TreeMap`
- `HashSet`, `TreeSet`
- Arrays (Object[], int[], etc.)

### 9. `jdwp_resume`
Resume execution of all threads in the VM.

**Parameters:** None

**Example:**
```
jdwp_resume()
```

**Returns:**
```
All threads resumed
```

**Note:** Resumes all threads, equivalent to F8/Resume in IntelliJ.


### 12. `jdwp_step_over`
Execute the current line and stop at the next line (Step Over, equivalent to F6).

**Parameters:**
- `threadId` (long) : Thread ID (must be suspended)

**Example:**
```
jdwp_step_over(threadId=25)
```

**Returns:**
```
Step over executed on thread 25 (http-nio-8080-exec-10)
```

**Note:** The thread must be suspended. Creates a StepRequest and resumes the thread.

### 13. `jdwp_step_into`
Enter method calls (Step Into, equivalent to F7).

**Parameters:**
- `threadId` (long) : Thread ID (must be suspended)

**Example:**
```
jdwp_step_into(threadId=25)
```

**Returns:**
```
Step into executed on thread 25 (http-nio-8080-exec-10)
```

### 14. `jdwp_step_out`
Exit the current method (Step Out, equivalent to Shift+F8).

**Parameters:**
- `threadId` (long) : Thread ID (must be suspended)

**Example:**
```
jdwp_step_out(threadId=25)
```

**Returns:**
```
Step out executed on thread 25 (http-nio-8080-exec-10)
```

### 15. `jdwp_set_breakpoint`
Set a breakpoint at a specific line in a class.

**Parameters:**
- `className` (String) : Fully qualified class name (e.g.: "com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto")
- `lineNumber` (int) : Line number

**Example:**
```
jdwp_set_breakpoint(
  className="com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto",
  lineNumber=82
)
```

**Returns:**
```
Breakpoint set at com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto:82
```

**Note:** The class must be loaded and compiled with debug information (-g).

### 16. `jdwp_clear_breakpoint`
Remove a breakpoint from a specific line.

**Parameters:**
- `className` (String) : Fully qualified class name
- `lineNumber` (int) : Line number

**Example:**
```
jdwp_clear_breakpoint(
  className="com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto",
  lineNumber=82
)
```

**Returns:**
```
Removed 1 breakpoint(s) at com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto:82
```

### 17. `jdwp_list_breakpoints`
List all active breakpoints.

**Parameters:** None

**Example:**
```
jdwp_list_breakpoints()
```

**Returns:**
```
Active breakpoints: 2

Breakpoint 1:
  Class: com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto
  Method: save
  Line: 82
  Enabled: true

Breakpoint 2:
  Class: com.axelor.meta.MetaFiles
  Method: attach
  Line: 597
  Enabled: true
```

### 18. `jdwp_get_events`
Get recent JDWP events (breakpoints, steps, exceptions, etc.).

**Parameters:**
- `count` (Integer, optional) : Number of events to retrieve (default: all)

**Example:**
```
jdwp_get_events()           # All events
jdwp_get_events(count=10)   # The last 10
```

**Returns:**
```
Recent JDWP events (10):

[21:45:32] BREAKPOINT: Thread 25 at com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto.save:74
[21:45:28] STEP: Thread 25 at com.axelor.web.service.RestService.find:186
[21:45:20] BREAKPOINT: Thread 23 at com.axelor.meta.MetaFiles.attach:597
```

**Note:** The event listener runs in the background and captures **ALL** JDWP events.

**Captured event types:**
- `BREAKPOINT` : Thread stopped at a breakpoint
- `STEP` : Step over/into/out completed
- `EXCEPTION` : Exception thrown
- `THREAD_START/DEATH` : Thread creation/destruction
- `CLASS_PREPARE` : Class loaded
- `METHOD_ENTRY/EXIT` : Method entry/exit (if configured)

### 19. `jdwp_clear_events`
Clear the JDWP event history.

**Parameters:** None

**Example:**
```
jdwp_clear_events()
```

**Returns:**
```
Event history cleared
```

**Note:** Useful for clearing the history after a debug session or to focus on new events.

### 20. `jdwp_get_current_thread`
Get the thread ID of the last breakpoint hit.

**Parameters:** None

**Example:**
```
jdwp_get_current_thread()
```

**Returns:**
```
Current thread: http-nio-8080-exec-6 (ID=26456, suspended=true, frames=93, breakpoint=1)
```

**Note:** Uses the JDI event listener to automatically track the thread of the last breakpoint hit.

### 22. `jdwp_get_exception_config`
Get the current exception monitoring configuration.

**Parameters:** None

**Returns:**
```
Exception monitoring configuration:
- Capture caught exceptions: true
- Include packages: com.axelor,org.myapp
- Exclude classes: java.lang.NumberFormatException
```

### 23. `jdwp_clear_all_breakpoints`
Remove all breakpoints set by this MCP server.

**Parameters:** None

### 24. `jdwp_attach_watcher`
Attach a watcher to a breakpoint to evaluate a Java expression.

**Parameters:**
- `breakpointId` (int) : Breakpoint ID (from `jdwp_list_breakpoints`)
- `label` (String) : Watcher description
- `expression` (String) : Java expression to evaluate (e.g.: `request.getData()`)

**Example:**
```
jdwp_attach_watcher(
  breakpointId=27,
  label="Trace request data",
  expression="request.getData()"
)
```

**Returns:**
```
✓ Watcher attached successfully

  Watcher ID: 47e8090c-dc4a-4b03-a93a-068cd1b1e1ec
  Label: Trace request data
  Breakpoint: 27
  Expression: request.getData()
```

### 25. `jdwp_evaluate_watchers`
Evaluate the expressions of watchers attached to a breakpoint.

**Parameters:**
- `threadId` (long) : Suspended thread ID
- `scope` (String) : `"current_frame"` or `"full_stack"`
- `breakpointId` (Integer, optional) : Breakpoint ID for optimization

**Example:**
```
jdwp_evaluate_watchers(
  threadId=26162,
  scope="current_frame",
  breakpointId=27
)
```

**Returns:**
```
=== Watcher Evaluation for Thread 26162 ===

─── Current Frame #0: RestService:192 (Breakpoint ID: 27) ───

  • [47e8090c] Trace request data
    request.getData() = Object#33761 (java.util.LinkedHashMap)

  • [82632e7d] Test string
    "Hello World" = "Hello World"

Total: Evaluated 2 expression(s)
```

**Result format:**
- **Strings**: `"value"`
- **Primitives**: `42`, `true`
- **Objects**: `Object#ID (type)`

**Full documentation**: See [EXPRESSION_EVALUATION.md](EXPRESSION_EVALUATION.md)

### 26. `jdwp_detach_watcher`
Detach a watcher from a breakpoint.

**Parameters:**
- `watcherId` (String) : Watcher UUID (returned by `jdwp_attach_watcher`)

**Example:**
```
jdwp_detach_watcher(watcherId="47e8090c-dc4a-4b03-a93a-068cd1b1e1ec")
```

### 27. `jdwp_list_watchers_for_breakpoint`
List all watchers attached to a specific breakpoint.

**Parameters:**
- `breakpointId` (int) : Breakpoint ID

### 28. `jdwp_list_all_watchers`
List all active watchers on all breakpoints.

**Parameters:** None

**Returns:**
```
Active watchers: 3

Breakpoint 27 (RestService:192) - 2 watcher(s):
  • [47e8090c] Trace request data
    Expression: request.getData()

  • [82632e7d] Test string
    Expression: "Hello World"

Breakpoint 29 (AuctionService:45) - 1 watcher(s):
  • [9f3c2a1b] Check auction status
    Expression: auction.getStatus()
```

### 29. `jdwp_clear_all_watchers`
Remove all watchers from all breakpoints.

**Parameters:** None

### 30. `jdwp_inspect_stack` 🚀
_(Already documented above as tool #21)_

## Recursive breakpoint protection

When an expression evaluation at a breakpoint re-enters the breakpointed line — directly (e.g. `this.compute(n - 1)` evaluated while suspended inside `compute`), or indirectly via `toString()`, a `<clinit>`, or a classloader walk — JDI would re-suspend the very thread the outer `invokeMethod` is waiting on, and the MCP server would hang until the client times out. This is the same hazard IntelliJ IDEA's debugger handles by freezing breakpoint processing for the duration of an evaluation.

To prevent the deadlock, every MCP-driven `invokeMethod` chain is wrapped in a per-thread reentrancy guard keyed on `ThreadReference.uniqueID()`. While the firing thread is inside a guarded evaluation, the event listener short-circuits any `BreakpointEvent`, `ExceptionEvent`, or `StepEvent` that fires on that thread:

1. Auto-resumes the event set via `EventSet.resume()` — the target thread continues immediately, no user-facing stop.
2. Records a `BREAKPOINT_SUPPRESSED` / `EXCEPTION_SUPPRESSED` / `STEP_SUPPRESSED` entry in the event history (visible via `jdwp_get_events`).
3. Leaves `jdwp_get_current_thread` and the next-event latch untouched, so the user's outer breakpoint context is preserved.

The original breakpoint remains armed and will fire again on the next natural hit.

### Covered invocation sites

Every MCP tool path that calls JDI `invokeMethod` under the hood is guarded:

- `jdwp_evaluate_expression`, `jdwp_assert_expression`, `jdwp_evaluate_watchers`
- Logpoint and conditional-breakpoint expression evaluation (runs on the JDI listener thread)
- `jdwp_to_string` — the invoked `toString()` may hit a breakpoint
- Classpath discovery and local-JDK matching (walks the target VM's classloader graph via `invokeMethod`)
- Force-load of deferred breakpoint classes via `Class.forName` — the forced `<clinit>` may hit a breakpoint

### Test flight scenario

The `jdwp-sandbox` module ships a deterministic scenario in the `io.mcp.jdwp.sandbox.recursion` package ("Echo Chamber") that reproduces the recursive-breakpoint case from a real JVM.

```bash
# Terminal 1 — launch the sandbox JVM, paused, listening on port 5005
mvn -pl jdwp-sandbox test -Dtest=RecursiveCalculatorTest -DskipTests=false -Dmaven.surefire.debug

# From Claude Code (MCP):
# 1. jdwp_wait_for_attach()
# 2. jdwp_set_breakpoint("io.mcp.jdwp.sandbox.recursion.RecursiveCalculator", 22)
#    — line 22 is "int left = compute(n - 1);"
# 3. jdwp_resume_until_event()
#    — BP fires once inside compute(5)
# 4. jdwp_evaluate_expression(threadId, "this.compute(3)")
#    — returns 2 without deadlock
# 5. jdwp_get_events()
#    — shows two BREAKPOINT_SUPPRESSED entries (one per recursive hit in the Fib(3) call tree)
```

Pre-fix, step 4 hangs until the client times out. Post-fix, it returns `2` immediately and the event history explains exactly what was suppressed and why.

## Typical workflow

### Scenario 1: Debugging a REST request

```
1. In IntelliJ: Set a breakpoint in RestService.find()

2. In the browser/Postman: Trigger the request

3. In Claude Code:
   "I have an active breakpoint, can you analyze the request?"

4. Claude automatically uses:
   - jdwp_connect() → automatic connection with .mcp.json config
   - jdwp_get_threads() → finds thread 15 suspended
   - jdwp_get_stack(15) → views the full stack
   - jdwp_get_locals(15, 0) → finds request = Object#26886
   - jdwp_get_fields(26886) → views request.data, request.limit, etc.
   - jdwp_get_fields(26936) → navigates into the LinkedHashMap

   "The problem is that request.model is null while..."
```

### Scenario 2: Monitoring IntelliJ breakpoints

```
1. In IntelliJ: Set a breakpoint
2. In the browser: Trigger a request
3. IntelliJ stops at the breakpoint

4. In Claude Code:
   "Did you detect the breakpoint?"

5. Claude uses:
   - jdwp_get_events(count=5) → views the latest events

   "[21:45:32] BREAKPOINT: Thread 25 at DMSFileRepositoryVPAuto.save:74"

   - jdwp_get_stack(25) → analyzes the stack of the stopped thread
   - jdwp_get_locals(25, 0) → inspects the variables

   "Yes, thread 25 is stopped at DMSFileRepositoryVPAuto.save:74
    I see that the variable 'key' contains..."
```

**Note:** The event listener allows Claude Code to "see" what is happening in IntelliJ, creating a collaborative debugging experience between the IDE and the AI.

## Project structure

```
mcp-jdwp-java/
├── pom.xml                         # Parent POM (reactor)
├── mvnw / mvnw.cmd                 # Maven wrapper
├── .gitignore
├── README.md                       # Main documentation
├── WORKFLOW.md                     # Development and debugging guide
├── EXPRESSION_EVALUATION.md        # Watchers and evaluation documentation
│
├── mcp-server/                     # Module 1: the MCP server itself
│   ├── pom.xml
│   ├── src/main/
│   │   ├── java/io/mcp/jdwp/
│   │   │   ├── JDWPMcpServerApplication.java  # Main Spring Boot
│   │   │   ├── JDWPTools.java                 # Exposed MCP tools
│   │   │   ├── JDIConnectionService.java      # Persistent JDWP connection
│   │   │   ├── BreakpointTracker.java         # Breakpoint and current thread tracking
│   │   │   ├── JdiEventListener.java          # JDI event listener
│   │   │   │
│   │   │   ├── watchers/
│   │   │   │   ├── WatcherManager.java        # Watcher management
│   │   │   │   └── Watcher.java               # Watcher model
│   │   │   │
│   │   │   └── evaluation/
│   │   │       ├── JdiExpressionEvaluator.java    # Java expression evaluation
│   │   │       ├── RemoteCodeExecutor.java        # Execution in the target JVM
│   │   │       ├── InMemoryJavaCompiler.java      # Dynamic compilation (ECJ)
│   │   │       ├── ClasspathDiscoverer.java       # Classpath discovery
│   │   │       ├── JdkDiscoveryService.java       # Compatible local JDK detection
│   │   │       └── exceptions/
│   │   │           └── JdiEvaluationException.java
│   │   │
│   │   └── resources/
│   │       ├── application.properties      # Spring Boot config
│   │       └── logback-spring.xml         # Log configuration
│   │
│   └── target/
│       └── mcp-jdwp-java-1.0.0.jar         # Final fat JAR
│
└── jdwp-sandbox/                   # Module 2: deliberately broken JDWP test-flight scenarios
    ├── pom.xml                     # (sandbox tests are skipped by default)
    └── src/
        ├── main/java/io/mcp/jdwp/sandbox/  # bank, config, events, order, session
        └── test/java/io/mcp/jdwp/sandbox/  # expected-to-fail "test flight" tests
```

## Dependencies

- **Spring Boot 3.4.1** - Framework
- **Spring AI MCP 1.1.0-M3** - MCP integration
- **MCP Annotations 0.1.0** - @McpTool
- **JDI** (com.sun.jdi from tools.jar) - Java debug interface
- **Eclipse JDT Compiler (ECJ) 3.33.0** - Dynamic expression compilation
- **Lombok** - Annotations (@Slf4j for logging)

## Advantages

✅ **vs Python implementation:**
- No manual JDWP parsing
- Stable and documented API (JDI)
- Strong typing, fewer errors
- Native Java performance

✅ **vs traditional debugger:**
- AI-automated inspection
- Smart object navigation
- Contextual problem analysis
- No need to navigate manually

## Troubleshooting

### "tools.jar not found"
Verify that `JAVA_HOME` points to a **JDK** (not a JRE).

### "SocketAttach connector not found"
JDI is not available. Use a JDK with tools.jar.

### Connection refused
- Verify that Tomcat is running with `-agentlib:jdwp=...address=*:5005`
- Check the port in `.mcp.json` (`JVM_JDWP_PORT=5005`)

### The MCP server does not respond in Claude Code
- Rebuild: `mvn clean package -DskipTests`
- Check the path in `.mcp.json`
- Restart Claude Code

### "Thread is not suspended"
A thread must be stopped at a breakpoint for:
- `jdwp_get_stack`
- `jdwp_get_locals`
- `jdwp_invoke_method`

## Custom configuration

### Changing the ports

**1. Modify `.mcp.json`:**
```json
{
  "mcpServers": {
    "jdwp-inspector": {
      "command": "java",
      "args": [
        "--add-modules", "jdk.jdi",
        "-DJVM_JDWP_PORT=12345",
        "-jar",
        "C:/Users/nicolasv/MCP_servers/mcp-jdwp-java/mcp-server/target/mcp-jdwp-java-1.0.0.jar"
      ]
    }
  }
}
```

**2. Modify the application's VM Options:**
```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:12345
```

**3. Restart Claude Code to reload the configuration**

## Version

**1.2.0** - Complete version with Java expression evaluation
- **30 MCP tools** (8 inspection + 9 control + 4 events + 9 watchers)
- **Expression evaluation (NEW):**
  - Dynamic compilation of Java expressions at breakpoints
  - Automatic classpath discovery (571 entries)
  - Automatic local JDK discovery
  - Dynamic proxy support (Guice, CGLIB)
  - Compilation cache for performance
  - 9 watcher tools (attach/evaluate/detach/list/clear)
- **Event monitoring:**
  - Background event listener
  - Captures ALL JDWP events (even from IntelliJ)
  - History of the last 100 events
  - Types: Breakpoints, Steps, Exceptions, Threads, etc.
- **Execution control:**
  - Resume/Suspend threads
  - Step Over/Into/Out
  - Set/Clear/List breakpoints
- **Inspection:**
  - Unlimited recursive navigation (Remote Inspector Pattern ~50x faster)
  - Smart collections
  - Method invocation
- Persistent singleton cache
- Configurable port via `.mcp.json`
- Direct connection to the JVM (without proxy)
- JDI event listener for breakpoint tracking

## License

MIT
