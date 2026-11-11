"""Source pins for the web FE key-derivation wrapper, signin-kdf.js.

Not a `test_*.py` file on purpose: the contract-file bijection counts every
`tests/contract/test_*.py` as one module's contract test. The class below is
collected through `test_web_fe_contract.py`, which imports it.

Each rule takes the text of the file (None when the file is missing). Each rule
has a real-tree test and a firing control: a good text must pass, and the same
text with one edit must fail.
"""

import os
import re
import unittest

import contract_support

WEB_JS = os.path.join("server", "modules", "web-fe", "src", "main", "resources", "web", "js")
KDF = "signin-kdf.js"
IMPORTS = [
    "./vendor/noble-hashes/argon2.js",
    "./vendor/noble-hashes/hkdf.js",
    "./vendor/noble-hashes/sha2.js",
]
EXPORTS = ["KdfRefused", "stretch"]
# The two label lines, each exactly once, and the join that turns the parts into the label text.
KEK_LINE = "const KEK_LABEL = ['breaker', 'kek', 'v1'];"
VERIFIER_LINE = "const VERIFIER_LABEL = ['breaker', 'auth', 'verifier', 'v1'];"
JOIN_TEXT = "parts.join('-')"
FORBIDDEN = [
    "fetch", "XMLHttpRequest", "eval", "new Function", "import(",
    "Math" ".random", "Date" ".now", "localStorage", "sessionStorage",
    "indexedDB", "document.", "window.", "Worker", "WebAssembly",
]
LINE_CAP = 300

# The try block of the good text, the same shape as the real stretch: the return inside the try, and the
# finally block that zero-fills the derived output. The unwrapped block is the same return with the fill after it.
TRY_BLOCK = "\n".join([
    "  try {",
    "    return [argon2idAsync, hkdf, sha256, KEK_LABEL, VERIFIER_LABEL, password, salt, params, tag];",
    "  } finally {",
    "    tag.fill(0);",
    "  }",
]) + "\n"
UNWRAPPED_BLOCK = (
    "    return [argon2idAsync, hkdf, sha256, KEK_LABEL, VERIFIER_LABEL, password, salt, params, tag];\n"
    "    tag.fill(0);\n"
)
GOOD_KDF = "\n".join([
    "import { argon2idAsync } from './vendor/noble-hashes/argon2.js';",
    "import { hkdf } from './vendor/noble-hashes/hkdf.js';",
    "import { sha256 } from './vendor/noble-hashes/sha2.js';",
    "export class KdfRefused extends Error {}",
    KEK_LINE,
    VERIFIER_LINE,
    "function labelBytes(parts) { return new TextEncoder().encode(parts.join('-')); }",
    "export async function stretch(password, salt, params) {",
    "  const tag = await argon2idAsync(password, salt, params);",
]) + "\n" + TRY_BLOCK + "}\n"


