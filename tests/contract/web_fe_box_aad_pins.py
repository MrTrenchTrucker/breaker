"""Source pins for the purpose AAD of the box private key in the web FE sign-in.

Not a `test_*.py` file on purpose: the contract-file bijection counts every
`tests/contract/test_*.py`. The class is collected through `test_web_fe_contract.py`.

The purpose string is never typed here: it is read from the shared vector file.
Each rule takes a dict of inputs and returns problems. Each control edits the
real text once, and the named rule must report it.
"""

import json
import os
import re
import unittest

import contract_support

WEB = os.path.join("server", "modules", "web-fe", "src", "main", "resources", "web", "js")
VECTORS = os.path.join("shared", "modules", "api-contracts", "examples", "crypto_vectors.json")
AESGCM = "aesgcm.js"
SIGNIN = "signin.js"
KEY_AESGCM = "aesgcm"
KEY_SIGNIN = "signin"
KEY_PAGE = ("web", "signin-page.js")
BOX_ENTRY = "box-private-key-under-dek"
PREV_ENTRY = "previous-dek-under-new-dek"
WRAP_SIG = "function wrapDek(dek, aad = new Uint8Array(0)) {"
UNWRAP_SIG = "function unwrapDek(wrapped, aad = new Uint8Array(0)) {"
SEAL_CALL = "seal(dek, aad);"
OPEN_CALL = "open(record, aad);"
STALE = ["NO aad argument", "EMPTY AAD always"]
PARTS_RE = re.compile(r"const BOX_PURPOSE_PARTS = Object\.freeze\(\[([^\]]*)\]\);")
DEFINE_LINE = "const BOX_KEY_PURPOSE = new TextEncoder().encode(BOX_PURPOSE_PARTS.join('-'));"
UNWRAP_HEAD = "function unwrapKeys("
UNWRAP_CALL_RE = re.compile(r"\.unwrapDek\(([^)]*)\)")
DEF_RE = re.compile(r"function\s+(?:un)?wrapDek\s*\(")
CALL_RE = re.compile(r"(?<![A-Za-z0-9_$])(?:un)?wrapDek\s*\(")
COMMENT_RE = re.compile(r"//.*$")


