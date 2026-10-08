"""Source pins for the decode bound of `android/modules/stt-ondevice`.

Not a `test_*.py` file on purpose: the contract-file bijection counts every
`tests/contract/test_*.py` as one module's contract test. The class below is
collected through `test_stt_ondevice_contract.py`, which imports it, so the
card's one contract test file still runs these pins.

Every pin reads CODE only, through the scanner of the main contract file:
comments (KDoc included) are removed and string literals are reduced to a
marker. Identifiers are matched as whole words. The scanner and the two body
helpers are borrowed lazily from that file, so there is one copy of each.
"""
import os
import re
import unittest

import contract_support

MAIN = (
    "android/modules/stt-ondevice/src/main/kotlin/dev/breaker/"
    "dictation/stt/ondevice/"
)
ENGINE = MAIN + "OnDeviceSttEngine.kt"
BOUND = MAIN + "DecodeBound.kt"
CLOCK = r"\b(?:delay|withTimeout\w*|sleep)\s*\(|\.\s*(?:orTimeout|completeOnTimeout)\s*\("
BUSY = (r"\bif\s*\(\s*bound\s*\.\s*abandonedRunning\s*\)\s*return(?:@\w+)?\s+"
        r"ErrorMapping\s*\.\s*decodeBusy\s*\(\s*\)")


def _base():
    """The main contract module and its test class (imported on first use)."""
    import test_stt_ondevice_contract as base
    return base, base.AndroidModulesSttOndeviceContractTest


