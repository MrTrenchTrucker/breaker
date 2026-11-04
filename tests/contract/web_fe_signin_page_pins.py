"""Source pins for the web FE sign-in page shell.

Covers the html, the css, the crypto stub, the two page modules and the new
test-side files. Not a `test_*.py` file on purpose: the contract-file
bijection counts every `tests/contract/test_*.py` as one module's contract
test. Collected through `test_web_fe_contract.py`.

Each rule takes a dict of texts (key to text, None when the file is missing).
Each rule has a real-tree test and a firing control: a good text must pass,
and the same text with one edit must fail.
"""

import os
import re
import unittest

import contract_support

WEB = os.path.join("server", "modules", "web-fe", "src", "main", "resources", "web")
TEST_DIR = os.path.join("server", "modules", "web-fe", "src", "test", "js")
TOKENS_PATH = os.path.join("shared", "modules", "ui-tokens", "src", "main", "resources", "tokens.css")
HTML, CSS, CRYPTO = "signin.html", "css/signin.css", "js/signin-crypto.js"
PAGE, NET, SIGNIN, TOKENS = "js/signin-page.js", "js/signin-net.js", "js/signin.js", "tokens.css"
WEB_FILES = [HTML, CSS, CRYPTO, PAGE, NET, SIGNIN]
NEW_TESTS = ["signin-page.test.mjs", "signin-wire.test.mjs", "signin-net.test.mjs",
             "signin-logout.test.mjs", "signin-page-fixtures.mjs"]
SCAN = WEB_FILES + NEW_TESTS
JS_KEYS = [CRYPTO, PAGE, NET, SIGNIN] + NEW_TESTS
MARKUP_KEYS = [HTML, CSS]
NOT_AVAILABLE = ("Signing in through the website is not available yet, so this page "
                 "cannot hand your sign-in to the app.")
CSP_VALUE = ("default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
             "img-src 'self'; base-uri 'none'; form-action 'none'")
CSP_META = '<meta http-equiv="Content-Security-Policy" content="' + CSP_VALUE + '">'
HTML_EXACT = ["<!doctype html>", '<html lang="en">', '<meta charset="utf-8">',
              '<meta name="viewport" content="width=device-width, initial-scale=1">', CSP_META,
              '<link rel="stylesheet" href="css/tokens.css">', '<link rel="stylesheet" href="css/signin.css">',
              '<script type="module" src="js/signin-page.js"></script>']
FORBIDDEN = ["innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval", "new Function",
             "localStorage", "sessionStorage", "indexedDB", "document.cookie", "import("]
URL_PATHS = ["/v1/auth/salt", "/v1/auth/login", "/v1/auth/handoff/start", "/v1/auth/logout"]
BANNED = ["it" "em", "ro" "und", "ga" "te", "verd" "ict", "rul" "ed", "rul" "ing", "sli" "ce",
          "sub" "-agent", "plan" " file", "read" "back", "review" "er", "le" "ad"]
BANNED_RE = re.compile(r"\b(?:" + "|".join(re.escape(w) for w in BANNED) + r")\b", re.I)
LABEL_RE = re.compile(r"\b[A-Z][0-9]{1,2}\b")
DIGIT_RE = re.compile(r"[0-9]{7,}")
HEX_RE = re.compile(r"[0-9A-Fa-f]{8,}")
LITERAL_RE = re.compile("['\"`]([A-Za-z0-9+/_=-]{17,})['\"`]")
ALPHABET_RE = re.compile(r"[A-Z]{26}|[a-z]{26}")
MARKUP_RUN_RE = re.compile(r"[A-Za-z0-9+/=]{17,}")
DATA_RE = re.compile(r"data:", re.IGNORECASE)


