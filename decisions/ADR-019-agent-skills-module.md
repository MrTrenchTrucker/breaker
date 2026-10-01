# ADR-019: Agent skills ship in the repo as their own module

**Status:** accepted
**Date:** 2026-09-30
**Approved by:** project owner

## Context
Breaker is built mostly by AI coding agents working in ranks: a project leader
owns the plan and the module structure, IT managers split a module into slices
and check every piece, and workers write one slice at a time. Every module card
already assumes that way of working. The skills those agents run, the rules for
each rank and the readback protocol, lived outside this repository. An outside
contributor could read the cards but had no way to set up their own agents to
work the same way.

## Decision
The six rank skills (one module SOP skill and one readback skill each for
project leader, IT manager and worker) ship inside this repository as one
top-level module, `agent-skills/`, registered like every other module. The
files are copied unchanged from their source and pinned by hash. The module has
a card, a README that serves as the install guide, a `base`-plugin build file
(nothing compiles), and a contract test.

## Reasons
- A contributor who can load the same skills works the way the rest of the
  project does, with no extra briefing.
- One top-level module, not a copy per level: each module's card already says
  what that module owns, and the skills are the method an agent loads once per
  session.
- Hash pins make any drift visible. A skill fixed in place here would quietly
  diverge from its source, and the contract test turns that into a failure.
- Registering it keeps the rule that every folder with a card is a module, so
  the consistency checker and the contract tests cover it too.

## Consequences
- The skill files stay under the MIT License of their source (`agent-skills/LICENSE`,
  recorded in NOTICE). The rest of the repository remains Apache-2.0.
- A skill changes only by replacing the file from its source and updating
  `agent-skills/SKILLS.sha256` in the same pull request.
- The skills name some files this repository doesn't have (`APPROVALS.md`,
  `SMOKE_TEST.md`, work-order files, a function index). The module README maps
  them to Breaker's own equivalents: the module cards, `ARCHITECTURE.md` and the
  contributing guide.
- The contributing guide points agent users at `agent-skills/README.md`.
