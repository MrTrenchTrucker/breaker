"""Shared readers for the ui-tokens unit tests. Standard library only.

Three independent readers, none of which imports another:

* the CARD reader parses the palette and state tables, the typography bullets and
  the numbers in the prose out of the module card (`AGENTS.md`), which is the
  specification;
* the CSS reader parses `tokens.css` strictly: exactly three rules, every
  statement a custom property;
* the KOTLIN reader parses `tokens.kt`, emulating how the Kotlin compiler turns
  `0xFF1E7A46.toInt()` into a signed Int.

Every reader refuses to return something empty or partial: a parser that finds
nothing would make every comparison downstream pass vacuously, so each one
counts what it should have found and raises when the count is off.

Module card: shared/modules/ui-tokens/AGENTS.md
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
MODULE = ROOT / "shared" / "modules" / "ui-tokens"
CARD = MODULE / "AGENTS.md"
KOTLIN = MODULE / "src" / "main" / "kotlin" / "dev" / "breaker" / "shared" / "tokens" / "tokens.kt"
CSS = MODULE / "src" / "main" / "resources" / "tokens.css"

CARD_POINTER = "shared/modules/ui-tokens/AGENTS.md"

PALETTE_TOKENS = [
    "bg", "surface", "text", "text-muted", "primary",
    "primary-hover", "accent", "danger", "trim",
]
STATE_TOKENS = ["sent", "warning", "danger"]
# Every color a mode carries: the nine palette tokens plus the two state colors
# the palette table does not already give (danger is in both tables).
COLOR_TOKENS = PALETTE_TOKENS + ["sent", "warning"]

EXPECTED_CSS_COLOR_VARS = {"color-" + t for t in COLOR_TOKENS}
EXPECTED_CSS_OTHER_VARS = {
    "font-display", "font-body", "font-mono",
    "radius", "touch-target-min", "breakpoint-mobile", "breakpoint-tablet",
    "led-segments-min", "led-segments-max",
}
EXPECTED_CSS_VARS = EXPECTED_CSS_COLOR_VARS | EXPECTED_CSS_OTHER_VARS

EXPECTED_KOTLIN_PALETTE_FIELDS = {
    "bg", "surface", "text", "textMuted", "primary", "primaryHover",
    "accent", "danger", "trim", "sent", "warning",
}
EXPECTED_KOTLIN_METRICS_FIELDS = {
    "cornerRadiusDp", "minTouchTargetDp", "mobileBreakpointDp", "tabletBreakpointDp",
}
EXPECTED_KOTLIN_TYPE_FIELDS = {
    "displayFamily", "displayAlternateFamily", "bodyFamily", "monoFamily",
}
EXPECTED_KOTLIN_LED_FIELDS = {"minSegments", "maxSegments"}

# Every class, enum, object and interface tokens.kt declares, and every val, var and
# fun name in it. A declaration outside these sets is a value or a type the card
# does not give.
EXPECTED_KOTLIN_TYPES = {
    "TokenColor", "ThemeMode", "TruckingPalette", "TruckingType",
    "LayoutBand", "TruckingMetrics", "TruckingLedBar", "TruckingTokens",
}
EXPECTED_KOTLIN_NAMES = (
    {"argb", "hex", "toString", "bandFor", "segmentRange", "palette", "toggled",
     "LIGHT", "DARK", "type", "metrics", "ledBar"}
    | EXPECTED_KOTLIN_PALETTE_FIELDS
    | EXPECTED_KOTLIN_METRICS_FIELDS
    | EXPECTED_KOTLIN_TYPE_FIELDS
    | EXPECTED_KOTLIN_LED_FIELDS
)

# The generic keyword CSS appends to a font stack; Kotlin carries family names only.
GENERIC_FONT_KEYWORDS = {"sans-serif", "serif", "monospace"}

# The card's Typography bullets, by their bold label.
TYPOGRAPHY_LABELS = ("Display/headings", "Body/UI", "Mono")


def read_text(path):
    path = Path(path)
    if not path.is_file():
        raise AssertionError(
            f"ui-tokens: {path} does not exist. These tests compare the module's "
            f"files against the card ({CARD_POINTER}) and must not pass vacuously."
        )
    return path.read_text(encoding="utf-8")


def kebab(camel):
    return re.sub(r"([A-Z])", lambda m: "-" + m.group(1).lower(), camel)


def names_word(text, word):
    """True when `word` appears in `text` as a whole name, not inside a longer one
    (so a truncated family such as 'Inte' is not found in 'Inter')."""
    return re.search(r"(?<![A-Za-z])" + re.escape(word) + r"(?![A-Za-z])", text) is not None


# ── the card ────────────────────────────────────────────────────────────────
def _rows_after(lines, index):
    """The cells of each row of the markdown table that follows lines[index]."""
    rows = []
    started = False
    for line in lines[index + 1:]:
        stripped = line.strip()
        if stripped.startswith("|"):
            started = True
            rows.append([c.strip() for c in stripped.strip("|").split("|")])
        elif started:
            break
        elif stripped == "":
            continue
        else:
            break
    return rows


def _data_rows(rows):
    """Drop the header row and the `---` separator row."""
    out = []
    for cells in rows:
        if cells and cells[0].strip("` ").lower() == "token":
            continue
        if all(re.fullmatch(r":?-+:?", c) for c in cells if c):
            continue
        out.append(cells)
    return out


def _find_line(lines, matcher, what):
    """The index of the one line that matches. No match is refused, and so is more
    than one: a second table under the same caption would never be read."""
    found = [i for i, line in enumerate(lines) if matcher(line.strip())]
    if not found:
        raise AssertionError(
            f"ui-tokens: the card has no {what}. The tests locate the tables by that "
            f"caption; the card and the tests have drifted apart ({CARD_POINTER})."
        )
    if len(found) > 1:
        raise AssertionError(
            f"ui-tokens: the card has the {what} on {len(found)} lines "
            f"({', '.join(str(i + 1) for i in found)}). The tests read the first table only, "
            f"so a second one under the same caption would pass unread; the card and the "
            f"tests have drifted apart ({CARD_POINTER})."
        )
    return found[0]


def _unique(pairs, what):
    """dict(pairs), refusing a name that appears twice (the second row would
    silently replace the first)."""
    names = [name for name, _ in pairs]
    repeated = sorted({n for n in names if names.count(n) > 1})
    if repeated:
        raise AssertionError(f"ui-tokens: the card's {what} names {repeated} more than once ({CARD_POINTER})")
    return dict(pairs)


def parse_card(text):
    """Return {"light": {token: hex}, "dark": {token: hex}, "state": {token: (light, dark)}}."""
    lines = text.splitlines()

    def palette(caption):
        i = _find_line(lines, lambda s: s == caption, f"'{caption}' caption")
        rows = _data_rows(_rows_after(lines, i))
        out = _unique([(cells[0].strip("` "), cells[1].strip("` ")) for cells in rows], f"'{caption}' table")
        if set(out) != set(PALETTE_TOKENS):
            raise AssertionError(
                f"ui-tokens: the card's '{caption}' table lists {sorted(out)}, the "
                f"tests expect {sorted(PALETTE_TOKENS)} ({CARD_POINTER})"
            )
        return out

    light = palette("**Light mode**")
    dark = palette("**Dark mode**")

    i = _find_line(lines, lambda s: s.startswith("## State colors"), "'## State colors' heading")
    pairs = []
    for cells in _data_rows(_rows_after(lines, i)):
        match = re.search(r"`([a-z-]+)`", cells[0])
        if not match:
            raise AssertionError(f"ui-tokens: unreadable state row {cells} ({CARD_POINTER})")
        pairs.append((match.group(1), (cells[1].strip("` "), cells[2].strip("` "))))
    state = _unique(pairs, "state table")
    if set(state) != set(STATE_TOKENS):
        raise AssertionError(
            f"ui-tokens: the card's state table lists {sorted(state)}, the tests "
            f"expect {sorted(STATE_TOKENS)} ({CARD_POINTER})"
        )
    for table in (light, dark):
        for token, value in table.items():
            if not re.fullmatch(r"#[0-9A-Fa-f]{6}", value):
                raise AssertionError(f"ui-tokens: card value for '{token}' is not #RRGGBB: {value!r}")
    return {"light": light, "dark": dark, "state": state}


def read_card():
    return parse_card(read_text(CARD))


def card_typography(text):
    """{label: bullet text} for the bullets of the card's `## Typography` section."""
    lines = text.splitlines()
    start = _find_line(lines, lambda s: s == "## Typography", "'## Typography' section")
    bullets = []
    for line in lines[start + 1:]:
        if line.startswith("## "):
            break
        if line.startswith("- "):
            bullets.append(line)
        elif line[:1] in (" ", "\t") and bullets:
            bullets[-1] += " " + line.strip()
    out = {}
    for label in TYPOGRAPHY_LABELS:
        found = [b for b in bullets if b.startswith(f"- **{label}:**")]
        if len(found) != 1:
            raise AssertionError(
                f"ui-tokens: the card's Typography section has {len(found)} '{label}' "
                f"bullets, expected one ({CARD_POINTER})"
            )
        out[label] = found[0]
    return out


