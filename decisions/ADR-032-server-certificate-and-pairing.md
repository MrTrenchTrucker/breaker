# ADR-032: The server makes its own certificate, and the one-time sign-in pairs the phone with it

**Status:** accepted in principle; the details are to be designed
**Date:** 2026-11-01
**Approved by:** the project owner, in his own words below (the words "decided in principle" and the list of open points are the maintainers')

## Context

The architecture said that the server serves a self-hosted certificate authority (CA) certificate that the user installs on the phone once, so that HTTPS to the server is trusted (F18, section 14, section 15; docs/06 Step 1). Risk R9 says the state of the certificate set-up is unknown and must be checked before any TLS change. The app trusts only the platform trust store, with no pin and no custom trust manager (ADR-030 and the cards of `android_http` and `android_stt_server`), and it refuses plain http.

A person who runs the Breaker container on their own server or computer has no public certificate, and asking them to run a certificate authority and install its certificate on each phone is the opposite of "Breaker magically works".

The project owner, 2026-11-01: "One time login for the end user thats it and Breaker magically works."

And on the certificates: "Ok so we need to make the certs like magic if it needs to set up its own cert? Is that what you were saying? We can try that."

## Decision (in principle)

1. The Breaker server container makes its own certificate the first time it starts, and keeps it. The admin sets up no certificate authority and installs nothing on the phone by hand.
2. The one-time sign-in also teaches the phone to trust that exact server. For example, the server shows a pairing code or a QR that carries its address and the fingerprint of its certificate; the phone keeps the address and the fingerprint it was given.
3. After pairing, the phone trusts that server's certificate by its fingerprint. The platform trust store stays the rule for every other host (the update host, model downloads).
4. The app still refuses plain http. Pairing changes what is trusted, not whether TLS is used.
5. A certificate that does not match what the phone was taught is refused. The phone never silently trusts it.
6. Until this is built, nothing changes in the code: the app trusts the platform store only.

## To be designed (not decided here)

- What exactly the pairing carries and how the person gets it onto the phone.
- Where the phone keeps the trusted fingerprint and how it is protected.
- How a server certificate is replaced or renewed without breaking paired phones.
- What the phone shows when the fingerprint does not match (it refuses and says so; it never trusts silently).
- That the app still refuses plain http.
- How the trust code stays inside the one HTTP client of ADR-030, with no second client.

## Consequences

Rules amended (each only as far as stated above, and only once the design is built):
- ADR-030 items 3 and 9, `android/modules/http` card (trust: the platform trust store only), `android/modules/stt-server` card (platform trust only, no pinning, no custom CA): the paired server's fingerprint will be a second thing the one client trusts.
- ARCHITECTURE: F18, section 14 (certificates and sideload), section 15 (TLS from the self-hosted CA), R9; `docs/01-requirements.md` F18; `docs/05-decisions-and-risks.md` R9 and "Certificate lifecycle"; `docs/06-sideload-and-install.md` Step 1; the glossary.
- Threat model: T3 (VPN exposure), T6 (audio on the wire), T31 (impersonation at pairing).
- The web-fe no longer hosts a CA certificate for download; the deploy module's certificate work is the server making its own certificate.

Rules out: a certificate authority for the admin to run; installing a certificate on the phone by hand as the normal route.
