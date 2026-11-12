#!/usr/bin/env python3
"""Assemble a temp tree mirroring the web-fe build output and run node --test.

This is the single entry point for running the web-fe node tests after the
web-libs move. It:

  1. Copies server/modules/web-fe/src/main/resources/web and
     server/modules/web-fe/src/test/js into a temp dir at their original
     relative positions (main/resources/web/, test/js/).
  2. Copies each noble sub-module's files from shared/modules/web-libs/
     <lib>/ into <temp>/main/resources/web/js/vendor/<lib>/, excluding
     metadata files (AGENTS.md, README.md, VERSION, build.gradle.kts).
     Sub-folders are copied too (noble-curves/abstract/). The served
     noble-curves copy is rewritten by REWRITES below: four import paths
     change, and the pinned sources do not.
     libsodium is NEVER copied — it is vendored + pinned but never served
     (ADR-040 CSP has no wasm-unsafe-eval).
  3. Runs `node --test` on every test/js/*.test.mjs file (named, never the folder) in the temp tree.
  4. Prints the real test count and list of test names (before/after evidence).
  5. Removes the temp dir, also on failure.

The copy mapping (sub-module folder -> vendor/<lib>) lives in COPY_MAPPING
below. It must match web-fe's copyWebLibs task in build.gradle.kts exactly;
tests/contract/weblibs_copy_mapping.py asserts that (and that it equals this table).

Run from the repo root:

    python3 tools/assemble_weblibs_test_tree.py

Exit code = node --test's exit code.
"""

import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB_FE = os.path.join(ROOT, "server", "modules", "web-fe")
WEB_SRC = os.path.join(WEB_FE, "src", "main", "resources", "web")
TEST_SRC = os.path.join(WEB_FE, "src", "test", "js")
WEBLIBS = os.path.join(ROOT, "shared", "modules", "web-libs")

# ONE mapping table: sub-module folder -> vendor/<lib> destination.
# Must match web-fe/build.gradle.kts copyWebLibs task exactly. libsodium is
# intentionally absent — it is never copied to any served path.
COPY_MAPPING = [
    ("noble-ciphers", "noble-ciphers"),
    ("noble-hashes", "noble-hashes"),
    ("noble-curves", "noble-curves"),
]

METADATA = {"AGENTS.md", "README.md", "VERSION", "build.gradle.kts"}

# The served noble-curves copy: exactly these four import paths are rewritten.
# The pinned sources in shared/modules/web-libs/noble-curves/ stay unmodified.
# The twin table is nobleCurvesRewrites in server/modules/web-fe/build.gradle.kts.
REWRITE_LIB = "noble-curves"
REWRITES = [
    ("utils.js",
     "import { abytes as abytes_, anumber as anumber_, bytesToHex as bytesToHex_, concatBytes as concatBytes_, hexToBytes as hexToBytes_, isBytes as isBytes_, randomBytes as randomBytes_, } from '@noble/hashes/utils.js';",
     "import { abytes as abytes_, anumber as anumber_, bytesToHex as bytesToHex_, concatBytes as concatBytes_, hexToBytes as hexToBytes_, isBytes as isBytes_, randomBytes as randomBytes_, } from '../noble-hashes/utils.js';"),
    ("ed25519.js",
     "import { sha512 } from '@noble/hashes/sha2.js';",
     "import { sha512 } from '../noble-hashes/sha2.js';"),
    ("ed25519.js",
     "import { abytes, concatBytes, hexToBytes } from '@noble/hashes/utils.js';",
     "import { abytes, concatBytes, hexToBytes } from '../noble-hashes/utils.js';"),
    ("abstract/frost.js",
     "import { utf8ToBytes } from '@noble/hashes/utils.js';",
     "import { utf8ToBytes } from '../../noble-hashes/utils.js';"),
]


def apply_rewrites(texts):
    """Return a NEW dict: texts with every REWRITES `before` replaced by its `after`.

    texts maps rel_path -> str (decoded UTF-8, newlines untouched). Each `before`
    must occur exactly once in its file, else ValueError names the file and the
    count. A file named by no tuple comes back unchanged. No file access.
    """
    out = dict(texts)
    for rel_path, before, after in REWRITES:
        text = out.get(rel_path, "")
        count = text.count(before)
        if count != 1:
            raise ValueError("%s: rewrite line found %d times, expected 1" % (rel_path, count))
        out[rel_path] = text.replace(before, after)
    return out


def _read_text(path):
    with open(path, "rb") as fh:
        return fh.read().decode("utf-8")


def _write_text(path, text):
    with open(path, "wb") as fh:
        fh.write(text.encode("utf-8"))


def _vendor_files(sub_module):
    """Relative paths (forward slashes) of the files to copy from a sub-module.

    Sub-folders are walked, so noble-curves/abstract/ is copied too. Metadata
    files are skipped at any depth.
    """
    src = os.path.join(WEBLIBS, sub_module)
    files = []
    for dirpath, dirnames, filenames in os.walk(src):
        dirnames.sort()
        for name in sorted(filenames):
            if name in METADATA:
                continue
            rel = os.path.relpath(os.path.join(dirpath, name), src)
            files.append(rel.replace(os.sep, "/"))
    return sorted(files)


def assemble_and_run():
    tmp = tempfile.mkdtemp(prefix="weblibs-node-test-")
    try:
        # 1. Copy web + test/js at original relative positions.
        web_dst = os.path.join(tmp, "main", "resources", "web")
        test_dst = os.path.join(tmp, "test", "js")
        shutil.copytree(WEB_SRC, web_dst)
        shutil.copytree(TEST_SRC, test_dst)

        # 2. Copy noble files into vendor/<lib>/ (never libsodium). The served
        #    REWRITE_LIB files get the REWRITES table applied, byte-exact text.
        for sub_module, vendor_lib in COPY_MAPPING:
            vendor_dst = os.path.join(web_dst, "js", "vendor", vendor_lib)
            served = {}
            if sub_module == REWRITE_LIB:
                named = sorted({p for p, _before, _after in REWRITES})
                served = apply_rewrites(
                    {p: _read_text(os.path.join(WEBLIBS, sub_module, *p.split("/")))
                     for p in named}
                )
            for rel in _vendor_files(sub_module):
                src = os.path.join(WEBLIBS, sub_module, *rel.split("/"))
                dst = os.path.join(vendor_dst, *rel.split("/"))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                if rel in served:
                    _write_text(dst, served[rel])
                else:
                    shutil.copy2(src, dst)

        # 3. Run node --test there.
        # Explicit file names: newer node versions run nothing when given a folder.
        test_files = sorted(
            os.path.join("test", "js", name)
            for name in os.listdir(test_dst)
            if name.endswith(".test.mjs")
        )
        result = subprocess.run(
            ["node", "--test"] + test_files,
            cwd=tmp,
            capture_output=False,
        )

        # 4. Print summary for before/after evidence.
        print(f"\n=== web-libs node test assembly ===")
        print(f"temp tree: {tmp}")
        print(f"copied libs: {', '.join(v for _, v in COPY_MAPPING)}")
        print(f"libsodium copied: NO (never served)")
        print(f"node --test exit code: {result.returncode}")
        print(f"=== end ===\n")

        return result.returncode
    finally:
        # 5. Remove temp dir, also on failure.
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(assemble_and_run())