def _read(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except (OSError, UnicodeDecodeError):
        return None


def kdf_exists(text):
    return [] if text is not None else [KDF + ": missing file"]


def kdf_imports(text):
    if text is None:
        return [KDF + ": missing file"]
    paths = re.findall(r"^\s*import\b[^'\"]*['\"]([^'\"]+)['\"]", text, re.M)
    statements = len(re.findall(r"^\s*import\b", text, re.M))
    dynamic = re.search(r"\bimport\s*\(", text)
    ok = statements == 3 and sorted(paths) == sorted(IMPORTS) and not dynamic
    return [] if ok else [KDF + ": imports " + ", ".join(paths)]


def kdf_exports(text):
    if text is None:
        return [KDF + ": missing file"]
    names = sorted(re.findall(r"^export\s+(?:async\s+)?(?:function\*?|const|let|var|class)\s+(\w+)", text, re.M))
    bad = names != sorted(EXPORTS) or re.search(r"^export\s*(?:\{|default\b)", text, re.M)
    return [KDF + ": exports are " + ", ".join(names)] if bad else []


def _label_line_rule(line):
    def rule(text):
        if text is None:
            return [KDF + ": missing file"]
        held = sum(1 for found in text.splitlines() if found.strip() == line)
        return [] if held == 1 else [KDF + ": line " + line + " held %d times" % held]
    return rule


kdf_label_kek = _label_line_rule(KEK_LINE)
kdf_label_verifier = _label_line_rule(VERIFIER_LINE)


def kdf_label_join(text):
    if text is None:
        return [KDF + ": missing file"]
    held = text.count(JOIN_TEXT)
    return [] if held == 1 else [KDF + ": " + JOIN_TEXT + " held %d times" % held]


def kdf_forbidden(text):
    if text is None:
        return [KDF + ": missing file"]
    return [KDF + ": forbidden " + token for token in FORBIDDEN if token in text]


def kdf_line_cap(text):
    if text is None:
        return [KDF + ": missing file"]
    count = len(text.splitlines())
    return [KDF + ": %d lines, the cap is %d" % (count, LINE_CAP)] if count > LINE_CAP else []


def kdf_ascii(text):
    if text is None:
        return [KDF + ": missing file"]
    return [KDF + " line %d: not ASCII" % n
            for n, line in enumerate(text.splitlines(), 1) if not line.isascii()]


STRETCH_HEAD = "export async function stretch("
TRY_OPEN = "try {"
FINALLY_JOIN = "} finally {"
FILL_CALL = "tag.fill(0);"


def _close_index(text, open_index):
    """Index of the brace that closes the brace at open_index, or -1 when it never closes."""
    depth = 0
    for i in range(open_index, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return -1


def _stretch_body(text):
    """The text between the braces of stretch, or None when the function is absent or never closes."""
    if text.count(STRETCH_HEAD) != 1:
        return None
    start = text.index(STRETCH_HEAD)
    open_index = text.find("{", start)
    if open_index < 0:
        return None
    close_index = _close_index(text, open_index)
    return None if close_index < 0 else text[open_index + 1:close_index]


def _fill_in_finally(body):
    """True when a try block of the body is followed by a finally block that holds the fill call."""
    pos = body.find(TRY_OPEN)
    while pos >= 0:
        try_close = _close_index(body, pos + len(TRY_OPEN) - 1)
        if try_close >= 0 and body.startswith(FINALLY_JOIN, try_close):
            finally_open = try_close + len(FINALLY_JOIN) - 1
            finally_close = _close_index(body, finally_open)
            if finally_close >= 0 and FILL_CALL in body[finally_open + 1:finally_close]:
                return True
        pos = body.find(TRY_OPEN, pos + 1)
    return False


def kdf_zero_fill(text):
    if text is None:
        return [KDF + ": missing file"]
    body = _stretch_body(text)
    if body is None:
        return [KDF + ": stretch body not found"]
    return [] if _fill_in_finally(body) else [KDF + ": stretch does not zero-fill its output in a finally block"]


# name, rule, old text, new text (None, None for the file-missing row)
RULES = [
    ("kdf_exists", kdf_exists, None, None),
    ("kdf_imports", kdf_imports, "import { sha256 } from './vendor/noble-hashes/sha2.js';",
     "import { sha256 } from './vendor/noble-hashes/sha2.js';\nimport { x } from './x.js';"),
    ("kdf_exports", kdf_exports, "export class KdfRefused extends Error {}",
     "export class KdfRefused extends Error {}\nexport const extra = 1;"),
    ("kdf_label_kek", kdf_label_kek, KEK_LINE, "const KEK_LABEL = ['breaker', 'kek', 'v2'];"),
    ("kdf_label_verifier", kdf_label_verifier, VERIFIER_LINE,
     "const VERIFIER_LABEL = ['breaker', 'auth', 'verifier', 'v2'];"),
    ("kdf_label_join", kdf_label_join, JOIN_TEXT, "parts.join('_')"),
    ("kdf_forbidden", kdf_forbidden, "export async function stretch(password, salt, params) {",
     "export async function stretch(password, salt, params) { void fetch;"),
    ("kdf_line_cap", kdf_line_cap, KEK_LINE, KEK_LINE + "\n" * 300),
    ("kdf_ascii", kdf_ascii, KEK_LINE, KEK_LINE + " // caf" + chr(0xe9)),
    ("kdf_zero_fill", kdf_zero_fill, "} finally {", "} catch (err) {"),
    ("kdf_zero_fill_fill_removed", kdf_zero_fill, "    tag.fill(0);\n", ""),
    ("kdf_zero_fill_finally_dropped", kdf_zero_fill, TRY_BLOCK, UNWRAPPED_BLOCK),
]


def check_signin_kdf(text):
    return [problem for _name, rule, _old, _new in RULES for problem in rule(text)]


class WebFeSigninKdfPinsTest(unittest.TestCase):

    def setUp(self):
        self.text = _read(os.path.join(contract_support.ROOT, WEB_JS, KDF))

    def test_real_tree_is_clean(self):
        self.assertEqual(check_signin_kdf(self.text), [])


def _real_test(rule):
    def test(self):
        self.assertEqual(rule(self.text), [])
    return test


def _control_test(rule, old, new):
    def test(self):
        if old is None:
            self.assertEqual(rule(GOOD_KDF), [], "the good text fails the rule")
            self.assertTrue(rule(None), "a missing file does not fire the rule")
            return
        self.assertIn(old, GOOD_KDF, "control edit did not land")
        bad = GOOD_KDF.replace(old, new, 1)
        self.assertEqual(rule(GOOD_KDF), [], "the good text fails the rule")
        self.assertTrue(rule(bad), "the edited text does not fire the rule")
    return test


for _name, _rule, _old, _new in RULES:
    setattr(WebFeSigninKdfPinsTest, "test_" + _name, _real_test(_rule))
    setattr(WebFeSigninKdfPinsTest, "test_control_" + _name, _control_test(_rule, _old, _new))
