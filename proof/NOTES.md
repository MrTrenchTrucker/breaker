# proof-u1

Failing-first runs and mutants for the updater module (client side), run by
`.github/workflows/proof.yml` through `proof/run.py`. Everything here lives only on this dev/proof
branch.

- Five items, in the order they stack: the core update port (a sealed install result, no rollback
  call), the schedule decision and ledger port with the module hygiene scan, the manifest reader
  with the version code and the download origin, the manifest signature with pinned keys and the
  stream digest, and the update checker.
- Each red tree is the item's base plus ONE of the item's test files; changed test fixtures (no
  tests of their own) ride along. Every class of the four updater items is new, so their legs are
  expected to fail to compile; the core item adds its new type inside an existing file. On any
  compile red the row lists every unresolved symbol.
- The kill and survive controls sit in the updater module itself (the version code bound).
- Four planted firings run as mutants on real main files: a thread primitive, a network import, a
  file read and a file padded past 300 lines. Each must turn its hygiene test red.
- A mutant counts as KILLED only when it adds failures beyond the module baseline. A Gradle
  timeout is a TIMEOUT row, never a kill.
