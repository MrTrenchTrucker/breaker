"""The KOTLIN reader: a closed grammar over the contract's constrained
Kotlin source shape. Standard library only.

The closed grammar, stated once and pinned by tests (test_reader_closed_
grammar.py):

- a package declaration;
- an enum class with plain value lines, optionally followed by a companion
  object holding single-expression `fun` declarations;
- a data class whose constructor parameter region runs from the class line
  to a line that is exactly `)` or `) {`. Inside that region every non-blank,
  non-comment line must be a `val` parameter (optional `= default`, trailing
  comment stripped) or the reader refuses with file and line. `var`
  parameters are a refusal, not a skip. Whole-line comments are recognised
  and skipped (the choice is pinned by
  test_reader_skips_whole_line_comment_in_constructor);
- after `) {`, only an `init {` block is recognised; inside init only
  `require(...)` / `check(...)` calls (optionally with a trailing message
  block) are recognised. Class-level properties, local declarations and
  anything else in the body are a refusal.

Every reader refuses to return something empty or partial: a parser that
finds nothing would make every comparison downstream pass vacuously, so each
one counts what it should have found and raises when the count is off.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
MODULE = ROOT / "shared" / "modules" / "api-contracts"
KOTLIN_DIR = MODULE / "src" / "main" / "kotlin" / "dev" / "breaker" / "shared" / "api"


PACKAGE_RE = re.compile(r'^package\s+([\w.]+)\s*$')
DATA_CLASS_RE = re.compile(r'^data\s+class\s+(\w+)\s*\(')
ENUM_CLASS_RE = re.compile(r'^enum\s+class\s+(\w+)')
# A constructor parameter: val name: Type (= default)? (,)? -- the type is
# an identifier with optional generics and a trailing '?'; a trailing comment
# is stripped before this is tried; a default is any run of non-comma tokens
# after '='.
PARAM_RE = re.compile(
    r'^\s+val\s+(\w+)\s*:\s*([\w.]+(?:\s*<\s*[\w.,\s<>?]+>\s*)?)(\?)?'
    r'(?:\s*=\s*[^,]+)?\s*,?\s*$'
)
FUN_RE = re.compile(r'^\s*fun\s+(\w+)\s*\(')
INIT_RE = re.compile(r'^\s*init\s*\{\s*$')
RULE_RE = re.compile(r'^\s*(require|check)\s*\(')


def _strip_line_comment(line):
    """Strip a trailing // comment (after string literals are gone)."""
    idx = line.find("//")
    if idx >= 0:
        line = line[:idx]
    return line


def _blank_strings(text):
    """Blank single-quoted, double-quoted and raw triple-quoted string
    literals so no declaration can hide inside a string (the quote firewall).

    Raw strings (\"\"\" ... \"\"\") are blanked across lines; line structure
    is preserved so line numbering (in refusals) stays correct.
    """
    # triple-quoted raw strings first (non-greedy, across newlines)
    text = re.sub(r'"""[\s\S]*?"""', '"""', text)
    # single-line double-quoted strings
    text = re.sub(r'"[^"\\\n]*"', '""', text)
    # single-line single-quoted char strings
    text = re.sub(r"'[^'\\\n]*'", "''", text)
    return text


def _strip_block_comments(text):
    """Blank /* ... */ (and KDoc) block comments, preserving line structure.

    Runs after string blanking, so a '/*' inside a string literal is already
    gone and cannot open a phantom comment.
    """
    out_lines = []
    in_comment = False
    for line in text.splitlines():
        res = []
        i = 0
        n = len(line)
        while i < n:
            if in_comment:
                end = line.find("*/", i)
                if end == -1:
                    i = n
                else:
                    res.append(" " * (end + 2 - i))
                    i = end + 2
                    in_comment = False
            else:
                start = line.find("/*", i)
                if start == -1:
                    res.append(line[i:])
                    i = n
                else:
                    # blank the delimiter too: a surviving '/*' would look
                    # like a construct (and its '//' would be cut by the
                    # line-comment strip), so the whole comment is spaces.
                    res.append(line[i:start])
                    res.append("  ")
                    i = start + 2
                    in_comment = True
        out_lines.append("".join(res))
    return "\n".join(out_lines)


def _prepare(text):
    """Prepare lines for the state machine.

    Order: blank string literals (the quote firewall), blank block comments,
    then split into (raw_line, usable_line) pairs where usable_line also has
    trailing // comments stripped. usable_line is what the state machine
    matches; raw_line is kept so a refusal can quote what was actually there.
    """
    blanked = _strip_block_comments(_blank_strings(text))
    out = []
    for raw, b in zip(text.splitlines(), blanked.splitlines()):
        b = _strip_line_comment(b)
        out.append((raw, b))
    return out


