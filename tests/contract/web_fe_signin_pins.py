"""Source pins for the web FE sign-in page logic.

Not a `test_*.py` file on purpose: the contract-file bijection counts every
`tests/contract/test_*.py` as one module's contract test. The class below is
collected through `test_web_fe_contract.py`, which imports it.

Each rule is a function over the texts it checks, so the class can run every
rule on the real tree and again on an edited copy kept in memory.
"""

import hashlib
import os
import re
import unittest

import contract_support

JS_DIR = os.path.join("server", "modules", "web-fe", "src", "main", "resources", "web", "js")
TEST_DIR = os.path.join("server", "modules", "web-fe", "src", "test", "js")
AESGCM = "aesgcm.js"
SIGNIN = "signin.js"
LINK = "signin-link.js"
SOURCES = [SIGNIN, LINK]
FIXTURES = "signin-fixtures.mjs"
TEST_SIDE = [
    "signin-link.test.mjs",
    "signin.test.mjs",
    "signin-flow.test.mjs",
    "signin-edge.test.mjs",
    FIXTURES,
]
RUNNER_FILES = sorted([
    "aesgcm.test.mjs",
    "signin-link.test.mjs",
    "signin.test.mjs",
    "signin-flow.test.mjs",
    "signin-edge.test.mjs",
    "signin-page.test.mjs",
    "signin-wire.test.mjs",
    "signin-net.test.mjs",
    "signin-logout.test.mjs",
    "signin-box-aad.test.mjs",
    "signin-box.test.mjs",
    "signin-crypto.test.mjs",
    "signin-e2e.test.mjs",
    "signin-kdf.test.mjs",
    "signin-seal.test.mjs",
    "devices-net.test.mjs",
    "devices.test.mjs",
    "devices-view.test.mjs",
    "noble-curves-served.test.mjs",
])
LINE_CAP = 300
ALLOWED_IMPORTS = {SIGNIN: ("./aesgcm.js", "./signin-link.js"), LINK: ()}
# The two alphabet lines, by file and line number; nothing else may be long.
ALPHABET_LINES = {
    (LINK, 7), (LINK, 8),
    ("signin-link.test.mjs", 16), ("signin-link.test.mjs", 17),
}
FORBIDDEN = [
    "fetch", "XMLHttpRequest", "eval", "new Function", "import(",
    "Math.random", "Date.now", "localStorage", "sessionStorage",
    "indexedDB", "document.cookie", "window.", "document.",
]
BANNED = [
    "it" "em", "ro" "und", "ga" "te", "verd" "ict", "rul" "ed", "rul" "ing", "sli" "ce",
    "sub" "-agent", "plan" " file", "read" "back", "review" "er", "le" "ad",
]
BANNED_RE = re.compile(
    r"\b(?:" + "|".join(re.escape(word) for word in BANNED) + r")\b",
    re.IGNORECASE,
)
LABEL_RE = re.compile(r"\b[A-Z][0-9]{1,2}\b")
DIGIT_RUN_RE = re.compile(r"[0-9]{7,}")
HEX_RUN_RE = re.compile(r"[0-9A-Fa-f]{8,}")
LITERAL_RE = re.compile("['\"`]([A-Za-z0-9+/_=-]{17,})['\"`]")
ALPHABET_RE = re.compile(r"[A-Z]{26}|[a-z]{26}")
FROM_RE = re.compile(r"""\bfrom\s*['"]([^'"]+)['"]""")
BARE_RE = re.compile(r"""^\s*import\s*['"]([^'"]+)['"]""", re.MULTILINE)
STATEMENT_RE = re.compile(r"\bimport\b")
# sha256 of aesgcm.js with the optional AAD, written in pieces of six characters.
AESGCM_SHA256 = "".join([
    "78e8a0", "488baa", "d3c03d", "55f85d", "309827", "1602b4",
    "2b0454", "c02f9a", "43dcdf", "d6b4da", "1b37",
])


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def load_inputs(root):
    """The texts of the seven files, the test folder listing, the aesgcm bytes."""
    texts = {}
    for name in SOURCES:
        texts[name] = _read(os.path.join(root, JS_DIR, name))
    for name in TEST_SIDE:
        texts[name] = _read(os.path.join(root, TEST_DIR, name))
    names = sorted(os.listdir(os.path.join(root, TEST_DIR)))
    with open(os.path.join(root, JS_DIR, AESGCM), "rb") as fh:
        aesgcm_bytes = fh.read()
    return texts, names, aesgcm_bytes


