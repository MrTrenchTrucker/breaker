"""A small reader for the YAML subset this repository's specs are written in.

The Python tier has no YAML library and adds none (see
decisions/ADR-016-model-registry-codegen.md). Specs and registries are read with
this module, and it holds itself to a narrow subset. Anything outside it is
refused with the file name and the line (the last section names the known
exceptions), so a file rewritten in a style this reader does not understand
fails loudly instead of a check quietly reading less than it used to.

    import yaml_subset
    spec = yaml_subset.load("shared/modules/model-registry/models.yaml")
    other = yaml_subset.loads("name: value\\n", name="inline")

`load(path)` reads a file as strict UTF-8, `loads(text, name)` reads text. Both
return a dict (the document is always a mapping) holding dicts, lists, strings
and None, and nothing else. Python 3.11 or later, standard library only.
`tools/` must be on `sys.path`, as for any script in tools/; `_yaml_subset_scan.py` is the reader's second file.

Supported
    * block mappings and sequences, indented with spaces; a sequence may sit at
      its key's own indent
    * flow sequences and mappings on one line, nested to any depth
    * plain scalars (an ASCII space at either end is trimmed, nothing else),
      'single quoted' ('' is a quote) and "double quoted"
      (escapes: \\n \\t \\" \\\\ only)
    * block scalars with the headers |  |-  >  >-  (real YAML semantics: the first
      line that has text sets the indent; blank lines and '#' lines inside are
      text; | keeps line breaks, > folds; the last line break is kept if a line
      end follows the last text line, and - drops it; a line of spaces is text
      only for the spaces past the indent)
    * whole-line comments and blank lines, LF or CRLF line ends

Types
    Every plain scalar is a string: `true`, `12`, `0.0`, `null`, `~` and `no` are
    the strings "true", "12", "0.0", "null", "~" and "no". A quoted scalar is a
    string. The only None is an empty value (`key:` with nothing after it, or
    `{key: }`). Callers convert types themselves and refuse what they cannot.

Refused, each as `<name>:<line>: <reason>`
    Documents: invalid UTF-8 (in `load`); a BOM at the start of the text (U+FEFF
    anywhere else is ordinary text); a control character (C0 except tab, LF and
    CR; DEL; the C1 range, U+0085 included, in a comment or a quoted scalar too);
    U+FFFE, U+FFFF and a lone surrogate (not printable YAML characters); a
    carriage return that is not part of CRLF; no content (empty, or only
    comments and blank lines); document markers: `---` or `...`
    alone on a line, at any indent (also inside a block scalar); `---` or `...`
    at column 0 followed by a space, from line two on; on line one, `...`
    followed by a space (also refused as a document marker), and any other text
    starting with `---` (refused as multi-document YAML); a document that is
    not a mapping; block nesting deeper than the reader follows (about 490
    nested mappings at Python's default recursion limit, about 330 levels of
    key plus sequence item; flow nesting has no limit of its own). `---x: v`
    from line two on and `...x: v` on any line are ordinary keys, and as a
    value `---x` and `...x` are plain text; a line that is only `---x` or
    `...x` is refused as not `key: value` (on line one, `---x` is refused as
    multi-document YAML).
    Structure: a tab in a content line, or on any line of a block scalar,
    including its trailing blank lines; indentation that matches no enclosing
    level; a value that continues on the next line (multi-line plain or quoted
    scalars); a line that is not `key: value`; an empty plain key (`: x`,
    `{: x}`; a quoted empty key `"": x` is read); duplicate keys, block or
    flow; a sequence item where a key is expected; a bare `-`; `- - x`,
    `key: - x` and `- |`; a lone `-` as a value (`key: -`); in a flow
    collection, a lone `-` or `- ` plus text as an entry or a mapping value
    (`[-]`, `[- x]`, `[a, - x]`, `{key: -}`, `{key: - x}`).
    Keys: anchors, aliases, tags and `?` on a key; a key that starts with `[` or
    `{`; `[` or `{` inside a plain flow key; a `#` in a plain key; a plain key
    that starts with `,` `]` `}` `|` `>` `%` `@` or a backtick, or (in a flow
    mapping) with `- `; the plain key `<<` (the merge key; quote it to use it).
    Plain values: anchors, aliases and tags; `?` alone or followed by a space; a
    `#` anywhere in a plain value, item or flow entry (`b#c` and a URL fragment
    included), and a comment after a quoted value or a flow collection (put `#`
    on its own line, or quote the value); a `: ` inside the value or a `:` at
    its end; `[` or `{` inside a plain scalar in a flow collection; whitespace
    other than an ASCII space at either edge of a plain key or value (quote
    it; whitespace is what `str.isspace` counts, so U+200B, U+2060, U+180E and
    U+FEFF there are text); a plain value, sequence item or flow entry that
    starts with `,` `]` `}` `|` `>` `%` `@` or a backtick (`|` and `>` at the
    start of a block value or item are refused as block scalars, and `,` `]`
    and `}` at the start of a flow entry as flow syntax); a plain flow entry or
    flow mapping value that starts with `?`.
    Quoted scalars: an unterminated one, including one whose last quote is
    escaped; text after the closing quote, such as an inner quote that is not
    escaped; an escape other than \\n \\t \\" \\\\; a \\" escape inside a
    double-quoted scalar in a flow collection (`''` inside single quotes is
    fine there).
    Block scalars: a header other than the four; no content; a line indented
    less than the block's first text line; a blank line before the first text
    line with more spaces than the text; a more-indented line in a folded
    scalar, a spaces-only line with more spaces than the indent included, in
    the middle or after the last text line (a `|` scalar keeps that line).
    Flow collections: an empty entry; an unterminated or unbalanced one; text
    after it; a missing separator between entries; a mapping entry that is not
    `key: value`.

Known limits and choices
    `load(path)` with a path that cannot be opened (a NUL byte, a lone
    surrogate, a missing file) raises the OSError or ValueError that `open`
    raises, not YamlSubsetError, and raises TypeError for a path that is not a
    str, bytes or path object. `loads` raises TypeError for text that is not a
    str. When the caller's own stack is within about a dozen frames of the
    recursion limit, `loads` can raise RecursionError, or refuse a document that
    is not deep with "the document nests too deeply". A plain key of more than
    1024 characters is read. A few refusals carry a hint that does not fit every
    case: line-one `---x` is refused as multi-document YAML, a bare indented
    `---` line inside a block scalar is refused as a document marker, and a
    quoted flow key (single or double quotes) followed by a colon with no space,
    as in `{"a":1}`, is refused with the inner-quote hint, as are `["a": 1]`,
    `{k: "a": 1}`, `{k: "a":1}` and `{"a"]: 1}`. The reader follows
    YAML 1.2 where YAML 1.1 differs: U+2028 and U+2029 are ordinary text inside
    a plain scalar, a key, a comment, a quoted scalar or a block scalar (at the
    edge of a plain key or value they are refused like other non-ASCII
    whitespace, and a line holding only one is content, not blank); a tab-only
    line between entries is blank (inside a block scalar, after its last text
    line too, it is refused); a tab before a whole-line comment is read, and
    the comment ignored (also right after a block scalar's last text line);
    and, inside a flow collection, a `:` that has no space after it and is not
    at the end of the entry, and a `?` that does not start the entry, are text
    (`[a:b]`, `{k: a:b}`, `[x?y]`; `[a: b]` and `[a:]` are refused, and `{a:b}`
    is not a key/value pair).
    On line one, an indented `--- foo` is refused as not `key: value`; the column-0 marker
    checks do not see it.
"""
import os