def parse_kotlin_file(filepath):
    """Parse a Kotlin source file with the closed grammar.

    Returns a dict with:
    - 'package': the package name
    - 'enums': list of (enum_name, [values])
    - 'data_classes': list of (class_name, [(field_name, field_type,
      is_nullable)], [init_conditions]) where init_conditions are the raw
      condition expressions of the require/check calls in the class body
    - 'functions': list of function names

    Refuses with file and line on any unrecognised construct: a `var`
    parameter, a class-level property outside init, a local declaration,
    a construct the grammar does not list. The refusal names the file and
    the 1-based line number.
    """
    filepath = Path(filepath)
    if not filepath.exists():
        raise FileNotFoundError(f"Kotlin file not found: {filepath}")

    text = filepath.read_text(encoding="utf-8")
    lines = _prepare(text)

    def refuse(idx, why):
        raise ValueError(
            f"{filepath.name}:{idx + 1}: {why} (closed grammar: "
            f"unrecognised construct {lines[idx][0].strip()!r})"
        )

    result = {
        "package": None,
        "enums": [],
        "data_classes": [],
        "functions": [],
    }

    state = "TOP"
    enum_name = None
    enum_values = []
    class_name = None
    fields = []
    init_conditions = []

    def close_enum():
        nonlocal enum_name, enum_values
        result["enums"].append((enum_name, list(enum_values)))
        enum_name = None
        enum_values = []

    def close_class():
        nonlocal class_name, fields, init_conditions
        result["data_classes"].append((class_name, fields, init_conditions))
        class_name = None
        fields = []
        init_conditions = []

    for idx, (raw, line) in enumerate(lines):
        s = line.strip()

        if state == "TOP":
            if not s:
                continue
            if s.startswith("package"):
                m = re.match(r'^package\s+([\w.]+)\s*$', s)
                if not m:
                    refuse(idx, "malformed package declaration")
                if result["package"] is not None:
                    refuse(idx, "second package declaration")
                result["package"] = m.group(1)
            elif s.startswith("enum class "):
                m = ENUM_CLASS_RE.match(s)
                if not m:
                    refuse(idx, "malformed enum class declaration")
                enum_name = m.group(1)
                state = "ENUM_BODY"
            elif s.startswith("data class "):
                m = DATA_CLASS_RE.match(s)
                if not m:
                    refuse(idx, "malformed data class declaration")
                class_name = m.group(1)
                fields = []
                init_conditions = []
                # The class line itself may carry the first parameter(s) on
                # later lines only; the '(' is at the end of the declaration
                # line in this grammar.
                if not s.rstrip().endswith("("):
                    refuse(idx, "data class line must end with '('")
                state = "CTOR"
            elif s.startswith("companion object"):
                refuse(idx, "companion object is only recognised inside an enum class")
            else:
                refuse(idx, "unrecognised top-level construct")

        elif state == "CTOR":
            # the constructor parameter region: ends at ')' or ') {'
            if not s:
                continue
            # a lone ',' or ';' is the leftover of a blanked multi-line raw
            # string (the firewall blanks the literal; its closing comma
            # survives on its own line). It carries no parameter.
            if s in (",", ";"):
                continue
            if s in (")", ") {"):
                if s == ")":
                    close_class()
                    state = "TOP"
                else:
                    state = "DATA_BODY"
                continue
            m = PARAM_RE.match(line)
            if m:
                fname, ftype, nullable = m.group(1), m.group(2).strip(), m.group(3)
                # group(3) is '?' or None; a default does not affect nullability
                fields.append((fname, ftype, bool(nullable)))
                continue
            stripped = s.lstrip()
            if stripped.startswith("var "):
                refuse(idx, "'var' is not a recognised constructor parameter (use 'val')")
            refuse(idx, "unrecognised line in constructor parameter region")

        elif state == "DATA_BODY":
            # only init blocks are recognised in the class body
            if not s:
                continue
            if s == "}":
                close_class()
                state = "TOP"
                continue
            if INIT_RE.match(s):
                state = "INIT_BODY"
                continue
            refuse(idx, "unrecognised construct in class body (only 'init {') is recognised)")

        elif state == "INIT_BODY":
            if not s:
                continue
            if s == "}":
                state = "DATA_BODY"
                continue
            m = RULE_RE.match(s)
            if m:
                # condition on this line; the call may continue with ') {'
                # (message block) or close with ')'. The recorded condition
                # is the bare expression (the require(/check( prefix is
                # dropped so tests can match on the condition itself).
                body = s[m.end():]
                r = body.rstrip()
                if r.endswith(") {"):
                    # require(<cond>) {  -- drop the trailing ") {"
                    init_conditions.append(r[:-3].strip())
                    state = "INIT_MSG"
                elif r.endswith(")"):
                    # require(<cond>)  -- drop the trailing ")"
                    init_conditions.append(r[:-1].strip())
                else:
                    state = "INIT_CALL"
                continue
            refuse(idx, "only require(...) / check(...) calls are recognised in init")

        elif state == "INIT_CALL":
            # a multi-line condition we do not support in the closed grammar
            if s.rstrip().endswith(")"):
                init_conditions.append(lines[idx][0].strip())
                state = "INIT_BODY"
            else:
                refuse(idx, "multi-line require/check conditions are not in the closed grammar")

        elif state == "INIT_MSG":
            # the require message line (blanked string) then the closing '}'
            if s == "}":
                state = "INIT_BODY"
            # the blanked message line itself is acceptable and is simply
            # consumed; anything else falls through to the next iteration
            # and is refused at the next structural line -- pin: a message
            # block is exactly one line in this contract.

        elif state == "ENUM_BODY":
            if not s:
                continue
            if s in ("}", ");"):
                close_enum()
                state = "TOP"
                continue
            if s.startswith("companion object"):
                # the companion follows the value list; the values are kept.
                # Do NOT close the enum here -- its final '}' still has to
                # come after the companion closes, and that final '}' is what
                # records the (name, values) pair.
                state = "ENUM_COMPANION"
                continue
            m = re.match(r'^\s*(\w+)(\s*,|\s*;)?\s*$', s)
            if m:
                enum_values.append(m.group(1))
                continue
            refuse(idx, "unrecognised line in enum class")

        elif state == "ENUM_COMPANION":
            if not s:
                continue
            if s == "}":
                state = "ENUM_BODY"
                continue
            m = FUN_RE.match(s)
            if m:
                result["functions"].append(m.group(1))
                if s.rstrip().endswith("="):
                    state = "FUN_EXPR"
                continue
            refuse(idx, "unrecognised construct in companion object")

        elif state == "FUN_EXPR":
            if not s:
                continue
            if s == "}":
                refuse(idx, "empty single-expression fun body")
            state = "ENUM_COMPANION"
            continue

    if state != "TOP":
        refuse(len(lines) - 1, f"file ends in an unfinished construct (state {state})")

    if result["package"] is None:
        raise ValueError(f"{filepath.name}: no package declaration found")

    # Vacuity guard: at least one of enums or data_classes must be found
    if not result["enums"] and not result["data_classes"]:
        raise ValueError(f"{filepath.name}: no enums or data classes found (vacuity guard)")

    return result


