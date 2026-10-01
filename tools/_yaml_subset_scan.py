"""The scanning half of the reader in yaml_subset.py: the constants, `_Line`, three small functions and `_ScanMixin`.

Private to yaml_subset.py: nothing else in tools/ imports this module, and it imports nothing but the standard
library. The behaviour is documented in the docstring of yaml_subset.py.
"""
import re
from typing import NamedTuple

_HEADERS = ("|", "|-", ">", ">-")
_QUOTES = ("'", '"')
_ESCAPES = {"n": "\n", "t": "\t", '"': '"', "\\": "\\"}
_CONTROL = re.compile("[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]")
_NOT_PRINTABLE = re.compile("[\ud800-\udfff\ufffe\uffff]")  # the other code points YAML does not allow in text
_NO_PLAIN_START = ",]}|>%@`"  # a plain key, value or flow entry cannot start with one of these
_LONE_CR = re.compile("\r(?!\n)")
_KEY_END = re.compile(r":(?: |$)")
_COMMENT = "trailing comments are not supported; put '#' on its own line (quote a value that contains '#')"
_INNER_QUOTE = "an inner quote must be escaped: \\\" in double quotes, '' in single quotes"


class _Line(NamedTuple):
    number: int
    indent: int
    text: str  # without surrounding spaces and tabs
    raw: str  # without the line end
    skip: bool  # blank, or a whole-line comment


def _edge(text):
    """The whitespace character at the start or end of `text`, named for a message."""
    char = text[0] if text[0].isspace() else text[-1]
    return f"the whitespace character U+{ord(char):04X}"


def _is_item(text):
    return text == "-" or text.startswith("- ")


def _skip_spaces(text, pos):
    while pos < len(text) and text[pos] == " ":
        pos += 1
    return pos


