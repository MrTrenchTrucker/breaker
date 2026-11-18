# ADR-049: No ACTION_PASTE fallback for the accessibility insert

**Status:** accepted
**Date:** 2026-11-18
**Approved by:** the maintainers' manager under the project owner's delegation

## Context

The accessibility insert module named ACTION_PASTE as a possible fallback for fields that refuse ACTION_SET_TEXT, with the decision left open (module card Purpose and Owns; modules.toml). A read-only recon on main f5189f83 found the following. The trigger is unproven: no phone measurement shows real apps refusing ACTION_SET_TEXT. A fallback already exists: on refusal the text goes to the clipboard, marked sensitive, with the outcome COPIED_AFTER_REFUSAL (CommitService.kt:87, 106-107). A paste path would write the dictation to the shared clipboard as a normal step, against the module's rule 2 (commit README:78-79). Its platform facts (ACTION_PASTE semantics, clipboard reads by an accessibility service on Android 10+) are unverified. The recon pack is PASTE_RECON_20261118 in the maintainers' staging area.

## Decision

The accessibility insert does not use ACTION_PASTE. Insertion stays ACTION_SET_TEXT merging at the cursor. A refused field keeps the existing clipboard fallback.

## Reasons

- It avoids putting every dictation on the shared clipboard to work around an unproven problem.
- The existing fallback already covers a refusal, and makes no paste attempt behind the user's back.
- It means less code and no new permission surface.

## Consequences

- The device checks for the next phone build include the measurement that would reopen this. Does a real app refuse ACTION_SET_TEXT, or answer true without changing the text (named apps)? And does the clipboard fallback write actually land on each Android version? If real apps refuse widely, a new ADR may revisit this; this one does not pre-approve paste.
- The fallback only helps if the user is told the text was copied. Showing the existing refusal sentence is a separate defect fix in android_app.