def card_bands(text):
    """The band names the card's `- Breakpoints:` bullet gives, in order (its bold
    words). The bullet must appear exactly once and may wrap onto indented lines."""
    lines = text.splitlines()
    starts = [i for i, line in enumerate(lines) if line.startswith("- Breakpoints:")]
    if len(starts) != 1:
        raise AssertionError(
            f"ui-tokens: the card has {len(starts)} '- Breakpoints:' bullets "
            f"(lines {', '.join(str(i + 1) for i in starts) or 'none'}), expected one ({CARD_POINTER})"
        )
    bullet = lines[starts[0]]
    for line in lines[starts[0] + 1:]:
        if line[:1] == " " and line.strip():
            bullet += " " + line.strip()
        else:
            break
    bands = re.findall(r"\*\*([a-z]+)\*\*", bullet)
    if not bands:
        raise AssertionError(f"ui-tokens: the card's Breakpoints bullet names no band ({CARD_POINTER})")
    return bands


def _only(text, pattern, what):
    found = re.findall(pattern, text)
    if len(found) != 1:
        raise AssertionError(
            f"ui-tokens: the card has {len(found)} matches for {what}, expected one; "
            f"the card and the tests have drifted apart ({CARD_POINTER})"
        )
    return found[0]


def card_prose_numbers(text):
    """The numbers the card states in prose: radius at most, touch at least, the
    breakpoints and the LED segment range. Each sentence must appear exactly once."""
    # ≤ <=, ≥ >=, – en dash
    radius = int(_only(text, r"radius\s*≤\s*([0-9]+)\s*px", "the corner-radius limit"))
    touch = int(_only(text, r"Touch targets\s*≥\s*([0-9]+)\s*px", "the touch-target minimum"))
    mobile = int(_only(text, r"\*\*mobile\*\*\s*\(<\s*([0-9]+)\s*px", "the mobile breakpoint"))
    tablet = _only(text, r"\*\*tablet\*\*\s*\(([0-9]+)–([0-9]+)\s*px\)", "the tablet range")
    desktop = int(_only(text, r"\*\*desktop\*\*\s*\(>\s*([0-9]+)\s*px", "the desktop breakpoint"))
    led = _only(text, r"([0-9]+)–([0-9]+)\s*segment", "the LED segment range")
    return {
        "radius_max": radius,
        "touch_min": touch,
        "mobile_below": mobile,
        "tablet_from": int(tablet[0]),
        "tablet_to": int(tablet[1]),
        "desktop_above": desktop,
        "led_min": int(led[0]),
        "led_max": int(led[1]),
    }


