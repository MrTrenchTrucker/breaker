"""Source pins for the web FE box public key function, boxPublicKey, in signin-crypto.js.

Not a `test_*.py` file on purpose: the contract-file bijection counts every
`tests/contract/test_*.py` as one module's contract test. The class below is
collected through `test_web_fe_contract.py`, which imports it.

Each rule takes the text of the file (None when the file is missing). Each rule
has a real-tree test, and every mutant row is a named control: the good text
must pass, and the same text with one named edit must fail.
"""

import os
import re
import unittest

import contract_support

WEB_JS = os.path.join("server", "modules", "web-fe", "src", "main", "resources", "web", "js")
CRYPTO = "signin-crypto.js"
IMPORT_KDF = "import { stretch } from './signin-kdf.js';"
IMPORT_CURVES = "import { x25519 } from './vendor/noble-curves/ed25519.js';"
IMPORT_SEAL = "import { seal as sealBox } from './signin-seal.js';"
DERIVE_LINE = ("export async function derive(password, salt, kdf) { try { return await stretch(password, salt, kdf); }"
               " catch { return null; } }")
FUNCTION_HEAD = "export function boxPublicKey(boxKey) {"
TYPE_CHECK = "instanceof Uint8Array"
LENGTH_CHECK = "boxKey.length !== 32"
GET_CALL = "x25519.getPublicKey("
CATCH_NULL = re.compile(r"\}\s*catch\s*(?:\([^)]*\))?\s*\{\s*return null;\s*\}")
THROW = re.compile(r"\bthrow\b")
TOKENS = ["randomBytes", "getRandomValues", "Math" ".random", "console", "fetch", "localStorage", ".fill("]
SEAL_LINE = ("export function seal(plaintext, recipientPublic, ephemeralPrivate) { "
             "return sealBox(plaintext, recipientPublic, ephemeralPrivate); }")
AVAILABLE_LINE = "export const available = false;"
GUARD_LINE = "    if (!(boxKey instanceof Uint8Array) || boxKey.length !== 32) return null;"
CALL_LINE = "    return x25519.getPublicKey(boxKey);"
CATCH_LINE = "  } catch { return null; }"


