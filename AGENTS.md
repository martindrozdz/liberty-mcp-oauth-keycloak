# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Goal

Get OAuth to work end-to-end with **IBM Bob GUI**

## Project

Jakarta EE 10 / Open Liberty MCP demo. Compiled with **Java 25** (`maven.compiler.release=25`). Packaged as a WAR and run via the Liberty Maven plugin. No test sources exist.

## Commands (in order)

```bash
jbang Setup.java            # Full first-time setup (certs, Keycloak, Liberty)
./mvnw liberty:dev          # Start Liberty in dev mode (hot reload)

# Once finished and you want to run Setup.java again
podman compose down         # Start Keycloak container (also accepts docker compose)
```

## Architecture

MCP tools are plain CDI `@ApplicationScoped` beans. Any method annotated with `@Tool` (from `org.mcpjava.server.tools`) is automatically exposed as an MCP tool — no registration, no routing code needed.

Packages:
- `com.example.tasks` — `TaskTools` (tool bean) + `TaskService` (`@ApplicationScoped`, in-memory store) + `Task` (entity, with nested `Status` enum)

Task data is **in-memory only** — all tasks are lost on server restart. Only `taskadmin` may delete tasks.

## MCP / Liberty-specific patterns

- `@ToolArg(name, description)` on each parameter. For non-string types (dates, datetimes), add `@Schema(jsonSchemaString)` to tell the MCP layer the expected format.
- Use `ToolCallException` (from `io.openliberty.mcp.tools`) to signal tool-level failures; do not throw generic exceptions.

## Secrets

`src/main/liberty/config/server.env` holds `OAUTH_CLIENT_SECRET` and `keystore_password`. This file is **not** gitignored — do not commit real secrets.