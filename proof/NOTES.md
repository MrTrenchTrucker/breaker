# proof-2c

A re-run of the seven failing-first legs of the whisper-server result time-to-live work, run by
`.github/workflows/proof.yml` through `proof/run.py`. Everything here lives only on this dev/proof
branch. No mutants: the earlier run of this batch already killed all fifteen.

Why a re-run: in the earlier run every leg failed to compile. The changed test fixture that rides
along in each leg calls two members the work adds (the result window constant and the store's ttl
parameter), and Kotlin compiles the whole test source set together, so every leg failed on the
fixture whatever its own test checked.

What changed in each red tree: the tip tree with the whisper-server folder taken from before the
work, plus ONE of the work's test files and the changed fixture (as before), plus signature-only
stubs for the members the work adds:

- the result window constant (same value as the work's);
- the store's ttl constructor parameter, which the stub ignores;
- the ttl purge, which the stub makes clear nothing;
- the config's ttl field with a default and its two constants, never read from the environment;
- the store builder, which ignores the config's ttl.

The stubs keep the behaviour of the code before the work: the fetch keeps its 24 h expiry and
nothing is purged by ttl. So each leg compiles, and a red is an assertion about the new behaviour.
Each red row prints its prediction, and the header lists the stubbed files. On any compile red the
row lists every unresolved symbol with the file that names it.
