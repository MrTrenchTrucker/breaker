# proof-1z

Failing-first runs and mutants for the start-up purge clock guard and the settings-load theme
fallback, run by `.github/workflows/proof.yml` through `proof/run.py`. Everything here lives only on
this dev/proof branch.

- `proof.json`: the declared tip tree, the item with its base tree and item tip tree, the red
  entries (each with its prediction), six mutants and the two controls.
- `red/*.patch`: each patch turns the tip tree into the base tree plus ONE of the item's test files.
  The runner checks the resulting tree hash before it runs.
- A mutant counts as KILLED only when it adds failures beyond the module baseline measured on the
  tip in the same shard.
