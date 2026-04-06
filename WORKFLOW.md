# Development Workflow - MCP JDWP Inspector

## Stack Architecture

```
Claude Code (MCP Client)
    ↓
MCP Server (mcp-jdwp-java) - Build: Maven
    ↓ (JDI - Java Debug Interface)
JVM JDWP (Tomcat Application, port JVM_JDWP_PORT=5005)
```

**Component:**
- **MCP Server**: `C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\` (Maven, Spring Boot)

## CRITICAL RULE BEFORE ANY MODIFICATION

**BEFORE starting to code, ALWAYS follow these steps:**

### Step 1: Disable the MCP Server (ALWAYS)
1. **Ask the user** to disable the MCP server via `/mcp`
2. Wait for confirmation before continuing

### Step 2: Code
Now you can start modifying the code

### Why is this critical?
- The MCP server caches the JAR in memory
- Modifications will NOT be visible without a restart
- This avoids hours of unnecessary debugging

## Complete Workflow

### Modifying the MCP Server

```bash
# Step 1: Build the MCP server
cd C:\Users\nicolasv\MCP_servers\mcp-jdwp-java
mvn clean package -DskipTests

# Step 2: Verify the build succeeded
# Look for "BUILD SUCCESS" in the output
powershell -Command "Get-Item C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\target\mcp-jdwp-java-1.0.0.jar | Select-Object Name, Length, LastWriteTime"

# Step 3: Restart the MCP server in Claude Code
# In Claude Code: /mcp (disable)
# In Claude Code: /mcp (re-enable)
# The new JAR will be loaded automatically
```

## Diagnostic Commands

### Check if the target JVM is running with JDWP
```bash
netstat -ano | findstr :5005
# If a line with LISTENING appears -> JVM accessible
```

### Check the logs

MCP server log file: `C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\mcp-jdwp-inspector.log`

```bash
type C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\mcp-jdwp-inspector.log
```

**MCP log contents:**
- Classpath discovery messages (`[JDI] Discovering classpath...`)
- Expression compilation (`[Compiler] Compilation successful...`)
- Expression evaluation (`[Evaluator] Compiler classpath configured...`)
- JDI breakpoint/step events (`[JDI] Breakpoint X hit on thread Y...`)
- All exceptions with complete stack traces

## Using MCP JDWP Tools

### Stack Inspection Workflow

**IMPORTANT**: Always use `jdwp_get_current_thread()` before `jdwp_get_stack()` to inspect the thread at the current breakpoint:

```
1. Trigger a breakpoint in the application
2. Call jdwp_get_current_thread() to get the threadId
3. Use the returned threadId to call jdwp_get_stack(threadId)
```

### Available Tools

#### Connection and Navigation
- **jdwp_connect** - Connect to the target JVM
- **jdwp_get_current_thread** - Get the threadId of the current breakpoint
- **jdwp_get_threads** - List all threads

#### Inspection
- **jdwp_get_stack(threadId)** - Get the stack frames
- **jdwp_get_locals(threadId, frameIndex)** - Get the local variables of a frame
- **jdwp_get_fields(objectId)** - Get the fields of an object

#### Breakpoints and Execution
- **jdwp_set_breakpoint(className, lineNumber)** - Set a breakpoint (returns an ID)
- **jdwp_list_breakpoints** - List all breakpoints
- **jdwp_clear_breakpoint_by_id(breakpointId)** - Remove a breakpoint
- **jdwp_clear_all_breakpoints** - Remove all breakpoints
- **jdwp_resume** - Resume execution of all threads
- **jdwp_suspend_thread(threadId)** - Suspend a specific thread
- **jdwp_resume_thread(threadId)** - Resume a specific thread

#### Expression Evaluation (Watchers)
- **jdwp_attach_watcher(breakpointId, label, expression)** - Attach a watcher to a breakpoint
- **jdwp_evaluate_watchers(threadId, scope, breakpointId)** - Evaluate watcher expressions
- **jdwp_list_all_watchers()** - List all active watchers
- **jdwp_detach_watcher(watcherId)** - Detach a watcher

### Expression Evaluation

The MCP server allows evaluating arbitrary Java expressions in the context of a thread suspended at a breakpoint.

**Complete documentation**: See [EXPRESSION_EVALUATION.md](EXPRESSION_EVALUATION.md)

**Typical workflow**:
```
1. Set a breakpoint: jdwp_set_breakpoint("com.example.MyClass", 42) -> ID: 1
2. Trigger the breakpoint
3. Get the threadId: jdwp_get_current_thread()
4. Attach a watcher: jdwp_attach_watcher(breakpointId=1, label="Test", expression="request.getData()")
5. Evaluate: jdwp_evaluate_watchers(threadId=26162, scope="current_frame", breakpointId=1)
```

**Expression examples**:
```java
// Strings
"Hello World"                    -> "Hello World"

// Primitives
42 + 10                          -> 52

// Local variables
request.getData()                -> Object#33761 (java.util.LinkedHashMap)

// Navigation
request.getData().size()         -> 5

// Using 'this'
this.getClass().getName()        -> "com.axelor.web.service.RestService"
```

## Checklist Before Testing

- [ ] The MCP server has been built ("BUILD SUCCESS" in Maven)
- [ ] The MCP server has been restarted in Claude Code (`/mcp` disable + re-enable)
- [ ] The target JVM is running with JDWP on port 5005 (`netstat -ano | findstr :5005`)

## Port Used

| Port  | Service                    | Verification                     |
|-------|----------------------------|----------------------------------|
| 5005 | JVM JDWP                   | `netstat -ano \| findstr :5005` |

## Important Notes

1. **The MCP server loads the JAR at startup**: JAR modifications are only taken into account after restarting the MCP server via `/mcp`.

2. **Code modifications require a rebuild**: Java does not allow hot-reload for this type of application.

3. **Always check the logs**: In case of issues, consult `mcp-jdwp-inspector.log` in the MCP server directory.

4. **Presenting code to the user**: Always use markdown code blocks to display code.

5. **Exception handling**: ALWAYS use a logger (SLF4J) in ALL catch blocks to trace exceptions in log files.

```java
// INCORRECT - Returns the error but does not trace it in the logs
} catch (Exception e) {
    return "ERROR: " + e.getMessage();
}

// CORRECT - Log the exception before returning the error
} catch (Exception e) {
    log.error("[JDWP] Error description", e);
    return "ERROR: " + e.getMessage();
}
```
