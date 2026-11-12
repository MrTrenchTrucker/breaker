# ADR-041: Rate limits on the open sync-api routes, keyed by source address

**Status:** proposed
**Date:** 2026-11-10

## Context

Four routes of the sync API can be called without an account that has earned anything: `POST /v1/auth/register`,
`POST /v1/auth/salt`, `POST /v1/auth/complete-reset` and `POST /v1/sync` (this last one needs a session, but nothing
limits how often a session pushes). None has a rate limit. What one caller can make the server do is bounded only
by the body caps (4096 bytes on the three auth routes, 1 MiB on a push). Register runs one PBKDF2-HMAC-SHA256 hash at
600,000 iterations per request. Complete-reset runs one slow hash per request that carries a right code. Salt answers
for any name and tells an unknown name from a known one (ADR-006).

Two rate limits exist today: the wrong-password limit on sign-in (ADR-036: after 5 wrong passwords an account waits
15 minutes; a table of attempts in the database, an admin switch, on by default) and the optional per-agent-token
request limit of the transcription server (in memory, per token, off unless the admin sets a cap).

The server speaks plain http, and a reverse proxy of the admin's choice sits in front of it when the admin wants
https (ADR-034). Behind such a proxy the socket peer is the proxy, so how the server learns the client address is part
of the decision.

## Decision

1. **The key is the source address.** It is the socket peer. `X-Forwarded-For` is read only when the peer is in an
   admin-set list of trusted proxies (a new setting, default empty) [now an environment setting: see the Update of 2026-11-11 (trusted proxies)]. With an empty list the peer address is used and
   the header is ignored. With a list, the key is the right-most entry of the header that is not itself a trusted
   proxy. Behind a proxy the admin has not listed, every client looks like the proxy and the per-address limit behaves
   as one shared limit; the admin card says so.
2. **A global limit stays, as a backstop for server CPU only.** It is set high enough that one noisy address cannot
   reach it. It is therefore a denial switch only for a distributed attack, which a private deployment does not
   defend against and this ADR does not claim to.
3. **Complete-reset counts failed codes per address** (and against the global backstop). A right code is never
   counted and never refused for rate: the route already finds out whether the code is right before it runs the slow
   hash. A flood of wrong codes from one address makes that address's wrong codes answer 429; other addresses are not
   affected. Complete-reset is never keyed on the account name, or an attacker could lock the real owner out of
   recovery.
4. **Sync is limited per account**, because a push needs a session. The number comes from the real client's push
   pattern (batching, and the catch-up after a long offline time), not from a guess.
5. **No number is set by this ADR.** The first step of the build is a measurement of the register hash (and the
   salt and complete-reset work) on one core. The per-address and global numbers are derived from it with the
   arithmetic written down.
6. **The admin can switch the limits off; they are on by default**, the same pattern as the wrong-password limit. A
   refusal is HTTP 429 with a `Retry-After` header in whole seconds (1 to 3600) and a fixed JSON body that holds no
   key and no address. 423 stays reserved for the sign-in lock. The phone side already reads a 429's `Retry-After`
   for transcription; the sign-in, register and salt calls of the auth client still answer a generic server error for
   any other status and need their own step.
7. **Hazard tests come with every limit**: one test per limit that goes red when the limit is removed, plus the
   denial-switch test: one address flooding salt must not stop a different address from registering or signing
   in. A design with only a global limit would fail that test.

## Reasons

- Keying on the name does not bound register or salt: an abuser sends a fresh name each time and each is counted
  once.
- A small global limit alone is a switch anyone can pull to stop every sign-in and every sign-up.
- The address is the only key that exists before an account does, and it is the key that does not punish one
  person for another's flood.
- Counting only failed codes on complete-reset leaves the recovery path open to the person who holds a right code.
- Measuring before choosing numbers keeps the limits from being either decoration or a lock on honest users.

## Consequences

- A counter store is needed. It follows the table of the wrong-password limit: kept in the database so a restart
  does not reset it, one row per key, a fixed cap on rows, the oldest row dropped first, stale rows swept. The
  in-memory map of the transcription server's limit is not a model, because it forgets everything on a restart.
- A new admin setting (the trusted-proxy list [now an environment setting, see the Update]) and an admin switch for the limits are added; the admin card says what
  they do and what an unlisted proxy means.
- Behind an unlisted proxy the per-address limit protects the server but treats all clients as one.
- Build order: the measurement; the counter store with register and salt; complete-reset; sync; the trusted-proxy
  setting; the auth client's handling of a 429. Each step has its own review.
- `docs/03` T18 and `docs/05` R19 stay "planned, not built" until the first limit lands.

## To be designed (not decided here)

- Where the trusted-proxy list lives and how the admin edits it. [Decided: see the Update of 2026-11-11 (trusted proxies).]
- The numbers (see decision 5).
- What the sign-in screen says when the server answers 429.

Update 2026-11-11 (trusted proxies, built): the trusted-proxy list is ONE environment setting, `BREAKER_TRUSTED_PROXIES`, not an admin setting: IPv4 or IPv6 literals or CIDR blocks, at most 20 entries, default empty (trust none), read at start; a bad value, a prefix length of 0 or a host name stops the start. A remotely editable trust list would be an attack surface, and there is no admin page yet; an admin route may follow when one exists. The key of a request is the socket peer, unless the peer is listed: then it is the right-most `X-Forwarded-For` entry that is not itself listed, written in its normalised form (an IPv4 mapped IPv6 address is its IPv4 address). A header that cannot be read (an entry that is not an IP literal, more than 20 entries, an entry over 45 characters) never decides a key: the peer does. Behind an unlisted proxy every client looks like the proxy. No migration.
