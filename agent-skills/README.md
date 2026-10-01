# agent-skills — set up your AI agents to work on Breaker

Breaker is built mostly by AI coding agents working in ranks. This folder holds
the skills those agents run: instruction files that give an agent the working
rules for its rank. Load them into your own agents and they will work the way
the rest of the project does.

Each rank has two skills:

- a **module SOP** skill: how that rank works with modules, module cards,
  tests and reviews;
- a **readback** skill (the "Parrot Protocol"): how an order is passed down
  word for word, and read back in the agent's own words before any work starts.

| Rank | What it does | Skills |
|---|---|---|
| Project leader | Owns the plan and the module structure (registry, cards, ADRs) and checks that the modules fit together. Usually a maintainer. | `project-leader/module-sop-project-leader`, `project-leader/parrot-protocol-project-leader` |
| IT manager | Splits one module into slices, gives each slice to a worker, and checks every piece of work before it moves on. | `it-manager/module-sop-itm`, `it-manager/parrot-protocol-itm` |
| Worker | Writes one slice of one module, with tests, inside that module's card. | `worker/module-sop-worker`, `worker/parrot-protocol-worker` |

## Which rank do I need?

- **One agent helping you write code:** give it the worker pair. You act as its
  IT manager: you give the order, check its readback, and review its work.
- **One agent building a whole module for you:** give it the IT manager pair.
  If it uses sub-agents to write the code, give them the worker pair.
- **The project leader pair** is for maintainers. Most contributors don't need it.

## Install

1. Copy each `SKILL.md` you need into your agent's skills folder, in a folder
   named after the `name:` line at the top of the file, not after the rank
   folder here. For example, `worker/module-sop-worker/SKILL.md` goes to
   `<your skills folder>/module-sop-worker/SKILL.md`.
2. Have the agent load both skills of its rank at the start of every session
   and before every task.
3. Don't edit your installed copies. If a rule looks wrong, open an issue.

These are standard `SKILL.md` files: YAML front matter (`name`, `description`)
followed by Markdown. If your agent doesn't support skills, give it the file as
its instructions.

## Words the skills use

- **Commander**: the human at the top of the chain. For your own agents that's
  you. For anything that lands on `main`, it's the project's maintainers.
- **Module card**: a module's `AGENTS.md`, which says what the module owns, what
  it doesn't, its interface, its tests, and what must always hold.
- **Work order**: one assigned piece of work. In Breaker, that's a GitHub issue
  and the pull request that closes it.
- **Readback**: before starting, the agent restates in its own words what it
  must not do and what the finished work will look like, then waits for an OK.
- The skills also name files Breaker doesn't have. Use these instead:

  | The skills say | In Breaker |
  |---|---|
  | `workorders/WO-NNN.md` (a work order) | The module's GitHub issue and the pull request that closes it |
  | `APPROVALS.md` (an approval record) | A maintainer's approving review or comment on the pull request |
  | `SMOKE_TEST.md` (a smoke-test record) | The phase's exit criteria in `docs/04-build-order.md`, proven in the pull request's test evidence |
  | `.sop/function_index.json` (a function index) | The module card's Public Interface and the module's `public` entry in `modules.toml`; search the code before adding a function |

- When a skill and Breaker's own docs disagree, Breaker's docs win, in this
  order: root `AGENTS.md`, then `.github/CONTRIBUTING.md`, then the module's
  card. The skills describe how to work; Breaker's docs say what to build and
  how it is reviewed.

## Where the skills come from

They are unchanged copies of the project owner's module SOP and Parrot Protocol
skill sets, published at https://github.com/MrTrenchTrucker/module-sop.
`SKILLS.sha256` pins every file, and the contract test fails if a
copy changes. The skills are MIT-licensed (see `LICENSE` in this folder). The
rest of Breaker is Apache-2.0.

To update a skill, replace the file from its source, update `SKILLS.sha256`,
and do both in the same pull request.

Full module card: `AGENTS.md` in this folder.