class SttOndeviceBoundPinsTest(unittest.TestCase):

    def _code(self, rel):
        path = os.path.join(contract_support.ROOT, rel)
        self.assertTrue(os.path.isfile(path), f"missing: {rel}")
        with open(path, "r", encoding="utf-8") as fh:
            return _base()[0]._scan_kotlin(fh.read(), False)

    def _body(self, code, name):
        return _base()[1]._body(self, code, name)

    def _module_code(self):
        return _base()[1]._module_code(self, keep_strings=False)

    def _span(self, code, name):
        """(start, end) of the one `fun NAME(` body inside `code`."""
        body = self._body(code, name)
        self.assertEqual(code.count(body), 1, f"fun {name}: its body text must occur once")
        at = code.index(body)
        return at, at + len(body)

    def _branch(self, text, arrow):
        """The text a `when` arm runs: a braced block or one expression."""
        m = re.search(arrow, text)
        self.assertIsNotNone(m, f"the arm {arrow!r} is missing")
        base = _base()[0]
        start = m.end()
        rest = text[start:]
        if rest.lstrip().startswith("{"):
            brace = start + len(rest) - len(rest.lstrip())
            return text[brace + 1:base._close_of(text, brace)]
        return text[start:base._expression_end(text, start)]

    # --- 12. constructor shape ------------------------------------------

    def test_engine_constructor_keeps_both_defaults_in_order(self):
        """The class header lists `loader`, then `inferenceDispatcher` defaulting to
        `singleSlot(Dispatchers.IO)`, then `decodeDeadline` defaulting to
        `DecodeDeadline.afterAudio()`, then `decodeWorkers` defaulting to `Dispatchers.IO`."""
        code = self._code(ENGINE)
        head = re.search(r"\bclass\s+OnDeviceSttEngine\s*\(", code)
        self.assertIsNotNone(head, "constructor pin: class OnDeviceSttEngine( is missing")
        close = _base()[0]._close_of(code, head.end() - 1)
        parts = _base()[0]._top_level_parts(code[head.end():close])
        self.assertGreaterEqual(len(parts), 4, "constructor pin: expected at least four parameters")
        want = (
            ("loader", r"\A\s*private\s+val\s+loader\s*:\s*ModelLoaderPort\s*\Z"),
            ("inferenceDispatcher", r"\A\s*private\s+val\s+inferenceDispatcher\s*:\s*CoroutineDispatcher"
             r"\s*=\s*singleSlot\s*\(\s*Dispatchers\s*\.\s*IO\s*\)\s*\Z"),
            ("decodeDeadline", r"\A\s*decodeDeadline\s*:\s*DecodeDeadline"
             r"\s*=\s*DecodeDeadline\s*\.\s*afterAudio\s*\(\s*\)\s*\Z"),
            ("decodeWorkers", r"\A\s*decodeWorkers\s*:\s*CoroutineDispatcher"
             r"\s*=\s*Dispatchers\s*\.\s*IO\s*\Z"),
        )
        for place, (name, pattern) in enumerate(want):
            self.assertRegex(
                parts[place], pattern,
                f"constructor pin: parameter {place + 1} must be `{name}` with its documented default")

    # --- 13. ownership of the recognizer --------------------------------

    def test_decoded_and_released_recognizer_is_the_wrapped_one(self):
        """In `performTranscribe` the local `recognizer` is assigned once, from
        `bound.wrap(<loader result>.recognizer)`, and the loader's own recognizer is never
        decoded or released there (the local keeps the old name, so the line that assigns it is read)."""
        body = self._body(self._code(ENGINE), "performTranscribe")
        found = list(re.finditer(r"\b(?:val|var)\s+recognizer\b\s*(?::[^=\n]*)?=\s*", body))
        self.assertEqual(len(found), 1, f"ownership pin: `recognizer` must be assigned once (found {len(found)})")
        value = body[found[0].end():_base()[0]._expression_end(body, found[0].end())]
        self.assertRegex(
            value, r"\Abound\s*\.\s*wrap\s*\(\s*\w+\s*\.\s*recognizer\s*\)\s*\Z",
            "ownership pin: recognizer must come from bound.wrap(<loader result>.recognizer)")
        self.assertNotRegex(
            body, r"\.\s*recognizer\s*\.\s*(?:decode|release)\s*\(",
            "ownership pin: performTranscribe must not decode or release the loader's own recognizer")

    # --- 14. the only clock ---------------------------------------------

    def test_the_default_deadline_holds_the_only_clock(self):
        """`delay(`, `withTimeout*(`, `sleep(` (and the JDK timed completions) occur in main
        sources only inside `afterAudio` in DecodeBound.kt, and there at least once."""
        files = self._module_code()
        self.assertIn("DecodeBound.kt", files, "clock pin: DecodeBound.kt is missing")
        for name, text in sorted(files.items()):
            if name == "DecodeBound.kt":
                continue
            self.assertIsNone(re.search(CLOCK, text), f"clock pin: {name} uses a clock call outside DecodeDeadline")
        code = files["DecodeBound.kt"]
        start, end = self._span(code, "afterAudio")
        outside = code[:start] + " " + code[end:]
        self.assertIsNone(
            re.search(CLOCK, outside),
            "clock pin: DecodeBound.kt uses a clock call outside DecodeDeadline.afterAudio")
        self.assertIsNotNone(
            re.search(CLOCK, code[start:end]), "clock pin: afterAudio must hold the deadline clock")

    # --- 15. no wait after expiry ---------------------------------------

    def test_expiry_returns_without_waiting_for_the_worker(self):
        """The `is Outcome.Expired ->` arm of decode() holds no `.get(`, `.join(`, `.await(` and no
        `workerDone`, and nothing in the file ever waits on `workerDone`."""
        code = self._code(BOUND)
        arms = re.findall(r"\bis\s+Outcome\s*\.\s*Expired\s*->", self._body(code, "decode"))
        self.assertEqual(len(arms), 1, "expiry pin: decode() needs one `is Outcome.Expired ->` arm")
        arm = self._branch(self._body(code, "decode"), r"\bis\s+Outcome\s*\.\s*Expired\s*->")
        self.assertTrue(arm.strip(), "expiry pin: the Expired arm is empty")
        self.assertNotRegex(
            arm, r"\.\s*(?:get|join|await)\s*\(|\bworkerDone\b",
            "expiry pin: the slot thread must not wait for the worker after the deadline")
        self.assertNotRegex(
            code, r"\bworkerDone\s*\.\s*(?:get|join|await|getNow)\b",
            "expiry pin: nothing may wait on workerDone")

    # --- 15b. what "abandoned and still running" means -------------------

    def test_abandoned_is_expired_and_worker_still_running(self):
        """The returned value of the `abandonedRunning` getter in DecodeBound.kt, with the getter's
        own `val` locals written back in, is a conjunction (no `||`, no leading `!`) that holds the
        done check on `outcome`, `is Outcome.Expired`, and `!<handle>.workerDone.isDone`. A decode
        that finished normally while its worker is still in its last statements is not busy."""
        code = self._code(BOUND)
        head = list(re.finditer(r"\bval\s+abandonedRunning\s*:\s*Boolean\s*get\s*\(\s*\)\s*([={])", code))
        self.assertEqual(len(head), 1, f"abandoned pin: `val abandonedRunning` with a getter must be declared once (found {len(head)})")
        base = _base()[0]
        at = head[0].end()
        if head[0].group(1) == "=":
            body = "return " + code[at:base._expression_end(code, at)]
        else:
            body = code[at:base._close_of(code, at - 1)]
        returns = list(re.finditer(r"\breturn\b(?!@)", body))
        self.assertTrue(returns, "abandoned pin: the getter must return a value")
        last = returns[-1].end()
        value = body[last:base._expression_end(body, last)]
        locals_ = {m.group(1): body[m.end():base._expression_end(body, m.end())]
                   for m in re.finditer(r"\bval\s+(\w+)\s*(?::[^=\n]*)?=\s*", body)}
        locals_ = {k: v for k, v in locals_.items() if not re.search(r"\breturn\b", v)}
        for _ in range(6):
            value = re.sub(r"\b(\w+)\b", lambda w: "(" + locals_[w.group(1)] + ")"
                           if w.group(1) in locals_ else w.group(1), value)
        value = " ".join(value.split())
        self.assertNotIn("||", value, f"abandoned pin: the busy value must be a conjunction, not an `||` ({value})")
        self.assertNotRegex(value, r"\A\s*!", f"abandoned pin: the busy value must not be negated as a whole ({value})")
        self.assertIn("&&", value, f"abandoned pin: the busy value must join its checks with `&&` ({value})")
        self.assertRegex(value, r"\boutcome\s*\.\s*isDone\b",
                         f"abandoned pin: the outcome must be read as done before it is joined ({value})")
        self.assertRegex(value, r"(?<!!)\bis\s+Outcome\s*\.\s*Expired\b",
                         f"abandoned pin: only an Expired outcome may count as abandoned ({value})")
        self.assertRegex(value, r"!\s*\(*\s*\w+\s*\.\s*workerDone\s*\.\s*isDone\b",
                         f"abandoned pin: the worker must be checked as not finished ({value})")

    # --- 16. busy refusal -----------------------------------------------

    def test_busy_refusal_guards_both_bodies_and_nothing_else(self):
        """`if (bound.abandonedRunning) return ... ErrorMapping.decodeBusy()` sits in the
        transcribe body and in the transcribeAsync body, inside the dispatched block, after the
        reentrancy check and before the marker is set; preload holds the same guard once (its
        position is pinned in test_preload_is_refused_as_busy_before_it_loads); diagnostics and the
        rest of the engine never read `bound.abandonedRunning` (four reads in all, the fourth is the test hook)."""
        code = self._code(ENGINE)
        for fun, anchor in (("transcribe", r"\brunBlocking\s*\(\s*inferenceDispatcher\s*\)"),
                            ("transcribeAsync", r"\bscope\s*\.\s*async\b")):
            body = self._body(code, fun)
            guard = list(re.finditer(BUSY, body))
            self.assertEqual(len(guard), 1, f"busy pin: {fun} must hold the busy guard once (found {len(guard)})")
            where = guard[0].start()
            top = re.search(anchor, body)
            self.assertIsNotNone(top, f"busy pin: {fun} lost its dispatcher bridge")
            self.assertGreater(where, top.start(), f"busy pin: the guard in {fun} must be inside the dispatched body")
            marker = re.search(r"\bdecodingHere\s*\.\s*set\s*\(\s*true\s*\)", body)
            self.assertIsNotNone(marker, f"busy pin: {fun} must set the marker")
            self.assertLess(where, marker.start(), f"busy pin: the guard in {fun} must come before the marker is set")
            checks = [m.start() for m in re.finditer(r"\bdecodingHere\s*\.\s*get\s*\(\s*\)", body[:marker.start()])]
            self.assertTrue(checks and checks[-1] < where,
                            f"busy pin: the guard in {fun} must come after the reentrancy check")
        # preload is refused as busy while an abandoned decode runs, so that no second recognizer is created
        # next to the stuck one. It used to be allowed; the contract changed on purpose.
        self.assertEqual(
            len(re.findall(BUSY, self._body(code, "preload"))), 1,
            "busy pin: preload must hold the busy guard once, it is refused while an abandoned decode runs")
        for fun in ("diagnostics",):
            self.assertNotRegex(
                self._body(code, fun), r"\babandonedRunning\b|\bdecodeBusy\b",
                f"busy pin: {fun} must not be refused while an abandoned decode runs")
        reads = re.findall(r"\bbound\s*\.\s*abandonedRunning\b", code)
        self.assertEqual(len(reads), 4, f"busy pin: bound.abandonedRunning is read {len(reads)} times, expected 4")

    # --- 16b. preload is refused while an abandoned decode runs -----------

    def test_preload_is_refused_as_busy_before_it_loads(self):
        """`preload` holds `if (bound.abandonedRunning) return ... ErrorMapping.decodeBusy()` once,
        inside the dispatched block, after the reentrancy check, before the marker is set and
        before `loader.load(`, so an abandoned decode never gets a second recognizer next to it."""
        body = self._body(self._code(ENGINE), "preload")
        guard = list(re.finditer(BUSY, body))
        self.assertEqual(len(guard), 1, f"preload busy pin: preload must hold the busy guard once (found {len(guard)})")
        where = guard[0].start()
        top = re.search(r"\brunBlocking\s*\(\s*inferenceDispatcher\s*\)", body)
        self.assertIsNotNone(top, "preload busy pin: preload lost its dispatcher bridge")
        self.assertGreater(where, top.start(), "preload busy pin: the guard must be inside the dispatched body")
        load = re.search(r"\bloader\s*\.\s*load\s*\(", body)
        self.assertIsNotNone(load, "preload busy pin: preload must load through the loader")
        self.assertLess(where, load.start(), "preload busy pin: the guard must come before the loader is asked for a model")
        marker = re.search(r"\bdecodingHere\s*\.\s*set\s*\(\s*true\s*\)", body)
        self.assertIsNotNone(marker, "preload busy pin: preload must set the marker")
        self.assertLess(where, marker.start(), "preload busy pin: the guard must come before the marker is set")
        checks = [m.start() for m in re.finditer(r"\bdecodingHere\s*\.\s*get\s*\(\s*\)", body[:marker.start()])]
        self.assertTrue(checks and checks[-1] < where,
                        "preload busy pin: the guard must come after the reentrancy check")

    # --- 17. the worker never takes the slot ----------------------------

    def test_the_worker_is_dispatched_on_the_workers_dispatcher_not_the_slot(self):
        """DecodeBound.kt names neither `inferenceDispatcher` nor `singleSlot`, dispatches on
        `workers`, and the engine builds it as `DecodeBound(decodeDeadline, decodeWorkers)`."""
        bound = self._code(BOUND)
        self.assertNotRegex(bound, r"\b(?:inferenceDispatcher|singleSlot)\b",
                            "slot pin: DecodeBound.kt must not reach the engine's slot")
        self.assertEqual(len(re.findall(r"\bworkers\s*\.\s*dispatch\s*\(", bound)), 1,
                         "slot pin: the worker must be dispatched once, on `workers`")
        engine = self._code(ENGINE)
        made = list(re.finditer(r"\bDecodeBound\s*\(", engine))
        self.assertEqual(len(made), 1, f"slot pin: the engine must build DecodeBound once (found {len(made)})")
        args = _base()[0]._top_level_parts(
            engine[made[0].end():_base()[0]._close_of(engine, made[0].end() - 1)])
        self.assertEqual([a.strip() for a in args], ["decodeDeadline", "decodeWorkers"],
                         "slot pin: DecodeBound must be built from decodeDeadline and decodeWorkers")

    # --- 10b. thread primitives in DecodeBound.kt ------------------------

    def test_decode_bound_uses_one_thread_call_and_no_other_primitive(self):
        """DecodeBound.kt has no `Thread` except one `Thread.currentThread().interrupt()`, no
        `Atomic*`, `synchronized`, `CountDownLatch`; `CompletableFuture` lives only there."""
        files = self._module_code()
        code = files["DecodeBound.kt"]
        threads = re.findall(r"\bThread\b", code)
        exact = re.findall(r"\bThread\s*\.\s*currentThread\s*\(\s*\)\s*\.\s*interrupt\s*\(\s*\)", code)
        self.assertEqual(len(exact), 1, "thread pin: DecodeBound.kt must re-set the interrupt flag exactly once")
        self.assertEqual(len(threads), 1, "thread pin: DecodeBound.kt uses a Thread other than the interrupt call")
        self.assertNotRegex(code, r"\b(?:CountDownLatch|Atomic\w*|[Ss]ynchronized)\b",
                            "thread pin: DecodeBound.kt uses a primitive the module does not allow")
        self.assertIn("CompletableFuture", code, "thread pin: DecodeBound.kt must hold the JDK future")
        for name, text in sorted(files.items()):
            if name != "DecodeBound.kt":
                self.assertNotRegex(text, r"\bCompletableFuture\b",
                                    f"thread pin: {name} uses CompletableFuture; only DecodeBound.kt may")
