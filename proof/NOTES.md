# proof-q1

Failing-first run and mutants for one stt-ondevice change: the fed word stream reports its dropped
chunks and samples through its interface. Run by `.github/workflows/proof.yml` through `proof/run.py`;
everything here lives only on this branch.

- The red tree is the base plus the new test file. The test reads the two counts through the interface,
  which lacks them at the base, so it is predicted to fail to compile; the row lists the unresolved symbols.
- Two mutants change the two getters. A mutant counts as KILLED only when it adds failures beyond the
  module baseline. A Gradle timeout is a TIMEOUT row.
- The kill and survive controls sit on the chunk-count getter in the same module.
