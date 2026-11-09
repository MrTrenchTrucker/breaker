"""Source pins for the sync wiring of `android/app` (BreakerApp.kt).

Not a `test_*.py` file on purpose: the contract-file bijection counts every
`tests/contract/test_*.py` as one module's contract test
(`contract_support.py:478-512`, `test_contract_tests_are_in_bijection_with_the_registry`).
The class below is collected through `test_app_contract.py`, which imports it,
so the card's one contract test file still runs these pins.

Every pin reads CODE only, through a Kotlin comment scanner: comments (KDoc
included) are removed and string literals are reduced to a marker, so a KDoc
mention cannot satisfy a pin. Identifiers are matched as whole substrings of
the scanned text. The scanner (`_scan_kotlin`) and the bracket helper
(`_close_of`) are local copies of the ones in `test_stt_ondevice_contract.py`,
so this file is self-contained and borrows nothing from a sibling module.
"""
import os
import re
import unittest

import contract_support


def _scan_kotlin(text, keep_strings):
    """Return `text` without comments (line, block, nested block).

    String and character literals are skipped as units, so `//` inside a URL
    does not start a comment. With keep_strings the literals stay as written;
    without it each string literal becomes `"x"` when it holds any non-blank
    character and `""` otherwise.
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


BREAKER_APP = (
    "android/app/src/main/kotlin/dev/breaker/dictation/BreakerApp.kt"
)


class AppSyncWiringPinsTest(unittest.TestCase):

    def _code(self):
        path = os.path.join(contract_support.ROOT, BREAKER_APP)
        self.assertTrue(os.path.isfile(path), f"missing: {BREAKER_APP}")
        with open(path, "r", encoding="utf-8") as fh:
            return _scan_kotlin(fh.read(), False)

    def test_history_is_wrapped_in_the_sync_decorator(self):
        """BreakerApp wraps the real history store in SyncingHistoryStore so both save callers enqueue after a save."""
        code = self._code()
        self.assertIn(
            "SyncingHistoryStore(inner = history, syncService = syncService, worker = syncWorker)",
            code,
            "wiring pin: BreakerApp.kt must build the history as "
            "SyncingHistoryStore(inner = history, syncService = syncService, worker = syncWorker)",
        )

    def test_getter_hands_out_the_decorated_store(self):
        """The public historyStore getter returns the decorated store, never the raw SqliteHistoryStore."""
        code = self._code()
        self.assertIn(
            "val historyStore: HistoryStore get() = decoratedHistoryStore",
            code,
            "wiring pin: the historyStore getter must hand out decoratedHistoryStore",
        )

    def test_worker_is_built_with_the_live_pending_count(self):
        """The worker sees live rows only: the queue count less the parked rows (ADR-038).

        A queue holding only parked rows must read 0, otherwise the worker arms a
        15-minute retry for ever for rows the server refused for good.
        """
        code = self._code()
        self.assertIn(
            "pendingCount = { syncService.pendingCount() - syncService.parkedCount() }",
            code,
            "wiring pin: SyncWorker must be built with the live pending count "
            "(`pendingCount = { syncService.pendingCount() - syncService.parkedCount() }`)",
        )
        self.assertNotIn(
            "pendingCount = { syncService.pendingCount() }",
            code,
            "wiring pin: the worker must not count parked rows as pending",
        )

    def test_on_start_is_called_exactly_once(self):
        """syncWorker.onStart() starts the drain at app start and must appear exactly once."""
        code = self._code()
        self.assertEqual(
            code.count("syncWorker.onStart()"),
            1,
            "wiring pin: syncWorker.onStart() must be called exactly once",
        )

    def test_stop_is_called_in_on_terminate(self):
        """syncWorker.stop() must be inside the onTerminate override so the worker stops cleanly with the app."""
        code = self._code()
        match = re.search(r"\boverride\s+fun\s+onTerminate\s*\(", code)
        self.assertIsNotNone(match, "wiring pin: the onTerminate override is missing")
        close = _close_of(code, match.end() - 1)
        self.assertGreater(close, 0, "wiring pin: onTerminate has an unbalanced parameter list")
        brace = code.index("{", close + 1)
        body = code[brace + 1:_close_of(code, brace)]
        self.assertIn(
            "syncWorker.stop()",
            body,
            "wiring pin: syncWorker.stop() must be inside the onTerminate override",
        )


if __name__ == "__main__":
    unittest.main()
