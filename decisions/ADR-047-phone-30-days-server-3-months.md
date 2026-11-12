# ADR-047: The phone keeps 30 days of transcriptions and delete notes, the server keeps 3 months

**Status:** accepted
**Date:** 2026-11-12

## Context

Transcriptions pile up on the phone. The phone's purge (the ADR-010 precise rule) removes old rows, and tombstones (the phone's own delete notes) record deletes so sync can carry them to the server. Until now both the phone and the server used a 3-month window.

The project owner, 2026-11-12, asked whether the phone's delete notes need to live 90 days, and then ruled: "Lock it in at 30." and, on how to keep older text reachable: "Yeah let's change that with a "Load more" button that can call the server to load more so less is actually saved on the phone over time." Both answers hold: the phone's 30 days cover the transcriptions AND the delete notes.

## Decision

1. **The phone window is 30 days**, for transcriptions and for tombstones alike: 30 x 24 hours counted back from the current instant, on UTC instants, a row exactly at the cutoff kept (the ADR-010 precise rule with a 30 day window). One window covers both, so no second constant is needed. The same 30 days is the idle sign-out window of ADR-031.
2. **The server keeps 3 months** (90 x 24 hours, ADR-010), unchanged. The server therefore always keeps a row longer than the phone does.
3. **Load more.** A button on the history screen asks the server for the page of rows older than the oldest row on screen, newest first, a fixed page size. The rows it brings are shown for the session and are NOT saved on the phone. When the server cannot be reached, a quiet notice shows and the list stays as it is. Deleting a row from such a list sends a delete to the server (a tombstone is written and queued) although the row was never stored.
4. **A pull never re-stores a row older than the phone window:** the phone's apply of a pulled row refuses it, pinned by a test.

## Reasons

- Less text sits on the phone over time, as the project owner asked, and nothing is lost: the server still holds 3 months and Load more reaches it.
- One 30 day window for rows and delete notes is the simplest rule to state and to test, and it matches the 30 days after which an idle session ends.

## Consequences

- The phone and the server no longer use the same window. ADR-010 carries an amendment block; ARCHITECTURE.md F28 says phone 30 days, server 3 months; the history module card and README change with the code.
- A delete that never reached the server can be undone by a later pull after the phone dropped its own delete note, until that row reaches the server's 3 month retention (see the ADR-035 consequence).
- Rows shown by Load more are not on the phone, so they are gone from the screen when the session ends.

## Status of the build

DECIDED, NOT BUILT. On main the phone still keeps 90 days. The work comes in four chunks: the docs (this ADR, the ADR-010 amendment, F28, the ADR-035 consequence), the history module (the 30 day window, the tombstone sweep, the pull refusal), a server read route for older rows, and the client and the button.

## Not decided here

The page size and the shape of the server route (they come with the route), and any change to the server's 3 month rule (there is none).