def _read(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except (OSError, UnicodeDecodeError):
        return None


def load_inputs(root):
    texts = {name: _read(os.path.join(root, WEB, name)) for name in WEB_FILES}
    texts[TOKENS] = _read(os.path.join(root, TOKENS_PATH))
    texts.update({name: _read(os.path.join(root, TEST_DIR, name)) for name in NEW_TESTS})
    return texts


def _tag(text, element_id):
    found = re.search(rf'<(\w+)\b[^>]*\bid="{element_id}"[^>]*>', text)
    return found.group(0) if found else ""


def _file(key, check):
    def rule(texts):
        text = texts.get(key)
        return [key + ": missing file"] if text is None else check(key, text)
    return rule


def _scan(check, keys=SCAN):
    def rule(texts):
        out = []
        for key in keys:
            text = texts.get(key)
            out += [key + ": missing file"] if text is None else check(key, text)
        return out
    return rule


def html_exact_tags(key, text):
    return [key + ": missing " + tag for tag in HTML_EXACT if tag not in text]


def html_title(key, text):
    return [] if re.search(r"<title>[^<]*\S[^<]*</title>", text) else [key + ": no title text"]


def html_refs(key, text):
    refs = re.findall(r'\b(?:src|href)="([^"]*)"', text)
    want = ["css/tokens.css", "css/signin.css", "js/signin-page.js"]
    return [] if refs == want else [key + ": local references " + ", ".join(refs)]


def html_no_inline_script(key, text):
    scripts = len(re.findall(r"<script\b", text))
    problems = [] if scripts == 1 else [key + ": script tags " + str(scripts)]
    return problems + ([key + ": style element"] if re.search(r"<style\b", text) else [])


def html_no_attributes(key, text):
    return [key + ": attribute " + m.group(0).strip()
            for m in re.finditer(r"\s(?:style|action|on[a-z]+)\s*=", text, re.I)]


def html_no_http(key, text):
    return [key + ": http or https text"] if re.search(r"https?(?!-equiv)", text, re.I) else []


def html_password(key, text):
    tag = _tag(text, "signin-password")
    return [key + ": signin-password lacks " + need
            for need in ('type="password"', 'autocomplete="current-password"', " required") if need not in tag]


def html_submit(key, text):
    tag = _tag(text, "signin-submit")
    ok = 'type="submit"' in tag and re.search(r"\sdisabled\b", tag)
    return [] if ok else [key + ": signin-submit is not a disabled submit"]


def html_status(key, text):
    tag = _tag(text, "signin-status")
    return [key + ": signin-status lacks " + need for need in ('role="status"', 'aria-live="polite"')
            if need not in tag]


def html_ids(key, text):
    return [key + ": no id " + name for name in ("signin-form", "signin-username", "signin-password",
                                                  "signin-submit", "signin-status") if 'id="%s"' % name not in text]


def html_status_text(texts):
    html, page = texts.get(HTML), texts.get(PAGE)
    if html is None or page is None:
        return [name + ": missing file" for name, text in ((HTML, html), (PAGE, page)) if text is None]
    message = re.search(r"^  not_available: '([^']*)',$", page, re.M)
    shown = re.search(r'<(\w+)\b[^>]*\bid="signin-status"[^>]*>(.*?)</\1>', html, re.S)
    if message is None:
        return [PAGE + ": no not_available line"]
    if shown is None:
        return [HTML + ": no signin-status text"]
    return [] if shown.group(2) == message.group(1) else [HTML + ": status text differs from " + PAGE]


def css_no_url_or_import(key, text):
    return [key + ": url( or @import in css"] if re.search(r"url\(|@import", text, re.I) else []


def css_colours(key, text):
    literal = re.search(r"#[0-9A-Fa-f]{3,8}\b|\b(?:rgba?|hsla?)\(", text, re.I)
    return [key + ": colour literal"] if literal else []


def _small(value):
    found = re.fullmatch(r"(\d+)px", value)
    return found is not None and int(found.group(1)) <= 4


def css_radius(key, text):
    values = [v.strip() for v in re.findall(r"\bborder(?:-[a-z]+)*-radius\s*:\s*([^;}]+)", text)]
    return [key + ": radius " + v for v in values if v not in ("var(--radius)", "0") and not _small(v)]


def css_breakpoints(key, text):
    return [key + ": missing " + q for q in ("@media (min-width: 640px)", "@media (min-width: 1024px)")
            if q not in text]


def css_vars(texts):
    css, tokens = texts.get(CSS), texts.get(TOKENS)
    if css is None or tokens is None:
        return [name + ": missing file" for name, text in ((CSS, css), (TOKENS, tokens)) if text is None]
    declared = set(re.findall(r"(--[a-z0-9-]+)\s*:", tokens))
    used = re.findall(r"var\(\s*(--[a-z0-9-]+)", css)
    problems = [CSS + ": " + name + " not declared in tokens.css" for name in sorted(set(used) - declared)]
    if len(re.findall(r"var\(", css)) != len(used):
        problems.append(CSS + ": var( that is not a custom property")
    return problems


def css_hidden(key, text):
    return [] if re.search(r"\[hidden\]\s*\{\s*display:\s*none;\s*\}", text) else [key + ": no [hidden] rule"]


def css_focus_and_touch(key, text):
    ring = ":focus-visible" in text and "outline: 3px solid var(--color-primary)" in text
    touch = text.count("min-height: var(--touch-target-min);") >= 2
    return ([] if ring else [key + ": no focus ring"]) + ([] if touch else [key + ": touch targets"])


def crypto_lines(key, text):
    return [] if len(text.splitlines()) <= 30 else [key + ": more than 30 lines"]


def no_import(key, text):
    return [key + ": import statement"] if re.search(r"^\s*import\b|\bfrom\s*['\"]|\bimport\s*\(", text, re.M) else []


def crypto_available(key, text):
    ok = "available = false" in text and not re.search(r"\bavailable\s*=\s*true", text)
    return [] if ok else [key + ": available is not false"]


def crypto_exports(key, text):
    names = sorted(re.findall(r"^export\s+(?:async\s+)?(?:function\*?|const|let|var|class)\s+(\w+)", text, re.M))
    bad = names != sorted(["available", "derive", "boxPublicKey", "seal"]) or re.search(
        r"^export\s*(?:\{|default\b)", text, re.M)
    return [key + ": exports are " + ", ".join(names)] if bad else []


def crypto_throws(key, text):
    count = text.count("new Error('not available')")
    return [] if count == 3 else [key + ": %d not available throws" % count]


def forbidden(key, text):
    return [key + ": forbidden " + token for token in FORBIDDEN if token in text]


def page_imports(key, text):
    paths = sorted(re.findall(r"^\s*import\b[^'\"]*['\"]([^'\"]+)['\"]", text, re.M))
    statements = len(re.findall(r"^\s*import\b", text, re.M))
    ok = statements == 3 and paths == sorted(["./signin.js", "./signin-crypto.js", "./signin-net.js"])
    return [] if ok else [key + ": imports " + ", ".join(paths)]


def net_no_document_or_window(key, text):
    return [key + ": contains " + word for word in ("document", "window.") if word in text]


def net_urls(key, text):
    return [key + ": no url " + path for path in URL_PATHS if not re.search(r"['\"]" + re.escape(path) + r"['\"]", text)]


def _lines(key, text, regex, message):
    return [f"{key} line {n}: {message}" for n, line in enumerate(text.splitlines(), 1) if regex.search(line)]


def scan_ascii(key, text):
    return [f"{key} line {n}: not ASCII" for n, line in enumerate(text.splitlines(), 1) if not line.isascii()]


def scan_banned(key, text):
    return _lines(key, text, BANNED_RE, "banned word")


def scan_labels(key, text):
    return _lines(key, text, LABEL_RE, "review label")


def scan_digit_runs(key, text):
    return _lines(key, text, DIGIT_RE, "digit run of 7 or more")


def scan_hex_runs(key, text):
    return _lines(key, text, HEX_RE, "hex run of 8 or more")


def _base64_looking(literal):
    # Root-relative routes (/v1/...) and the 26-letter alphabet lines are the only exemptions.
    return not (literal.startswith("/v1/") or ALPHABET_RE.fullmatch(literal))


def scan_literals(key, text):
    return [f"{key} line {n}: base64-looking literal" for n, line in enumerate(text.splitlines(), 1)
            for found in LITERAL_RE.finditer(line) if _base64_looking(found.group(1))]


def scan_data_text(key, text):
    return _lines(key, text, DATA_RE, "data: text")


def scan_markup_base64(key, text):
    return _lines(key, text, MARKUP_RUN_RE, "base64 text of 17 or more characters")


def scan_line_cap(key, text):
    count = len(text.splitlines())
    return [f"{key}: {count} lines, the cap is 300"] if count >= 300 else []


RULES = [
    ("html_exact_tags", _file(HTML, html_exact_tags), HTML, "style-src 'self'", "style-src 'none'"),
    ("html_title", _file(HTML, html_title), HTML, "<title>Sign in</title>", "<title> </title>"),
    ("html_refs", _file(HTML, html_refs), HTML, '<link rel="stylesheet" href="css/signin.css">',
     '<link rel="stylesheet" href="css/signin.css">\n<link rel="stylesheet" href="css/extra.css">'),
    ("html_no_inline_script", _file(HTML, html_no_inline_script), HTML, "</head>",
     "<script>void 0;</script></head>"),
    ("html_no_attributes", _file(HTML, html_no_attributes), HTML, '<form id="signin-form">',
     '<form id="signin-form" style="margin: 0">'),
    ("html_no_http", _file(HTML, html_no_http), HTML, "<title>Sign in</title>", "<title>See http://x</title>"),
    ("html_password", _file(HTML, html_password), HTML, 'type="password" autocomplete="current-password"',
     'type="text" autocomplete="current-password"'),
    ("html_submit", _file(HTML, html_submit), HTML, 'type="submit" disabled', 'type="submit"'),
    ("html_status", _file(HTML, html_status), HTML, 'role="status" aria-live="polite"', 'role="status"'),
    ("html_ids", _file(HTML, html_ids), HTML, 'id="signin-status"', 'id="signin-state"'),
    ("html_status_text", html_status_text, HTML, NOT_AVAILABLE, "Signing in is not available."),
    ("css_no_url_or_import", _file(CSS, css_no_url_or_import), CSS, "font-family: var(--font-body);",
     "background-image: url(x.png);"),
    ("css_colours", _file(CSS, css_colours), CSS, "color: var(--color-text);", "color: " + "#" + "fff;"),
    ("css_radius", _file(CSS, css_radius), CSS, "border-radius: var(--radius);", "border-radius: 5px;"),
    ("css_breakpoints", _file(CSS, css_breakpoints), CSS, "@media (min-width: 640px)",
     "@media (min-width: 641px)"),
    ("css_vars", css_vars, CSS, "color: var(--color-text);", "color: var(--color-missing);"),
    ("css_hidden", _file(CSS, css_hidden), CSS, "[hidden] { display: none; }", "[hidden] { display: block; }"),
    ("css_focus_and_touch", _file(CSS, css_focus_and_touch), CSS, ":focus-visible", ":focus-within"),
    ("crypto_lines", _file(CRYPTO, crypto_lines), CRYPTO, "export const available = false;",
     "export const available = false;" + "\n//" * 30),
    ("crypto_no_import", _file(CRYPTO, no_import), CRYPTO, "export const available = false;",
     "import { x } from './x.js';\nexport const available = false;"),
    ("crypto_available", _file(CRYPTO, crypto_available), CRYPTO, "export const available = false;",
     "export const available = true;"),
    ("crypto_exports", _file(CRYPTO, crypto_exports), CRYPTO, "export const available = false;",
     "export const available = false;\nexport const extra = 1;"),
    ("crypto_throws", _file(CRYPTO, crypto_throws), CRYPTO, "throw new Error('not available');", "return null;"),
    ("page_forbidden", _file(PAGE, forbidden), PAGE, "export const PAGE_MESSAGES",
     "x.innerHTML = 1;\nexport const PAGE_MESSAGES"),
    ("page_imports", _file(PAGE, page_imports), PAGE, "import { createSignin } from './signin.js';",
     "import { createSignin } from './signin.js';\nimport './extra.js';"),
    ("net_forbidden", _file(NET, forbidden), NET, "return { urls: URLS, fetchFn };",
     "return { urls: URLS, fetchFn, html: x.innerHTML };"),
    ("net_no_document_or_window", _file(NET, net_no_document_or_window), NET,
     "return { urls: URLS, fetchFn };", "return { urls: URLS, fetchFn, doc: document };"),
    ("net_no_import", _file(NET, no_import), NET, "const URLS = {", "import { x } from './x.js';\nconst URLS = {"),
    ("net_urls", _file(NET, net_urls), NET, "'/v1/auth/logout'", "'/v1/auth/logouts'"),
    ("scan_ascii", _scan(scan_ascii), HTML, "<title>Sign in</title>", "<title>Sign in\u00e9</title>"),
    ("scan_banned", _scan(scan_banned), HTML, "<title>Sign in</title>", "<title>Sign in " + "it" + "em</title>"),
    ("scan_labels", _scan(scan_labels), HTML, "<title>Sign in</title>", "<title>Sign in " + "K" + "1</title>"),
    ("scan_digit_runs", _scan(scan_digit_runs), HTML, "<title>Sign in</title>",
     "<title>Sign in " + "1234" + "567</title>"),
    ("scan_hex_runs", _scan(scan_hex_runs), HTML, "<title>Sign in</title>",
     "<title>Sign in " + "dead" + "beef0</title>"),
    ("scan_literals", _scan(scan_literals, JS_KEYS), PAGE, "export const PAGE_MESSAGES",
     "const x = 'abcdefghij" + "klmnopqrst';\nexport const PAGE_MESSAGES"),
    ("scan_data_text", _scan(scan_data_text, MARKUP_KEYS), HTML, "<title>Sign in</title>",
     "<title>" + "data:" + "x</title>"),
    ("scan_markup_base64", _scan(scan_markup_base64, MARKUP_KEYS), HTML, "<title>Sign in</title>",
     "<title>" + "Zm9vYmFy" + "YmF6cXV4MTIz" + "</title>"),
    ("scan_line_cap", _scan(scan_line_cap), HTML, "<title>Sign in</title>", "<title>Sign in</title>" + "\n" * 300),
]
ALL_RULES = [rule for _name, rule, _key, _old, _new in RULES]


def check_signin_page_sources(root):
    texts = load_inputs(root)
    return [problem for rule in ALL_RULES for problem in rule(texts)]


GOOD_HTML = "\n".join([
    "<!doctype html>",
    '<html lang="en"><head><meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">' + CSP_META,
    "<title>Sign in</title>",
    '<link rel="stylesheet" href="css/tokens.css"><link rel="stylesheet" href="css/signin.css">',
    '<script type="module" src="js/signin-page.js"></script></head><body>',
    '<form id="signin-form">',
    '<input id="signin-username" name="username" type="text" autocomplete="username" required>',
    '<input id="signin-password" name="password" type="password" autocomplete="current-password" required>',
    '<button id="signin-submit" type="submit" disabled>Sign in</button></form>',
    '<p id="signin-status" role="status" aria-live="polite">' + NOT_AVAILABLE + "</p></body></html>",
]) + "\n"
GOOD_CSS = "\n".join([
    "[hidden] { display: none; }",
    "body { color: var(--color-text); background: var(--color-bg); font-family: var(--font-body); }",
    "input { min-height: var(--touch-target-min); border: 1px solid var(--color-trim); "
    "border-radius: var(--radius); }",
    "button { min-height: var(--touch-target-min); border: 2px solid var(--color-primary); "
    "border-radius: var(--radius); color: var(--color-primary); background: var(--color-bg); }",
    "button:disabled { color: var(--color-text-muted); background: var(--color-surface); }",
    ":focus-visible { outline: 3px solid var(--color-primary); }",
    "@media (min-width: 640px) { body { margin: 0; } }",
    "@media (min-width: 1024px) { body { margin: 0; } }",
]) + "\n"
GOOD_TOKENS = ":root {\n" + "\n".join(
    "  --%s: none;" % name for name in ("color-bg", "color-surface", "color-text", "color-text-muted",
                                        "color-primary", "color-trim", "font-body", "radius",
                                        "touch-target-min")) + "\n}\n"
GOOD_CRYPTO = "\n".join([
    "export const available = false;",
    "export function derive() { throw new Error('not available'); }",
    "export function boxPublicKey() { throw new Error('not available'); }",
    "export function seal() { throw new Error('not available'); }",
]) + "\n"
GOOD_PAGE = "\n".join([
    "import * as cryptoModule from './signin-crypto.js';",
    "import { createNet } from './signin-net.js';",
    "import { createSignin } from './signin.js';",
    "export const PAGE_MESSAGES = Object.freeze({",
    "  not_available: '" + NOT_AVAILABLE + "',",
    "});",
]) + "\n"
GOOD_NET = "\n".join([
    "const URLS = { salt: '/v1/auth/salt', login: '/v1/auth/login', "
    "start: '/v1/auth/handoff/start', logout: '/v1/auth/logout' };",
    "export function createNet(fetchFn) { return { urls: URLS, fetchFn }; }",
]) + "\n"
GOOD = dict.fromkeys(SCAN, "")
GOOD.update({HTML: GOOD_HTML, CSS: GOOD_CSS, CRYPTO: GOOD_CRYPTO, PAGE: GOOD_PAGE, NET: GOOD_NET,
             TOKENS: GOOD_TOKENS})


class WebFeSigninPagePinsTest(unittest.TestCase):

    def setUp(self):
        self.texts = load_inputs(contract_support.ROOT)

    def test_real_tree_is_clean(self):
        self.assertEqual(check_signin_page_sources(contract_support.ROOT), [])


def _real_test(rule):
    def test(self):
        self.assertEqual(rule(self.texts), [])
    return test


def _control_test(rule, key, old, new):
    def test(self):
        self.assertIn(old, GOOD[key], "control edit did not land")
        bad = dict(GOOD)
        bad[key] = GOOD[key].replace(old, new, 1)
        self.assertEqual(rule(GOOD), [], "the good text fails the rule")
        self.assertTrue(rule(bad), "the edited text does not fire the rule")
    return test


for _name, _rule, _key, _old, _new in RULES:
    setattr(WebFeSigninPagePinsTest, "test_" + _name, _real_test(_rule))
    setattr(WebFeSigninPagePinsTest, "test_control_" + _name, _control_test(_rule, _key, _old, _new))
