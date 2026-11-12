"""Pins for the served copy of noble-curves: four specifier rewrites and nothing else.

The served copy (node test tree from tools/assemble_weblibs_test_tree.py, and the web-fe
copyWebLibs task) rewrites exactly four bare '@noble/hashes/...' import lines. The table
is REWRITE_LIB, REWRITES and apply_rewrites in the helper; the Gradle task mirrors it as
nobleCurvesRewrites. Not a `test_*.py` file on purpose (contract-file bijection): the class
is collected through `test_web_libs_contract.py`. Each check is a plain function run on the
real tree and on a named mutant that must be reported.
"""

import hashlib
import importlib.util
import os
import posixpath
import re
import unittest

import contract_support
from app_sync_wiring_pins import _scan_kotlin
from web_libs_pins import SUB_MODULE_FILES
from weblibs_copy_mapping import _task_body

ROOT = contract_support.ROOT
WEBLIBS = os.path.join(ROOT, "shared", "modules", "web-libs")
CURVES_DIR = os.path.join(WEBLIBS, "noble-curves")
HASHES_DIR = os.path.join(WEBLIBS, "noble-hashes")
HELPER = os.path.join(ROOT, "tools", "assemble_weblibs_test_tree.py")
BUILD_KTS = os.path.join(ROOT, "server", "modules", "web-fe", "build.gradle.kts")
NOBLE_LIBS = ["noble-ciphers", "noble-hashes", "noble-curves"]
REWRITTEN_FILES = ["utils.js", "ed25519.js", "abstract/frost.js"]

# The four bare lines of the contract, section 0: (rel_path, whole line).
EXPECTED_PAIRS = [
    ("utils.js", "import { abytes as abytes_, anumber as anumber_, bytesToHex as bytesToHex_, concatBytes as concatBytes_, hexToBytes as hexToBytes_, isBytes as isBytes_, randomBytes as randomBytes_, } from '@noble/hashes/utils.js';"),
    ("ed25519.js", "import { sha512 } from '@noble/hashes/sha2.js';"),
    ("ed25519.js", "import { abytes, concatBytes, hexToBytes } from '@noble/hashes/utils.js';"),
    ("abstract/frost.js", "import { utf8ToBytes } from '@noble/hashes/utils.js';"),
]

# A utils.js line that occurs once and holds no quote; the fifth-substitution mutant rewrites it.
FIFTH_LINE = "export const anumber = anumber_;"
BARE_LINE = "import { x } from '@noble/hashes/y.js';"
DYNAMIC_LINE = "const z = import('./z.js');"

# sha256 of each served file, written in pieces of six characters.
SERVED_SHA256 = {
    "utils.js": "".join(["6a66bc", "ce57dd", "abddd6", "8776ea", "5211be", "52c226", "984723", "c440cd", "79d795", "ccecb0", "f0d1"]),
    "ed25519.js": "".join(["f28777", "0bc7c7", "29964e", "aa8b29", "f68b3e", "3abff2", "3db922", "d4f628", "4c95e2", "0586e0", "9de2"]),
    "abstract/frost.js": "".join(["8defa9", "722591", "5ae6ab", "c53a6b", "930a43", "c69941", "aac07f", "a08f92", "eb135c", "d7dbe7", "e9d9"]),
}

GRADLE_MARKER = 'tasks.register<Copy>("copyWebLibs")'
MODULE_FROM_RE = re.compile(r"""\bfrom\s*['"]([^'"]*)['"]""")
SIDE_EFFECT_RE = re.compile(r"""^\s*import\s*['"]([^'"]*)['"]""")
HEAD_RE = re.compile(r"""^\s*(?:(?:import|export)\b|\})""")
DYNAMIC_RE = re.compile(r"\bimport\s*\(")


