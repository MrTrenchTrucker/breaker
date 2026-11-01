# ADR-033: Signed out is a quiet notice on Home, and nothing is forced

**Status:** accepted
**Date:** 2026-11-01
**Approved by:** the project owner, in his own words below

## Context

A phone has no live session on a fresh install, after a session expires (ADR-031), after an admin ends it, or when the server refuses the key. A design that opens a sign-in screen at start or after a refusal would put something in the user's way.

The project owner, 2026-11-01: "So long as the user can still use the app like I can now in offline mode without something over bearing in the way... so maby a notice in app on the home screen?"

## Decision

1. Nothing forces the user anywhere. There is no forced sign-in screen, at start or after a refusal.
2. The app keeps working exactly as it does offline today: on-device dictation, history and settings are not blocked by the missing session.
3. When there is no live session (fresh install, expired, revoked, or a key the server refused), Home shows a quiet notice with a way to sign in. The sign-in view opens only when the user asks for it.
4. The phone learns that a session is no longer live when the server refuses it, and at sign-in; it holds no clock for the 30 days of ADR-031.
5. This record does not change how the app picks an engine, and it does not name the words of the notice: the project owner approves the words before they are written.

## Consequences

Rules in: the signed-out behaviour above. Where it lives: the Home screen in `android_ui`, fed by the app (`android_app`) from the session state of `android_auth_client`; the cards of those three modules say so as planned work, not as built.

Rules out: any text that says the app opens on a sign-in screen, or that dictation stops without a session.

Not decided here: the exact words, the exact place on Home, and what dictation does with a server key the server refused (it keeps its own sentence today).
