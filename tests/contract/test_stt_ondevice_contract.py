"""Contract test for the `android/modules/stt-ondevice` module.

The structural half is inherited (registry entry, card, Gradle wiring,
declared dependencies). This file adds the behavioural contract as source
checks: registry constants, single verify entry point, and immediate deletion.

Every source check reads CODE only. Comments are removed. String literals are
either kept (when the check is about a literal) or reduced to a marker (when
the check is about structure). Identifiers are matched as whole words, so a
name that merely contains another name (`SMALL` contains `ALL`) never counts.
"""
import os
import re
import unittest

import contract_support
from stt_ondevice_bound_pins import SttOndeviceBoundPinsTest  # noqa: F401  (decode-bound pins, collected here)

REGISTRY_FILE = (
    "shared/modules/model-registry/src/main/kotlin/dev/breaker/"
    "shared/models/ModelRegistry.kt"
)
ONDEVICE_DIR = (
    "android/modules/stt-ondevice/src/main/kotlin/dev/breaker/"
    "dictation/stt/ondevice/"
)
KNOWN_MODELS = ("SMALL", "TINY", "BASE", "MEDIUM")
MODIFIERS = frozenset((
    "public", "private", "internal", "protected", "open", "final", "override",
    "inline", "operator", "infix", "tailrec", "suspend", "external", "abstract",
))


def _scan_kotlin(text, keep_strings):
    """Return `text` without comments (line, block, nested block).

    String and character literals are skipped as units, so `//` inside a URL
    does not start a comment. With keep_strings the literals stay as written;
    without it each string literal becomes `"x"` when it holds any
    non-blank character and `""` otherwise.
    """
    n = len(text)
    out = []

    def block_comment(i, emit):
        depth = 0
        while i < n:
            if text.startswith("/*", i):
                depth += 1
                i += 2
            elif text.startswith("*/", i):
                depth -= 1
                i += 2
                if depth == 0:
                    if emit:
                        out.append(" ")
                    return i
            else:
                if emit and text[i] == "\n":
                    out.append("\n")
                i += 1
        raise AssertionError("unterminated block comment")

    def char_literal(i, emit):
        j = i + 2
        if text.startswith("\\", i + 1):
            j = i + 3
        end = text.find("'", j)
        if end < 0:
            raise AssertionError("unterminated character literal")
        if emit:
            out.append(text[i:end + 1] if keep_strings else "'x'")
        return end + 1

    def string_literal(i, raw, emit):
        quote = '"""' if raw else '"'
        shown = emit and keep_strings
        seen = False
        if shown:
            out.append(quote)
        while i < n:
            if text.startswith(quote, i):
                if shown:
                    out.append(quote)
                elif emit:
                    out.append('"x"' if seen else '""')
                return i + len(quote)
            c = text[i]
            if not raw and c == "\\":
                if shown:
                    out.append(text[i:i + 2])
                seen = True
                i += 2
            elif text.startswith("${", i):
                if shown:
                    out.append("${")
                seen = True
                i = code(i + 2, True, shown)
            else:
                if shown:
                    out.append(c)
                seen = seen or not c.isspace()
                i += 1
        raise AssertionError("unterminated string literal")

    def code(i, nested, emit):
        depth = 0
        while i < n:
            c = text[i]
            if text.startswith("//", i):
                while i < n and text[i] != "\n":
                    i += 1
            elif text.startswith("/*", i):
                i = block_comment(i, emit)
            elif text.startswith('"""', i):
                i = string_literal(i + 3, True, emit)
            elif c == '"':
                i = string_literal(i + 1, False, emit)
            elif c == "'":
                i = char_literal(i, emit)
            else:
                if nested and c == "}":
                    if depth == 0:
                        if emit:
                            out.append(c)
                        return i + 1
                    depth -= 1
                elif nested and c == "{":
                    depth += 1
                if emit:
                    out.append(c)
                i += 1
        if nested:
            raise AssertionError("unterminated string template")
        return i

    code(0, False, True)
    return "".join(out)


def _close_of(text, open_idx):
    """Index of the bracket that closes the one at `open_idx`, or -1."""
    open_c = text[open_idx]
    close_c = {"(": ")", "{": "}", "[": "]"}[open_c]
    depth = 0
    for k in range(open_idx, len(text)):
        if text[k] == open_c:
            depth += 1
        elif text[k] == close_c:
            depth -= 1
            if depth == 0:
                return k
    return -1


