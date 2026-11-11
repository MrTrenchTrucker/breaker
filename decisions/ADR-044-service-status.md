# ADR-044: Service status route - signed-in users see which services are up

**Status:** accepted (the project owner typed OK to build the basic version, 2026-11-11)
**Date:** 2026-11-11

## Context

The phone app already probes server reachability (the sync probe /
refreshProbeInBackground) so it can tell whether it reaches the container at
all. There is no route that tells the app which services inside the container
are up or down: no `GET /v1/status` exists on this base. The admin health route
(`GET /v1/admin/health`, `AdminHealthRoute.kt:33-57`) always answers
`{"status":"ok","git_tag":"<tag>"}` - its status is always "ok" and it checks
no service at all, not whisper-server. So the premise that whisper-server state
comes through the same check the admin health route already uses does not hold
on this base; Decision 2 gives the mechanism that is ruled instead.

The project owner ruled out ntfy, webhook and any third-party notifier.
Service status is instead exposed as a light route on the server, a single line
on the phone Home screen, and a banner on the admin web page.

## Decision

1. **Server route.** A new `GET /v1/status` on sync-api answers
`{"services":[{"name":"sync-api","up":true},{"name":"whisper-server","up":false},
 ...]}` - service names plus up/down only. No versions, no addresses, no
errors. It is readable by any signed-in user (not admin-only), token-checked
like the other routes (401 for a dead/missing/malformed token), rate limited
like the other routes (the numbers come from the ADR-041 measurement, not from
this ADR). The admin health route stays admin-only and detailed.

2. **whisper-server state.** The admin health route checks no service, whisper-server
included (it answers ok whenever an admin token resolves), so the route does not
use it. sync-api probes whisper-server's own unauthenticated /health: the URL comes
from one environment setting and never from a request; unset means whisper-server
is not listed, so a stack without it does not show "down"; a value that is not a
plain http or https URL stops the start with the usual configuration error. The
probe is a GET with a 1 second total timeout; only HTTP 200 counts as up, the body
is never read or echoed, and any other answer, a timeout or a connection error is
up: false, never a 500. The answer is cached for about 10 seconds, failures
included, and concurrent callers share one probe (on top of the ADR-041 rate
limit). sync-api is listed up whenever it answers.

3. **Phone.** The app asks `/v1/status` when it comes to the front and then at
a gentle interval (about every 2 hours while the app is open). No background
scheduler, no new permission. Home shows one plain line in strings.xml:
`All services are up.` when every listed service is up, `Transcription is down.`
when whisper-server is down, and `Server not reachable.` when sync-api is down or
the app cannot reach the container at all (using the existing sync probe /
refreshProbeInBackground reachability probe). With sync off or signed out
there is no line.

4. **Admin web page.** A banner shows while a service is down, with since-when
(web-fe). Planned, not now: the web front end has only its sign-in page, so
there is no admin page to carry it yet.

5. **No notifier.** No ntfy, no webhook, no third-party notifier of any kind.

## Reasons

- Signed-in users can see service status without an admin gate; the admin
health route stays admin-only and detailed, so the two audiences are separated.
- One plain Home line avoids repeated per-service probing on the phone and
matches the owner's "one line" wording.
- The web banner keeps the admin informed without a notifier service or
external dependency.

## Consequences

Rules in: `GET /v1/status` readable by any signed-in user; names + up/down
only, no versions/addresses/errors; rate limited like other routes (numbers
from ADR-041 measurement); no ntfy/webhook/notifier; phone Home line
`All services are up.` / `Transcription is down.` / `Server not reachable.`;
phone asks at front + ~every 2h while open, no background scheduler, no new
permission; admin web banner while a service is down, since when; admin health
route unchanged (admin-only, detailed).

The deploy slice owes the setting (the compose service address of whisper-server).
whisper-server binds 127.0.0.1 by default, so the deploy must put both services on
the internal network (ADR-042).

Rules out: any notifier (ntfy, webhook, third-party); admin-only gate on
`/v1/status`; versions, addresses or errors in the `/v1/status` body; a phone
background scheduler; a new phone permission; assuming whisper-server state
comes from the admin health route check.

To be designed (not decided here): the exact probe interval beyond "about every 2 hours"; exact strings.xml
wording beyond the three given lines; the web banner wording and
implementation; the rate-limit numbers (ADR-041 sets none yet); how "since
when" is computed for the banner.

Honesty: this ADR records the owner's ruling; no code implements `/v1/status`,
the phone line, or the web banner on this base. The three strings.xml words are
given verbatim by the ruling; the whisper-server mechanism is ruled in Decision 2;
the rest of the wording is open.

Update 2026-11-11: the whisper-server mechanism above was decided on a code
finding (the admin health route checks no service).