from _yaml_subset_scan import (_CONTROL, _HEADERS, _LONE_CR, _NOT_PRINTABLE, _Line, _ScanMixin,
                               _is_item)

__all__ = ["YamlSubsetError", "load", "loads"]


class YamlSubsetError(ValueError):
    """The text is outside the supported subset. `str(err)` is `name:line: reason`."""

    def __init__(self, name, line, reason):
        super().__init__(f"{name}:{line}: {reason}")
        self.name = name
        self.line = line
        self.reason = reason

    def __reduce__(self):
        """Copy and pickle rebuild the error from its three parts, not from the one formatted message."""
        return type(self), (self.name, self.line, self.reason)


def load(path):
    """Read the file at `path` (strict UTF-8). Messages name `path` as text (`os.fsdecode(path)`)."""
    name = os.fsdecode(path)
    with open(path, "rb") as handle:
        data = handle.read()
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as err:
        line = data.count(b"\n", 0, err.start) + 1
        raise YamlSubsetError(name, line, "the file is not valid UTF-8") from None
    return loads(text, name)


def loads(text, name="<string>"):
    """Read YAML text; TypeError if `text` is not a str. `name` is what refusal messages call the source."""
    if not isinstance(text, str):
        raise TypeError("loads() takes text; use load() to read a file")
    reader = _Reader(text, name)
    try:
        return reader.document()
    except RecursionError:
        raise YamlSubsetError(name, reader.at, "the document nests too deeply for this reader") from None


