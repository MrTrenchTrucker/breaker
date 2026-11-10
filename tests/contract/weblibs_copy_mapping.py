"""Pin test: web-fe's copyWebLibs task maps exactly what tools/assemble_weblibs_test_tree.py maps,
and libsodium is in neither.

The helper's COPY_MAPPING and METADATA tables are the one source. This pin reads
server/modules/web-fe/build.gradle.kts through the Kotlin comment scanner of
`app_sync_wiring_pins.py` (comments removed, string literals reduced), takes the copyWebLibs task body,
and asserts: the lib names in its listOf(...) are the helper's lib names, each `from` takes
`webLibs.dir(lib)` and each is put `into(lib)`, the exclude list is the helper's METADATA set, the
destination is build/web/js/vendor, and the task is hooked to assemble. libsodium appears in neither the
helper table nor the task.

Red checks: add libsodium to the helper table -> a test fails by name; change one Gradle from-path
or the exclude list -> a test fails by name.

Not a `test_*.py` file on purpose (the contract-file bijection counts those); the class is collected
through `test_web_libs_contract.py`.
"""
import importlib.util
import os
import re
import unittest

import contract_support
from app_sync_wiring_pins import _scan_kotlin

ROOT = contract_support.ROOT
BUILD_KTS = os.path.join(ROOT, "server", "modules", "web-fe", "build.gradle.kts")
HELPER = os.path.join(ROOT, "tools", "assemble_weblibs_test_tree.py")


def _helper():
    spec = importlib.util.spec_from_file_location("assemble_weblibs_test_tree", HELPER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _build_text():
    with open(BUILD_KTS, encoding="utf-8") as fh:
        # Strings are kept: the lib names and the exclude names are string literals.
        return _scan_kotlin(fh.read(), keep_strings=True)


def _task_body(code):
    marker = 'tasks.register<Copy>("copyWebLibs")'
    start = code.find(marker)
    assert start >= 0, "build.gradle.kts has no copyWebLibs Copy task"
    open_brace = code.index("{", start)
    depth = 0
    for i in range(open_brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[open_brace:i + 1]
    raise AssertionError("copyWebLibs task body is not closed")


class WebLibsCopyMappingTest(unittest.TestCase):
    def setUp(self):
        self.helper = _helper()
        self.code = _build_text()
        self.body = _task_body(self.code)

    def test_helper_table_never_names_libsodium(self):
        names = [lib for pair in self.helper.COPY_MAPPING for lib in pair]
        self.assertEqual(
            [n for n in names if "libsodium" in n], [],
            "the helper copy table names libsodium; it must never be copied to a served path",
        )

    def test_gradle_copy_task_libs_equal_the_helper_table(self):
        m = re.search(r"listOf\(([^)]*)\)", self.body)
        self.assertIsNotNone(m, "the copy task has no listOf(...) of lib names")
        gradle = sorted(re.findall(r'"([^"]+)"', m.group(1)))
        table = sorted(src for src, dst in self.helper.COPY_MAPPING)
        self.assertEqual(gradle, table, "the Gradle lib list %r differs from the helper table %r" % (gradle, table))
        for src, dst in self.helper.COPY_MAPPING:
            self.assertEqual(src, dst, "the helper maps %s to a different vendor name %s; the Gradle loop uses one name" % (src, dst))

    def test_gradle_copy_task_takes_each_lib_from_its_folder_into_its_vendor_name(self):
        self.assertIn("from(webLibs.dir(lib))", self.body, "each lib must be taken from webLibs.dir(lib)")
        self.assertRegex(self.body, r"\binto\(lib\)", "each lib must be put into(lib)")
        self.assertIn('into(layout.buildDirectory.dir("web/js/vendor"))', self.body, "the destination must be build/web/js/vendor")
        self.assertIn(
            'val webLibs = rootProject.layout.projectDirectory.dir("shared/modules/web-libs")', self.code,
            "webLibs must be the shared/modules/web-libs folder",
        )

    def test_gradle_exclude_list_equals_the_helper_metadata_set(self):
        m = re.search(r"exclude\(([^)]*)\)", self.body)
        self.assertIsNotNone(m, "the copy task has no exclude(...)")
        gradle = sorted(re.findall(r'"([^"]+)"', m.group(1)))
        self.assertEqual(
            gradle, sorted(self.helper.METADATA),
            "the Gradle exclude list %r differs from the helper METADATA %r" % (gradle, sorted(self.helper.METADATA)),
        )

    def test_copy_task_never_names_libsodium_and_is_hooked_to_assemble(self):
        self.assertNotIn("libsodium", self.body, "the copy task names libsodium")
        self.assertRegex(
            self.code, r'tasks\.named\("assemble"\)\s*\{\s*dependsOn\(copyWebLibs\)\s*\}',
            "copyWebLibs must be hooked to assemble",
        )


if __name__ == "__main__":
    unittest.main()
