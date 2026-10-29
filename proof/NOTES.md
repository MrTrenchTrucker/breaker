# proof-1

Failing-first runs and mutants for the keyboard tile batch, run by `.github/workflows/proof.yml`
through `proof/run.py`. Everything here lives only on this dev/proof branch.

- `proof.json`: the declared tip tree, the three items with their base tree and item tip tree,
  the red entries, the mutants and the two controls.
- `red/*.patch`: each patch turns the tip tree into one item's base tree plus ONE of the item's
  test files from the item tip. The runner checks the resulting tree hash before it runs.
- In the first item's red trees, three base test files carried old mutant-label words in their
  comments. Those words are removed from the comments only; the code with comments removed is
  byte-identical to the base.
- Mutants mut-08 and mut-13 target lines that a later item in the batch rewrote. Their anchors carry the
  current text of the same line; the mutation is unchanged.
- One mutant (mut-07) is predicted to survive; it is reported as measured either way.
- A mutant counts as KILLED only when it adds failures beyond the module baseline measured on the
  tip in the same shard. COMPILE RED is reported with the item's new production classes, so a
  reader can tell whether the compile failure is the expected kind.
