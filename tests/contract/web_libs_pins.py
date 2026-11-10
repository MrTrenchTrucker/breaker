"""Pin table for the web-libs module and its four sub-modules.

Not a `test_*.py` file on purpose: the contract-file bijection counts every
`tests/contract/test_*.py` as one module's contract test. The class below is
collected through `test_web_libs_contract.py`, which imports it.

The pin table lists, for each sub-module, the exact files it holds and each
file's sha256 as taken from the upstream tarball. A test fires per row, so a
single wrong byte is caught by name.

The copy-task pin checks web-fe's copyWebLibs task: it must include exactly
the three noble sub-modules and exclude libsodium.
"""

import hashlib
import os
import re
import unittest

import contract_support

ROOT = contract_support.ROOT
WEBLIBS = os.path.join(ROOT, "shared", "modules", "web-libs")

# (sub_module, [(file, sha256_from_tarball), ...])
SUB_MODULE_FILES = [
    (
        "noble-ciphers",
        [
            ("LICENSE", "f36671a5487c9c5050efacb58011c37c24c55a889803cb036cf9d9a6347c1e2d"),
            ("_arx.js", "ead142ba410404c0698bb80e0d6f77780799bb77a9e3b098cf0f9fca1c486e37"),
            ("_poly1305.js", "5d2afd73b40dbafb7b6740c6e6388e7123c79a4e57306861b28c5734925fa84d"),
            ("_polyval.js", "735b078133e0c5a97dd984a21389f152dc7958b283f52504f6f0906ded243fa5"),
            ("aes.js", "941ebefe14c48cd1401ffe52f329d4e6ff14f3f596681b211a33cde21de3a90c"),
            ("salsa.js", "c5a3d72861d5793ab756476696b540aeffefbaebe6a219ba4fba19c0c5fd5b95"),
            ("utils.js", "a443ec1f60d1aaa25d1b7d8c102d33c0e8e6d683ada1ad17be9c32e69170ff95"),
        ],
    ),
    (
        "noble-hashes",
        [
            ("LICENSE", "4f221aee6e072336700c408c68ab3b96a3fc09f6aebe6f48f1bd99e5ef13faec"),
            ("_blake.js", "cabbff61d1f373bac45594ad758df7c42e176464ddbb94b44f5533fa977a4f56"),
            ("_md.js", "60cf3010fda89e3e4d3f0e7ff1ce249e9c467f34b4fd41b6fd6101d9f69be763"),
            ("_u64.js", "b09da8c07fe8187c07649494cdb7cd0bcf13df90b506a9473d19e4d5f8c2e102"),
            ("argon2.js", "f39753324bdf2f484150731d24b1d71236f26bf10bedd594f7d190fcdfdefdc9"),
            ("blake2.js", "03f00237bcc0b4d822a80f7c0ac22e42632c48e246c3b6da584e6452186e32f0"),
            ("hkdf.js", "ccb942a8008f974018965eeb1e33b8f4739bf767c6f95f2ceb06f5873a302f67"),
            ("hmac.js", "fec9f8f3aeda785ea2de91cc4c059d54ef98196261c026c43f48341007ae52c9"),
            ("sha2.js", "471746bba6ec4c6238ca41358d1d3b40b6ff31cf3363f0b4d550c649c1a8e83b"),
            ("utils.js", "037ad49adb78168b6b699598fd33f85f7877456b1d28b4d032e2a1a16807947c"),
        ],
    ),
    (
        "noble-curves",
        [
            ("LICENSE", "4f221aee6e072336700c408c68ab3b96a3fc09f6aebe6f48f1bd99e5ef13faec"),
            ("ed25519.js", "e13f6c50c36feb0d18bf7986d5f881bcefb5f1f5b16de3cc5ee7693254c7a0b5"),
            ("utils.js", "be9ff86aef76419376e4d66cf01a134f3117f1f8dd62d81f1b79100145503367"),
            ("abstract/curve.js", "dbaeee3b41ff47efb76b78e14170fe4dda7c7ecdc387c402f16c5118e0bac356"),
            ("abstract/edwards.js", "f24a9c221a549a8fff259eb8f245ab0dc17679b13c532c3970d91acc4df9fffd"),
            ("abstract/fft.js", "a4b2ff7ca33f4acc85d61f0e83ec3303c3f6b38ecc19b20d0f6462d820e518cf"),
            ("abstract/frost.js", "9a95f1fdf7e17b9d93a16049db675ee2967ed2a04cf59b1bd981d4662acbedf5"),
            ("abstract/hash-to-curve.js", "e3c97e7d0827b728b14e3311ed11d8e386d8a70d8fc77e0b9fd2a3090f19d955"),
            ("abstract/modular.js", "9ced3aa10598277a735e54f7b61d88e929d565da884ba5385f9006ebb3b6aab2"),
            ("abstract/montgomery.js", "cdafa8816dad5a24475ec51952c5f71fdd5d1b46880ab982694c4f8ff605fc46"),
            ("abstract/oprf.js", "2ca36b3e4930092db1f91559410e86975816c036e248067bc65a8055aa1c504c"),
        ],
    ),
    (
        "libsodium",
        [
            ("LICENSE", "ce7b8ba14db085aadb72359226ccf7273225db31dad653242a7726a10dabbbd4"),
            ("libsodium-sumo.mjs", "4c94708f7e78eac7a32b29e2ce0ff96f4bd599d78c129f27bf8a061e20776c8c"),
            ("libsodium-wrappers.mjs", "40de1ef7cb8f2caae02c2a8f04809151904fa28cb9364cadedc57af89a6c9284"),
        ],
    ),
]

