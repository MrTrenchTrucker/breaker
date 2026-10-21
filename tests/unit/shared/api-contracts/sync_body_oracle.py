"""Independent oracle for the sync body vectors. Standard library only.

Re-derives each vector from its input under the sync body format rules, without the
codec. Used by test_sync_body_vectors.py; it is not a test module itself.
"""
import base64
import datetime
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))))
API = os.path.join(ROOT, "shared", "modules", "api-contracts")
VECTOR_PATH = os.path.join(API, "examples", "sync_body_vectors.json")
KOTLIN_PATH = os.path.join(API, "src", "test", "kotlin", "dev", "breaker", "shared", "api", "sync",
                           "SyncBodyVectorData.kt")
MAX_DURATION = 9007199254740991
EPOCH0 = datetime.datetime(1970, 1, 1)
FAMILIES = {"base64", "utf8", "bom", "not_object", "version", "missing_key", "duplicate_key",
            "unknown_key", "type", "source", "duration", "created_at", "surrogate", "truncated",
            "trailing", "client_id", "syntax"}
KINDS = {"body", "plain", "client_id", "created_at"}
KNOWN = {"v", "text", "source", "model", "duration_ms", "created_at"}
WS, HEX, NUM = " \t\n\r", "0123456789abcdefABCDEF", "0123456789+-.eE"
CREATED_RE = r"([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})\.([0-9]{3})Z"
CLIENT_RE = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
ESC = {'"': '\\"', "\\": "\\\\", "\b": "\\b", "\f": "\\f", "\n": "\\n", "\r": "\\r", "\t": "\\t"}
UNESC = {'"': '"', "\\": "\\", "/": "/", "b": "\b", "f": "\f", "n": "\n", "r": "\r", "t": "\t"}


class Refusal(Exception):
    """The message is the refusal family."""


class Scanner:
    """Strict JSON reader for the sync body format (json.loads is not used)."""

    def __init__(self, src):
        self.s, self.i = src, 0

    def peek(self):
        if self.i >= len(self.s):
            raise Refusal("truncated")
        return self.s[self.i]

    def take(self, allowed):
        c = self.peek()
        if c not in allowed:
            raise Refusal("syntax")
        self.i += 1
        return c

    def ws(self):
        while self.i < len(self.s) and self.s[self.i] in WS:
            self.i += 1

    def document(self):
        self.ws()
        if self.peek() != "{":
            raise Refusal("not_object")
        members = self.container("{", "}")
        self.ws()
        if self.i < len(self.s):
            raise Refusal("trailing")
        return members

    def container(self, open_c, close_c):
        """An object for a brace, an array for a bracket."""
        self.take(open_c)
        self.ws()
        members = {} if open_c == "{" else []
        if self.peek() == close_c:
            self.take(close_c)
            return members
        while True:
            if open_c == "{":
                key = self.string()
                if key in members:
                    raise Refusal("duplicate_key")
                self.ws()
                self.take(":")
                self.ws()
                members[key] = self.value()
            else:
                self.value()
            self.ws()
            if self.take("," + close_c) == close_c:
                return members
            self.ws()

    def value(self):
        c = self.peek()
        if c == '"':
            return ("str", self.string())
        if c in "{[":
            self.container(c, "}" if c == "{" else "]")
            return ("nested", None)
        if c in "tfn":
            word = {"t": "true", "f": "false", "n": "null"}[c]
            for ch in word:
                self.take(ch)
            return ("lit", word)
        if c == "-" or c in "0123456789":
            start = self.i
            while self.i < len(self.s) and self.s[self.i] in NUM:
                self.i += 1
            return ("num", self.s[start:self.i])
        raise Refusal("syntax")

    def string(self):
        self.take('"')
        out = []
        while True:
            c = self.peek()
            self.i += 1
            if c == '"':
                return "".join(out)
            if c == "\\":
                out.append(self.escape())
            elif ord(c) < 0x20:
                raise Refusal("syntax")
            else:
                out.append(c)

    def unit(self):
        v = 0
        for _ in range(4):
            v = v * 16 + int(self.take(HEX), 16)
        return v

    def escape(self):
        c = self.take(list(UNESC) + ["u"])
        if c != "u":
            return UNESC[c]
        code = self.unit()
        if 0xDC00 <= code <= 0xDFFF:
            raise Refusal("surrogate")
        if not 0xD800 <= code <= 0xDBFF:
            return chr(code)
        if self.peek() != "\\":
            raise Refusal("surrogate")
        self.i += 1
        if self.peek() != "u":
            raise Refusal("surrogate")
        self.i += 1
        low = self.unit()
        if not 0xDC00 <= low <= 0xDFFF:
            raise Refusal("surrogate")
        return chr(0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00))