def _expression_end(text, start):
    """Index where the expression that begins at `start` ends."""
    depth = 0
    for k in range(start, len(text)):
        c = text[k]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return k
            depth -= 1
        elif depth == 0 and c == ";":
            return k
        elif depth == 0 and c == "\n":
            before = text[start:k].rstrip()
            after = text[k:].lstrip()
            if not (before.endswith(tuple("=,+-*/&|(?:."))
                    or after.startswith((".", "?.", "?:", "&&", "||", "+", "*"))):
                return k
    return len(text)


def _top_level_parts(text):
    """Split on commas that are not inside brackets."""
    parts, depth, last = [], 0, 0
    for k, c in enumerate(text):
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            parts.append(text[last:k])
            last = k + 1
    parts.append(text[last:])
    return [p for p in parts if p.strip()]


def _modifiers_before(code, decl_start):
    """Modifier words and annotations written just before a declaration."""
    prefix = code[:decl_start]
    found = []
    while True:
        m = re.search(r"(@?\w+(?:\([^)]*\))?)\s*$", prefix)
        if not m or not (m.group(1).startswith("@") or m.group(1) in MODIFIERS):
            return found
        found.append(m.group(1))
        prefix = prefix[:m.start()]


class AndroidModulesSttOndeviceContractTest(contract_support.ModuleContractTest):
    MODULE = "android_stt_ondevice"

    # --- helpers --------------------------------------------------------

    def _read_kotlin(self, rel_path):
        """Read a Kotlin source file relative to the repo root."""
        path = os.path.join(contract_support.ROOT, rel_path)
        self.assertTrue(os.path.isfile(path), f"missing: {rel_path}")
        with open(path, "r", encoding="utf-8") as fh:
            return fh.read()

    def _code(self, rel_path):
        """A file's code: comments removed, string literals reduced to markers."""
        return _scan_kotlin(self._read_kotlin(rel_path), False)

    def _module_code(self, keep_strings):
        """Main-source Kotlin files of the module as {file name: text}."""
        src = os.path.join(
            contract_support.ROOT, "android", "modules", "stt-ondevice",
            "src", "main", "kotlin"
        )
        found = {}
        for root, _, names in os.walk(src):
            for name in names:
                if name.endswith(".kt"):
                    with open(os.path.join(root, name), "r", encoding="utf-8") as fh:
                        found[name] = _scan_kotlin(fh.read(), keep_strings)
        self.assertTrue(found, "no Kotlin sources found in the stt-ondevice module")
        return found

    def _val_declaration(self, code, name):
        """Match of the one anchored `val NAME` declaration, with its initializer."""
        found = list(re.finditer(
            r"^[ \t]*(?:(?:public|internal)[ \t]+)?val[ \t]+" + name
            + r"\b[ \t]*(?::[^=\n]*)?=[ \t]*", code, re.M))
        self.assertEqual(
            len(found), 1,
            f"ModelRegistry must declare `val {name}` exactly once in code "
            f"(found {len(found)})")
        return found[0]

    # --- 1. ModelRegistry constants -------------------------------------

    def test_model_registry_constants_are_accessible_and_non_empty(self):
        """SMALL, TINY, BASE and MEDIUM must each be declared in code as
        `val NAME[: Type] = ModelEntry(...)` with non-empty string literals
        for the named arguments `id`, `url` and `sha256`."""
        code = self._code(REGISTRY_FILE)
        for const in KNOWN_MODELS:
            decl = self._val_declaration(code, const)
            call = re.match(r"ModelEntry[ \t]*\(", code[decl.end():])
            self.assertIsNotNone(
                call, f"ModelRegistry.{const} must be built with ModelEntry(...)")
            open_idx = decl.end() + call.end() - 1
            close_idx = _close_of(code, open_idx)
            self.assertGreater(close_idx, open_idx, f"ModelRegistry.{const}: unbalanced ModelEntry(")
            args = {}
            for part in _top_level_parts(code[open_idx + 1:close_idx]):
                named = re.fullmatch(r"\s*(\w+)\s*=\s*(.*?)\s*", part, re.S)
                if named:
                    args[named.group(1)] = named.group(2)
            for field in ("id", "url", "sha256"):
                self.assertIn(
                    field, args,
                    f"ModelRegistry.{const} must pass `{field}` as a named argument")
                self.assertEqual(
                    args[field], '"x"',
                    f"ModelRegistry.{const}.{field} must be a non-empty string literal")

    # --- 2. ModelRegistry.byId ------------------------------------------

    def test_model_registry_by_id_returns_model_for_each_known_id(self):
        """`fun byId(` is declared once and its body searches `ALL` as a whole
        word; `val ALL` is declared and lists SMALL, TINY, BASE and MEDIUM as
        whole words."""
        code = self._code(REGISTRY_FILE)
        funcs = list(re.finditer(r"\bfun\s+byId\s*\(", code))
        self.assertEqual(
            len(funcs), 1,
            f"ModelRegistry must declare fun byId( exactly once in code (found {len(funcs)})")
        params_end = _close_of(code, funcs[0].end() - 1)
        self.assertGreater(params_end, 0, "ModelRegistry.byId: unbalanced parameter list")
        head = re.match(r"[^={]*([={])", code[params_end + 1:])
        self.assertIsNotNone(head, "ModelRegistry.byId has no body")
        body_at = params_end + 1 + head.end()
        if head.group(1) == "{":
            body = code[body_at:_close_of(code, body_at - 1)]
        else:
            body = code[body_at:_expression_end(code, body_at)]
        self.assertRegex(
            body, r"\bALL\b", "byId must search the full model list `ALL` as a whole word")
        decl = self._val_declaration(code, "ALL")
        listed = code[decl.end():_expression_end(code, decl.end())]
        for const in KNOWN_MODELS:
            self.assertRegex(
                listed, r"\b" + const + r"\b",
                f"ModelRegistry.ALL must list {const} as a whole word")

    # --- 3. No runtime parsing of models.yaml ---------------------------

    def test_no_runtime_parsing_of_models_yaml(self):
        """No main source of the module names models.yaml in code or in a
        string literal. A mention in a comment is allowed."""
        for name, text in sorted(self._module_code(keep_strings=True).items()):
            self.assertNotRegex(
                text, r"(?i)models\.yaml",
                f"{name} names models.yaml in code or a string literal; "
                f"the app must not parse YAML at runtime")

    # --- 4. Single verify entry point -----------------------------------

    def test_verify_is_single_entry_point_for_archive_verification(self):
        """ModelIntegrity.kt declares exactly one `fun verify(` and it is
        public; no other main source declares one or touches MessageDigest;
        ModelLoader.kt and ModelInstaller.kt both call `ModelIntegrity.verify(`."""
        files = self._module_code(keep_strings=False)
        integrity = files.get("ModelIntegrity.kt")
        self.assertIsNotNone(integrity, "ModelIntegrity.kt is missing")
        self.assertRegex(integrity, r"\bobject\s+ModelIntegrity\b",
                         "ModelIntegrity.kt must declare object ModelIntegrity")
        decls = list(re.finditer(r"\bfun\s+verify\s*\(", integrity))
        self.assertEqual(
            len(decls), 1,
            f"ModelIntegrity.kt must declare fun verify( exactly once (found {len(decls)})")
        hidden = [m for m in _modifiers_before(integrity, decls[0].start())
                  if m in ("private", "internal", "protected")]
        self.assertEqual(
            hidden, [],
            "ModelIntegrity.verify must be public; it is the single entry point")
        for name, text in sorted(files.items()):
            if name != "ModelIntegrity.kt":
                self.assertNotRegex(
                    text, r"\bfun\s+verify\s*\(",
                    f"{name} declares its own verify; ModelIntegrity.verify is the only one")
        hashing = sorted(n for n, t in files.items() if re.search(r"\bMessageDigest\b", t))
        self.assertEqual(
            hashing, ["ModelIntegrity.kt"],
            "MessageDigest may be used in ModelIntegrity.kt only")
        for name in ("ModelLoader.kt", "ModelInstaller.kt"):
            self.assertRegex(
                files.get(name, ""), r"\bModelIntegrity\s*\.\s*verify\s*\(",
                f"{name} must verify archives through ModelIntegrity.verify(")

    # --- 5. Immediate deletion of corrupted files -----------------------

    def test_corrupted_model_file_is_deleted_immediately(self):
        """In ModelLoader.kt the branch that handles
        `ModelIntegrity.Verdict.Refused` calls `store.delete(` before it
        returns the refusal, binds the result and uses it afterwards. The
        word quarantine appears in no main source."""
        code = self._code(ONDEVICE_DIR + "ModelLoader.kt")
        conds = list(re.finditer(
            r"(?<!!)\bis\s+ModelIntegrity\s*\.\s*Verdict\s*\.\s*Refused\b", code))
        self.assertEqual(
            len(conds), 1,
            "ModelLoader must have exactly one `is ModelIntegrity.Verdict.Refused` "
            f"branch (found {len(conds)})")
        rest = code[conds[0].end():]
        arrow = re.match(r"\s*->\s*\{", rest)
        if arrow:
            brace = conds[0].end() + arrow.end() - 1
        else:
            depth, end = 0, -1
            for k, c in enumerate(rest):
                if c == "(":
                    depth += 1
                elif c == ")":
                    if depth == 0:
                        end = k
                        break
                    depth -= 1
            opener = re.match(r"\s*\{", rest[end + 1:]) if end >= 0 else None
            self.assertIsNotNone(
                opener, "the Refused branch must be a braced block")
            brace = conds[0].end() + end + opener.end()
        close = _close_of(code, brace)
        self.assertGreater(close, brace, "the Refused branch has unbalanced braces")
        branch = code[brace + 1:close]
        ret = re.search(r"\breturn\b", branch)
        self.assertIsNotNone(ret, "the Refused branch must return the refusal")
        call = re.search(r"\bstore\s*\.\s*delete\s*\(", branch)
        self.assertIsNotNone(
            call, "the Refused branch must call store.delete( on verification failure")
        self.assertLess(
            call.start(), ret.start(),
            "store.delete( must come before the return of the refusal")
        bound = re.search(
            r"\b(?:val|var)\s+(\w+)\s*(?::[^=\n]*)?=\s*$", branch[:call.start()])
        self.assertIsNotNone(
            bound, "the result of store.delete( must be bound to a name")
        after = branch[_close_of(branch, call.end() - 1) + 1:]
        self.assertRegex(
            after, r"\b" + re.escape(bound.group(1)) + r"\b",
            f"the delete result `{bound.group(1)}` must be used after the delete")
        for name, text in sorted(self._module_code(keep_strings=True).items()):
            self.assertNotRegex(
                text, r"(?i)quarantine",
                f"{name} mentions quarantine in code; delete immediately instead")

    # --- 6. Engine: one decode at a time on a single-slot dispatcher ----
    ENGINE = ONDEVICE_DIR + "OnDeviceSttEngine.kt"

    def _body(self, code, name):
        """Body of the one `fun NAME(` in `code`, braced or `= expression`."""
        found = list(re.finditer(r"\bfun\s+" + name + r"\s*\(", code))
        self.assertEqual(len(found), 1, f"engine pin: fun {name}( must be declared once (found {len(found)})")
        end = _close_of(code, found[0].end() - 1)
        head = re.match(r"[^={]*([={])", code[end + 1:])
        self.assertIsNotNone(head, f"fun {name} has no body")
        at = end + 1 + head.end()
        return code[at:(_expression_end(code, at) if head.group(1) == "=" else _close_of(code, at - 1))]

    def test_engine_default_dispatcher_is_a_single_slot_over_io(self):
        """The default is `singleSlot(Dispatchers.IO)`, `singleSlot` is exactly
        `base.limitedParallelism(1)`, the scope is `inferenceDispatcher + SupervisorJob()`."""
        code = self._code(self.ENGINE)
        self.assertRegex(
            code, r"\bclass\s+OnDeviceSttEngine\s*\([^()]*\binferenceDispatcher\s*:\s*CoroutineDispatcher"
            r"\s*=\s*singleSlot\s*\(\s*Dispatchers\s*\.\s*IO\s*\)\s*[,)]",
            "dispatcher pin: the default must be singleSlot(Dispatchers.IO)")
        self.assertRegex(
            self._body(code, "singleSlot"), r"\A\s*\w+\s*\.\s*limitedParallelism\s*\(\s*1\s*\)\s*\Z",
            "dispatcher pin: singleSlot must be exactly limitedParallelism(1)")
        self.assertRegex(
            code, r"\bCoroutineScope\s*\(\s*inferenceDispatcher\s*\+\s*SupervisorJob\s*\(\s*\)\s*\)",
            "dispatcher pin: the scope must be inferenceDispatcher + SupervisorJob()")

    def test_async_path_never_calls_the_blocking_path(self):
        """`transcribe` uses `runBlocking(inferenceDispatcher)`; `transcribeAsync` uses `scope.async`
        and, like the private functions it calls (one hop), holds no `runBlocking`, `transcribe(`, `::transcribe`."""
        code = self._code(self.ENGINE)
        self.assertRegex(
            self._body(code, "transcribe"), r"\brunBlocking\s*\(\s*inferenceDispatcher\s*\)",
            "bridge pin: transcribe must use runBlocking(inferenceDispatcher)")
        asy = self._body(code, "transcribeAsync")
        self.assertRegex(asy, r"\bscope\s*\.\s*async\b", "bridge pin: transcribeAsync must use scope.async")
        bodies = {"transcribeAsync": asy}
        for name in set(re.findall(r"\bprivate\s+(?:suspend\s+|inline\s+)*fun\s+(\w+)", code)):
            if re.search(r"\b" + name + r"\s*\(", asy):
                bodies[name] = self._body(code, name)
        for name, body in sorted(bodies.items()):
            self.assertNotRegex(
                body, r"\brunBlocking\b|\btranscribe\s*\(|::\s*transcribe\b",
                f"no-call pin: {name}, reached from transcribeAsync, must not call transcribe or runBlocking")

    def test_reentrancy_marker_is_reset_in_a_finally(self):
        """Each `decodingHere.set(true)` is followed by `try {` and its block by a
        `finally {` holding `decodingHere.set(false)`; both calls occur equally often."""
        code = self._code(self.ENGINE)
        flag = r"\bdecodingHere\s*\.\s*set\s*\(\s*%s\s*\)"
        sets = list(re.finditer(flag % "true", code))
        self.assertTrue(sets and len(sets) == len(re.findall(flag % "false", code)),
                        "marker pin: set(true) and set(false) must both occur, equally often")
        for m in sets:
            body = re.match(r"\s*try\s*\{", code[m.end():])
            end = _close_of(code, m.end() + body.end() - 1) if body else -1
            fin = re.match(r"\s*finally\s*\{", code[end + 1:]) if body else None
            self.assertIsNotNone(fin, "marker pin: set(true) must be followed by try { } finally {")
            self.assertRegex(
                code[end + fin.end():_close_of(code, end + fin.end())], flag % "false",
                "marker pin: the finally must call decodingHere.set(false)")

    def test_closed_flag_is_volatile(self):
        """`@Volatile private var closed`: close() may run on any thread."""
        self.assertRegex(
            self._code(self.ENGINE), r"@Volatile\s+private\s+var\s+closed\b",
            "volatile pin: closed must be a @Volatile private var")

    def test_engine_and_error_mapping_use_no_thread_primitives(self):
        """No `Thread`, `synchronized`, `Atomic*`, `CountDownLatch`, `sleep(` in
        OnDeviceSttEngine.kt or ErrorMapping.kt; `ThreadLocal` stays allowed."""
        files = self._module_code(keep_strings=False)
        for name in ("OnDeviceSttEngine.kt", "ErrorMapping.kt"):
            self.assertIn(name, files, f"{name} is missing")
            self.assertNotRegex(
                files[name], r"\b(?:Thread|CountDownLatch|Atomic\w*|[Ss]ynchronized)\b|\bsleep\s*\(",
                f"thread pin: {name} uses a thread primitive the module does not allow")

    def test_preload_releases_the_recognizer_it_loaded(self):
        """In `preload` the `Ready` arm has a `finally {` calling `.recognizer.release()`."""
        body = self._body(self._code(self.ENGINE), "preload")
        arm = re.search(r"\bis\s+ModelLoader\s*\.\s*LoadResult\s*\.\s*Ready\s*->\s*\{", body)
        self.assertIsNotNone(arm, "release pin: preload needs a LoadResult.Ready arm")
        self.assertRegex(
            body[arm.end():_close_of(body, arm.end() - 1)],
            r"\bfinally\s*\{[^{}]*\.\s*recognizer\s*\.\s*release\s*\(\s*\)",
            "release pin: the Ready arm of preload must release the recognizer in a finally")


if __name__ == "__main__":
    unittest.main()
