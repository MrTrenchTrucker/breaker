"""Source pins for the seal wrapper, signin-seal.js, and the three files that call it or re-export it.

Not a `test_*.py` file on purpose: the contract-file bijection counts every
`tests/contract/test_*.py`. The class is collected through `test_web_fe_contract.py`.

Each rule takes the text of one file (None when the file is missing) and returns problems.
Each rule has a real-tree test. Every mutant row is a named control: the real text must
pass the rule, and the same text with one named edit must fail it.
"""

import os
import re
import unittest

import contract_support

WEB_JS = os.path.join("server", "modules", "web-fe", "src", "main", "resources", "web", "js")
SEAL, CRYPTO, PAGE, SIGNIN = "signin-seal.js", "signin-crypto.js", "signin-page.js", "signin.js"
REGISTRY = "modules.toml"
SEAL_IMPORTS = [
    "import { x25519 } from './vendor/noble-curves/ed25519.js';",
    "import { hsalsa, xsalsa20poly1305 } from './vendor/noble-ciphers/salsa.js';",
    "import { randomBytes } from './vendor/noble-ciphers/utils.js';",
    "import { blake2b } from './vendor/noble-hashes/blake2.js';",
]
GUARDS = [
    "if (!(plaintext instanceof Uint8Array) || plaintext.length < 1) return null;",
    "if (!(recipientPublic instanceof Uint8Array) || recipientPublic.length !== 32) return null;",
    "if (ephemeralPrivate !== undefined && (!(ephemeralPrivate instanceof Uint8Array) "
    "|| ephemeralPrivate.length !== 32)) return null;",
]
CRYPTO_MARKERS = ["randomBytes(", "x25519.", "hsalsa(", "blake2b(", "xsalsa20poly1305("]
SIGMA_LINE = "const SIGMA = wordsLE(new TextEncoder().encode('expand 32-byte k'));"
SHARED_WORDS_LINE = "sharedWords = wordsLE(shared);"
SHARED_CALL = "shared = x25519.getSharedSecret(ephemeral, recipientPublic);"
LOW_ORDER_LINE = "if (shared.every((byte) => byte === 0)) return null;"
LOW_ORDER_OLD = "    " + LOW_ORDER_LINE + "\n"
LOW_ORDER_MOVED_OLD = ("    " + LOW_ORDER_LINE + "\n    sharedWords = wordsLE(shared);\n"
                       "    keyWords = new Uint32Array(8);\n"
                       "    hsalsa(SIGMA, sharedWords, new Uint32Array(4), keyWords);\n")
LOW_ORDER_MOVED_NEW = ("    sharedWords = wordsLE(shared);\n    keyWords = new Uint32Array(8);\n"
                       "    hsalsa(SIGMA, sharedWords, new Uint32Array(4), keyWords);\n"
                       "    " + LOW_ORDER_LINE + "\n")
NONCE_CALL = "const nonce = blake2b(concat(epk, recipientPublic), { dkLen: 24 });"
KEY_CALL = "hsalsa(SIGMA, sharedWords, new Uint32Array(4), keyWords);"
RANDOM_GUARD = "const createdHere = ephemeralPrivate === undefined;"
RANDOM_CALL = "ephemeral = createdHere ? randomBytes(32) : ephemeralPrivate;"
FINALLY_LINES = [
    "if (createdHere && ephemeral !== null) ephemeral.fill(0);",
    "if (shared !== null) shared.fill(0);",
    "if (sharedWords !== null) sharedWords.fill(0);",
    "if (key !== null) key.fill(0);",
    "if (keyWords !== null) keyWords.fill(0);",
]
CATCH_NULL = re.compile(r"\bcatch\s*(?:\([^)]*\))?\s*\{\s*return null;")
RANDOM_TOKEN = re.compile(r"\w*[Rr]andom\w*")
WORD_TOKENS = re.compile(r"\b(?:getRandomValues|crypto|console|fetch|localStorage|Date)\b")
FACADE_SEAL = ("export function seal(plaintext, recipientPublic, ephemeralPrivate) { "
               "return sealBox(plaintext, recipientPublic, ephemeralPrivate); }")
