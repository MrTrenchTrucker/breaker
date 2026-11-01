# ADR-034: Breaker serves plain HTTP; TLS is the admin's choice

**Status:** accepted; the points under To be designed are open
**Date:** 2026-11-01
**Approved by:** the project owner, in his own words below

## Context

ADR-032 decided that the Breaker server container makes its own certificate on first start, keeps it, and that the one-time sign-in teaches the phone to trust that exact server by fingerprint, so that HTTPS to Local Server is trusted with no certificate authority for the admin to set up. ADR-032 was decided in principle, details to be designed, and nothing of it is built: the code still refuses plain http, the manifest and network config still switch cleartext off, and no module makes or pins a certificate.

The project owner reversed that decision, 2026-11-01:

"We were talking certs earlier so do we leave it to the end user to decide how to do certs assuming they want it and its up to them to do http or https with certs if they want? Sounds about right."

and on how sign-in should feel:

"the idea was to be able to go to the website that the container was hosted at more than likely over a local network or vpn and then you do the first time log in for the admin so like the first account that gets signed up is admin by default then like when you log in from your phone onto the website now you put your username and password in and then it just automatically logs you into the phone"

## Decision

1. Breaker serves plain HTTP. Nothing in Breaker makes, keeps, pins or renews a certificate. No module owns TLS. If an admin wants https, a reverse proxy of the admin's choice sits in front of Breaker.

2. The app accepts http and https server addresses. An https address uses the phone's normal trust. A plain http address is allowed.

3. On a plain http server address the app shows one quiet line in settings: the connection is not encrypted, use a VPN or an https address. The project owner approved the exact words on 2026-11-01 ("It can say that in the settings."): "This connection is not encrypted. Use a VPN or an https address."

4. Known and accepted risk: on a network without a VPN, plain http is readable by anyone on that network (the settings line names it).

5. To be designed (the maintainers' technical additions; both ways are open and neither is chosen here):

(a) The app also trusts a certificate authority the user installed on the phone, for the server address only, when the admin's proxy uses its own authority.

(b) Android's cleartext block must be lifted for the configured server only.

Note: Android's cleartext permission is a static per-domain setting in an xml file, and the server address is typed by the user at run time, so "only the configured server" cannot be a static rule. The two ways are: cleartext permitted in the base config and the app's one http client refuses every address but the configured server (and the other fixed hosts) in code, or a config built at run time. Both ways are open and neither is chosen here.

6. The intended sign-in flow, from the owner's words, recorded as intended, design not decided here, no QR code and no pairing step: the user opens the Breaker website (served by the container, usually over the home network or a VPN); the first account signed up is the admin; on the phone the user signs in on the website with username and password and the website hands the login to the app automatically.

Open points: how the website hands the login to the app; what protects the hand-off on plain http.

7. Supersedes: ADR-032 is superseded by ADR-034. ADR-030 is amended: the http module no longer refuses http for the server address (the other fixed hosts, the update host and the model download hosts stay https only).

## Consequences

Rules in: plain HTTP on the server side; TLS is the admin's choice via a reverse proxy; the app accepts http and https; one quiet settings line on plain http; ADR-032 superseded; ADR-030 amended for the server address only.

Rules out: a certificate authority for the admin to run; installing a certificate on the phone by hand as the normal route; a QR code or pairing step for sign-in; any module owning TLS or making certificates.

Honesty: nothing of ADR-034 is built today. Every document that describes what the code does today (https only, cleartext off) stays true and gains one sentence: ADR-034 decides X; the code still does Y until the slice for it lands (not built). Nothing of ADR-032 was built either.

## To be designed (not decided here)

- How the website hands the login to the app; what protects the hand-off on plain http.
- Whether and how the app trusts a user-installed certificate authority for the server address only.
- How Android's cleartext block is lifted for the configured server only, given the server address is typed at run time.