LIBSODIUM_IMPORT = 'from"libsodium-sumo"'


class WebLibsPinsTest(unittest.TestCase):
    """Pin table rows: one test per (sub-module, file) pair, plus folder
    exactness, VERSION vs tarball sha256, and the copy-task pin."""

    def _sub_dir(self, sub_module):
        path = os.path.join(WEBLIBS, sub_module)
        self.assertTrue(os.path.isdir(path), f"missing sub-module dir: {path}")
        return path

    def _read_bytes(self, rel):
        path = os.path.join(ROOT, rel)
        self.assertTrue(os.path.isfile(path), f"missing: {rel}")
        with open(path, "rb") as fh:
            return fh.read()

    def _sha256(self, data):
        import hashlib as _h

        return _h.sha256(data).hexdigest()

    # ── one test per file: sha256 matches the tarball ─────────────────

    def test_every_file_matches_tarball_sha256(self):
        for sub_module, files in SUB_MODULE_FILES:
            for name, expected_hash in files:
                rel = os.path.join("shared", "modules", "web-libs", sub_module, name)
                data = self._read_bytes(rel)
                actual = self._sha256(data)
                self.assertEqual(
                    actual,
                    expected_hash,
                    f"{rel} sha256 mismatch: got {actual}, expected {expected_hash}",
                )

    # ── folder exactness: nothing extra, nothing missing ──────────────

    def test_each_sub_module_holds_exactly_its_files(self):
        expected_names = set()
        for sub_module, files in SUB_MODULE_FILES:
            for name, _expected_hash in files:
                expected_names.add((sub_module, name))

        seen = set()
        for sub_module, _files in SUB_MODULE_FILES:
            sub_dir = self._sub_dir(sub_module)
            for dirpath, dirnames, filenames in os.walk(sub_dir):
                dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle")]
                for entry in sorted(filenames):
                    full = os.path.join(dirpath, entry)
                    rel = os.path.join(sub_module, os.path.relpath(full, sub_dir))
                    # Metadata files (card, readme, version, build) live beside
                    # the vendored files but are not part of the vendored file set.
                    if os.path.basename(rel) in ("AGENTS.md", "README.md", "VERSION", "build.gradle.kts"):
                        continue
                    seen.add(rel)
                    self.assertIn(
                        rel,
                        {os.path.join(sm, n) for sm, n in expected_names},
                        f"unexpected file in web-libs: {sub_module}/{rel}",
                    )

        missing = {
            os.path.join(sm, n) for sm, n in expected_names
        } - seen
        self.assertFalse(
            missing, f"web-libs sub-module missing file(s): {sorted(missing)}"
        )

    # ── VERSION: name + version + tarball sha256 match the cards ──────

    def test_version_records_name_and_tarball_sha256(self):
        expected = {
            "noble-ciphers": {
                "name": "shared_web_libs_noble_ciphers",
                "version": "2.4.0",
                "tarball": "ciphers-2.4.0.tgz",
                "tarball_sha256": "4a398bcc280c8742d474f9162f6d3c153e52ab1078e8ab592b4720410fb3c3e9",
                "licence": "MIT",
            },
            "noble-hashes": {
                "name": "shared_web_libs_noble_hashes",
                "version": "2.4.0",
                "tarball": "hashes-2.4.0.tgz",
                "tarball_sha256": "e1946149b780017b2564fcc092cb01c04e1d7f20627d0296c17da8868f4436dc",
                "licence": "MIT",
            },
            "noble-curves": {
                "name": "shared_web_libs_noble_curves",
                "version": "2.4.0",
                "tarball": "curves-2.4.0.tgz",
                "tarball_sha256": "55279c71b0201d6c0e58d0206d15825359e02a3932f326ba7b7909ac2d334aa9",
                "licence": "MIT",
            },
            "libsodium": {
                "name": "shared_web_libs_libsodium",
                "version": "0.8.4",
                "tarball_1": "libsodium-sumo-0.8.4.tgz",
                "tarball_1_sha256": "c51e7573c7b048acd07ec7b8677640208e9c00e0ab11416d3895bf6e299e5f7e",
                "tarball_2": "libsodium-wrappers-sumo-0.8.4.tgz",
                "tarball_2_sha256": "858ad2910255d9b6c3527d6b7e8bcfcd98917a495ad2c97775bf2bfede13dfaf",
                "licence": "ISC",
            },
        }
        for sub_module, fields in expected.items():
            version_path = os.path.join(WEBLIBS, sub_module, "VERSION")
            text = self._read_bytes(
                os.path.join("shared", "modules", "web-libs", sub_module, "VERSION")
            ).decode("utf-8")
            for key, value in fields.items():
                self.assertIn(
                    f"{key}: {value}",
                    text,
                    f"{sub_module}/VERSION missing '{key}: {value}'",
                )

    # ── VERSION version == AGENTS.md == README.md version ──
    def test_version_matches_card_and_readme(self):
        expected_version = {
            "noble-ciphers": "2.4.0",
            "noble-hashes": "2.4.0",
            "noble-curves": "2.4.0",
            "libsodium": "0.8.4",
        }
        for sub_module, version in expected_version.items():
            base = os.path.join(WEBLIBS, sub_module)
            for fname in ("AGENTS.md", "README.md"):
                fpath = os.path.join(base, fname)
                self.assertTrue(os.path.isfile(fpath), f"missing: {fpath}")
                content = self._read_bytes(fpath).decode("utf-8")
                self.assertIn(
                    version, content, f"{sub_module}/{fname} missing version {version}"
                )
            vpath = os.path.join(base, "VERSION")
            self.assertTrue(os.path.isfile(vpath), f"missing: {vpath}")
            vtext = self._read_bytes(vpath).decode("utf-8")
            self.assertIn(
                f"version: {version}", vtext, f"{sub_module}/VERSION missing version {version}"
            )

    # ── libsodium pair: wrappers-sumo imports sumo by bare specifier ──

    def test_libsodium_wrappers_imports_sumo_by_bare_name(self):
        data = self._read_bytes(
            os.path.join("shared", "modules", "web-libs", "libsodium", "libsodium-wrappers.mjs")
        ).decode("utf-8")
        self.assertIn(LIBSODIUM_IMPORT, data, "wrappers.mjs does not import libsodium-sumo")

    # ── copy task pin: include noble, exclude libsodium ───────────────

    def test_copy_task_includes_noble_excludes_libsodium(self):
        text = self._read_bytes(
            os.path.join("server", "modules", "web-fe", "build.gradle.kts")
        ).decode("utf-8")
        self.assertIn("copyWebLibs", text, "web-fe has no copyWebLibs Copy task")
        self.assertIn(
            'listOf("noble-ciphers", "noble-hashes", "noble-curves")',
            text,
            "copy task does not list the three noble sub-modules",
        )
        self.assertIn(
            "from(webLibs.dir(lib))",
            text,
            "copy task does not iterate from(webLibs.dir(lib))",
        )
        self.assertIn(
            'exclude("VERSION", "README.md", "AGENTS.md", "build.gradle.kts")',
            text,
            "copy task does not exclude metadata",
        )
        self.assertIn(
            'tasks.named("assemble")',
            text,
            "copy task is not hooked to assemble",
        )
        self.assertIn(
            "dependsOn(copyWebLibs)",
            text,
            "assemble does not depend on copyWebLibs",
        )
        # libsodium may appear only inside comments (the header comment names it)
        lines = [line for line in text.splitlines() if not line.strip().startswith("//")]
        body = "\n".join(lines)
        self.assertNotIn(
            "libsodium",
            body,
            "copy task body mentions libsodium (must never copy libsodium)",
        )

    # ── NO agent names / RC ids / hosts anywhere under web-libs ───────

    def test_no_work_item_ids_in_web_libs_text(self):
        """No work-item id (a dash and six hex digits after the two letters R and C) in any file of the module."""
        work_item = re.compile(r"\bRC-[0-9A-Fa-f]{6}\b")
        for dirpath, dirnames, filenames in os.walk(WEBLIBS):
            dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle")]
            for entry in sorted(filenames):
                full = os.path.join(dirpath, entry)
                with open(full, "rb") as fh:
                    text = fh.read().decode("utf-8", errors="replace")
                hit = work_item.search(text)
                self.assertIsNone(hit, f"{os.path.relpath(full, ROOT)} contains a work-item id: {hit and hit.group(0)}")
