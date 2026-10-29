# proof-2

Failing-first runs and mutants for the keyboard tile second batch, run by
`.github/workflows/proof.yml` through `proof/run.py`. Everything here lives only on this dev/proof
branch.

- Item a: the keyboard tile permission page in first run, and the removal of the debug keyboard
  probe page and its settings. Item b: probe readings stamped from the monotonic clock.
- Each red tree is the item's base tree plus ONE of the item's test files from the item tip. No
  changed test fixture rides along in this batch. Each red row prints its prediction.
- Several first-run legs are predicted to fail to compile. In every case the missing symbols are
  members the item adds (the new page and its text constants), not new classes. On any compile red
  the row lists every unresolved symbol with the file that names it, so this can be checked from
  the summary.
- The removal has no new behaviour, so its proof is three planted firings, run as mutants on the
  real files: a launcher filter planted in the real debug manifest (two kept manifest rules must
  refuse it), and a real debug source file padded past two hundred lines (the size rule must refuse
  it).
- A mutant counts as KILLED only when it adds failures beyond the module baseline measured on the
  tip in the same shard.
