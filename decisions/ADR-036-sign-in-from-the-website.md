# ADR-036: Sign-in from the website hands the login to the app

**Status:** accepted; the points under To be designed are open
**Date:** 2026-11-01
**Approved by:** the project owner, 2026-11-01

Related: ADR-035 (several devices; its point 6 relies on this hand-off). ADR-034 records the intended flow and leaves its hand-off open; this ADR answers that.

## Context

ADR-034 records the owner's intended flow: "...and then it just automatically logs you into the phone". It left two things open: how the website hands the login to the app, and what protects that hand-off on plain http. ADR-035 needs the hand-off, so that a new device gets its keys without a second password prompt.

Two facts rule out the simplest answer. A verified app link needs a public https host that is known when the app is built, and every admin's host is different. A custom `breaker://` link works without https, but another app on the phone could claim it. So the link alone is not enough. The code it carries is useless without a secret that the app keeps (the same idea as PKCE, RFC 7636).

The project owner answered the open questions on 2026-11-01, in his own words:

> "1 approved / 2 probally fine if its a handoff approved. / 3. Is this needed for settle hosting? / 4. Approved. More than one device per account? Probally fine actually [...] / 5. Approved. / 6. Approved"

The questions were: 1, the automatic hand-off; 2, a 5-minute single-use code; 3, a wrong-password limit; 4, several devices per account; 5, sign-out ends this device only, with "sign out all my devices" on the website; 6, the password typed on the website, with the phone's own sign-in kept as the fallback. On question 3 he was told the limit matters less on a home network or VPN, and was offered it on by default with an admin off switch. He answered "Yes", and confirmed the whole design: "Breaker confirmed. Update the repo when ready." This goes against what ADR-006 and ADR-031 say: both state that this repo ships no brute-force protection or rate limiting, and that auth hardening is out of scope. This ADR changes that for sign-in only. Until the code is built, those statements still describe the code.

## Decision

**The hand-off, in order:**

1. The app starts the sign-in. It keeps a one-time secret and makes a fresh key pair for this one sign-in. It opens the Breaker website in the phone's browser with a one-time challenge made from the secret (the secret itself is not sent) and the app's public key.
2. The person types the username and password on the website. Login itself does not change. The website page derives the KEK in the browser (ADR-006's web path) and seals the account's keys to the app's public key (ADR-035, point 6).
3. After sign-in the website creates a one-time code, valid for 5 minutes and usable once. It sends the browser back to the app through a `breaker://` link that carries the code. The return is automatic: nothing is typed or copied.
4. The app trades the code and its secret for its own session token and the sealed key package. It opens the package with its private key and wraps the keys for its own storage.
5. A session from this hand-off is an ordinary session: it expires 30 days after its last use (ADR-031).

**Server side.** Two new routes: a start route, where the website creates the code after sign-in, and an exchange route, where the app trades the code and its secret for a session token. Login is unchanged. The code is a new short-lived, single-use kind in the sync-api token store, so it survives a restart and has one place for its expiry. The sign-in page itself is served by web-fe; sync-api only serves the routes.

**Phone storage.** The session token is wrapped with an Android Keystore key from the start. It lives in the app's private storage and is not included in backups. There is no plain store behind a flag.

**Several devices and sign-out.** An account may use several devices. The website lists them, with a sign-out for each one. Signing out in the app ends that device only. On the website, "sign out all my devices" ends every device of the account.

**Fallback.** The app's own sign-in screen stays. If the hand-off fails, the person can type the password on the phone. That path derives the KEK on the device, as ADR-006 requires.

**Wrong-password limit.** After 5 wrong passwords for an account, sign-in for that account waits 15 minutes. The limit applies on the website and on the device. It is on by default, and the admin can turn it off. This is the exception to ADR-006 and ADR-031 described in Context.

**Admin session revoke.** An admin can end a session at once. This route is built over the existing token-store functions, because a lost phone needs it now that the website hands out sessions.

**Link host.** No app link host and no verified-link file are used. The link is the custom `breaker://` scheme, and the secret the app keeps is what protects it.

**Plain http.** Plain http to the configured server is allowed (ADR-034). How the app limits plain http to that one server is To be designed.

## Reasons

- A custom `breaker://` link needs no https host known at build time, so it works for every admin's server, plain http included (ADR-034).
- The secret the app keeps means a copied or claimed link is useless without the phone that started the sign-in.
- A 5-minute, single-use code keeps a leaked code short-lived.
- The password is typed once, on the website. On this path it does not reach the app.
- Login and key derivation do not change, so ADR-006's guarantees for the password path stay as they are.
- The return is automatic, as the owner asked: sign in on the website and the phone is signed in.
- The owner approved the wrong-password limit (2026-11-01).

## Consequences

Rules in: the hand-off above, with a 5-minute single-use code and the app's secret; the sealed key package from ADR-035, point 6; a device list with a sign-out for each device, and "sign out all my devices" on the website; sign-out on the phone ends that device only; the wrong-password limit on by default, with the admin able to turn it off; sessions from the hand-off follow ADR-031.

Rules out: a verified app link and its host file; redeeming the code without the app's secret; the password reaching the app on the hand-off path (the fallback keeps its own sign-in screen); removing the phone's own sign-in screen.

**Trust boundary, named.** The hand-off rests on the website page doing what it should. As ADR-006 says for the web path, anyone who can change the page can change what it does with the password. On plain http the code can be read in transit, but nobody else can redeem it without the app's secret. The token exchange is as readable as any plain-http call, which ADR-034 already accepts as a risk. The admin closes that risk with https.

Nothing of this is built. There is no hand-off route, no code kind and no wrong-password limit in the code yet.

## To be designed (not decided here)

- The route names and the wire form of the challenge, the code and the exchange.
- The sealed package's format and key type (ADR-035).
- How the app limits plain http to the configured server, and the matching change to its HTTP client (ADR-034).
- How the wrong-password limit counts failures and where the 15-minute wait is kept.
- The exact words on the website and in the app, and where the sign-in notice sits on Home (ADR-033).
- What the device list shows for each device.
