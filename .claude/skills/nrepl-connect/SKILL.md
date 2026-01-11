---
name: nrepl-connect
description: Connect to an nREPL server using the nrepl MCP tool on port 7888
---

# nREPL Connection Skill

Use the nrepl MCP to connect to the running nREPL server.

## Connection Details

- **Port**: 7888
- **MCP Tool**: nrepl

## Usage

When you need to evaluate Clojure code or interact with a running REPL, use the nrepl MCP tool to connect on port 7888.

### Connecting

Use the nrepl MCP's `connect` operation to establish a connection:

```
Port: 7888
```

### Evaluating Code

Once connected, you can evaluate Clojure expressions through the nrepl MCP. This allows you to:

- Test functions in the running system
- Inspect state
- Run queries against the database
- Debug issues interactively

## When to Use

- Testing Clojure code changes
- Debugging runtime issues
- Querying application state
- Running ad-hoc evaluations
- Verifying implementations work correctly