def field(members, name, kind):
    if name not in members:
        raise Refusal("missing_key")
    if members[name][0] != kind:
        raise Refusal("type")
    return members[name][1]


def body(raw):
    """BOM, UTF-8, scan, unknown keys, then the six fields (missing, type, value)."""
    if raw[:3] == b"\xef\xbb\xbf":
        raise Refusal("bom")
    try:
        members = Scanner(raw.decode("utf-8")).document()
    except UnicodeDecodeError:
        raise Refusal("utf8")
    if any(k not in KNOWN for k in members):
        raise Refusal("unknown_key")
    if "v" not in members:
        raise Refusal("missing_key")
    if members["v"] != ("num", "1"):
        raise Refusal("version")
    text = field(members, "text", "str")
    source = field(members, "source", "str")
    if source not in ("local", "server"):
        raise Refusal("source")
    model = field(members, "model", "str")
    token = field(members, "duration_ms", "num")
    if not re.fullmatch(r"0|[1-9][0-9]*", token) or int(token) > MAX_DURATION:
        raise Refusal("duration")
    created = created_at_epoch(field(members, "created_at", "str"))
    return {"text": text, "source": source, "model": model, "duration": int(token), "created": created}


def created_at_epoch(text):
    m = re.fullmatch(CREATED_RE, text)
    if not m or int(m.group(1)) < 1970:
        raise Refusal("created_at")
    p = [int(g) for g in m.groups()]
    try:
        when = datetime.datetime(p[0], p[1], p[2], p[3], p[4], p[5], p[6] * 1000)
    except ValueError:
        raise Refusal("created_at")
    return (when - EPOCH0) // datetime.timedelta(milliseconds=1)


def esc(s):
    return "".join(ESC.get(ch, ch if ord(ch) >= 0x20 else "\\u%04x" % ord(ch)) for ch in s)


def r1_text(b):
    when = EPOCH0 + datetime.timedelta(milliseconds=b["created"])
    stamp = when.strftime("%Y-%m-%dT%H:%M:%S") + ".%03dZ" % (when.microsecond // 1000)
    return ('{"v":1,"text":"%s","source":"%s","model":"%s","duration_ms":%d,"created_at":"%s"}'
            % (esc(b["text"]), b["source"], esc(b["model"]), b["duration"], stamp))


def plain(s):
    try:
        raw = base64.b64decode(s, validate=True) if s else None
    except ValueError:
        raw = None
    if raw is None or base64.b64encode(raw).decode("ascii") != s:
        raise Refusal("base64")
    return body(raw)


def client_id(s):
    if not re.fullmatch(CLIENT_RE, s):
        raise Refusal("client_id")


def kotlin_literal(s):
    """Backslash and quote escaped, printable ASCII raw, other UTF-16 units as lower-case escapes."""
    out = []
    for ch in s:
        if ch in '"\\':
            out.append("\\" + ch)
        elif 0x20 <= ord(ch) <= 0x7E:
            out.append(ch)
        else:
            units = ch.encode("utf-16-be")
            out += ["\\u%04x" % int.from_bytes(units[k:k + 2], "big") for k in range(0, len(units), 2)]
    return "".join(out)


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def load_doc():
    return json.loads(read(VECTOR_PATH))


def vectors(kind=None, expect=None):
    return [v for v in load_doc()["vectors"]
            if kind in (None, v["kind"]) and expect in (None, v["expect"])]


def input_bytes(v):
    return bytes.fromhex(v["input_hex"]) if "input_hex" in v else v["input"].encode("utf-8")


def refusal(fn, arg):
    """The refusal family of fn(arg), or 'ok' when fn accepts the input."""
    try:
        fn(arg)
    except Refusal as e:
        return str(e)
    return "ok"


def b64(s):
    return base64.b64encode(s.encode("utf-8")).decode("ascii")
