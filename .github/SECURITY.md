# Security Policy

Breaker handles people's speech and the text made from it, so security
reports matter to us. Thank you for taking the time.

## Reporting a vulnerability

**Please do not open a public issue, discussion or pull request for a
security problem.** Report it privately through GitHub instead:

1. Open the repository's **Security** tab.
2. Click **Report a vulnerability**.
3. Describe the problem: what is affected, how to reproduce it, and what an
   attacker could do with it. A proof of concept helps, if you have one.

Only the maintainers can see the report. We will reply in that report,
work on a fix there, and credit you when the fix is published unless you
ask us not to.

## What is in scope

Everything in this repository: the Android app modules, the server modules,
the shared contracts, the build, and the design documents themselves. A
design flaw, such as a claim in `decisions/ADR-006-encryption.md` that does
not hold, is a valid report even though no code exists for it yet.

## Known limits, already documented

Some limits are stated openly in the docs and are not new findings on
their own:

- `docs/03-security-threat-model.md` lists every threat with its
  mitigation and its honest limits (for example, offline password guessing
  against leaked wrapped keys, and a compromised server serving the web
  frontend's code).
- sherpa-onnx's transducer greedy-search decoder has an open upstream bug
  (issue #3983). Breaker keeps sherpa-onnx off the server's network-facing
  path and pins every model to an immutable release-asset id verified
  against upstream's checksum (`decisions/ADR-003-sherpa-onnx.md`).
- Brute-force protection for the login endpoints is out of scope for the
  first version (`docs/01-requirements.md`).

A report that shows one of these is worse than the docs say is welcome.

## Supported versions

Breaker has no release yet. Reports apply to the `main` branch.
