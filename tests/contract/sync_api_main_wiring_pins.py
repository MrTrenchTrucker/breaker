"""Source pin for the start-up wiring of `server/modules/sync-api` (SyncApiMain.kt).

Not a `test_*.py` file on purpose, like `app_sync_wiring_pins.py`: the contract-file
bijection counts every `tests/contract/test_*.py` as one module's contract test. The class
below is collected through `test_sync_api_contract.py`, which imports it.

The pin reads CODE only, through the Kotlin comment scanner of `app_sync_wiring_pins.py`:
comments (KDoc included) are removed and string literals are reduced to a marker, so a
comment cannot satisfy it. SyncApiMain.kt has no unit test (it opens the database and the
two listeners), so a start-up that forgets to hand the configured manifest path to the
module would pass every other test and answer 404 for ever.
"""
import os
import unittest

import contract_support
from app_sync_wiring_pins import _close_of, _scan_kotlin

MAIN = os.path.join(
    contract_support.ROOT, "server", "modules", "sync-api", "src", "main", "kotlin",
    "dev", "breaker", "server", "syncapi", "SyncApiMain.kt",
)


class SyncApiMainWiringPinsTest(unittest.TestCase):
    def test_main_passes_the_configured_manifest_file_to_the_module(self):
        """syncApiModule( ... config.manifestFile ) is called once with the config's path.

        Red on a deliberate break: drop the last argument (the module then takes its
        default, an empty path, and /v1/updates/latest.json answers 404 whatever the
        environment says).
        """
        with open(MAIN, encoding="utf-8") as fh:
            code = _scan_kotlin(fh.read(), keep_strings=False)
        name = "syncApiModule("
        self.assertEqual(code.count(name), 1, "SyncApiMain.kt must call syncApiModule exactly once")
        start = code.index(name) + len(name) - 1
        end = _close_of(code, start)
        self.assertGreater(end, start, "the syncApiModule call is not closed")
        args = [a.strip() for a in code[start + 1:end].split(",") if a.strip()]
        self.assertIn(
            "config.manifestFile", args,
            "SyncApiMain.kt must pass config.manifestFile to syncApiModule; the arguments are %r" % args,
        )