# ── the CSS ─────────────────────────────────────────────────────────────────
_ROOT_SEL = re.compile(r":root")
_MEDIA_SEL = re.compile(r"@media\s*\(\s*prefers-color-scheme\s*:\s*dark\s*\)")
_ATTR_SEL = re.compile(r':root\[data-theme="dark"\]')
_NOT_LIGHT_SEL = re.compile(r':root:not\(\[data-theme="light"\]\)')
_STATEMENT = re.compile(r"--[a-z0-9-]+\s*:\s*[^;{}]+")


def _css_fail(message):
    raise AssertionError(f"ui-tokens: tokens.css {message}")


def _strip_css_comments(text):
    return re.sub(r"/\*.*?\*/", "", text, flags=re.S)


def _css_rules(text, where):
    """The top-level `selector { body }` rules of `text`, in order. Text outside a
    rule is refused."""
    out = []
    i = 0
    while True:
        opening = text.find("{", i)
        if opening < 0:
            if text[i:].strip():
                _css_fail(f"has text outside any rule in {where}: {text[i:].strip()!r}")
            return out
        depth = 1
        j = opening + 1
        while j < len(text) and depth > 0:
            if text[j] == "{":
                depth += 1
            elif text[j] == "}":
                depth -= 1
            j += 1
        if depth:
            _css_fail(f"has an unclosed '{{' in {where}")
        out.append((text[i:opening].strip(), text[opening + 1:j - 1]))
        i = j