class _ScanMixin:
    """Quoted scalars, plain-value checks, keys and flow collections, for the reader class that mixes this in.

    The class that mixes this in must provide `fail(line, reason)`, which raises, and nothing else: no other
    attribute of it is used here. Nothing else in tools/ may use this class.
    """

    def _find_key_end(self, text, number):
        """Index of the `:` that ends a mapping key at the start of `text`, or -1."""
        if text[0] in _QUOTES:
            rest = self._scan_quoted(text, 0, number)
            colon = _skip_spaces(text, rest)
            if text[colon:colon + 1] == ":" and text[colon + 1:colon + 2] in ("", " "):
                return colon
            return -1
        found = _KEY_END.search(text)
        return found.start() if found else -1

    def _key_and_value(self, line):
        text = line.text
        end = self._find_key_end(text, line.number)
        if end < 0:
            self.fail(line.number, f"expected 'key: value', was '{text}'")
        return self._key_text(text[:end].strip(" "), line.number), text[end + 1:].strip(" ")

    def _key_text(self, raw, number, dash_space=False):
        """`raw` is the key without its edge spaces; `dash_space` says the key was written with '- ' at the start."""
        if raw[:1] in _QUOTES:
            return self._unquote(raw, number)
        if raw == "":
            self.fail(number, "a mapping key is empty")
        if raw[0].isspace() or raw[-1].isspace():
            self.fail(number, f"a plain key cannot start or end with {_edge(raw)}; quote it")
        if raw[0] in "&*!?":
            self.fail(number, f"anchors, aliases, tags and '?' are not supported on a key ('{raw}'); "
                              "quote the key to use one of those characters")
        if raw[0] in "[{":
            self.fail(number, f"a mapping key cannot start with '{raw[0]}'; quote it")
        if "#" in raw:
            self.fail(number, f"a '#' in a plain key is not supported ('{raw}'): if it starts a comment, put the "
                              "comment on its own line; otherwise quote the key")
        if raw[0] in _NO_PLAIN_START:
            self.fail(number, f"a plain key cannot start with '{raw[0]}'; quote it")
        # only a flow key can get here: a block line that starts with '- ' is an item
        if dash_space:
            self.fail(number, "a plain key cannot start with '- '; quote it")
        if raw == "<<":
            self.fail(number, "the merge key '<<' is not supported; quote it")
        return raw

    def _scalar(self, text, number):
        if text[0] in "[{":
            return self._flow(text, number)
        if text[0] in _QUOTES:
            return self._unquote(text, number)
        self._check_plain(text, number)
        return text

    def _check_plain(self, text, number, flow=False):
        if text[0].isspace() or text[-1].isspace():
            self.fail(number, f"a plain value cannot start or end with {_edge(text)}; quote it")
        if text[0] in "&*!":
            self.fail(number, f"anchors, aliases and tags are not supported ('{text}')")
        if _is_item(text):
            self.fail(number, f"a plain value cannot start with '-' followed by a space ('{text}'); "
                              "a sequence item cannot be written inline")
        if text == "?" or text.startswith("? "):
            self.fail(number, f"a plain value cannot be '?' or start with '? ' ('{text}'); quote it")
        if "#" in text:
            self.fail(number, _COMMENT)
        if ": " in text:
            self.fail(number, "a plain value may not contain ': '")
        if text.endswith(":"):
            self.fail(number, f"a plain value cannot end with ':' ('{text}'); quote it, "
                              "or write 'key: value' for a mapping")
        if text[0] in _NO_PLAIN_START:
            self.fail(number, f"a plain value cannot start with '{text[0]}'; quote it")
        if flow and text[0] == "?":
            self.fail(number, f"a plain value cannot start with '?' in a flow collection ('{text}'); quote it")

    def _scan_quoted(self, text, start, number):
        """Index just past the closing quote of the quoted scalar starting at `start`."""
        quote = text[start]
        i = start + 1
        while i < len(text):
            char = text[i]
            if quote == '"' and char == "\\":
                i += 2
            elif char == quote:
                if quote == "'" and text[i + 1:i + 2] == "'":
                    i += 2
                else:
                    return i + 1
            else:
                i += 1
        self.fail(number, "unterminated quoted scalar")

    def _after_quote(self, number, rest):
        rest = rest.strip(" ")
        if rest.startswith("#"):
            self.fail(number, _COMMENT)
        self.fail(number, f"unexpected text after the closing quote ({rest!r}); " + _INNER_QUOTE)

    def _unquote(self, text, number, flow=False):
        """`text` is exactly one quoted scalar, nothing before or after it."""
        end = self._scan_quoted(text, 0, number)
        if end != len(text):
            self._after_quote(number, text[end:])
        body = text[1:-1]
        if text[0] == "'":
            return body.replace("''", "'")

        def unescape(match):
            char = match.group(1)
            if char not in _ESCAPES:
                self.fail(number, f"unsupported escape '\\{char}'; only \\n, \\t, \\\" and \\\\ are supported")
            if flow and char == '"':
                self.fail(number, "an escaped quote inside a quoted scalar in a flow collection is not "
                                  "supported; write the value on its own line")
            return _ESCAPES[char]

        return re.sub(r"\\(.)", unescape, body, flags=re.S)

    def _flow(self, text, number):
        n = len(text)
        stack = []  # [is_mapping, container, pending key]
        pos = 0
        have = False
        quoted = False  # the value just read was a quoted scalar
        value = None
        while True:
            if not have:
                pos = _skip_spaces(text, pos)
                frame = stack[-1] if stack else None
                if frame is not None:
                    closer = "}" if frame[0] else "]"
                    if pos >= n:
                        self.fail(number, "unterminated flow " + ("mapping" if frame[0] else "sequence"))
                    if text[pos] == "," or text[pos] == closer:
                        self.fail(number, "an empty entry in a flow collection")
                    if text[pos] in "]}":
                        self.fail(number, "unbalanced brackets")
                    if frame[0]:
                        pos, frame[2] = self._flow_key(text, pos, number)
                        pos = _skip_spaces(text, pos)
                        if pos >= n:
                            self.fail(number, "unterminated flow mapping")
                        if text[pos] in ",}":
                            value, have, quoted = None, True, False
                        elif text[pos] == "]":
                            self.fail(number, "unbalanced brackets")
                if not have:
                    char = text[pos]
                    if char in "[{":
                        closer = "}" if char == "{" else "]"
                        stack.append([char == "{", {} if char == "{" else [], None])
                        pos += 1
                        peek = _skip_spaces(text, pos)
                        if peek < n and text[peek] == closer:
                            pos = peek + 1
                            value, have, quoted = stack.pop()[1], True, False
                        else:
                            continue
                    else:
                        quoted = char in _QUOTES
                        pos, value = self._flow_scalar(text, pos, number)
                        have = True
            # a value is complete: attach it, close what it closes, or go on to the next entry
            while True:
                if not stack:
                    rest = text[pos:].strip(" ")
                    if rest.startswith("#"):
                        self.fail(number, _COMMENT)
                    if rest:
                        self.fail(number, "unbalanced brackets" if rest[0] in "]}" else
                                  f"unexpected text after the flow collection ({rest!r})")
                    return value
                frame = stack[-1]
                if frame[0]:
                    if frame[2] in frame[1]:
                        self.fail(number, f"duplicate key '{frame[2]}' in a flow mapping")
                    frame[1][frame[2]] = value
                else:
                    frame[1].append(value)
                closer = "}" if frame[0] else "]"
                pos = _skip_spaces(text, pos)
                if pos >= n:
                    self.fail(number, "unterminated flow " + ("mapping" if frame[0] else "sequence"))
                if text[pos] == ",":
                    pos += 1
                    have = False
                    break
                if text[pos] == closer:
                    pos += 1
                    value = stack.pop()[1]
                    quoted = False
                    continue
                if text[pos] in "]}":
                    self.fail(number, "unbalanced brackets")
                if quoted:
                    self._after_quote(number, text[pos:])
                self.fail(number, f"expected ',' or '{closer}' after a flow entry, was {text[pos]!r}")

    def _flow_key(self, text, pos, number):
        """Read a key inside `{...}`. Returns (position after its colon, key)."""
        n = len(text)
        if text[pos] in _QUOTES:
            end = self._scan_quoted(text, pos, number)
            key = self._unquote(text[pos:end], number, flow=True)
            colon = _skip_spaces(text, end)
            if colon < n and text[colon] == ":" and text[colon + 1:colon + 2] in ("", " ", ",", "}"):
                return colon + 1, key
            if colon >= n or text[colon] in ",}":
                self.fail(number, f"'{text[pos:end]}' is not a key/value pair")
            self._after_quote(number, text[end:colon + 1])
        i = pos
        while i < n:
            char = text[i]
            if char == ":" and text[i + 1:i + 2] in ("", " ", ",", "}"):
                return i + 1, self._key_text(text[pos:i].strip(" "), number, text.startswith("- ", pos))
            if char in ",}":
                self.fail(number, f"'{text[pos:i].strip(' ')}' is not a key/value pair")
            if char in "[]{":
                self.fail(number, f"'{char}' is not allowed in a plain key in a flow mapping; quote it"
                          if char != "]" else "unbalanced brackets")
            i += 1
        self.fail(number, "unterminated flow mapping")

    def _flow_scalar(self, text, pos, number):
        """Read one scalar inside a flow collection. Returns (next position, value)."""
        if text[pos] in _QUOTES:
            end = self._scan_quoted(text, pos, number)
            return end, self._unquote(text[pos:end], number, flow=True)
        i = pos
        while i < len(text) and text[i] not in ",]}":
            if text[i] in "[{":
                self.fail(number, f"'{text[i]}' is not allowed inside a plain scalar in a flow collection; quote it")
            i += 1
        raw = text[pos:i].strip(" ")
        self._check_plain(raw, number, flow=True)
        return i, raw