FACADE_IMPORT = "import { seal as sealBox } from './signin-seal.js';"
FACADE_NAMES = ["available", "boxPublicKey", "derive", "seal"]


def _read(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except (OSError, UnicodeDecodeError):
        return None


def load_texts(root):
    web = os.path.join(root, WEB_JS)
    return {
        "seal": _read(os.path.join(web, SEAL)),
        "crypto": _read(os.path.join(web, CRYPTO)),
        "page": _read(os.path.join(web, PAGE)),
        "signin": _read(os.path.join(web, SIGNIN)),
        "registry": _read(os.path.join(root, REGISTRY)),
    }


def _held(text, line):
    return sum(1 for found in text.splitlines() if found.strip() == line)


def _block(text, marker):
    """The text between the braces that open at the end of marker, or None when absent or never closed."""
    if text.count(marker) != 1:
        return None
    open_index = text.index(marker) + len(marker) - 1
    depth = 0
    for i in range(open_index, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[open_index + 1:i]
    return None


def seal_imports(text):
    if text is None:
        return [SEAL + ": missing file"]
    statements = len(re.findall(r"^\s*import\b", text, re.M))
    dynamic = re.search(r"\bimport\s*\(", text)
    held = all(_held(text, line) == 1 for line in SEAL_IMPORTS)
    ok = statements == 4 and held and dynamic is None
    return [] if ok else [SEAL + ": import lines are not the four required ones"]


def seal_guards(text):
    if text is None:
        return [SEAL + ": missing file"]
    if any(_held(text, line) != 1 for line in GUARDS):
        return [SEAL + ": an input guard line is not held once"]
    first = [text.index(marker) for marker in CRYPTO_MARKERS if marker in text]
    last = max(text.index(line) for line in GUARDS)
    ok = bool(first) and last < min(first)
    return [] if ok else [SEAL + ": an input guard comes after the first crypto call"]


def seal_nonce(text):
    if text is None:
        return [SEAL + ": missing file"]
    ok = text.count("blake2b(") == 1 and _held(text, NONCE_CALL) == 1
    return [] if ok else [SEAL + ": the nonce call is not the pinned blake2b call"]


def seal_key_step(text):
    if text is None:
        return [SEAL + ": missing file"]
    ok = (text.count("hsalsa(") == 1 and _held(text, KEY_CALL) == 1 and _held(text, SIGMA_LINE) == 1
          and _held(text, SHARED_WORDS_LINE) == 1)
    return [] if ok else [SEAL + ": the key step is not the pinned hsalsa call"]


def seal_low_order(text):
    if text is None:
        return [SEAL + ": missing file"]
    ok = (text.count(SHARED_CALL) == 1 and _held(text, LOW_ORDER_LINE) == 1
          and text.index(SHARED_CALL) < text.index(LOW_ORDER_LINE) < text.index("hsalsa("))
    return [] if ok else [SEAL + ": the all-zero shared secret check is not between the shared call and hsalsa"]


def seal_randomness(text):
    if text is None:
        return [SEAL + ": missing file"]
    tokens = set(RANDOM_TOKEN.findall(text))
    ok = (tokens == {"randomBytes"} and text.count("randomBytes(") == 1 and _held(text, RANDOM_CALL) == 1
          and _held(text, RANDOM_GUARD) == 1 and WORD_TOKENS.search(text) is None)
    return [] if ok else [SEAL + ": randomness or a forbidden token outside the pinned shape"]


def seal_never_throw(text):
    if text is None:
        return [SEAL + ": missing file"]
    body = _block(text, "finally {")
    ok = ("try {" in text and CATCH_NULL.search(text) is not None and body is not None
          and all(_held(body, line) == 1 for line in FINALLY_LINES)
          and re.search(r"\bthrow\b", text) is None and "ephemeralPrivate.fill(" not in text)
    return [] if ok else [SEAL + ": the catch, the finally zero-fill or the no-throw rule is not held"]


def seal_call_shape(text):
    if text is None:
        return [SIGNIN + ": missing file"]
    calls = re.findall(r"deps\.seal\(([^()]*)\)", text)
    ok = text.count("deps.seal(") == 1 and len(calls) == 1 and len(calls[0].split(",")) == 2
    return [] if ok else [SIGNIN + ": deps.seal is not called with exactly two arguments"]


def seal_wiring(text):
    if text is None:
        return [PAGE + ": missing file"]
    ok = _held(text, "seal: crypto.seal,") == 1
    return [] if ok else [PAGE + ": seal is not wired to crypto.seal directly"]


def seal_registry(text):
    if text is None:
        return [REGISTRY + ": missing file"]
    parts = text.split("[module.server_web_fe]")
    if len(parts) != 2:
        return [REGISTRY + ": web-fe header not found once"]
    body = re.split(r"\n\[", parts[1], 1)[0]
    public = [line for line in body.splitlines() if line.startswith("public = ")]
    ok = len(public) == 1 and "js/signin-seal.js" in public[0]
    return [] if ok else [REGISTRY + ": the web-fe public text does not name js/signin-seal.js"]


def seal_facade(text):
    if text is None:
        return [CRYPTO + ": missing file"]
    names = sorted(re.findall(r"^export\s+(?:async\s+)?(?:function\*?|const|let|var|class)\s+(\w+)", text, re.M))
    bare = re.search(r"^export\s*(?:\{|default\b)", text, re.M)
    ok = (names == sorted(FACADE_NAMES) and bare is None and _held(text, FACADE_SEAL) == 1
          and _held(text, FACADE_IMPORT) == 1 and _held(text, "export const available = false;") == 1
          and "getSharedSecret" not in text)
    return [] if ok else [CRYPTO + ": the facade is not the four exports with the seal line"]


# name, rule, the key of the text it reads
RULES = [
    ("seal_imports", seal_imports, "seal"),
    ("seal_guards", seal_guards, "seal"),
    ("seal_nonce", seal_nonce, "seal"),
    ("seal_key_step", seal_key_step, "seal"),
    ("seal_low_order", seal_low_order, "seal"),
    ("seal_randomness", seal_randomness, "seal"),
    ("seal_never_throw", seal_never_throw, "seal"),
    ("seal_call_shape", seal_call_shape, "signin"),
    ("seal_wiring", seal_wiring, "page"),
    ("seal_registry", seal_registry, "registry"),
    ("seal_facade", seal_facade, "crypto"),
]

# name, rule, the key of the text, the old text (once in the real text), the new text
CONTROLS = [
    ("seal_imports_fifth", seal_imports, "seal", SEAL_IMPORTS[0],
     SEAL_IMPORTS[0] + "\nimport { y } from './y.js';"),
    ("seal_imports_bare", seal_imports, "seal", SEAL_IMPORTS[0],
     "import { x25519 } from '@noble/curves/ed25519.js';"),
    ("seal_imports_removed", seal_imports, "seal", SEAL_IMPORTS[3] + "\n", ""),
    ("seal_guard_plaintext_removed", seal_guards, "seal", " || plaintext.length < 1", ""),
    ("seal_guard_recipient_removed", seal_guards, "seal", " || recipientPublic.length !== 32", ""),
    ("seal_guard_ephemeral_removed", seal_guards, "seal", " || ephemeralPrivate.length !== 32", ""),
    ("seal_guard_after_call", seal_guards, "seal", GUARDS[0],
     "const early = randomBytes(32);\n  " + GUARDS[0]),
    ("seal_nonce_order_swapped", seal_nonce, "seal", "concat(epk, recipientPublic)",
     "concat(recipientPublic, epk)"),
    ("seal_nonce_key_added", seal_nonce, "seal", "{ dkLen: 24 }", "{ key: recipientPublic, dkLen: 24 }"),
    ("seal_nonce_dklen_32", seal_nonce, "seal", "dkLen: 24", "dkLen: 32"),
    ("seal_nonce_personalization_added", seal_nonce, "seal", "{ dkLen: 24 }",
     "{ dkLen: 24, personalization: recipientPublic }"),
    ("seal_key_call_removed", seal_key_step, "seal", KEY_CALL, "keyWords.fill(0);"),
    ("seal_key_input_nonzero", seal_key_step, "seal", "new Uint32Array(4), keyWords",
     "new Uint32Array([1, 0, 0, 0]), keyWords"),
    ("seal_key_sigma_changed", seal_key_step, "seal", "'expand 32-byte k'", "'expand 32-byte K'"),
    ("seal_key_words_inline", seal_key_step, "seal", KEY_CALL,
     "hsalsa(SIGMA, wordsLE(shared), new Uint32Array(4), keyWords);"),
    ("seal_key_words_assignment_removed", seal_key_step, "seal", "    sharedWords = wordsLE(shared);\n", ""),
    ("seal_low_order_removed", seal_low_order, "seal", LOW_ORDER_OLD, ""),
    ("seal_low_order_after_hsalsa", seal_low_order, "seal", LOW_ORDER_MOVED_OLD, LOW_ORDER_MOVED_NEW),
    ("seal_random_math_added", seal_randomness, "seal", "  try {",
     "  try {\n    const roll = Math" + ".random();"),
    ("seal_random_unconditional", seal_randomness, "seal", "createdHere ? randomBytes(32) : ephemeralPrivate",
     "randomBytes(32)"),
    ("seal_finally_fill_removed", seal_never_throw, "seal", "    if (shared !== null) shared.fill(0);\n", ""),
    ("seal_finally_words_fill_removed", seal_never_throw, "seal",
     "    if (sharedWords !== null) sharedWords.fill(0);\n", ""),
    ("seal_finally_caller_key_fill", seal_never_throw, "seal",
     "if (createdHere && ephemeral !== null) ephemeral.fill(0);",
     "if (createdHere && ephemeral !== null) ephemeral.fill(0);\n    ephemeralPrivate.fill(0);"),
    ("seal_throw_added", seal_never_throw, "seal", "  } catch {", "  } catch {\n    throw new Error('x');"),
    ("seal_call_third_argument", seal_call_shape, "signin", "await deps.seal(pkg, cur.appCopy);",
     "await deps.seal(pkg, cur.appCopy, undefined);"),
    ("seal_wiring_wrapped", seal_wiring, "page", "    seal: crypto.seal,",
     "    seal: (a, b, c) => crypto.seal(a, b, c),"),
    ("seal_registry_dropped", seal_registry, "registry", "js/signin-seal.js, ", ""),
    ("seal_facade_holds_shared_secret", seal_facade, "crypto", "export const available = false;",
     "export const available = false;\nconst unused = x25519.getSharedSecret;"),
    ("seal_facade_extra_export", seal_facade, "crypto", "export const available = false;",
     "export const available = false;\nexport const extra = 1;"),
]


class WebFeSigninSealPinsTest(unittest.TestCase):

    def setUp(self):
        self.texts = load_texts(contract_support.ROOT)

    def test_missing_file_fires_every_rule(self):
        for name, rule, _key in RULES:
            self.assertTrue(rule(None), name)


def _real_test(rule, key):
    def test(self):
        self.assertEqual(rule(self.texts[key]), [])
    return test


def _control_test(rule, key, old, new):
    def test(self):
        text = self.texts[key]
        self.assertIn(old, text, "control edit did not land")
        bad = text.replace(old, new, 1)
        self.assertEqual(rule(text), [], "the real text fails the rule")
        self.assertTrue(rule(bad), "the edited text does not fire the rule")
    return test


for _name, _rule, _key in RULES:
    setattr(WebFeSigninSealPinsTest, "test_real_" + _name, _real_test(_rule, _key))
for _name, _rule, _key, _old, _new in CONTROLS:
    setattr(WebFeSigninSealPinsTest, "test_control_" + _name, _control_test(_rule, _key, _old, _new))