def _css_properties(body, where):
    """The custom properties of a leaf block. Anything that is not `--name: value;`
    is refused, and so is a name declared twice."""
    parts = body.split(";")
    if parts[-1].strip():
        _css_fail(f"has a last statement without a ';' in {where}: {parts[-1].strip()!r}")
    out = {}
    for part in parts[:-1]:
        statement = part.strip()
        if not _STATEMENT.fullmatch(statement):
            _css_fail(f"has a statement that is not a custom property in {where}: {statement!r}")
        name = statement.split(":", 1)[0].strip()[2:]
        if name in out:
            _css_fail(f"declares --{name} twice in {where}")
        out[name] = statement.split(":", 1)[1].strip()
    if not out:
        _css_fail(f"has no declaration in {where}")
    return out


def parse_css(text):
    """Return {"root": {...}, "dark_media": {...}, "dark_attr": {...}} of custom properties.

    tokens.css is exactly three rules: `:root`, the prefers-color-scheme rule holding
    one `:root:not([data-theme="light"])` rule, and `:root[data-theme="dark"]`. A
    different selector, a nested at-rule, a stray declaration, a repeated rule or a
    statement that is not a custom property is refused."""
    found = {}
    for selector, body in _css_rules(_strip_css_comments(text), "the file"):
        if _ROOT_SEL.fullmatch(selector):
            name, props = "root", _css_properties(body, "':root'")
        elif _MEDIA_SEL.fullmatch(selector):
            inner = _css_rules(body, "the prefers-color-scheme rule")
            if len(inner) != 1 or not _NOT_LIGHT_SEL.fullmatch(inner[0][0]):
                _css_fail(
                    "prefers-color-scheme rule must hold exactly one "
                    f"':root:not([data-theme=\"light\"])' rule, found {[s for s, _ in inner]}"
                )
            name, props = "dark_media", _css_properties(inner[0][1], "the prefers-color-scheme rule")
        elif _ATTR_SEL.fullmatch(selector):
            name, props = "dark_attr", _css_properties(body, "':root[data-theme=\"dark\"]'")
        else:
            _css_fail(
                f"has a rule for {selector!r}; it holds only ':root', the "
                f"prefers-color-scheme rule and ':root[data-theme=\"dark\"]'"
            )
        if name in found:
            _css_fail(f"has more than one '{name}' rule")
        found[name] = props
    missing = {"root", "dark_media", "dark_attr"} - set(found)
    if missing:
        _css_fail(f"has no {sorted(missing)} rule")
    return found


def read_css():
    return parse_css(read_text(CSS))


def css_palette(block):
    """{token: '#RRGGBB'} for the color variables of one CSS block."""
    out = {}
    for name, value in block.items():
        if not name.startswith("color-"):
            continue
        if not re.fullmatch(r"#[0-9A-Fa-f]{6}", value):
            raise AssertionError(f"ui-tokens: --{name} is {value!r}, not #RRGGBB")
        out[name[len("color-"):]] = value.upper()
    return out


def css_px(value):
    match = re.fullmatch(r"([0-9]+)px", value.strip())
    if not match:
        raise AssertionError(f"ui-tokens: expected a whole px value, got {value!r}")
    return int(match.group(1))


def css_int(value):
    if not re.fullmatch(r"[0-9]+", value.strip()):
        raise AssertionError(f"ui-tokens: expected a whole number, got {value!r}")
    return int(value.strip())


def css_font_stack(value):
    """The entries of a font stack, unquoted, in order."""
    return [part.strip().strip('"').strip("'") for part in value.split(",")]


def css_font_families(value):
    """Family names in a font stack, without the generic keyword."""
    return [n for n in css_font_stack(value) if n and n not in GENERIC_FONT_KEYWORDS]


# ── the Kotlin ──────────────────────────────────────────────────────────────
def _strip_kotlin_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def argb_from_kotlin_literal(literal):
    """What `0xAARRGGBB.toInt()` is: the low 32 bits read as a signed Int."""
    value = int(literal, 16)
    if value >= 1 << 31:
        value -= 1 << 32
    return value


