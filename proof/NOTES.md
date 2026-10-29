# proof-u3

Failing-first runs and mutants for the updater slices after proof-u2: the recordless dismiss, the ledger
file with the install record, a one-line doc correction (no test, no red run), the commit router and the
public enum pin, the checker's install guard, the first-start engine with the install gate, and the
install facade with its Android shells. Run by `.github/workflows/proof.yml` through `proof/run.py`;
everything here lives only on this branch.

- Each red tree is the item base plus ONE of the item's test files, with the item's changed test fixtures
  riding along. Most legs are predicted to fail to compile on the item's new classes. The hygiene legs and
  the changed tests predict assertion failures. The enum pin is predicted NOT red: it pins entries that
  already exist. Every leg prints its prediction.
- Two mutants re-run from proof-u2: one predicted to SURVIVE as equivalent (for a gone session both
  branches return the same value), and one, an added public enum entry, now predicted to be killed by the
  enum pin.
- The Android shells (installer session, package manager, system log) have no JVM test; their behaviour
  is checked on a device, not here. Only their source-text pins run.
- Signature and type mutants are predicted BROKE: a compiler catch, never a kill. A mutant counts as
  KILLED only when it adds failures beyond the module baseline. A Gradle timeout is a TIMEOUT row.
- The kill and survive controls sit in the updater module (the version code bound).
