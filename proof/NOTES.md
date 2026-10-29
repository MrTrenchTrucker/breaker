# proof-2b

Failing-first runs and mutants for the whisper-server batch, run by `.github/workflows/proof.yml`
through `proof/run.py`. Everything here lives only on this dev/proof branch.

- Item a (the whole batch against main): every class of the module is new here, so every test of it
  fails to compile on main. Those legs are not run; `proof.json` says so on the item.
- Item b (the result time-to-live work against the module as it was before that work): each red
  tree is the tip tree with the whisper-server folder taken from that earlier state, plus ONE of the
  item's test files and the changed test fixture. These legs can fail on assertions.
- 15 mutants in the server module. Three further mutants on the API spec are checked by the Python
  contract test and are not part of this run.
- One mutant anchor (purge of the time-to-live results) is extended upward to the lines above it,
  because the same line also occurs in the expired-row purge; the mutation is unchanged.
- A mutant counts as KILLED only when it adds failures beyond the module baseline measured on the
  tip in the same shard.
