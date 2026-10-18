# ADR-005: Text commit = IME-first with clipboard fallback

**Status:** superseded by ADR-022
**Date:** 2026-09-29

## Context
The user's spec: tap send → text lands in the focused field; if it can't paste,
copy to clipboard. Terminology matters: this is a *text commit*, never an
"injection".

## Decision
CommitService tries `InputMethodService.commitText(...)` when an IME is active
and a field is focused; otherwise copies to the clipboard + toast. Optional
candidate-bar preview.

## Reasons
- IME commit is the only reliable way to land text into an arbitrary focused
  field on Android; clipboard is the universal fallback.

## Consequences
Rules in: IME enablement in onboarding, clipboard fallback, toast feedback.
Rules out: clipboard-only (loses focus-targeting), accessibility hacks.
