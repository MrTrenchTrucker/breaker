"""Contract test for the `shared/modules/ui-tokens` module.

The structural contract this module has with the rest of the repository
(registry entry, card, Gradle wiring, declared dependencies) is inherited from
`contract_support`. This file adds the module's own behavioural contract: the
public files the registry names exist where the build compiles them, the module
stays free of platform imports and of new dependencies, and its card carries real
invariants and names test locations that exist.
"""
import os
import re
import unittest

import contract_support

# Platform and third-party namespaces the module's sources must not reach for. A
# fully qualified name is caught as well as an import.
FORBIDDEN_REFERENCE = re.compile(r"\b(android|androidx|com\.google\.android|kotlinx)\.")

# The card lists at least this many invariants.
MIN_INVARIANTS = 7

# Each invariant the card lists, by a phrase that is in it. An invariant edited away
# or reworded to nothing takes its phrase with it.
INVARIANT_PHRASES = [
    "exactly the value the table gives",
    "identical values",
    "declared twice",
    "no spacing scale",
    "at most 4",
    "4.5:1",
    "text, text-muted, primary, primary-hover, danger, warning, accent and sent",
    "no Android or Compose import",
]


def _module_files(folder):
    """Every file under the module, skipping build output."""
    for dirpath, dirnames, filenames in os.walk(folder):
        dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle")]
        for name in filenames:
            yield os.path.join(dirpath, name)


def _source_files(src):
    """Every file under src, whatever the folder is called."""
    for dirpath, _dirnames, filenames in os.walk(src):
        for name in filenames:
            yield os.path.join(dirpath, name)


def _strip_code_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def _block(text, name):
    """The body of every top-level `name { ... }` block, as a list."""
    return re.findall(rf"^{name}\s*\{{(.*?)^\}}", text, re.S | re.M)


def _statements(body):
    lines = (re.sub(r"//.*$", "", line).strip() for line in body.splitlines())
    return [line for line in lines if line]


class SharedModulesUiTokensContractTest(contract_support.ModuleContractTest):
    MODULE = "shared_ui_tokens"

    def _build_text(self):
        with open(os.path.join(self.folder, "build.gradle.kts"), encoding="utf-8") as fh:
            return fh.read()

    def test_every_public_file_the_registry_names_exists_once_and_is_not_empty(self):
        names = [n.strip() for n in self.entry["public"].split(",") if n.strip()]
        self.assertTrue(names, "the registry names no public file for shared_ui_tokens")
        files = list(_module_files(self.folder))
        for name in names:
            found = [f for f in files if os.path.basename(f) == name]
            self.assertEqual(
                len(found), 1,
                f"[module.{self.MODULE}] public file {name} must exist exactly once under "
                f"{self.path}, found {found}",
            )
            self.assertGreater(os.path.getsize(found[0]), 0, f"{found[0]} is empty")

    def test_public_files_sit_where_gradle_compiles_and_packages_them(self):
        kotlin = os.path.join(self.folder, "src", "main", "kotlin")
        resources = os.path.join(self.folder, "src", "main", "resources")
        self.assertTrue(
            any(f.startswith(kotlin + os.sep) and f.endswith("tokens.kt") for f in _module_files(self.folder)),
            "tokens.kt must live under src/main/kotlin, or Gradle never compiles it",
        )
        self.assertTrue(
            os.path.isfile(os.path.join(resources, "tokens.css")),
            "tokens.css must live under src/main/resources, or it is not in the jar",
        )

    def test_module_sources_reach_for_no_android_compose_or_kotlinx_name(self):
        offenders = []
        for f in _source_files(os.path.join(self.folder, "src")):
            if not f.endswith((".kt", ".java", ".kts")):
                continue
            with open(f, encoding="utf-8") as fh:
                code = _strip_code_comments(fh.read())
            for number, line in enumerate(code.splitlines(), 1):
                if FORBIDDEN_REFERENCE.search(line):
                    offenders.append(f"{os.path.relpath(f, self.folder)}:{number}")
        self.assertFalse(
            offenders,
            f"{self.path} is plain Kotlin/JVM with no dependency; these refer to an Android, "
            f"Compose or kotlinx name: {offenders}",
        )

    def test_build_file_adds_no_dependency_beyond_the_test_runner(self):
        text = self._build_text()
        blocks = _block(text, "dependencies")
        self.assertEqual(
            len(blocks), 1,
            f"{self.path}/build.gradle.kts has {len(blocks)} top-level dependencies blocks, expected one",
        )
        declared = _statements(blocks[0])
        self.assertEqual(
            declared, ["testImplementation(libs.junit)"],
            f"{self.path}/build.gradle.kts declares {declared}; the module's only dependency "
            f"is the JUnit test runner",
        )

    def test_build_file_applies_only_the_kotlin_jvm_and_java_library_plugins(self):
        blocks = _block(self._build_text(), "plugins")
        self.assertEqual(len(blocks), 1, f"{self.path}/build.gradle.kts has {len(blocks)} plugins blocks, expected one")
        self.assertEqual(
            _statements(blocks[0]), ["alias(libs.plugins.kotlin.jvm)", "`java-library`"],
            f"{self.path}/build.gradle.kts applies a plugin beyond kotlin-jvm and java-library",
        )

    def test_card_invariants_are_real_not_the_placeholder(self):
        card = contract_support._card_text(self.entry["card"])
        section = contract_support._card_section(card, "## Invariants")
        self.assertNotIn(
            "See the acceptance criteria above", section,
            f"{self.entry['card']} Invariants is still the generated placeholder",
        )
        bullets = re.findall(r"^- \S", section, re.M)
        self.assertGreaterEqual(
            len(bullets), MIN_INVARIANTS,
            f"{self.entry['card']} Invariants lists {len(bullets)} invariants; the module has at "
            f"least {MIN_INVARIANTS}",
        )
        flat = " ".join(section.split())
        missing = [p for p in INVARIANT_PHRASES if p not in flat]
        self.assertEqual(
            missing, [],
            f"{self.entry['card']} Invariants no longer says: {missing}",
        )

    def test_card_test_locations_name_paths_that_exist(self):
        card = contract_support._card_text(self.entry["card"])
        section = contract_support._card_section(card, "## Test Locations")
        paths = [p for p in re.findall(r"`([^`]+)`", section) if "/" in p]
        for required in ("tests/unit/shared/ui-tokens/", "shared/modules/ui-tokens/src/test/kotlin/"):
            self.assertIn(required, paths, f"{self.entry['card']} Test Locations does not name {required}")
        self.assertGreaterEqual(len(paths), 3, f"{self.entry['card']} Test Locations names {paths}")
        for rel in paths:
            self.assertTrue(
                os.path.exists(os.path.join(contract_support.ROOT, rel)),
                f"{self.entry['card']} Test Locations names {rel}, which does not exist",
            )


if __name__ == "__main__":
    unittest.main()
