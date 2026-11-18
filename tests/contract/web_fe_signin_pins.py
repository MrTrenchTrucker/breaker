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
import shutil
import tempfile
import unittest

import contract_support

JS_DIR = os.path.join("server", "modules", "web-fe", "src", "main", "resources", "web", "js")
TEST_DIR = os.path.join("server", "modules", "web-fe", "src", "test", "js")
AESGCM = "aesgcm.js"
SIGNIN = "signin.js"
LINK = "signin-link.js"
SOURCES = [SIGNIN, LINK]
FIXTURES = "signin-fixtures.mjs"
CONVERSION_FIXTURES = "conversion-fixtures.mjs"
TEST_SIDE = [
    "signin-link.test.mjs",
    "signin.test.mjs",
    "signin-flow.test.mjs",
    "signin-edge.test.mjs",
    "signin-conversion.test.mjs",
    FIXTURES,
    CONVERSION_FIXTURES,
]
RUNNER_FILES = sorted([
    "admin-settings-net.test.mjs",
    "admin-settings-view.test.mjs",
    "admin-settings.test.mjs",
    "admin-store-net.test.mjs",
    "admin-store-view.test.mjs",
    "admin-store.test.mjs",
    "admin-tokens-net.test.mjs",
    "admin-tokens-view.test.mjs",
    "admin-tokens.test.mjs",
    "admin-users-net.test.mjs",
    "admin-users-view.test.mjs",
    "admin-users.test.mjs",
    "aesgcm.test.mjs",
    "bootstrap-crypto.test.mjs",
    "bootstrap-net.test.mjs",
    "conversion-encrypt.test.mjs",
    "conversion-upload.test.mjs",
    "devices-net.test.mjs",
    "devices-view.test.mjs",
    "devices.test.mjs",
    "noble-curves-served.test.mjs",
    "pending-sealed-net.test.mjs",
    "pending-sealed.test.mjs",
    "register-net.test.mjs",
    "register-policy.test.mjs",
    "register-view.test.mjs",
    "register.test.mjs",
    "signin-box-aad.test.mjs",
    "signin-box.test.mjs",
    "signin-conversion.test.mjs",
    "signin-crypto.test.mjs",
    "signin-e2e.test.mjs",
    "signin-edge.test.mjs",
    "signin-flow.test.mjs",
    "signin-kdf.test.mjs",
    "signin-link.test.mjs",
    "signin-logout.test.mjs",
    "signin-net.test.mjs",
    "signin-page.test.mjs",
    "signin-seal.test.mjs",
    "signin-wire.test.mjs",
    "signin.test.mjs",
    "status-banner.test.mjs",
    "status-net.test.mjs",
    "transcriptions-net.test.mjs",
    "transcriptions-view.test.mjs",
    "transcriptions.test.mjs",
    "unseal.test.mjs",
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
# The root-relative route exemption, the same narrow one the page pin carries.
ROUTE_PREFIX = "/v1/"
ALPHABET_RE = re.compile(r"[A-Z]{26}|[a-z]{26}")
FROM_RE = re.compile(r"""\bfrom\s*['"]([^'"]+)['"]""")
BARE_RE = re.compile(r"""^\s*import\s*['"]([^'"]+)['"]""", re.MULTILINE)
STATEMENT_RE = re.compile(r"\bimport\b")
# sha256 of aesgcm.js with the optional AAD, written in pieces of six characters.
AESGCM_SHA256 = "".join([
    "78e8a0", "488baa", "d3c03d", "55f85d", "309827", "1602b4",
    "2b0454", "c02f9a", "43dcdf", "d6b4da", "1b37",
])

# The test-only libsodium symlink, its pinned vendored target, and the served tree it
# must never enter. A served file "references libsodium" when it IMPORTS it; the word
# in a comment (the sealer's header) is not a module reference.
LIBSODIUM_LINK = os.path.join(
    "server", "modules", "web-fe", "src", "test", "js", "libsodium-sumo.mjs"
)
LIBSODIUM_VENDORED = os.path.join(
    "shared", "modules", "web-libs", "libsodium", "libsodium-sumo.mjs"
)
SERVED_WEB = os.path.join(
    "server", "modules", "web-fe", "src", "main", "resources", "web"
)
DYNAMIC_IMPORT_RE = re.compile(r"""import\(\s*['"]([^'"]+)['"]""")


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def load_inputs(root):
    """The texts of the nine files, the test folder listing, the aesgcm bytes."""
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
                if literal.startswith(ROUTE_PREFIX):
                    # A root-relative route, the one route exemption, exactly as in the page pin
                    # (web_fe_signin_page_pins.py:286-287): the literal must START with /v1/.
                    continue
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


def _served_texts(root):
    """rel path -> text for every file under the served web tree."""
    texts = {}
    for dirpath, _dirnames, filenames in os.walk(os.path.join(root, SERVED_WEB)):
        for name in sorted(filenames):
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            with open(path, encoding="utf-8", errors="replace") as fh:
                texts[rel] = fh.read()
    return texts


def check_libsodium_pin(root, link_target=None, served_texts=None):
    """The test-only symlink resolves to the pinned vendored core, and no served file
    imports libsodium. `link_target` / `served_texts` are injection points for the
    control pair; the real tree is read when they are None."""
    problems = []
    if link_target is None:
        link = os.path.join(root, LIBSODIUM_LINK)
        if not os.path.islink(link):
            return [LIBSODIUM_LINK + ": not a symlink"]
        link_target = os.path.realpath(link)
    wanted = os.path.realpath(os.path.join(root, LIBSODIUM_VENDORED))
    if link_target != wanted:
        problems.append(
            LIBSODIUM_LINK + ": resolves to " + link_target + ", expected " + wanted
        )
    # realpath returns a path even when nothing is there, so identity alone cannot tell a
    # pinned vendored core from a deleted one. Existence is the check.
    if not os.path.isfile(wanted):
        problems.append(LIBSODIUM_VENDORED + ": missing")
    if served_texts is None:
        served_texts = _served_texts(root)
    for rel, text in sorted(served_texts.items()):
        specifiers = (
            FROM_RE.findall(text)
            + BARE_RE.findall(text)
            + DYNAMIC_IMPORT_RE.findall(text)
        )
        for specifier in specifiers:
            if "libsodium" in specifier:
                problems.append(rel + ": imports libsodium (" + specifier + ")")
    return problems


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
        + check_libsodium_pin(root)
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

    def _plant(self, name, extra):
        """Append one line to an in-memory copy of a real file; return (texts, line number)."""
        texts = dict(self.texts)
        text = texts[name]
        if not text.endswith("\n"):
            text += "\n"
        number = len(text.splitlines()) + 1
        texts[name] = text + extra + "\n"
        return texts, number

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

    # ── the /v1/ route exemption on the literal rule: the page pin's narrow exemption
    # (web_fe_signin_page_pins.py:286-287), nothing wider. Only a literal that STARTS with
    # /v1/ is quiet; a base64-looking literal without the prefix, /v1x, and a mid-string
    # /v1/ all still fire.

    def test_control_route_literal_is_quiet(self):
        texts, _number = self._plant(LINK, "const route = '/v1/sync/pending-sealed';")
        self.assertEqual(check_literals(texts), [])

    def test_control_literal_without_route_prefix_still_fires(self):
        literal = "abcdefghijklmnopqrst"
        texts, number = self._plant(LINK, "const big = '" + literal + "';")
        self.assertEqual(
            check_literals(texts),
            [f"{LINK} line {number}: base64-looking literal of {len(literal)} characters"],
        )

    def test_control_v1x_literal_still_fires(self):
        literal = "/v1x/sync/pending-sealed"
        texts, number = self._plant(LINK, "const route = '" + literal + "';")
        self.assertEqual(
            check_literals(texts),
            [f"{LINK} line {number}: base64-looking literal of {len(literal)} characters"],
        )

    def test_control_mid_string_v1_literal_still_fires(self):
        literal = "x/v1/sync/pending-sealed"
        texts, number = self._plant(LINK, "const route = '" + literal + "';")
        self.assertEqual(
            check_literals(texts),
            [f"{LINK} line {number}: base64-looking literal of {len(literal)} characters"],
        )

    def test_each_file_is_under_the_line_cap(self):
        self.assertEqual(check_line_caps(self.texts), [])

    def test_control_line_cap_rule_fires(self):
        self.assertTrue(check_line_caps(self._append(LINK, "x\n" * LINE_CAP)))

    # ── signin-conversion.test.mjs is in TEST_SIDE, so every rule above reaches it.
    # Each control copies the real file in memory, plants ONE violation, and asserts the
    # rule fires naming this file; the real file itself stays quiet for every rule.

    CONVERSION_FILE = "signin-conversion.test.mjs"

    def _conversion_texts(self):
        return {self.CONVERSION_FILE: self.texts[self.CONVERSION_FILE]}

    def _plant_conversion(self, extra):
        return self._plant(self.CONVERSION_FILE, extra)

    def test_signin_conversion_is_in_the_scanned_set(self):
        self.assertIn(self.CONVERSION_FILE, TEST_SIDE)
        self.assertIn(self.CONVERSION_FILE, self.texts)

    def test_signin_conversion_real_file_is_quiet(self):
        texts = self._conversion_texts()
        self.assertEqual(check_forbidden(texts), [])
        self.assertEqual(check_banned(texts), [])
        self.assertEqual(check_labels(texts), [])
        self.assertEqual(check_runs(texts), [])
        self.assertEqual(check_ascii(texts), [])
        self.assertEqual(check_literals(texts), [])
        self.assertEqual(check_line_caps(texts), [])

    def test_control_signin_conversion_forbidden_fires(self):
        texts, number = self._plant_conversion("// " + "fetch")
        self.assertEqual(
            check_forbidden(texts),
            [f"{self.CONVERSION_FILE} line {number}: forbidden token fetch"],
        )

    def test_control_signin_conversion_banned_fires(self):
        texts, number = self._plant_conversion("// " + "it" + "em")
        self.assertEqual(
            check_banned(texts),
            [f"{self.CONVERSION_FILE} line {number}: banned word item"],
        )

    def test_control_signin_conversion_label_fires(self):
        texts, number = self._plant_conversion("const " + "K" + "1 = 0;")
        self.assertEqual(
            check_labels(texts),
            [f"{self.CONVERSION_FILE} line {number}: review label K1"],
        )

    def test_control_signin_conversion_run_rules_fire(self):
        texts, number = self._plant_conversion("// " + "dead" + "beef0 " + "1234" + "567")
        self.assertEqual(
            check_runs(texts),
            [
                f"{self.CONVERSION_FILE} line {number}: digit run of 7 or more",
                f"{self.CONVERSION_FILE} line {number}: hex run of 8 or more",
            ],
        )

    def test_control_signin_conversion_ascii_fires(self):
        texts, number = self._plant_conversion("// caf\u00e9")
        self.assertEqual(
            check_ascii(texts),
            [f"{self.CONVERSION_FILE} line {number}: not ASCII"],
        )

    def test_control_signin_conversion_literal_fires(self):
        texts, number = self._plant_conversion("const big = 'abcdefghij" + "klmnopqrst';")
        self.assertEqual(
            check_literals(texts),
            [f"{self.CONVERSION_FILE} line {number}: base64-looking literal of 20 characters"],
        )

    def test_control_signin_conversion_line_cap_fires(self):
        texts = self._append(self.CONVERSION_FILE, "x\n" * LINE_CAP)
        count = len(texts[self.CONVERSION_FILE].splitlines())
        self.assertEqual(
            check_line_caps(texts),
            [f"{self.CONVERSION_FILE}: {count} lines, the cap is {LINE_CAP}"],
        )

    # ── conversion-fixtures.mjs is in TEST_SIDE too, so every rule above reaches it. The
    # client ids in it are BUILT, not written out, so the file is quiet for every rule.

    def _fixtures_texts(self):
        return {CONVERSION_FIXTURES: self.texts[CONVERSION_FIXTURES]}

    def test_conversion_fixtures_is_in_the_scanned_set(self):
        self.assertIn(CONVERSION_FIXTURES, TEST_SIDE)
        self.assertIn(CONVERSION_FIXTURES, self.texts)

    def test_conversion_fixtures_real_file_is_quiet(self):
        texts = self._fixtures_texts()
        self.assertEqual(check_forbidden(texts), [])
        self.assertEqual(check_banned(texts), [])
        self.assertEqual(check_labels(texts), [])
        self.assertEqual(check_runs(texts), [])
        self.assertEqual(check_ascii(texts), [])
        self.assertEqual(check_literals(texts), [])
        self.assertEqual(check_line_caps(texts), [])

    def test_control_conversion_fixtures_forbidden_fires(self):
        texts, number = self._plant(CONVERSION_FIXTURES, "// " + "fetch")
        self.assertEqual(
            check_forbidden(texts),
            [f"{CONVERSION_FIXTURES} line {number}: forbidden token fetch"],
        )

    def test_control_conversion_fixtures_banned_fires(self):
        texts, number = self._plant(CONVERSION_FIXTURES, "// " + "it" + "em")
        self.assertEqual(
            check_banned(texts),
            [f"{CONVERSION_FIXTURES} line {number}: banned word item"],
        )

    def test_control_conversion_fixtures_label_fires(self):
        texts, number = self._plant(CONVERSION_FIXTURES, "const " + "K" + "1 = 0;")
        self.assertEqual(
            check_labels(texts),
            [f"{CONVERSION_FIXTURES} line {number}: review label K1"],
        )

    def test_control_conversion_fixtures_run_rules_fire(self):
        texts, number = self._plant(CONVERSION_FIXTURES, "// " + "dead" + "beef0 " + "1234" + "567")
        self.assertEqual(
            check_runs(texts),
            [
                f"{CONVERSION_FIXTURES} line {number}: digit run of 7 or more",
                f"{CONVERSION_FIXTURES} line {number}: hex run of 8 or more",
            ],
        )

    def test_control_conversion_fixtures_ascii_fires(self):
        texts, number = self._plant(CONVERSION_FIXTURES, "// caf\u00e9")
        self.assertEqual(
            check_ascii(texts),
            [f"{CONVERSION_FIXTURES} line {number}: not ASCII"],
        )

    def test_control_conversion_fixtures_literal_fires(self):
        texts, number = self._plant(CONVERSION_FIXTURES, "const big = 'abcdefghij" + "klmnopqrst';")
        self.assertEqual(
            check_literals(texts),
            [f"{CONVERSION_FIXTURES} line {number}: base64-looking literal of 20 characters"],
        )

    def test_control_conversion_fixtures_line_cap_fires(self):
        texts = self._append(CONVERSION_FIXTURES, "x\n" * LINE_CAP)
        count = len(texts[CONVERSION_FIXTURES].splitlines())
        self.assertEqual(
            check_line_caps(texts),
            [f"{CONVERSION_FIXTURES}: {count} lines, the cap is {LINE_CAP}"],
        )

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

    def test_control_libsodium_pin_fires(self):
        """Good text passes, bad text fails: the symlink target and the served imports."""
        root = contract_support.ROOT
        wanted = os.path.realpath(os.path.join(root, LIBSODIUM_VENDORED))
        self.assertEqual(
            check_libsodium_pin(root, link_target=wanted, served_texts={}), []
        )
        self.assertTrue(
            check_libsodium_pin(
                root, link_target=os.path.join(root, "elsewhere.mjs"), served_texts={}
            )
        )
        bad = {"js/served.js": "import sodium from './libsodium-sumo.mjs';\n"}
        self.assertTrue(
            check_libsodium_pin(root, link_target=wanted, served_texts=bad)
        )

    def _libsodium_scratch_root(self, vendored=True, served_binary=False):
        """A real throwaway tree. These two controls are about FILESYSTEM state - a
        deleted target and a binary served asset - which an in-memory text edit
        cannot represent."""
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
        target = os.readlink(os.path.join(contract_support.ROOT, LIBSODIUM_LINK))
        link = os.path.join(tmp, *LIBSODIUM_LINK.split("/"))
        os.makedirs(os.path.dirname(link))
        if vendored:
            vendored_path = os.path.join(tmp, *LIBSODIUM_VENDORED.split("/"))
            os.makedirs(os.path.dirname(vendored_path))
            with open(vendored_path, "w", encoding="utf-8") as fh:
                fh.write("// vendored stand-in\n")
        os.symlink(target, link)
        served = os.path.join(tmp, *SERVED_WEB.split("/"))
        os.makedirs(served)
        if served_binary:
            with open(os.path.join(served, "img.bin"), "wb") as fh:
                fh.write(b"\xff\xfe\x00\x01" * 8)
        return tmp

    def test_control_deleted_vendored_target_fails_the_pin(self):
        """A dangling symlink must FAIL: realpath agrees with itself on a path that
        does not exist, so the pin has to check existence, not just identity."""
        root = self._libsodium_scratch_root(vendored=False)
        self.assertTrue(check_libsodium_pin(root))

    def test_control_binary_served_asset_does_not_crash_the_scan(self):
        """A binary asset under the served tree must be scanned without raising."""
        root = self._libsodium_scratch_root(served_binary=True)
        self.assertEqual(check_libsodium_pin(root), [])
