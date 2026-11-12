"""Pins 8 to 10 of CONTRACT_S2A.md section 8: the apply_rewrites count check, the docs
texts that state the four served rewrites, and the registry text that names signin-kdf.

Kept in a second file because web_libs_rewrite_pins.py is at its 300-line cap. Not a
`test_*.py` file: the class is collected through `test_web_libs_contract.py`. Each check
runs on the real text, then on an altered copy (a named mutant) that must be reported.
"""

import os
import re
import unittest

import contract_support
from app_sync_wiring_pins import _scan_kotlin
from web_libs_rewrite_pins import HELPER, _helper
from weblibs_copy_mapping import _task_body

ROOT = contract_support.ROOT
NOTICE = os.path.join(ROOT, "NOTICE")
ADR_40 = os.path.join(ROOT, "decisions", "ADR-040-the-website-uses-noble-for-key-derivation-and-the-sealed-box.md")
README = os.path.join(ROOT, "shared", "modules", "web-libs", "noble-curves", "README.md")
MODULES = os.path.join(ROOT, "modules.toml")

# The count check of apply_rewrites as it stands in the helper; the 'no count check' mutant removes it.
COUNT_CHECK = (
    "        count = text.count(before)\n"
    "        if count != 1:\n"
    "            raise ValueError(\"%s: rewrite line found %d times, expected 1\" % (rel_path, count))\n"
)
TOY_BEFORE = "toy line before the rewrite"
TOY_AFTER = "toy line after the rewrite"
NOTICE_KEYS = ["four import-path rewrites", "unmodified copies"]
ADR_KEYS = ["four specifier rewrites", "unmodified"]
README_KEYS = ["four import-path rewrites", "unmodified"]
SIGNIN_KDF = "js/signin-kdf.js"


class _Holder:
    """Carries the globals of an exec'd helper copy, so REWRITES can be set on it like on a module."""


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def refusal_problems(helper):
    """apply_rewrites must raise ValueError, naming the file, for a missing and for a repeated before."""
    problems = []
    helper.REWRITES = [("toy.js", TOY_BEFORE, TOY_AFTER)]
    for label, text in (("missing", "nothing here"), ("repeated", TOY_BEFORE + "\n" + TOY_BEFORE)):
        try:
            helper.apply_rewrites({"toy.js": text})
        except ValueError as exc:
            if "toy.js" not in str(exc):
                problems.append("%s: the ValueError does not name the file" % label)
        else:
            problems.append("%s: apply_rewrites did not raise ValueError" % label)
    return problems


def mutant_without_count_check():
    """A copy of the helper with the count check removed; the rest of the function is unchanged."""
    source = _read(HELPER)
    mutant = source.replace(COUNT_CHECK, "")
    holder = _Holder()
    holder.__dict__ = {"__file__": HELPER, "__name__": "mutant_helper"}
    exec(compile(mutant, HELPER, "exec"), holder.__dict__)
    return holder


def entry_block(text, head):
    """The lines from the numbered entry that starts with [head] up to the next numbered entry."""
    lines = text.split("\n")
    starts = [i for i, line in enumerate(lines) if line.startswith(head)]
    if len(starts) != 1:
        return ""
    block = [lines[starts[0]]]
    for line in lines[starts[0] + 1:]:
        if re.match(r"\d+\. ", line):
            break
        block.append(line)
    return "\n".join(block)


def docs_problems(notice, adr, readme):
    problems = []
    block = entry_block(notice, "8. @noble/curves")
    problems += ["NOTICE entry 8 lacks %r" % key for key in NOTICE_KEYS if key not in block]
    updates = [line for line in adr.split("\n") if line.strip().startswith("Update 2026-11-11")]
    if len(updates) != 1:
        problems.append("ADR-040 has %d Update 2026-11-11 lines, expected 1" % len(updates))
    else:
        problems += ["ADR-040 Update line lacks %r" % key for key in ADR_KEYS if key not in updates[0]]
    problems += ["noble-curves README lacks %r" % key for key in README_KEYS if key not in readme]
    return problems


def registry_problems(toml_text):
    """The public text of [module.server_web_fe] names js/signin-kdf.js."""
    lines = toml_text.split("\n")
    starts = [i for i, line in enumerate(lines) if line.strip() == "[module.server_web_fe]"]
    if len(starts) != 1:
        return ["[module.server_web_fe] header found %d times, expected 1" % len(starts)]
    public = None
    for line in lines[starts[0] + 1:]:
        if line.startswith("["):
            break
        if line.strip().startswith("public = "):
            public = line.strip()
            break
    if public is None:
        return ["[module.server_web_fe] has no public line"]
    return [] if SIGNIN_KDF in public else ["the public text of [module.server_web_fe] lacks %s" % SIGNIN_KDF]


