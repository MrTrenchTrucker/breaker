"""Source pins for the daily expiry scheduler (sync-api).

Not a `test_*.py` file on purpose: the contract-file bijection counts every
`tests/contract/test_*.py` as one module's contract test. The class below is
collected through `test_sync_api_contract.py`, which imports it.

Each pin reads CODE only, through the Kotlin comment scanner of
`app_sync_wiring_pins.py`: comments (KDoc included) are removed and string
literals are reduced to a marker, so a comment cannot satisfy it.
"""
import os
import unittest

import contract_support
from app_sync_wiring_pins import _close_of, _scan_kotlin

EXPIRY = os.path.join(
    contract_support.ROOT, "server", "modules", "sync-api", "src", "main", "kotlin",
    "dev", "breaker", "server", "syncapi", "transcriptions", "ExpiryScheduler.kt",
)

MAIN = os.path.join(
    contract_support.ROOT, "server", "modules", "sync-api", "src", "main", "kotlin",
    "dev", "breaker", "server", "syncapi", "SyncApiMain.kt",
)


class SyncApiExpirySchedulerPinsTest(unittest.TestCase):
    def test_expiry_scheduler_passes_only_retention_ttl(self):
        """ExpiryScheduler.kt passes only TranscriptionPurge.RETENTION_TTL_MS as ttlMs.

        Red on a deliberate break: any other literal or constant as the ttl argument
        (for example a hard-coded 7_776_000_000L spelled out, or a parameter defaulted
        to something else) fails here.
        """
        with open(EXPIRY, encoding="utf-8") as fh:
            code = _scan_kotlin(fh.read(), keep_strings=False)
        # RETENTION_TTL_MS must appear as the ttl argument exactly once, in expireBatch's call site.
        ref = "TranscriptionPurge.RETENTION_TTL_MS"
        self.assertIn(ref, code, "ExpiryScheduler.kt must reference TranscriptionPurge.RETENTION_TTL_MS")
        # The expireBatch call passes ttlMs = TranscriptionPurge.RETENTION_TTL_MS and nothing else as ttl.
        call = "expireBatch("
        self.assertEqual(code.count(call), 1, "ExpiryScheduler.kt must call expireBatch exactly once")
        start = code.index(call) + len(call) - 1
        end = _close_of(code, start)
        args = [a.strip() for a in code[start + 1:end].split(",") if a.strip()]
        self.assertIn("ttlMs = TranscriptionPurge.RETENTION_TTL_MS", args, "ttl must be RETENTION_TTL_MS")
        # No other long literal that looks like a TTL value.
        self.assertNotIn("7_776_000_000", code, "no hard-coded TTL value; use the constant")

    def test_sync_api_main_launches_scheduler_in_sweep_job(self):
        """SyncApiMain.kt launches ExpiryScheduler.runExpiryScheduler inside sweepJob.

        Red on a deliberate break: the launch line removed, so the expiry job never runs
        and never stops with the sweep job.
        """
        with open(MAIN, encoding="utf-8") as fh:
            code = _scan_kotlin(fh.read(), keep_strings=False)
        name = "ExpiryScheduler.runExpiryScheduler("
        self.assertEqual(code.count(name), 1, "SyncApiMain.kt must launch the scheduler exactly once")
        start = code.index(name) + len(name) - 1
        end = _close_of(code, start)
        args = [a.strip() for a in code[start + 1:end].split(",") if a.strip()]
        self.assertEqual(args, ["transcriptionStore", "accountStore"], "launch args: %r" % args)
        # It must be inside the sweepJob launch block: after the block opens and before it closes.
        sweep = "val sweepJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {"
        self.assertIn(sweep, code, "sweepJob launch block must exist")
        open_brace = code.index(sweep) + len(sweep) - 1
        close_brace = _close_of(code, open_brace)
        self.assertGreater(close_brace, open_brace, "the sweepJob block is not closed")
        self.assertTrue(
            open_brace < start < close_brace,
            "the scheduler launch must sit inside the sweepJob block",
        )


if __name__ == "__main__":
    unittest.main()