def _helper():
    spec = importlib.util.spec_from_file_location("assemble_weblibs_test_tree", HELPER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _raw(rel):
    with open(os.path.join(CURVES_DIR, *rel.split("/")), "rb") as fh:
        return fh.read()


def _path_only(before, after):
    b, a = before.split("'"), after.split("'")
    return (len(b) == 3 and len(a) == 3 and b[0] == a[0] and b[2] == a[2]
            and b[1].startswith("@noble/hashes/") and a[1].startswith("../"))


def table_problems(rewrites, lib):
    problems = []
    if len(rewrites) != 4:
        problems.append("the table holds %d rewrites, expected 4" % len(rewrites))
    if lib != "noble-curves":
        problems.append("the table names %r, expected noble-curves" % lib)
    if [(rel, before) for rel, before, _after in rewrites] != EXPECTED_PAIRS:
        problems.append("the (rel_path, before) pairs differ from the four lines of the contract")
    return problems


def served_problems(helper):
    problems = []
    pins = dict(next(files for sub, files in SUB_MODULE_FILES if sub == "noble-curves"))
    raw = {rel: _raw(rel) for rel in REWRITTEN_FILES}
    for rel in REWRITTEN_FILES:
        if hashlib.sha256(raw[rel]).hexdigest() != pins[rel]:
            problems.append("source %s no longer matches its pin" % rel)
    served = helper.apply_rewrites({rel: raw[rel].decode("utf-8") for rel in REWRITTEN_FILES})
    for rel in REWRITTEN_FILES:
        got = hashlib.sha256(served[rel].encode("utf-8")).hexdigest()
        if got != SERVED_SHA256[rel]:
            problems.append("served %s hashes to %s, pinned %s" % (rel, got, SERVED_SHA256[rel]))
    return problems


def only_four_lines_problems(helper):
    problems = []
    texts = {rel: _raw(rel).decode("utf-8") for rel in REWRITTEN_FILES}
    served = helper.apply_rewrites(texts)
    found = []
    for rel in REWRITTEN_FILES:
        src_lines, srv_lines = texts[rel].split("\n"), served[rel].split("\n")
        if len(src_lines) != len(srv_lines):
            problems.append("%s changed its line count" % rel)
            continue
        found += [(rel, n, s, v) for n, (s, v) in enumerate(zip(src_lines, srv_lines), 1) if s != v]
    if len(found) != 4:
        problems.append("%d lines differ, expected 4" % len(found))
    for rel, number, src, srv in found:
        if (rel, src, srv) not in helper.REWRITES:
            problems.append("%s line %d differs and is not a table pair" % (rel, number))
        elif not _path_only(src, srv):
            problems.append("%s line %d differs beyond the module path" % (rel, number))
    return problems


def path_problems(rewrites, hashes_names):
    problems = []
    for rel, _before, after in rewrites:
        folder = posixpath.join("vendor/noble-curves", posixpath.dirname(rel))
        target = posixpath.normpath(posixpath.join(folder, after.split("'")[1]))
        if posixpath.dirname(target) != "vendor/noble-hashes":
            problems.append("%s resolves to %s, not into vendor/noble-hashes" % (rel, target))
        elif posixpath.basename(target) not in hashes_names:
            problems.append("%s resolves to %s, which is not a vendored noble-hashes file" % (rel, target))
    return problems


def bare_or_dynamic(text):
    offending = []
    for line in text.split("\n"):
        if line.strip().startswith(("*", "/*", "//")):
            continue
        from_match = MODULE_FROM_RE.search(line)
        side = SIDE_EFFECT_RE.match(line)
        bad = (bool(DYNAMIC_RE.search(line))
               or bool(from_match and HEAD_RE.match(line) and not from_match.group(1).startswith(("./", "../")))
               or bool(side and not side.group(1).startswith(("./", "../"))))
        if bad:
            offending.append(line)
    return offending


def noble_problems(texts):
    return ["%s: %s" % (key, line.strip()) for key in sorted(texts) for line in bare_or_dynamic(texts[key])]


def _noble_texts():
    texts = {}
    for lib in NOBLE_LIBS:
        base = os.path.join(WEBLIBS, lib)
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle")]
            for name in sorted(filenames):
                if name.endswith(".js"):
                    full = os.path.join(dirpath, name)
                    rel = os.path.relpath(full, base).replace(os.sep, "/")
                    with open(full, "rb") as fh:
                        texts[lib + "/" + rel] = fh.read().decode("utf-8")
    return texts


def _served_noble_texts(helper, raw):
    curves = {key[len("noble-curves/"):]: text for key, text in raw.items() if key.startswith("noble-curves/")}
    served = dict(raw)
    for rel, text in helper.apply_rewrites(curves).items():
        served["noble-curves/" + rel] = text
    return served


def listing_problems(files, rewrites, metadata):
    problems = []
    if "abstract/frost.js" not in files:
        problems.append("the noble-curves listing has no abstract/frost.js")
    problems += ["the noble-curves listing holds the metadata file %s" % name
                 for name in files if posixpath.basename(name) in metadata]
    problems += ["the table names %s, which is not a file of the curves folder" % rel
                 for rel, _before, _after in rewrites if rel.startswith("..") or rel not in files]
    return problems


def gradle_problems(code, rewrites):
    problems = []
    for rel, before, after in rewrites:
        for label, text in (("before", before), ("after", after)):
            if code.count(text) != 1:
                problems.append("the %s text of %s occurs %d times in the Gradle text" % (label, rel, code.count(text)))
    for word in ("nobleCurvesRewrites", "doLast", "readText", "writeText", "GradleException"):
        if word not in code:
            problems.append("the Gradle text has no %s" % word)
    if not re.search(r"[!=]=\s*1\b", code):
        problems.append("the Gradle text has no count check against 1")
    start = code.find(GRADLE_MARKER)
    if start < 0:
        return problems + ["the Gradle text has no copyWebLibs task"]
    table_at = code.find("val nobleCurvesRewrites")
    if table_at < 0 or table_at > start:
        problems.append("the table is not declared before the copyWebLibs task")
    body = _task_body(code)
    if "val nobleCurvesRewrites" in body:
        problems.append("the table is declared inside the copyWebLibs task")
    if "doLast" not in body:
        problems.append("doLast is not inside the copyWebLibs task")
    problems += ["a table line of %s is inside the copyWebLibs task" % rel
                 for rel, before, after in rewrites if before in body or after in body]
    return problems


class WebLibsRewritePinsTest(unittest.TestCase):

    def setUp(self):
        self.helper = _helper()

    def test_table_has_exactly_four_rewrites_of_the_curves_lib(self):
        helper = self.helper
        self.assertEqual(table_problems(helper.REWRITES, helper.REWRITE_LIB), [])
        # Mutant "rule dropped": the table without its last tuple fails the count check.
        self.assertTrue(table_problems(helper.REWRITES[:-1], helper.REWRITE_LIB), "mutant 'rule dropped' was not reported")

    def test_served_bytes_are_recomputed_from_the_pinned_sources_and_match_sha256(self):
        self.assertEqual(served_problems(self.helper), [])
        # Mutant "table off by one": utils.js gets one ../ too many, so its sha256 changes.
        mutant = _helper()
        mutant.REWRITES = [(rel, b, a.replace("'../noble-hashes/utils.js'", "'../../noble-hashes/utils.js'"))
                           if rel == "utils.js" else (rel, b, a) for rel, b, a in self.helper.REWRITES]
        self.assertTrue(served_problems(mutant), "mutant 'table off by one' was not reported")

    def test_only_four_lines_differ_between_source_and_served(self):
        self.assertEqual(only_four_lines_problems(self.helper), [])
        # Mutant "extra substitution": a fifth tuple rewrites another line of utils.js.
        mutant = _helper()
        mutant.REWRITES = list(self.helper.REWRITES) + [("utils.js", FIFTH_LINE, FIFTH_LINE.replace("anumber_;", "anumber_ ;"))]
        self.assertTrue(only_four_lines_problems(mutant), "mutant 'extra substitution' was not reported")

    def test_every_rewritten_path_resolves_inside_the_served_tree(self):
        helper = self.helper
        names = {n for n in os.listdir(HASHES_DIR)
                 if os.path.isfile(os.path.join(HASHES_DIR, n)) and n not in helper.METADATA}
        self.assertEqual(path_problems(helper.REWRITES, names), [])
        # Mutant "path one ../ short": the abstract/frost.js path loses one level.
        short = [(rel, b, a.replace("'../../noble-hashes/utils.js'", "'../noble-hashes/utils.js'"))
                 if rel == "abstract/frost.js" else (rel, b, a) for rel, b, a in helper.REWRITES]
        self.assertTrue(path_problems(short, names), "mutant 'path one ../ short' was not reported")
        # Mutant "path one ../ long": the utils.js path gains one level.
        long = [(rel, b, a.replace("'../noble-hashes/utils.js'", "'../../noble-hashes/utils.js'"))
                if rel == "utils.js" else (rel, b, a) for rel, b, a in helper.REWRITES]
        self.assertTrue(path_problems(long, names), "mutant 'path one ../ long' was not reported")

    def test_served_noble_sources_hold_no_bare_specifier_and_no_dynamic_import(self):
        raw = _noble_texts()
        served = _served_noble_texts(self.helper, raw)
        self.assertEqual(noble_problems(served), [])
        # The scan sees what the table removes: the unrewritten curves sources give exactly four lines.
        self.assertEqual(len([p for p in noble_problems(raw) if p.startswith("noble-curves/")]), 4)
        # Self-test: a bare import and a dynamic import are flagged; a comment line with the same words is not.
        self.assertEqual(len(bare_or_dynamic(BARE_LINE)), 1)
        self.assertEqual(len(bare_or_dynamic(DYNAMIC_LINE)), 1)
        self.assertEqual(bare_or_dynamic(" * " + BARE_LINE), [])
        # Mutant "fifth bare line text": a fifth bare specifier in a served curves source.
        mutant = dict(served)
        mutant["noble-curves/ed25519.js"] = served["noble-curves/ed25519.js"] + "\n" + BARE_LINE + "\n"
        self.assertTrue(noble_problems(mutant), "mutant 'fifth bare line text' was not reported")
        # Mutant "dynamic import text": a dynamic import in a served cipher source.
        mutant = dict(served)
        mutant["noble-ciphers/aes.js"] = served["noble-ciphers/aes.js"] + "\n" + DYNAMIC_LINE + "\n"
        self.assertTrue(noble_problems(mutant), "mutant 'dynamic import text' was not reported")

    def test_gradle_text_mirrors_the_table(self):
        with open(BUILD_KTS, encoding="utf-8") as fh:
            code = _scan_kotlin(fh.read(), keep_strings=True)
        self.assertEqual(gradle_problems(code, self.helper.REWRITES), [])
        # Mutant "Gradle text with one after changed": the ed25519.js sha2 path differs in the Gradle text.
        sha2_after = self.helper.REWRITES[1][2]
        changed = code.replace(sha2_after, sha2_after.replace("sha2.js", "sha3.js"), 1)
        self.assertNotEqual(changed, code)
        self.assertTrue(gradle_problems(changed, self.helper.REWRITES), "mutant 'Gradle text with one after changed' was not reported")

    def test_helper_copies_sub_folders_and_applies_the_table_only_to_the_curves_lib(self):
        helper = self.helper
        files = helper._vendor_files("noble-curves")
        self.assertEqual(listing_problems(files, helper.REWRITES, helper.METADATA), [])
        # Mutant "flat listing": only the top-level files, as the helper listed before the fix.
        flat = [name for name in files if "/" not in name]
        self.assertTrue(listing_problems(flat, helper.REWRITES, helper.METADATA), "mutant 'flat listing' was not reported")
        # Mutant "table names a path outside the curves folder".
        outside = [("../noble-hashes/utils.js", helper.REWRITES[0][1], helper.REWRITES[0][2])] + list(helper.REWRITES)
        self.assertTrue(listing_problems(files, outside, helper.METADATA), "mutant 'table names a path outside the curves folder' was not reported")


if __name__ == "__main__":
    unittest.main()
