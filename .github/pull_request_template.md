<!--
One module per pull request. Fill in every section; write "none" rather
than deleting one. The full checklist is in .github/CONTRIBUTING.md, section 7.
-->

Module: <!-- e.g. android/modules/audio -->
<!-- The module's main PR: "Closes #NNN". A sub-PR into module/<name>: "Refs #NNN" and "Part of #MMM". -->
Closes #

## What and why

## Test evidence
<!--
For each test added or changed: how you broke the code on purpose, the
failing output (the assertion that fired, in words), and the same test
passing after. A docs-only PR says what was checked instead.
-->

## Not verified
<!-- What you could not check, or "none". -->

## AI tools used
<!-- Which AI tools or agents helped with this PR, and roughly how much. "none" is fine. -->

## Checklist
- [ ] Finished work only: built and tested locally before I opened this PR; nothing here is a draft or half-done.
- [ ] One module, and I claimed its issue.
- [ ] I read `ARCHITECTURE.md`, the module's card and README, and every ADR the card cites.
- [ ] I changed only what the card says the module owns.
- [ ] No new module, public-interface change or dependency without a maintainer's OK.
- [ ] Tests ship in this PR, each watched failing first.
- [ ] The module's README is updated; card changes are proposed above, not edited.
- [ ] `python3 tools/check_repo.py` prints `REPO CONSISTENT`.
- [ ] `./gradlew --no-daemon --no-build-cache --rerun-tasks build` passes.
- [ ] No secrets, private hostnames or personal data anywhere.
