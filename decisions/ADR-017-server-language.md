# ADR-017: Breaker's server services are written in Kotlin on the JVM

**Status:** accepted
**Date:** 2026-09-30

## Context
The architecture names Breaker's own server modules (`whisper-server`,
`sync-api`, `web-fe`, `training`, `deploy`) but not the language they are
written in; their build files are bare stubs whose tooling "is added here by
the phase that implements it". Phase 4 (`whisper-server`) can start now, so the
choice is needed. The registry already makes `whisper-server` depend on
`shared/modules/api-contracts`, which until now published only an OpenAPI
spec.

## Decision
Breaker's own HTTP services (`whisper-server`, `sync-api`, and any HTTP
endpoints `web-fe` needs) are written in **Kotlin on the JVM, with Ktor** as
the HTTP server, built by the repo's Gradle build and tested in the same gate
as the app.
- `shared/modules/api-contracts` publishes two things: the OpenAPI spec (the
  language-neutral contract, for the web FE and agents) and Kotlin request
  and response types that match it. A conformance test in the Python tier
  (ADR-016's YAML reader) fails if the two ever differ.
- The services and the app both compile against those Kotlin types, so they
  use the same request and response types and cannot drift apart.
- Each dependency (Ktor, a JDBC SQLite driver, password hashing) is pinned in
  `gradle/libs.versions.toml` and gets a licence and security review in the
  module PR that first adds it.
- `training` (per-user phrase-model training, Phase 13) is decided when that
  phase starts: model-training tooling may need Python.
- Browser code in `web-fe` and shell scripts in `deploy` use whatever those
  targets require.

## Reasons
- One language across the app and the services it talks to, with shared
  contract types instead of two copies kept in step by tests.
- One build, one gate container and one set of tools, which the team already
  has and has proven.
- Ktor is open source (Apache-2.0) and native to Kotlin.

## Consequences
Rules in: Kotlin/JVM + Ktor for the HTTP services; a JRE-based container image
for each; the services' build files gain the Kotlin JVM plugin in the phase
that implements them.
Rules out: a second server language for these services; hand-copied contract
types on the server side.
