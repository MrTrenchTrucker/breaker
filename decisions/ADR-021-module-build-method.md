# ADR-021: The maintainers' team builds each module through sub-agents that write the code

**Status:** accepted
**Date:** 2026-10-07
**Approved by:** project owner

## Context
Every module card already carries a "How This Module Is Built" section: one
owner per module, who splits the work, has sub-agents write the code, and
checks what comes back. That section is a description, recommended rather
than required. In practice an owner sometimes wrote code in its own working
context, and sub-agents sometimes started without reading the documents that
define their piece. Both lead to work that has to be sent back: the owner's
context fills with code instead of checking, and a sub-agent that has not read
its card guesses at boundaries the card already settles.

## Decision
For the maintainers' team, building a module or a sub-module works like this:

1. **Read first, at every level.** Before any planning, the owner reads the
   root `ARCHITECTURE.md`, the parent area's card (`android/AGENTS.md`,
   `server/AGENTS.md` or `shared/AGENTS.md`), the module's `AGENTS.md` and
   `README.md`, and every ADR they cite. Each sub-agent reads the same
   documents for its own level before it writes anything.
2. **One plan file per sub-agent.** For each sub-agent the owner writes a
   separate markdown plan file: what to build, which documents to read, the
   tests that must fail first, and a checklist of what "done" means. The
   sub-agent reads it before starting; the owner checks the result against
   that checklist. The sub-agent ticks each checklist item in the plan file as
   it finishes it, so the owner, and anyone reviewing later, can see what is
   done and what is left. Plan files travel with the pull request for review;
   they are not committed into the module.
3. **Sub-agents write the code; the owner does not.** The owner plans,
   coordinates and verifies. Work that is wrong goes back down to a
   sub-agent with the reason. The owner may make a one-line edit itself;
   anything larger is a sub-agent's job (a review does not reject correct work
   for how it was written; see Consequences).
4. **Levels.** An owner of a whole module plans at the module level; its
   sub-agents work at the sub-module level.
5. **One model tier for sub-agents.** The maintainers' sub-agents run on
   Claude Sonnet; an agent on another harness uses the sub-agent model that
   harness is configured with.
6. **Order of work.** The owner gets the assignment, looks over the job to see
   what it involves, reads its work order and the documents in point 1, and
   only then writes the plan files and starts the sub-agents.

## Reasons
- A sub-agent that has read its card and plan knows the boundaries it must
  keep; one that has not, guesses.
- An owner whose context holds only plans and results can check every piece
  against its checklist instead of reviewing its own code.
- A plan file per sub-agent makes the check repeatable: a reviewer can see
  what each piece was asked to do and whether it did it.

## Consequences
Rules in: for the maintainers' team, every pull request names its plan files,
and every review asks whether sub-agents wrote the code.

Amended 2026-10-07: the review judges the code, not only how it was written.
Correct, tested work is not rejected because the owner wrote more than one line
itself; the review records it and reminds the owner of the method. Wrong work
goes back down to a sub-agent as before.

Rules out nothing for outside contributors. For them this stays the method
described in each card and in `.github/CONTRIBUTING.md`: recommended for AI
agents, not required. What every contributor must meet is unchanged: the
card's rules, the fail-first test requirement and the passing checks.
