#!/usr/bin/env python3
"""Tests for tools/assemble_weblibs_test_tree.py - T20, T21, T27 mutant killers.

T20: assemble_and_run must propagate node's exit code (mutant returns 0).
T21: temp tree must be removed after assemble_and_run returns (mutant skips rmtree).
T27: _read_text/apply_rewrites/_write_text must preserve multibyte bytes
     (mutant decodes/encodes latin-1, corrupting or erroring on non-ASCII).

Run: python3 -m unittest tests.unit.tools.test_assemble_weblibs -v
"""

import importlib.util
import os
import shutil
import tempfile
import unittest
from unittest.mock import patch, MagicMock

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
HELPER_PATH = os.path.join(ROOT, "tools", "assemble_weblibs_test_tree.py")

_spec = importlib.util.spec_from_file_location(
    "assemble_weblibs_test_tree_under_test", HELPER_PATH
)
helper = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(helper)


class T20ExitCodePropagated(unittest.TestCase):
    """T20: `return result.returncode` -> `return 0`.

    CI (checks.yml line 105) judges the node tier by this exit code, so a
    red node test would show green on GitHub if the exit code is swallowed.
    """

    def test_assemble_and_run_returns_1_when_node_fails(self):
        """subprocess.run faked to return returncode=1 -> code is 1 (not 0)."""
        fake_result = MagicMock()
        fake_result.returncode = 1
        with patch.object(helper.subprocess, "run", return_value=fake_result):
            code = helper.assemble_and_run()
        self.assertEqual(
            code, 1, "assemble_and_run must propagate node exit code 1"
        )

    def test_assemble_and_run_returns_0_when_node_passes(self):
        """subprocess.run faked to return returncode=0 -> code is 0."""
        fake_result = MagicMock()
        fake_result.returncode = 0
        with patch.object(helper.subprocess, "run", return_value=fake_result):
            code = helper.assemble_and_run()
        self.assertEqual(
            code, 0, "assemble_and_run must return 0 when node passes"
        )


class T21TempTreeRemoved(unittest.TestCase):
    """T21: `shutil.rmtree(tmp, ignore_errors=True)` -> `pass`.

    Mutant leaves the temp tree behind; tip removes it.
    """

    def test_temp_tree_removed_after_success(self):
        """After assemble_and_run returns 0, the temp dir does not exist."""
        real_tmp = tempfile.mkdtemp(prefix="t21-tip-success-")
        try:
            with patch.object(helper.tempfile, "mkdtemp", return_value=real_tmp):
                fake_result = MagicMock()
                fake_result.returncode = 0
                with patch.object(
                    helper.subprocess, "run", return_value=fake_result
                ):
                    code = helper.assemble_and_run()
            self.assertEqual(code, 0)
            self.assertFalse(
                os.path.exists(real_tmp),
                "temp tree must be removed after successful run",
            )
        finally:
            if os.path.exists(real_tmp):
                shutil.rmtree(real_tmp, ignore_errors=True)

    def test_temp_tree_removed_after_failure(self):
        """After assemble_and_run returns 1, the temp dir does not exist."""
        real_tmp = tempfile.mkdtemp(prefix="t21-tip-failure-")
        try:
            with patch.object(helper.tempfile, "mkdtemp", return_value=real_tmp):
                fake_result = MagicMock()
                fake_result.returncode = 1
                with patch.object(
                    helper.subprocess, "run", return_value=fake_result
                ):
                    code = helper.assemble_and_run()
            self.assertEqual(code, 1)
            self.assertFalse(
                os.path.exists(real_tmp),
                "temp tree must be removed after failed run",
            )
        finally:
            if os.path.exists(real_tmp):
                shutil.rmtree(real_tmp, ignore_errors=True)


class T27Utf8RoundTrip(unittest.TestCase):
    """T27: `decode("utf-8")` -> `decode("latin-1")`, and the same on the write side.

    A served file with a non-ASCII character must come out byte-identical apart
    from the four REWRITES. The expected bytes are built here from the REWRITES
    table, not from the helper.
    """

    def test_rewrites_preserve_multibyte_bytes(self):
        """_read_text + apply_rewrites + _write_text keep every multibyte byte."""
        multibyte = "caf\u00e9 \u20ac \u4e2d\u6587"
        carrier = "".join(before + "\n" for _rel, before, _after in helper.REWRITES) + multibyte + "\n"
        expected = carrier
        for rel, before, after in helper.REWRITES:
            if rel == "ed25519.js":
                expected = expected.replace(before, after, 1)

        with tempfile.TemporaryDirectory() as folder:
            source = os.path.join(folder, "in.js")
            target = os.path.join(folder, "out.js")
            with open(source, "wb") as fh:
                fh.write(carrier.encode("utf-8"))

            text = helper._read_text(source)
            served = helper.apply_rewrites({
                "utils.js": text,
                "ed25519.js": text,
                "abstract/frost.js": text,
            })
            helper._write_text(target, served["ed25519.js"])

            with open(target, "rb") as fh:
                written = fh.read()

        self.assertEqual(written, expected.encode("utf-8"))
        self.assertIn(multibyte.encode("utf-8"), written)


class T28CopyMappingPinned(unittest.TestCase):
    """T28: COPY_MAPPING pair names are pinned literally - a changed pair name is red.

    Expected values are written out literally, never read from the module's own table:
    a test that reads the table it checks can never be red.
    """

    def test_copy_mapping_pair_names_are_exactly(self):
        expected = [
            ("noble-ciphers", "noble-ciphers"),
            ("noble-hashes", "noble-hashes"),
            ("noble-curves", "noble-curves"),
        ]
        self.assertEqual(helper.COPY_MAPPING, expected)


class T29MetadataPinned(unittest.TestCase):
    """T29: METADATA set is pinned literally - a changed entry is red."""

    def test_metadata_set_is_exactly(self):
        expected = {"AGENTS.md", "README.md", "VERSION", "build.gradle.kts"}
        self.assertEqual(helper.METADATA, expected)


class T30RewriteTargetPinned(unittest.TestCase):
    """T30: REWRITES `after` target paths are pinned literally - a changed target is red."""

    def test_rewrite_after_values_are_exactly(self):
        expected = [
            "import { abytes as abytes_, anumber as anumber_, bytesToHex as bytesToHex_, concatBytes as concatBytes_, hexToBytes as hexToBytes_, isBytes as isBytes_, randomBytes as randomBytes_, } from '../noble-hashes/utils.js';",
            "import { sha512 } from '../noble-hashes/sha2.js';",
            "import { abytes, concatBytes, hexToBytes } from '../noble-hashes/utils.js';",
            "import { utf8ToBytes } from '../../noble-hashes/utils.js';",
        ]
        actual = [after for _rel, _before, after in helper.REWRITES]
        self.assertEqual(actual, expected)


if __name__ == "__main__":
    unittest.main()
