# ADR-009: Roles + agent tokens (first-account-is-admin)

**Status:** accepted, amended 2026-11-01 (a session token expires 30 days after its last use)
**Date:** 2026-09-29

## Context
Multi-user needs roles; AI agents need API access without an Android app.

## Decision
The **first account to register is admin by default**; admins can assign other
admins. Agents use scoped tokens (`transcribe` default | `admin`), generated
and revoked by admins, calling the same API through the same FIFO queue;
results land in the owner's account. The **last remaining admin cannot delete
their account or be demoted**. Role checks are server-side only.

## Reasons
- Bootstrap rule is simple and safe; agent tokens make the server usable by
  the user's own AI agents; the last-admin rule prevents lockout.

## Consequences
Rules in: `/v1/admin/roles`, `/v1/admin/agent-tokens`, `/v1/admin/reset-account`;
admin-scoped tokens can trigger an account reset, which deletes the account's
keys, transcriptions and pending-sealed rows, and issues a one-time code
(≥128 bits, single use, 24-hour expiry). Whoever presents that code first
sets the new password on their own device — normally the user, and the admin
does not set it in that normal flow, but the code does not itself guarantee
who completes the reset; the account's very next password change — every
password change rotates the DEK and box keypair, with no reset-specific case
needed (ADR-006) — is what bounds that gap (ADR-018).
Rules out: self-promotion, last-admin deletion.

Amended 2026-11-01: a session token expires 30 days after its last use, and each use moves the expiry to 30 days from that moment (ADR-031). An admin can still revoke a session at once. Agent tokens keep their own optional expiry and are never moved by a use.
