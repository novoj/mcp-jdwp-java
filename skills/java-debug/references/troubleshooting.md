# Troubleshooting the JDWP MCP Server

## Tool returns an unexpected error or server seems stuck

1. Check the server log if your installation writes one — the JDWP MCP server logs JDI operations and errors there.
2. Reconnect the MCP server: run `/mcp` in Claude Code and reconnect `jdwp-inspector`. This spawns a fresh subprocess.

## Port 5005 is already in use

Find what holds the port:

```bash
ps -ef | grep "jdwp=transport"
```

Only kill a process you launched yourself in this session. If the process is unrecognized or was not started by you, **ask the user before killing it** — it may be a long-running service or another developer's debug session.

## Both processes gone after a crash

The test JVM exits when its debugger detaches; the MCP server may also have crashed. Reconnect the MCP server first (`/mcp`), then relaunch the test JVM from scratch.