class WebLibsRewriteTextPinsTest(unittest.TestCase):
    """Pins 8 to 10 of section 8. Each test ends with its named mutant."""

    def setUp(self):
        self.notice = _read(NOTICE)
        self.adr = _read(ADR_40)
        self.readme = _read(README)
        self.toml = _read(MODULES)

    def test_apply_rewrites_refuses_a_missing_or_repeated_before(self):
        helper = _helper()
        helper.REWRITES = [("toy.js", TOY_BEFORE, TOY_AFTER)]
        self.assertEqual(helper.apply_rewrites({"toy.js": TOY_BEFORE}), {"toy.js": TOY_AFTER})
        self.assertEqual(refusal_problems(_helper()), [])
        # Mutant "no count check": the helper without its count check returns instead of raising.
        self.assertTrue(refusal_problems(mutant_without_count_check()), "mutant 'no count check' was not reported")

    def test_docs_state_the_four_rewrites(self):
        self.assertEqual(docs_problems(self.notice, self.adr, self.readme), [])
        # Mutant "NOTICE without its sentence": the four-rewrites phrase removed from NOTICE entry 8.
        mutant = self.notice.replace(NOTICE_KEYS[0], "")
        self.assertNotEqual(mutant, self.notice, "mutant 'NOTICE without its sentence' did not land")
        self.assertTrue(docs_problems(mutant, self.adr, self.readme), "mutant 'NOTICE without its sentence' was not reported")
        # Mutant "ADR-040 Update line without its sentence": the specifier-rewrites phrase removed.
        mutant = self.adr.replace(ADR_KEYS[0], "")
        self.assertNotEqual(mutant, self.adr, "mutant 'ADR-040 Update line without its sentence' did not land")
        self.assertTrue(docs_problems(self.notice, mutant, self.readme), "mutant 'ADR-040 Update line without its sentence' was not reported")
        # Mutant "README without its sentence": the four-rewrites phrase removed from the README.
        mutant = self.readme.replace(README_KEYS[0], "")
        self.assertNotEqual(mutant, self.readme, "mutant 'README without its sentence' did not land")
        self.assertTrue(docs_problems(self.notice, self.adr, mutant), "mutant 'README without its sentence' was not reported")

    def test_registry_text_lists_signin_kdf(self):
        self.assertEqual(registry_problems(self.toml), [])
        # Mutant "registry line without js/signin-kdf.js": the entry with that one name removed.
        mutant = self.toml.replace(SIGNIN_KDF + ", ", "")
        self.assertNotEqual(mutant, self.toml, "mutant 'registry line without js/signin-kdf.js' did not land")
        self.assertTrue(registry_problems(mutant), "mutant 'registry line without js/signin-kdf.js' was not reported")

    def test_gradle_copy_doLast_reads_the_captured_served_dir_only(self):
        build_kts = os.path.join(ROOT, "server", "modules", "web-fe", "build.gradle.kts")
        with open(build_kts, encoding="utf-8") as fh:
            code = _scan_kotlin(fh.read(), keep_strings=True)
        capture = 'val nobleCurvesServedDir = layout.buildDirectory.dir("web/js/vendor/noble-curves")'
        marker = 'tasks.register<Copy>("copyWebLibs")'

        def problems(text):
            found = []
            body = _task_body(text)
            at = body.find("doLast")
            if at < 0:
                return ["the copyWebLibs body has no doLast"]
            open_brace = body.index("{", at)
            depth = 0
            for end in range(open_brace, len(body)):
                if body[end] == "{":
                    depth += 1
                elif body[end] == "}":
                    depth -= 1
                    if depth == 0:
                        break
            do_last = body[open_brace:end + 1]
            if "layout." in do_last:
                found.append("doLast reads layout")
            if "nobleCurvesServedDir" not in do_last:
                found.append("doLast does not use nobleCurvesServedDir")
            if text.count(capture) != 1:
                found.append("the capture line occurs %d times, expected 1" % text.count(capture))
            elif text.find(capture) > text.find(marker):
                found.append("the capture line is inside the copyWebLibs task")
            return found

        self.assertEqual(problems(code), [])
        # Mutant "doLast reads layout again": the doLast target line reads layout as it did before the hunk.
        mutant = code.replace(
            "File(nobleCurvesServedDir.get().asFile, rel)",
            'File(layout.buildDirectory.dir("web/js/vendor/noble-curves").get().asFile, rel)',
        )
        self.assertNotEqual(mutant, code, "mutant 'doLast reads layout again' did not land")
        self.assertTrue(problems(mutant), "mutant 'doLast reads layout again' was not reported")


if __name__ == "__main__":
    unittest.main()