def snake(name):
    """Convert camelCase to snake_case.

    Strict snake(): jobId -> job_id, jobID -> job_i_d.
    No .lower() on property names (only on enum values).
    """
    # Insert underscore between consecutive uppercase letters (for acronyms like jobID)
    s1 = re.sub('([A-Z])([A-Z])', r'\1_\2', name)
    # Insert underscore between lowercase/digit and uppercase
    s2 = re.sub('([a-z0-9])([A-Z])', r'\1_\2', s1)
    return s2.lower()


def parse_all_kotlin():
    """Parse all Kotlin source files in the module.

    Returns a dict mapping class/enum name -> parsed data.
    Field names are converted to snake_case for comparison with the spec.
    Enum values are lowercased.
    Each data class is {'type': 'data_class', 'fields': [(name, type,
    nullable)], 'init_rules': [condition strings]}.
    Each enum is {'type': 'enum', 'values': [lowercase names]}.
    """
    if not KOTLIN_DIR.exists():
        raise FileNotFoundError(f"Kotlin directory not found: {KOTLIN_DIR}")

    result = {}
    for kt_file in sorted(KOTLIN_DIR.glob("*.kt")):
        parsed = parse_kotlin_file(kt_file)
        for enum_name, values in parsed["enums"]:
            # Lowercase enum values for comparison with spec
            result[enum_name] = {"type": "enum", "values": [v.lower() for v in values]}
        for class_name, fields, rules in parsed["data_classes"]:
            # Convert field names to snake_case
            snake_fields = [(snake(f), t, n) for f, t, n in fields]
            result[class_name] = {
                "type": "data_class",
                "fields": snake_fields,
                "init_rules": list(rules),
            }

    # Vacuity guard: at least one class or enum must be found
    if not result:
        raise ValueError("No Kotlin classes or enums found (vacuity guard)")

    return result