def kotlin_declarations(text):
    """(names, types): every val/var/fun name and every class/object/interface name
    the file declares, comments excluded."""
    text = _strip_kotlin_comments(text)
    names = set(re.findall(r"\b(?:val|var|fun)\s+(\w+)", text))
    types = set(re.findall(r"\b(?:class|object|interface)\s+(\w+)", text))
    return names, types


def kotlin_layout_bands(text):
    """The entry names of `enum class LayoutBand { ... }`, in order, comments excluded.
    Exactly one such enum must be declared."""
    found = re.findall(r"enum\s+class\s+LayoutBand\s*\{([^}]*)\}", _strip_kotlin_comments(text))
    if len(found) != 1:
        raise AssertionError(f"ui-tokens: tokens.kt declares {len(found)} 'enum class LayoutBand' bodies, expected one")
    entries = [e.strip() for e in found[0].replace("\n", " ").split(",")]
    if any(not re.fullmatch(r"[A-Z][A-Z0-9_]*", e) for e in entries):
        raise AssertionError(f"ui-tokens: the LayoutBand entries {entries} are not plain UPPER_CASE names")
    return entries


def parse_kotlin(text):
    """Return the palettes, metrics, type and LED range the Kotlin file declares."""
    text = _strip_kotlin_comments(text)

    palettes = {}
    for m in re.finditer(r"val\s+(LIGHT|DARK)\s*=\s*TruckingPalette\((.*?)\n    \)", text, flags=re.S):
        fields = {}
        for f in re.finditer(r"(\w+)\s*=\s*TokenColor\((0x[0-9A-Fa-f]{8})\.toInt\(\)\)", m.group(2)):
            signed = argb_from_kotlin_literal(f.group(2))
            unsigned = signed & 0xFFFFFFFF
            fields[f.group(1)] = {"alpha": unsigned >> 24, "hex": "#%06X" % (unsigned & 0xFFFFFF)}
        palettes[m.group(1)] = fields
    if set(palettes) != {"LIGHT", "DARK"}:
        raise AssertionError(f"ui-tokens: tokens.kt must declare LIGHT and DARK palettes, found {sorted(palettes)}")
    parsed = sum(len(p) for p in palettes.values())
    written = len(re.findall(r"TokenColor\(0x", text))
    if parsed != written or parsed == 0:
        raise AssertionError(
            f"ui-tokens: tokens.kt writes {written} 'TokenColor(0x...' literals but "
            f"{parsed} parsed as 0xAARRGGBB.toInt(); a literal has the wrong shape"
        )

    def args(pattern, kind):
        """Every `name = value` in the call, whatever its shape. A value of the wrong
        shape is kept raw rather than dropped, so a field the file should not have
        still shows up by name in the declared-fields test."""
        m = re.search(pattern, text, flags=re.S)
        if not m:
            raise AssertionError(f"ui-tokens: tokens.kt has no declaration matching {pattern!r}")
        out = {}
        for name, value in re.findall(r"(\w+)\s*=\s*([^,\n]+)", m.group(1)):
            value = value.strip()
            if kind == "int" and re.fullmatch(r"[0-9]+", value):
                out[name] = int(value)
            elif kind == "str" and re.fullmatch(r'"[^"]*"', value):
                out[name] = value[1:-1]
            else:
                out[name] = value
        return out

    metrics = args(r"val\s+metrics\s*=\s*TruckingMetrics\((.*?)\)", "int")
    type_ = args(r"val\s+type\s*=\s*TruckingType\((.*?)\)", "str")
    led = args(r"val\s+ledBar\s*=\s*TruckingLedBar\((.*?)\)", "int")
    return {"palettes": palettes, "metrics": metrics, "type": type_, "led": led}


def read_kotlin():
    return parse_kotlin(read_text(KOTLIN))


# ── WCAG 2.x contrast ───────────────────────────────────────────────────────
def relative_luminance(hex_color):
    """Relative luminance of `#RRGGBB`. The sRGB linearisation threshold is 0.04045
    (older WCAG text says 0.03928; 8-bit channels give the same ratios)."""
    hex_color = hex_color.lstrip("#")
    channels = [int(hex_color[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    lin = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
    return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]


def contrast_ratio(a, b):
    la, lb = relative_luminance(a), relative_luminance(b)
    if la < lb:
        la, lb = lb, la
    return (la + 0.05) / (lb + 0.05)
