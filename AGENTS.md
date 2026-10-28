# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Goal

Get OAuth to work end-to-end with **IBM Bob GUI**

## Project

Jakarta EE 10 / Open Liberty MCP demo. Compiled with **Java 25** (`maven.compiler.release=25`). Packaged as a WAR and run via the Liberty Maven plugin. No test sources exist.

## Commands

```bash
./mvnw liberty:dev          # Start Liberty in dev mode (hot reload)
./mvnw package              # Build WAR only
./mvnw liberty:run          # Start Liberty without dev mode
jbang Setup.java            # Full first-time setup (certs, Keycloak, Liberty)
jbang SetupLocalKeycloak.java  # Keycloak-only setup
podman compose up         # Start Keycloak container (also accepts docker compose)
```

## Architecture

MCP tools are plain CDI `@ApplicationScoped` beans. Any method annotated with `@Tool` (from `org.mcpjava.server.tools`) is automatically exposed as an MCP tool — no registration, no routing code needed.

Packages:
- `com.example.tasks` — `TaskTools` (tool bean) + `TaskService` (`@ApplicationScoped`, in-memory store) + `Task` (entity, with nested `Status` enum)

Task data is **in-memory only** — all tasks are lost on server restart. Only `taskadmin` may delete tasks.

## MCP / Liberty-specific patterns

- `@Tool` annotation: set `description`. The optional `annotations` hints (`readOnlyHint`, `destructiveHint`, `openWorldHint`) are not used in this project.
- `@ToolArg(name, description)` on each parameter. For non-string types (dates, datetimes), add `@Schema(jsonSchemaString)` to tell the MCP layer the expected format.
- `@Schema` takes a raw JSON Schema string as a constant — declare these as `private static final String` (or instance `final` if non-static method context requires it).
- Use `ToolCallException` (from `io.openliberty.mcp.tools`) to signal tool-level failures; do not throw generic exceptions.
- `HttpServletRequest` can be injected directly into tool beans to read `getUserPrincipal()` and `isUserInRole()`.

## Auth / Keycloak gotchas

- The Client Scope `mcp-demo-server` must be type **Default** (not Optional) — optional scopes are silently dropped from tokens, causing `CWWKS9104A` auth failures.
- The role mapper must use **User Realm Role** (not the predefined `groups` mapper which maps Keycloak *Groups*, not *Realm Roles*).
- Token claim name for roles must be `groupIds` — this is what Liberty's `isUserInRole()` reads.
- Bob (Electron/Chromium) requires the cert in the **login keychain with `-p ssl`**, not just the System keychain; the System keychain alone is silently ignored by Chromium.
- Liberty OIDC config uses `inboundPropagation="required"` — tokens must be passed by the client, Liberty does not mint them.

## Secrets

`src/main/liberty/config/server.env` holds `OAUTH_CLIENT_SECRET` and `keystore_password`. This file is **not** gitignored — do not commit real secrets.
