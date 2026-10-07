# ADR-022: Text goes into the app through an accessibility service (supersedes ADR-005)

**Status:** accepted
**Date:** 2026-10-07
**Approved by:** project owner

## Context
ADR-005 chose IME-first text commit: Breaker ships its own keyboard, and
sends text through `InputMethodService.commitText(...)` when that keyboard is
active and a field is focused. On review, before any of it shipped, this
does not fit the product. An IME requires the user to switch away from whatever keyboard they already use —
unlike Wispr Flow, which works with any keyboard. Worse, as soon as any
screen opened (the dictation UI, a permission prompt, anything), the target
field lost focus and IME commit could not fire, so the UX spec of "tap send →
text lands in the focused field" broke in the most common case. The forked
base app already used an accessibility service (`WhisperAccessibilityService`)
for exactly this reason, and it works with the keyboard already on screen.

## Decision
Dictation happens in place from the floating tile. The tile is the CB mic
glyph with the LED bar meter above it. Tap the tile to start; while recording
the meter shows on the tile, with a small cancel control; tap again (or say
the send phrase) to send. No Activity opens for dictation, so the app the
user is typing in keeps focus and the user's own keyboard stays up. The app's
screens remain for history, settings, onboarding.

Text goes into the focused field through an Android accessibility service
(`AccessibilityService`): it finds the focused editable node and inserts the
text (`AccessibilityNodeInfo.ACTION_SET_TEXT` merging with the existing text
at the cursor, or `ACTION_PASTE` from the clipboard). `ACTION_SET_TEXT`
replaces a node's whole text, so the merge is the service's own job: it reads
the field's current text and selection, builds the new text, sets it, then
places the cursor after the inserted text. If there is no focused editable
field, or the insert is refused, the text goes to the clipboard; on Android 12
and below a toast says so, on Android 13+ the system's own copy confirmation
is enough.

This supersedes ADR-005. Breaker no longer ships its own keyboard.

## Reasons
- Works with whatever keyboard the user already uses (the way Wispr Flow
  does) — no switch, no extra setup step beyond one permission grant.
- An IME requires the user to switch keyboards, and as soon as any screen
  opened the target field lost focus, so IME commit could not fire.
- The forked base app already used an accessibility service
  (`WhisperAccessibilityService`) for the same job, so the mechanism is not
  new to this codebase (the base app itself was never built or run, see
  `docs/03`).
- Dictation in place, with no Activity opening, matches the product's own
  hands-free design better than a window that can steal focus.

## Consequences
An accessibility service is a powerful permission; Android lets it read what
is on screen. The limits we keep, stated honestly rather than left implicit:
the service's config requests only what inserting text needs (focused-view
events, `canRetrieveWindowContent` only because finding the focused field
needs it); it never stores or logs screen content or field text; it never
sends anything off the device; it acts only on an explicit send; it never
inserts into password fields (`isPassword`). Onboarding explains the
permission plainly before sending the user to Settings, and walks the
Android 13+ "restricted setting" step: sideloaded apps show accessibility
services as restricted until the user allows it in the app's info screen.

Module boundaries: the `commit` module keeps the mechanism-neutral logic
(focused field gets the text, otherwise the clipboard) and gains two
sub-modules: `commit/accessibility`, which owns the accessibility service,
finding the focused editable field, the insert and the password-field refusal;
and `commit/ime`, which holds the keyboard adapter written for ADR-005. That
adapter is no longer used: it is kept, marked dead with a `DEAD_CODE.md` file,
and left out of the build, so it can never register a keyboard. The `ui`
module no longer owns a dictation screen; it owns the onboarding for the
permission. The `app` module owns the foreground service (microphone type)
that keeps recording alive while another app is in front; starting it from a
tile tap is the main platform risk and is spiked first.

Rules in: the accessibility service as the text-insert mechanism; in-place
dictation from the floating tile (no Activity opens while dictating);
clipboard fallback when there is no focused field or the insert is refused;
onboarding that covers both the permission grant and the restricted-setting
step; the limits above (focused-view + window-content access only, no
storage or logging of screen content, no network egress, explicit-send-only,
never into password fields).

Rules out: Breaker shipping its own keyboard/IME; a dictation Activity or any
other window opening during dictation; reading or keeping any screen content
beyond what one insert needs in the moment.
