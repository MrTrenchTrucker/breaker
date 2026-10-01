# ADR-001: Modular monorepo layout (android / server / shared)

**Status:** accepted
**Date:** 2026-09-29

## Context
Breaker spans a phone app, a self-hosted server, and shared contracts. Agents
(Bug Hunt, Orchestration, Coding) build lanes in parallel and need a structure
where every module is discoverable and independently buildable.

## Decision
A modular monorepo: `android/` (Kotlin app + 17 modules), `server/` (Local Server
services, 5 modules), `shared/` (contracts + registries, 4 modules). Every
module has its own folder, `AGENTS.md` card, and `README.md`, registered in
`modules.toml`. Modules never import each other directly — they talk through
`core` ports or the app's DI wiring.

## Reasons
- The swept base is already Kotlin/Android; the server already exists on
  Local Server; contracts must be shared, not duplicated [1].
- A machine-checkable registry lets agents find the right module without
  reading code, and lets a checker keep the tree honest.

## Consequences
Rules in: acyclic `depends_on`; registry/tree agreement enforced by
`tools/check_repo.py`. Rules out: cross-module imports, `utils`/`misc` modules,
undocumented sub-modules.
