# proof-2f

Failing-first runs and mutants for one small change on the keyboard tile second batch: the privacy
line of the keyboard tile page now says exactly what the app remembers, two test files over 300
lines are split by topic with every test kept, and two module cards follow the service config.

- The red tree is the base plus ONE test file of the change. The two new shared test base classes
  (no tests of their own) ride along in every leg; they use no production member the change adds.
- The wording pin is predicted to fail on the old sentence. The moved tests are predicted to pass
  on the base, because moving a test changes no behaviour. The pinned file list is predicted to
  fail on the base, because it names a new test file.
- Mutants: one word changed in the new privacy sentence; each new file taken out of the pinned
  list.