def check_imports(texts):
    problems = []
    for name, allowed in ALLOWED_IMPORTS.items():
        text = texts[name]
        paths = FROM_RE.findall(text) + BARE_RE.findall(text)
        statements = len(STATEMENT_RE.findall(text))
        if statements != len(paths):
            problems.append(f"{name}: {statements} import statement(s), {len(paths)} module path(s)")
        for path in paths:
            if path not in allowed:
                problems.append(f"{name}: imports {path}, which is not allowed")
        for path in allowed:
            if path not in paths:
                problems.append(f"{name}: does not import {path}")
    return problems


def check_forbidden(texts):
    problems = []
    for name, text in texts.items():
        for number, line in enumerate(text.splitlines(), 1):
            for token in FORBIDDEN:
                if token in line:
                    problems.append(f"{name} line {number}: forbidden token {token}")
    return problems


def check_banned(texts):
    problems = []
    for name, text in texts.items():
        for number, line in enumerate(text.splitlines(), 1):
            match = BANNED_RE.search(line)
            if match:
                problems.append(f"{name} line {number}: banned word {match.group(0)}")
    return problems


def check_labels(texts):
    problems = []
    for name, text in texts.items():
        for number, line in enumerate(text.splitlines(), 1):
            match = LABEL_RE.search(line)
            if match:
                problems.append(f"{name} line {number}: review label {match.group(0)}")
    return problems


def check_runs(texts):
    problems = []
    for name, text in texts.items():
        for number, line in enumerate(text.splitlines(), 1):
            if DIGIT_RUN_RE.search(line):
                problems.append(f"{name} line {number}: digit run of 7 or more")
            if HEX_RUN_RE.search(line):
                problems.append(f"{name} line {number}: hex run of 8 or more")
    return problems


def check_ascii(texts):
    problems = []
    for name, text in texts.items():
        for number, line in enumerate(text.splitlines(), 1):
            if any(ord(char) > 127 for char in line):
                problems.append(f"{name} line {number}: not ASCII")
    return problems


def check_literals(texts):
    problems = []
    for name, text in texts.items():
        for number, line in enumerate(text.splitlines(), 1):
            for match in LITERAL_RE.finditer(line):
                literal = match.group(1)
                if (name, number) in ALPHABET_LINES and ALPHABET_RE.fullmatch(literal):
                    continue
                problems.append(f"{name} line {number}: base64-looking literal of {len(literal)} characters")
    return problems


def check_line_caps(texts):
    return [
        f"{name}: {len(text.splitlines())} lines, the cap is {LINE_CAP}"
        for name, text in texts.items()
        if len(text.splitlines()) >= LINE_CAP
    ]


def check_test_names(names):
    picked = sorted(name for name in names if name.endswith(".test.mjs"))
    if picked != RUNNER_FILES:
        return [f"runner glob picks {picked}, expected {RUNNER_FILES}"]
    if FIXTURES.endswith(".test.mjs"):
        return [f"{FIXTURES} is named as a test file"]
    return []


def check_aesgcm(aesgcm_bytes):
    actual = hashlib.sha256(aesgcm_bytes).hexdigest()
    if actual != AESGCM_SHA256:
        return [f"{AESGCM}: sha256 is {actual}, pinned value differs"]
    return []