def _read(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except (OSError, UnicodeDecodeError):
        return None


def _held(text, line):
    return sum(1 for found in text.splitlines() if found.strip() == line)


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


def _box_body(text):
    """The text between the braces of boxPublicKey, or None when the function is absent or never closes."""
    if text.count(FUNCTION_HEAD) != 1:
        return None
    open_index = text.index(FUNCTION_HEAD) + len(FUNCTION_HEAD) - 1
    close_index = _close_index(text, open_index)
    return None if close_index < 0 else text[open_index + 1:close_index]


def _with_body(text, check, label):
    if text is None:
        return [CRYPTO + ": missing file"]
    body = _box_body(text)
    if body is None:
        return [CRYPTO + ": boxPublicKey body not found"]
    return [] if check(body) else [CRYPTO + ": boxPublicKey " + label]


def _guard_before_call(body, guard):
    return body.count(guard) == 1 and body.count(GET_CALL) == 1 and body.index(guard) < body.index(GET_CALL)


def box_imports(text):
    if text is None:
        return [CRYPTO + ": missing file"]
    statements = len(re.findall(r"^\s*import\b", text, re.M))
    dynamic = re.search(r"\bimport\s*\(", text)
    ok = (statements == 3 and _held(text, IMPORT_KDF) == 1 and _held(text, IMPORT_CURVES) == 1
          and _held(text, IMPORT_SEAL) == 1 and dynamic is None)
    return [] if ok else [CRYPTO + ": import lines are not the three required ones"]


def box_type_check(text):
    return _with_body(text, lambda body: _guard_before_call(body, TYPE_CHECK),
                      "has no Uint8Array type check before the call")


def box_length_check(text):
    return _with_body(text, lambda body: _guard_before_call(body, LENGTH_CHECK),
                      "has no length check before the call")


def box_try_catch(text):
    return _with_body(text, lambda body: "try {" in body and CATCH_NULL.search(body) is not None,
                      "has no try block with a catch that returns null")


def box_no_throw(text):
    return _with_body(text, lambda body: THROW.search(body) is None, "throws inside the body")


def box_tokens(text):
    return _with_body(text, lambda body: not any(token in body for token in TOKENS),
                      "holds a forbidden token")


def box_seal_stub(text):
    if text is None:
        return [CRYPTO + ": missing file"]
    return [] if _held(text, SEAL_LINE) == 1 else [CRYPTO + ": seal is not the facade line"]


def box_available(text):
    if text is None:
        return [CRYPTO + ": missing file"]
    return [] if _held(text, AVAILABLE_LINE) == 1 else [CRYPTO + ": available is not exactly false"]


BASE = [
    ("box_imports", box_imports),
    ("box_type_check", box_type_check),
    ("box_length_check", box_length_check),
    ("box_try_catch", box_try_catch),
    ("box_no_throw", box_no_throw),
    ("box_tokens", box_tokens),
    ("box_seal_stub", box_seal_stub),
    ("box_available", box_available),
]

# The same layout as the real file, without its comment lines.
GOOD_CRYPTO = "\n".join([
    IMPORT_KDF,
    IMPORT_CURVES,
    IMPORT_SEAL,
    AVAILABLE_LINE,
    DERIVE_LINE,
    FUNCTION_HEAD,
    "  try {",
    GUARD_LINE,
    CALL_LINE,
    CATCH_LINE,
    "}",
    SEAL_LINE,
]) + "\n"

# name, rule, old text, new text: each edit is applied once to the good text, and the rule must then fire.
MUTANTS = [
    ("box_imports_third", box_imports, IMPORT_CURVES,
     IMPORT_CURVES + "\nimport { y } from './y.js';"),
    ("box_imports_bare", box_imports, IMPORT_CURVES,
     "import { x25519 } from '@noble/curves/ed25519.js';"),
    ("box_imports_removed", box_imports, IMPORT_SEAL + "\n", ""),
    ("box_type_check_removed", box_type_check, "!(boxKey instanceof Uint8Array) || ", ""),
    ("box_length_check_removed", box_length_check, " || boxKey.length !== 32", ""),
    ("box_length_check_after_call", box_length_check, GUARD_LINE + "\n" + CALL_LINE,
     CALL_LINE + "\n" + GUARD_LINE),
    ("box_try_catch_returns_error", box_try_catch, CATCH_LINE,
     "  } catch (err) { return err; }"),
    ("box_no_throw_inside", box_no_throw, CALL_LINE,
     "    throw new Error('length');\n" + CALL_LINE),
    ("box_tokens_console", box_tokens, CALL_LINE,
     "    console.log(boxKey.length);\n" + CALL_LINE),
    ("box_seal_returns_null", box_seal_stub, SEAL_LINE,
     "export function seal(plaintext, recipientPublic, ephemeralPrivate) { return null; }"),
    ("box_available_true", box_available, AVAILABLE_LINE, "export const available = true;"),
]


def check_signin_box(text):
    return [problem for _name, rule in BASE for problem in rule(text)]


def _real_test(rule):
    def test(self):
        self.assertEqual(rule(self.text), [])
    return test


def _mutant_test(rule, old, new):
    def test(self):
        self.assertIn(old, GOOD_CRYPTO, "control edit did not land")
        bad = GOOD_CRYPTO.replace(old, new, 1)
        self.assertEqual(rule(GOOD_CRYPTO), [], "the good text fails the rule")
        self.assertTrue(rule(bad), "the edited text does not fire the rule")
    return test


class WebFeSigninBoxPinsTest(unittest.TestCase):

    def setUp(self):
        self.text = _read(os.path.join(contract_support.ROOT, WEB_JS, CRYPTO))

    def test_real_tree_is_clean(self):
        self.assertEqual(check_signin_box(self.text), [])

    def test_missing_file_fires_every_rule(self):
        for name, rule in BASE:
            self.assertTrue(rule(None), name)


for _name, _rule in BASE:
    setattr(WebFeSigninBoxPinsTest, "test_real_" + _name, _real_test(_rule))
for _name, _rule, _old, _new in MUTANTS:
    setattr(WebFeSigninBoxPinsTest, "test_control_" + _name, _mutant_test(_rule, _old, _new))
