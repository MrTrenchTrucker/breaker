# ADR-042: Network model — VPN or private bind only, ordered server addresses

**Status:** accepted
**Date:** 2026-11-11
**Approved by:** the project owner, 2026-11-11 (home network or VPN)

## Context

Breaker's server container binds a TCP port so the phone app and the website
can reach it. The phone app will keep an ordered list of up to three server
addresses (home first, road second), probes each in turn, uses the first that
answers, and remembers the last good one. Today the app has ONE server address
(F10): the list is the decision, not the current behaviour.

Where the server container binds must be safe by default: binding to a public
address, or to 0.0.0.0 (every address including public ones), exposes the
server to anyone on the network that can reach the host. A public bind also
defeats the VPN model entirely — the phone could reach the server without the
VPN, bypassing the network boundary the admin chose.

No bind rule is written down as a decision yet, and nothing enforces one: the
whisper-server defaults to 127.0.0.1, but sync-api can bind any address through
its environment. A verify-only helper, vpn-bind.sh, is being built in the
deploy module; this ADR is the rule it checks.

## Decision

1. **Bind rule.** The server container binds either (a) the VPN interface
address, or (b) a private-network address — RFC 1918 IPv4 (the three private
blocks: 10/8, 172.16/12 and 192.168/16), the 100.64/10 carrier-grade NAT block, fd00::/8
IPv6 ULA, or loopback (127.0.0.1, ::1). It never binds a public address, and
it never binds 0.0.0.0 / ::.

2. **vpn-bind.sh refuses a public address.** The helper refuses a public bind
address even when --allow-private is passed; --allow-private only permits the
private-network set above, never public.

3. **Ordered server addresses on the phone.** The app stores one ordered list
of 1-3 server addresses (home first, road second), entered on one settings
screen. On each start it probes the entries in order (one probe per entry),
uses the first that answers, and remembers the last
good one as the default for the next start. A failed probe moves to the next
entry. The probe timeout and what the app shows when no entry answers are
under To be designed.

4. **TLS stays the admin's choice.** Plain HTTP is served by the container; if
the admin wants HTTPS, a reverse proxy of the admin's choice sits in front
(ADR-034). The bind rule above is independent of TLS: a VPN or private bind can
carry plain HTTP behind a proxy, or HTTPS terminated by the proxy.

5. **Follow-up doc edits (not made by this ADR's code).** The stale s6 service
line that still says "HTTPS + API key" is updated to reflect plain HTTP behind
the admin's proxy, and threat model row T3 ("VPN exposure") is updated to match
this bind rule. Both edits are listed here because they are consequences of
this decision; they are docs patches that follow this ADR, not code changes in
it.

## Reasons

- A public bind is the simplest way to accidentally expose a self-hosted
server to the local network or the internet. Defaulting to VPN-or-private
makes exposure a deliberate admin action, not a default.
- The ordered address list covers the two places the owner uses the phone (at
home, on the road) and needs only one settings screen, keeping the UI change
small.
- Probing each entry in order and remembering the last good one means a road
user who can no longer reach the home address falls back to the road address
without retyping anything.
- Mirroring ADR-034 keeps TLS out of this decision: the container serves plain
HTTP, and the admin's proxy (if any) handles HTTPS.

## Consequences

Rules in: VPN-or-private-only bind; vpn-bind.sh refuses public even with
--allow-private; no 0.0.0.0 / :: bind; one ordered list of 1-3 addresses on
the phone; per-entry ordered probe with last-good memory; one settings screen;
TLS is the admin's reverse-proxy choice (ADR-034).

Rules out: binding to a public address; binding to 0.0.0.0 / ::; the app
choosing a server address by any rule other than the ordered probe; any
server-side TLS or certificate handling in Breaker.

To be designed (not decided here): the probe timeout and what the app shows
when no address answers; the exact wording of the s6 line and of threat-model
T3, which the following docs patch decides in the shape this ADR sets.

Honesty: this ADR sets a rule; no code enforces it yet. vpn-bind.sh (a
verify-only check, not run by any deploy step), the app's address list and its
probe loop are described here, not built. Nothing in this ADR changes server
code, phone code or the s6 line.