class _Reader(_ScanMixin):
    def __init__(self, text, name):
        self.name = name
        self.at = 1  # the line being read, for a refusal that has no better one
        self.lines = self._split(text)

    def fail(self, line, reason):
        raise YamlSubsetError(self.name, line, reason)

    def _split(self, text):
        if text.startswith("\ufeff"):
            self.fail(1, "a byte-order mark is not supported; save the file as plain UTF-8")
        bad = _CONTROL.search(text)
        if bad:
            self.fail(text.count("\n", 0, bad.start()) + 1,
                      f"the control character U+{ord(bad.group()):04X} is not supported")
        odd = _NOT_PRINTABLE.search(text)
        if odd:
            self.fail(text.count("\n", 0, odd.start()) + 1,
                      f"the character U+{ord(odd.group()):04X} is not a printable YAML character")
        lone = _LONE_CR.search(text)
        if lone:
            self.fail(text.count("\n", 0, lone.start()) + 1,
                      "a carriage return outside a CRLF line end is not supported")
        lines = []
        for number, raw in enumerate(text.split("\n"), 1):
            raw = raw.removesuffix("\r")
            stripped = raw.strip(" \t")
            skip = stripped == "" or stripped.startswith("#")
            if not skip:
                if "\t" in raw:
                    self.fail(number, "a tab is not supported in a content line; indent and separate with spaces")
                # a marker at column 0 with text after it: `---` from line two on, `...` from line one
                # (a first line that starts with `---` gets its own refusal, as multi-document YAML)
                marker_then_text = raw[:3] in ("---", "...") and raw[3:4] == " " and (number > 1 or raw[0] == ".")
                if stripped in ("---", "...") or marker_then_text:
                    self.fail(number, "document markers are not supported")
            lines.append(_Line(number, len(raw) - len(raw.lstrip(" ")), stripped, raw, skip))
        return lines

    def _next(self, i):
        while i < len(self.lines) and self.lines[i].skip:
            i += 1
        return i

    def document(self):
        first = self._next(0)
        if first >= len(self.lines):
            self.fail(1, "the document is empty")
        if self.lines[0].raw.startswith("---"):
            self.fail(1, "multi-document YAML is not supported")
        line = self.lines[first]
        if _is_item(line.text):
            self.fail(line.number, "the document is not a mapping (it starts with a sequence item)")
        result, end = self._mapping(first, line.indent)
        stray = self._next(end)
        if stray < len(self.lines):
            self.fail(self.lines[stray].number, "the indentation does not match any enclosing level")
        return result

    def _deeper(self, line, last):
        if last == "inline":
            reason = "a value cannot continue on the next line; multi-line plain and quoted scalars are not supported"
        elif last == "block":
            reason = "a block scalar line is indented less than the block's first line"
        else:
            reason = "the indentation does not match any enclosing level"
        self.fail(line.number, reason)

    def _mapping(self, i, indent):
        result = {}
        first_seen = {}
        last = None
        while True:
            i = self._next(i)
            if i >= len(self.lines):
                break
            line = self.lines[i]
            self.at = line.number
            if line.indent < indent:
                break
            if line.indent > indent:
                self._deeper(line, last)
            if _is_item(line.text):
                self.fail(line.number, "a sequence item cannot sit where a mapping key is expected")
            key, inline = self._key_and_value(line)
            if key in result:
                self.fail(line.number, f"duplicate key '{key}' (first on line {first_seen[key]})")
            first_seen[key] = line.number
            result[key], i, last = self._value(i + 1, indent, inline, line)
        return result, i

    def _sequence(self, i, indent):
        result = []
        last = None
        while True:
            i = self._next(i)
            if i >= len(self.lines):
                break
            line = self.lines[i]
            self.at = line.number
            if line.indent < indent:
                break
            if line.indent > indent:
                self._deeper(line, last)
            if not _is_item(line.text):
                break
            if line.text == "-":
                self.fail(line.number, "an empty sequence item is not supported")
            after = line.text[1:]
            content = after.lstrip(" ")
            column = line.indent + 1 + len(after) - len(content)
            if _is_item(content):
                self.fail(line.number, "a sequence item cannot start with another sequence item; use a mapping")
            if content[0] in "|>":
                self.fail(line.number, "a block scalar cannot be a sequence item; put it under a key")
            if content[0] not in "[{" and self._find_key_end(content, line.number) >= 0:
                # "- key: value" opens a mapping; its other keys sit at the column of `key`
                self.lines[i] = line._replace(indent=column, text=content)
                item, i = self._mapping(i, column)
                last = "child"
            else:
                item = self._scalar(content, line.number)
                i += 1
                last = "inline"
            result.append(item)
        return result, i

    def _value(self, i, key_indent, inline, line):
        """The value of one key. Returns (value, next line index, how it ended)."""
        if inline == "":
            nxt = self._next(i)
            if nxt < len(self.lines):
                following = self.lines[nxt]
                if following.indent > key_indent:
                    parse = self._sequence if _is_item(following.text) else self._mapping
                    value, end = parse(nxt, following.indent)
                    return value, end, "child"
                if following.indent == key_indent and _is_item(following.text):
                    value, end = self._sequence(nxt, key_indent)
                    return value, end, "child"
            return None, i, "inline"
        if inline in _HEADERS:
            value, end = self._block(i, key_indent, inline, line.number)
            return value, end, "block"
        if inline[0] in "|>":
            self.fail(line.number, f"'{inline}' is not a supported block scalar header; only |, |-, > and >- are "
                                   "(quote the value to keep it as text)")
        return self._scalar(inline, line.number), i, "inline"

    def _block(self, i, key_indent, header, number):
        rows = []  # (line number, raw line, blank?)
        content = None
        while i < len(self.lines):
            line = self.lines[i]
            if line.text == "":  # blank: empty, or only spaces and tabs
                if "\t" in line.raw:
                    self.fail(line.number, "a tab is not supported in a block scalar; use spaces")
                rows.append((line.number, line.raw, True))
                i += 1
                continue
            if line.indent <= key_indent:
                break
            if content is None:
                content = line.indent
            elif line.indent < content:
                break
            if "\t" in line.raw:
                self.fail(line.number, "a tab is not supported in a block scalar; use spaces")
            rows.append((line.number, line.raw, False))
            i += 1
        if content is None:
            self.fail(number, "a block scalar has no content")
        first_text = next(k for k, row in enumerate(rows) if not row[2])
        cut = []  # (line number, text): a spaces-only line keeps the spaces past the indent, as YAML reads it
        for k, (at, raw, blank) in enumerate(rows):
            text = raw[content:]
            if blank and k < first_text and text:
                self.fail(at, "a blank line before the first text line of a block scalar has more spaces than "
                              "the text is indented by")
            cut.append((at, text))
        while cut and cut[-1][1] == "":
            cut.pop()
        if header.startswith(">"):
            for at, text in cut:
                if text.startswith(" "):
                    self.fail(at, "a more-indented line inside a folded block scalar is not supported")
            body = self._fold([text for _, text in cut])
        else:
            body = "\n".join(text for _, text in cut)
        unterminated = cut[-1][0] == len(self.lines)  # the last text line is the last line, with no line end after it
        return (body if header.endswith("-") or unterminated else body + "\n"), i

    @staticmethod
    def _fold(items):
        out = ""
        breaks = 0
        for text in items:
            if text == "":
                breaks += 1
                continue
            out += (("\n" * breaks or " ") if out else "\n" * breaks) + text
            breaks = 0
        return out