def _read(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except (OSError, UnicodeDecodeError):
        return None


def load_inputs(root):
    web_dir = os.path.join(root, WEB)
    web = {}
    for dirpath, dirnames, filenames in os.walk(web_dir):
        dirnames[:] = [name for name in dirnames if name != "vendor"]
        for name in filenames:
            if name.endswith(".js"):
                full = os.path.join(dirpath, name)
                web[os.path.relpath(full, web_dir).replace(os.sep, "/")] = _read(full)
    raw = _read(os.path.join(root, VECTORS))
    return {
        KEY_AESGCM: web.get(AESGCM),
        KEY_SIGNIN: web.get(SIGNIN),
        "web": web,
        "vectors": None if raw is None else json.loads(raw),
    }


def _close_index(text, open_index):
    depth = 0
    for i in range(open_index, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return -1


def _unwrap_keys_body(text):
    if text.count(UNWRAP_HEAD) != 1:
        return None
    open_index = text.index("{", text.index(UNWRAP_HEAD))
    close_index = _close_index(text, open_index)
    return None if close_index < 0 else text[open_index + 1:close_index]


def _purpose(vectors, name):
    rows = [row for row in vectors.get("wrap", []) if row.get("name") == name]
    if len(rows) != 1:
        return None
    return bytes.fromhex(rows[0]["aad_hex"]).decode("ascii")


def _parts(text):
    found = PARTS_RE.findall(text)
    if len(found) != 1:
        return None
    return re.findall(r"'([^']*)'", found[0])


def _with_parts(text, items):
    body = ", ".join("'" + part + "'" for part in items)
    return PARTS_RE.sub(lambda _found: "const BOX_PURPOSE_PARTS = Object.freeze([" + body + "]);", text, count=1)


def rule_aesgcm(inputs):
    text = inputs[KEY_AESGCM]
    if text is None:
        return [AESGCM + ": missing file"]
    problems = [AESGCM + ": " + need + " is not held once"
                for need in (WRAP_SIG, UNWRAP_SIG, SEAL_CALL, OPEN_CALL) if text.count(need) != 1]
    return problems + [AESGCM + ": stale comment " + phrase for phrase in STALE if phrase in text]


def rule_unwrap_keys(inputs):
    text = inputs[KEY_SIGNIN]
    if text is None:
        return [SIGNIN + ": missing file"]
    body = _unwrap_keys_body(text)
    if body is None:
        return [SIGNIN + ": unwrapKeys body not found"]
    calls = [[arg.strip() for arg in found.split(",")] for found in UNWRAP_CALL_RE.findall(body)]
    if calls != [["wrappedDek"], ["wrappedBox", "BOX_KEY_PURPOSE"]]:
        return [SIGNIN + ": unwrapKeys calls are " + repr(calls)]
    return []


def rule_constant(inputs):
    text, vectors = inputs[KEY_SIGNIN], inputs["vectors"]
    if text is None:
        return [SIGNIN + ": missing file"]
    if vectors is None:
        return [VECTORS + ": missing file"]
    box, prev = _purpose(vectors, BOX_ENTRY), _purpose(vectors, PREV_ENTRY)
    if box is None or prev is None:
        return [VECTORS + ": the box or previous entry is not held once"]
    parts = _parts(text)
    if parts is None:
        return [SIGNIN + ": the purpose parts array is not held once"]
    joined = "-".join(parts)
    problems = []
    if joined != box:
        problems.append(SIGNIN + ": the parts do not join to the box purpose of the vector file")
    if joined == prev:
        problems.append(SIGNIN + ": the previous-key purpose is used")
    if text.count(DEFINE_LINE) != 1:
        problems.append(SIGNIN + ": the purpose bytes line is not held once")
    if box in text:
        problems.append(SIGNIN + ": the whole purpose string is held as text")
    return problems


def callers(web):
    found = []
    for name in sorted(web):
        for line in (web[name] or "").splitlines():
            code = DEF_RE.sub("", COMMENT_RE.sub("", line))
            if CALL_RE.search(code):
                found.append(name)
    return found


def rule_callers(inputs):
    found = callers(inputs["web"])
    if found != [SIGNIN, SIGNIN]:
        return ["calls of wrapDek or unwrapDek are in " + ", ".join(found)]
    return []


def _text(inputs, key):
    if isinstance(key, tuple):
        return inputs[key[0]][key[1]]
    return inputs[key]


def _put(inputs, key, text):
    out = dict(inputs)
    if isinstance(key, tuple):
        out[key[0]] = dict(inputs[key[0]])
        out[key[0]][key[1]] = text
    else:
        out[key] = text
    return out


def _once(old, new):
    def change(text, _vectors):
        if text.count(old) != 1:
            raise AssertionError("the control anchor is not held exactly once: " + old)
        return text.replace(old, new, 1)
    return change


def _append(extra):
    def change(text, _vectors):
        return text + extra
    return change


def _parts_change(make):
    def change(text, vectors):
        items = _parts(text)
        if items is None:
            raise AssertionError("the purpose parts array is not held exactly once")
        return _with_parts(text, make(items, vectors))
    return change


def _last_character_up(items, _vectors):
    last = items[-1]
    return items[:-1] + [last[:-1] + chr(ord(last[-1]) + 1)]


def _previous_items(_items, vectors):
    return _purpose(vectors, PREV_ENTRY).split("-")


def _whole_text(text, vectors):
    return text + "\nconst WHOLE_TEXT = '" + _purpose(vectors, BOX_ENTRY) + "';\n"


def _define_dropped(text, _vectors):
    if text.count(DEFINE_LINE) != 1:
        raise AssertionError("the purpose bytes line is not held exactly once")
    return text.replace(DEFINE_LINE, "", 1)


RULES = [
    ("aesgcm_aad", rule_aesgcm),
    ("unwrap_keys_aad", rule_unwrap_keys),
    ("constant_from_vectors", rule_constant),
    ("only_signin_calls", rule_callers),
]

MUTANTS = [
    ("aad_dropped_from_wrap", rule_aesgcm, KEY_AESGCM,
     _once(SEAL_CALL, "seal(dek, new Uint8Array(0));"), SEAL_CALL),
    ("aad_dropped_from_unwrap", rule_aesgcm, KEY_AESGCM,
     _once(OPEN_CALL, "open(record, new Uint8Array(0));"), OPEN_CALL),
    ("default_not_empty", rule_aesgcm, KEY_AESGCM,
     _once(WRAP_SIG, "function wrapDek(dek, aad = new Uint8Array(1)) {"), WRAP_SIG),
    ("wrap_without_parameter", rule_aesgcm, KEY_AESGCM,
     _once(WRAP_SIG, "function wrapDek(dek) {"), WRAP_SIG),
    ("unwrap_without_parameter", rule_aesgcm, KEY_AESGCM,
     _once(UNWRAP_SIG, "function unwrapDek(wrapped) {"), UNWRAP_SIG),
    ("unwrap_default_not_empty", rule_aesgcm, KEY_AESGCM,
     _once(UNWRAP_SIG, "function unwrapDek(wrapped, aad = new Uint8Array(1)) {"), UNWRAP_SIG),
    ("stale_comment_restored", rule_aesgcm, KEY_AESGCM,
     _append("\n// EMPTY AAD always\n"), "stale comment EMPTY AAD always"),
    ("box_aad_dropped", rule_unwrap_keys, KEY_SIGNIN,
     _once("unwrapDek(wrappedBox, BOX_KEY_PURPOSE)", "unwrapDek(wrappedBox)"), "unwrapKeys calls are"),
    ("aad_passed_for_dek", rule_unwrap_keys, KEY_SIGNIN,
     _once("unwrapDek(wrappedDek)", "unwrapDek(wrappedDek, BOX_KEY_PURPOSE)"), "unwrapKeys calls are"),
    ("aad_swapped_name", rule_unwrap_keys, KEY_SIGNIN,
     _once("unwrapDek(wrappedBox, BOX_KEY_PURPOSE)", "unwrapDek(wrappedBox, PREV_DEK_PURPOSE)"),
     "unwrapKeys calls are"),
    ("one_character_changed", rule_constant, KEY_SIGNIN,
     _parts_change(_last_character_up), "the parts do not join to the box purpose"),
    ("previous_purpose_used", rule_constant, KEY_SIGNIN,
     _parts_change(_previous_items), "the previous-key purpose is used"),
    ("whole_text_held", rule_constant, KEY_SIGNIN,
     _whole_text, "the whole purpose string is held as text"),
    ("define_line_dropped", rule_constant, KEY_SIGNIN,
     _define_dropped, "the purpose bytes line is not held once"),
    ("third_caller_added", rule_callers, KEY_PAGE,
     _append("\nexport const probe = (c, p) => c.wrapDek(p);\n"), "calls of wrapDek or unwrapDek are in"),
]


def _real(rule):
    def test(self):
        self.assertEqual(rule(self.inputs), [])
    return test


def _control(rule, key, change, fragment):
    def test(self):
        self.assertEqual(rule(self.inputs), [], "the real text fails the rule")
        before = _text(self.inputs, key)
        self.assertIsNotNone(before, "the real text is missing")
        after = change(before, self.inputs["vectors"])
        self.assertNotEqual(after, before, "control edit did not land")
        problems = rule(_put(self.inputs, key, after))
        self.assertTrue(any(fragment in problem for problem in problems),
                        "the named pin did not report the edit: " + repr(problems))
    return test


class WebFeBoxAadPinsTest(unittest.TestCase):

    def setUp(self):
        self.inputs = load_inputs(contract_support.ROOT)


for _name, _rule in RULES:
    setattr(WebFeBoxAadPinsTest, "test_real_" + _name, _real(_rule))
for _name, _rule, _key, _change, _fragment in MUTANTS:
    setattr(WebFeBoxAadPinsTest, "test_control_" + _name, _control(_rule, _key, _change, _fragment))
