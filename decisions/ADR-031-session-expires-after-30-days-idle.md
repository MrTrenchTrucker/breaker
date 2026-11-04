# ADR-031: A session token expires 30 days after its last use

**Status:** accepted, amended 2026-11-04 (no session outlives 180 days from its creation)
**Date:** 2026-11-01
**Approved by:** the project owner, in his own words below

## Context

Until now a session token never expired. The requirements and the architecture listed "session expiry" among the auth hardening left for later, and the sync-api card said a session row stays until a password change, a reset or the deletion of the account removes it. A lost or stolen phone would stay signed in for ever.

The project owner, 2026-11-01: "And agreed, 30 days no login, no action,  log out. Each use pushes the date out."

And, describing what he wants a user to live with: "One time login for the end user thats it and Breaker magically works. They loose phone? No worries in 30 days their phone's credentials no longer valid and they have to re log in."

## Decision

1. A session token expires 30 days after its last use, and no session outlives 180 days from its creation. Logging in stores an expiry of now + 30 days. Every authenticated use of a live session token moves its expiry to the lesser of now + 30 days and creation + 180 days (a sliding expiry clamped at a 180-day cap). Two constants hold the rule: the 30-day idle lifetime and the 180-day cap. Agent tokens are not affected (point 4).
2. A token past its expiry is refused like an unknown token (the normal 401) and stays refused, also after a later try. The user signs in again.
3. An admin can still end a session at once (ADR-009, revoke). A password change or a reset still ends sessions as before.
4. Agent tokens are not affected. They keep their own optional expiry and are never moved by a use (ADR-009, F27).
5. A session row stored before this rule with no expiry gets its expiry at its next use.
6. What the user sees when the phone has no live session is a separate decision: ADR-033 (a quiet notice on Home, nothing forced).

## Consequences

Rules in: the session lifetime above, including the 180-day cap; sync-api's token store is the place that enforces it (the sync-api card and its tests say how). A session kept alive past the cap is refused at the cap; the slide is clamped so a live session can never be pushed past creation + 180 days; agent tokens past the cap stay live (the guard is session-scoped).

Rules out: a session that lives for ever; "session expiry" as deferred auth hardening (the lines that said so in `docs/01-requirements.md`, `docs/05-decisions-and-risks.md` and `ARCHITECTURE.md` are amended). Brute-force protection stays out of scope for now.

Amends: ADR-009 (tokens). Threat model: T30 (lost or stolen phone).

How the job API (whisper-server) learns that a token is live, so that an expired or revoked token is refused there too, is ruled in ADR-015 (amended) and `ARCHITECTURE.md` R31: it asks sync-api. The job API now checks the token (built).
