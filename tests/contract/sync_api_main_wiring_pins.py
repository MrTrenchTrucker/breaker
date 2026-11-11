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

    def _install_code_block(self):
        with open(MAIN, encoding="utf-8") as fh:
            code = _scan_kotlin(fh.read(), keep_strings=False)
        guard = "if (accountCount == 0L)"
        start = code.index(guard)
        brace = code.index("{", start)
        end = _close_of(code, brace)
        return code[start:end]

    def test_install_code_guard_is_account_count_zero_long(self):
        """install-code guard compares accountCount with 0L exactly once."""
        block = self._install_code_block()
        self.assertEqual(
            block.count("accountCount == 0L"),
            1,
            "install-code guard must compare accountCount with 0L exactly once",
        )

    def test_install_code_existed_is_captured_before_load_or_create(self):
        """existed is captured before loadOrCreate is called."""
        block = self._install_code_block()
        existed = block.index("val existed = Files.exists(")
        created = block.index("InstallCode.loadOrCreate(")
        self.assertLess(
            existed,
            created,
            "existed must be captured before loadOrCreate is called",
        )

    def test_install_code_notice_is_gated_on_not_existed(self):
        """the created-notice is gated on if (!existed) exactly once."""
        block = self._install_code_block()
        self.assertEqual(
            block.count("if (!existed)"),
            1,
            "the install-code notice must be gated on if (!existed) exactly once",
        )

    def test_install_code_load_or_create_is_inside_the_guard(self):
        """loadOrCreate is called exactly once inside the zero-account guard."""
        block = self._install_code_block()
        self.assertEqual(
            block.count("InstallCode.loadOrCreate("),
            1,
            "loadOrCreate must be called exactly once inside the zero-account guard",
        )

    def test_install_code_catch_closes_db_before_exit_process_two(self):
        """the install-code catch closes db then exits 2, each exactly once."""
        block = self._install_code_block()
        catch = "catch (e: IllegalStateException)"
        cstart = block.index(catch)
        cbrace = block.index("{", cstart)
        cend = _close_of(block, cbrace)
        catch_body = block[cbrace + 1:cend]
        self.assertEqual(
            catch_body.count("db.close()"),
            1,
            "the install-code catch must close the database exactly once",
        )
        self.assertEqual(
            catch_body.count("exitProcess(2)"),
            1,
            "the install-code catch must exit with 2 exactly once",
        )
        self.assertLess(
            catch_body.index("db.close()"),
            catch_body.index("exitProcess(2)"),
            "db.close() must run before exitProcess(2)",
        )

    def test_install_code_load_or_create_is_inside_the_try(self):
        """loadOrCreate is inside the try, not before it."""
        block = self._install_code_block()
        try_idx = block.index("try {")
        catch_idx = block.index("catch (e: IllegalStateException)")
        load_idx = block.index("InstallCode.loadOrCreate(")
        self.assertGreater(load_idx, try_idx, "loadOrCreate must be inside the try")
        self.assertLess(load_idx, catch_idx, "loadOrCreate must be inside the try, before the catch")
