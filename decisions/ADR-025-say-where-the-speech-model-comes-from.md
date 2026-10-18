# ADR-025: The app tells the user where the speech model is downloaded from

**Status:** accepted
**Date:** 2026-10-12

## Context
The on-device speech model is about 366 MB (349 MiB) and is not shipped inside the app.
The first time the user taps the download button, the phone fetches it from the
public GitHub release page of the speech library's makers, not from the Local
Server. Nothing in the app said so, and the user had to ask where the file
came from.

## Decision
The model step in the launcher, and the notice shown while the download runs,
name the source in plain words: the download comes from GitHub, the public
page of the speech library makers; the Local Server is not used; the file is
checked before it is used. The size shown comes from the model registry entry,
never from a number written into the screen. The registry stores MiB, so the
screen converts it to the decimal MB that Android shows for storage, and a test
pins that unit.

## Reasons
A download of this size over mobile data is the user's to approve knowingly,
and a file that arrives from the internet should say so before it arrives.

## Consequences
- The registry entry is the single source for the address, the size and the
  fingerprint the app checks; the screen text reads the size from it.
- Changing the download host is a change to the registry and to this text
  together.
- Other downloads added later (for example further models) get the same
  kind of statement.
