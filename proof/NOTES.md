# proof-1c

Failing-first runs and mutants for the keyboard tile fix round (a switch-off releases the raised
mark; the wiring tests reset the reading holder and wait on a signal), run by
`.github/workflows/proof.yml` through `proof/run.py`. Everything here lives only on this dev/proof branch.

- Three red legs, each with its prediction. Only the switch-off test is expected to fail on the base;
  the other two are test-only changes.
- Four mutants. One (the holder reset removed from the test setup) is predicted to survive, because
  JUnit does not fix the order of tests; it is reported as measured either way.
- A mutant counts as KILLED only when it adds failures beyond the module baseline measured on the
  tip in the same shard.
