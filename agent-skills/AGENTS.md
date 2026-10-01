# AGENTS.md — agent-skills/

## Purpose

The working rules and readback skills for AI agents that contribute to Breaker,
one pair per rank (project leader, IT manager, worker), so any contributor can
set up their agents to work the way the project is built (ADR-019).

**Build phase:** Not a build module: it ships instruction files. It is on main.

## Owns
- The six rank skill files: one module SOP skill and one readback skill each for
  project leader, IT manager and worker.
- Their pinned hashes (`SKILLS.sha256`).
- The install guide (`README.md`).
- Their license notice (`LICENSE`, MIT).

## Does Not Own
- The project's own rules: `ARCHITECTURE.md`, the module cards and the ADRs.
  The skills defer to them.
- The contributing guide (`.github/CONTRIBUTING.md`).
- Any code. Nothing here is compiled or imported.

## Public Interface
- `agent-skills/<rank>/<skill-name>/SKILL.md`: the six skill files, where
  `<rank>` is `project-leader`, `it-manager` or `worker`, and `<skill-name>` is
  the skill's own front-matter `name`.
- `agent-skills/README.md`: which rank to give which agent, and how to install
  the skills.

## Depends On
- none

## Invariants
- Every `SKILL.md` matches its hash in `SKILLS.sha256` exactly. The skills are
  copied unchanged from their source, never edited here.
- Every skill sits in a folder named after its front-matter `name`.
- Each rank folder holds exactly one `module-sop-*` skill and one
  `parrot-protocol-*` skill, and no other skills.
- Nothing here names a private person, host, service or credential. The files
  are scanned for that before every change.

## Test Locations
- Contract: `tests/contract/test_agent_skills_contract.py` (the structural
  contract plus every invariant above). Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_agent_skills_contract.py`
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.

## Test Requirement
Every test added or touched for this module must be proven to fail loudly:
break the protected behavior on purpose, confirm the test fails and says why,
then restore the code. A test that only ever passes proves nothing. This
applies to every tier and invariant listed above, not only the ones that seem
fragile.

## How This Module Is Built
One engineer owns this module and delivers it as one pull request, working on
one module at a time. The owner does not write the module's code. The owner:
- splits the work into sub-modules and has sub-agents write each one, code and
  tests;
- coordinates and orchestrates those sub-agents, checks every piece of their
  work, and sends back anything that is wrong until it is right;
- convenes a small council of sub-agents to advise on design, risks and tests
  before and during the build;
- hands the finished, checked module directly to the reviewer as a single pull
  request.
The owner's own work is orchestration, checking and correction, not writing
code.

This is how the project's own team builds modules. It is recommended for AI
agents, not required: an outside contributor may write the code themselves
(`.github/CONTRIBUTING.md`).

## Known Gotchas
- Install each skill into a folder named after its front-matter `name`
  (`module-sop-itm`), not after the rank folder here (`it-manager`). An agent
  that looks skills up by name won't find a skill in the wrong folder.
- "Commander" in the skills is the role of the human at the top of the chain,
  not a person's name.
- The skills name files this repo doesn't have (`APPROVALS.md`,
  `SMOKE_TEST.md`, `workorders/`, `.sop/function_index.json`). Follow
  `ARCHITECTURE.md`, the module cards and the contributing guide instead. The
  README says so.
- Never fix a skill in place: the hash test fails. Replace the file from its
  source and update `SKILLS.sha256` in the same pull request.
