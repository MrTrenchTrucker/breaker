<!--
Copy this file to decisions/ADR-NNN-<slug>.md, replace NNN with the next
free number (see decisions/README.md for the current index), fill in every
section, and delete every HTML comment (including this one) before opening
the PR.
-->
# ADR-NNN: <the decision itself, in a few words — what was chosen, not just the topic>
<!--
State the decision, not the subject: "Server-primary transcription with
automatic local fallback", not "Transcription mode".

If this ADR fully replaces an earlier one, add "(supersedes ADR-0NN)" after
the title — e.g. "Trucking UI + CB mic motif (supersedes CB Radio palette)".
-->

**Status:** proposed
<!--
One of:
  - proposed                — opened in a PR, not yet approved
  - accepted                — approved by a maintainer, currently in effect
  - accepted, amended <YYYY-MM-DD> (<what changed, briefly>)
                             — this same ADR was revised in place; see
                               "Amending vs. superseding" at the end of this
                               file
  - superseded by ADR-0NN   — no longer in effect; the file stays, it is
                               never deleted
-->
**Date:** YYYY-MM-DD
<!--
The date this ADR was accepted. Set it once and never change it — an
amendment gets its own date inside the Status line above; the original Date
line stays as it was.
-->
**Approved by:** <maintainer name>
<!--
Optional — most ADRs in this repo omit this line entirely. Add it only when
it's worth recording who signed off (a contested or unusually significant
call).
-->

<!--
Optional: one or two lines here, above ## Context, pointing at a closely
related ADR the reader should check too (e.g. one ADR says "see ADR-018 for
X, which this one doesn't cover"). Delete this comment if there's nothing to
point at.
-->

## Context
<!--
The situation that forced this decision: the constraint, requirement, bug or
conflicting need that made a choice unavoidable right now. State the
constraint plainly, not your reasoning about it — that belongs in Reasons
below. If it traces to one of the two security reviews cited project-wide,
reuse the existing [1] / [2] markers defined in the root README.md's
References section rather than inventing a new citation number.
-->

## Decision
<!--
What will be built or done — concrete enough that someone could act on it
without a follow-up question. Name the actual files, modules, endpoints or
behavior where that helps. This is the part a future PR is bound by, and the
part a later ADR has to cite if it wants to override it.
-->

## Reasons
<!--
Bullet list, one line per reason: why this option over the alternatives, the
tradeoff that settled it. Reuse [1] / [2] here too where a reason traces to
one of the two cited security reviews.
-->
- <reason>
- <reason>

## Consequences
<!--
Two short statements, not a pros/cons list — keep the "Rules in:" /
"Rules out:" labels exactly; most ADRs in this repo use them.

Rules in: what this decision commits the project to.
Rules out: what it now forecloses — alternatives considered and rejected,
and anything a later PR shouldn't try to quietly bring back.
-->
Rules in: <…>.

Rules out: <…>.

<!--
## Amending vs. superseding an existing ADR

Small revision, same decision, same scope (this repo's ADR-006 did this):
  1. Edit the existing ADR-0NN file in place — same number, same filename.
  2. Change its Status line to "accepted, amended <today's date> (<what
     changed, in a few words>)". Leave the Date line at the original
     acceptance date.
  3. Add a short paragraph before (or inside) Context explaining what the
     earlier version got wrong or missed, and why the amendment is right.

Full replacement (a different decision takes over the same ground):
  1. Write a new ADR at the next free number, titled
     "<new decision> (supersedes ADR-0NN)".
  2. Go back and change the OLD ADR's Status line to
     "superseded by ADR-<new number>". Never delete the old file — the
     record of why the project changed its mind is part of the point.

Either way: a PR that goes against a settled ADR must cite it by number in
the PR description and argue for changing it (see .github/CONTRIBUTING.md) — it
can't just quietly do something different and let the diff speak for itself.
-->
