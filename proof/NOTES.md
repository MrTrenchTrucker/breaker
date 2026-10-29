# proof-u1b

Re-runs ONE mutant of proof-u1 on the updater stack after a test-only change: the core port
contract test now holds its fake through the port type, so it reads install() as the port declares it.

- The mutant makes install() return a nullable result. It is predicted BROKE: the contract test no
  longer compiles against it. That is a compiler catch, reported as BROKE, never as a kill.
- No red legs: the change is a test line only.
- The kill and survive controls are the updater module's, as in proof-u1.