def check_signin_sources(root):
    texts, names, aesgcm_bytes = load_inputs(root)
    return (
        check_imports(texts)
        + check_forbidden(texts)
        + check_banned(texts)
        + check_labels(texts)
        + check_runs(texts)
        + check_ascii(texts)
        + check_literals(texts)
        + check_line_caps(texts)
        + check_test_names(names)
        + check_aesgcm(aesgcm_bytes)
    )


class WebFeSigninPinsTest(unittest.TestCase):

    def setUp(self):
        self.texts, self.names, self.aesgcm_bytes = load_inputs(contract_support.ROOT)

    def _edit(self, name, old, new):
        texts = dict(self.texts)
        self.assertIn(old, texts[name], "control edit did not land")
        texts[name] = texts[name].replace(old, new, 1)
        return texts

    def _append(self, name, extra):
        texts = dict(self.texts)
        texts[name] = texts[name] + extra
        return texts

    def test_real_tree_is_clean(self):
        self.assertEqual(check_signin_sources(contract_support.ROOT), [])

    def test_imports_of_the_two_source_files(self):
        self.assertEqual(check_imports(self.texts), [])

    def test_control_import_rule_fires(self):
        problems = check_imports(self._edit(SIGNIN, "'./signin-link.js'", "'./other.js'"))
        self.assertIn("./other.js", " ".join(problems))

    def test_forbidden_tokens_are_absent(self):
        self.assertEqual(check_forbidden(self.texts), [])

    def test_control_forbidden_rule_fires(self):
        self.assertTrue(check_forbidden(self._append(LINK, "\n// fetch\n")))

    def test_banned_words_are_absent(self):
        self.assertEqual(check_banned(self.texts), [])

    def test_control_banned_rule_fires(self):
        self.assertTrue(check_banned(self._append(LINK, "\n// " + "it" + "em\n")))

    def test_review_labels_are_absent(self):
        self.assertEqual(check_labels(self.texts), [])

    def test_control_label_rule_fires(self):
        self.assertTrue(check_labels(self._append(LINK, "\nconst " + "K" + "1 = 0;\n")))

    def test_digit_and_hex_runs_are_absent(self):
        self.assertEqual(check_runs(self.texts), [])

    def test_control_run_rules_fire(self):
        self.assertTrue(check_runs(self._append(LINK, "\nx = " + "1234" + "567\n")))
        self.assertTrue(check_runs(self._append(LINK, "\n// " + "dead" + "beef0\n")))

    def test_text_is_ascii_only(self):
        self.assertEqual(check_ascii(self.texts), [])

    def test_control_ascii_rule_fires(self):
        self.assertTrue(check_ascii(self._append(LINK, "\n// caf\u00e9\n")))

    def test_base64_looking_literals_are_short(self):
        self.assertEqual(check_literals(self.texts), [])

    def test_control_literal_rule_fires(self):
        extra = "\nconst big = 'abcdefghij" + "klmnopqrst';\n"
        self.assertTrue(check_literals(self._append(LINK, extra)))

    def test_each_file_is_under_the_line_cap(self):
        self.assertEqual(check_line_caps(self.texts), [])

    def test_control_line_cap_rule_fires(self):
        self.assertTrue(check_line_caps(self._append(LINK, "x\n" * LINE_CAP)))

    def test_runner_picks_exactly_the_sign_in_test_files(self):
        self.assertEqual(check_test_names(self.names), [])

    def test_control_runner_rule_fires(self):
        names = [name for name in self.names if name != "signin-edge.test.mjs"]
        self.assertTrue(check_test_names(names))
        self.assertTrue(check_test_names(self.names + ["signin-fixtures.test.mjs"]))

    def test_aesgcm_js_digest_matches_base(self):
        self.assertEqual(check_aesgcm(self.aesgcm_bytes), [])

    def test_control_digest_rule_fires(self):
        self.assertTrue(check_aesgcm(self.aesgcm_bytes + b"\n"))
