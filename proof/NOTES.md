# proof-u2

Failing-first runs and mutants for the next four updater slices, on the stack after proof-u1 and its
test-only follow-up: the installer port change with the unreadable installed version, the manifest
read over https, the download into app-private staging, the registry line for the install module's
public names (no test, no red run), and the first-start decision table. Run by
`.github/workflows/proof.yml` through `proof/run.py`; everything here lives only on this branch.

- Each red tree is the item base plus ONE of the item's test files, with the item's changed test
  fixtures riding along. Nearly every class here is new, so most legs are predicted to fail to
  compile on the item's new classes; every compile red lists the unresolved symbols. The download
  item's hygiene leg is predicted to fail an assertion. Every leg prints its prediction.
- In the manifest item, the hygiene leg also carries the shared HTTP test fakes, which name the
  item's new opener types, so it is predicted to fail to compile. Its allow-list assertions are pinned
  by the hygiene mutants instead.
- One manifest-item mutant targeted a hygiene line that the next item rewrote; it is anchored to the
  current text of that line with the same expected red (a name that is not a main file).
- Signature and type mutants are predicted BROKE: a compiler catch, never a kill. A mutant counts as
  KILLED only when it adds failures beyond the module baseline. A Gradle timeout is a TIMEOUT row.
- The kill and survive controls sit in the updater module (the version code bound).
