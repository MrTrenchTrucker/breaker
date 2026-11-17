"""Contract test for the `server/modules/sync-api` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code.
"""
import os
import re
import unittest

import contract_support
from sync_api_main_wiring_pins import SyncApiMainWiringPinsTest  # noqa: F401  (start-up wiring pin, collected here)
from sync_api_expiry_scheduler_pins import SyncApiExpirySchedulerPinsTest  # noqa: F401  (expiry scheduler source pins, collected here)

MAIN_SOURCE_REL = "server/modules/sync-api/src/main/kotlin/dev/breaker/server/syncapi/SyncApiMain.kt"


def _distribution_problems(build_text, source_text):
    """Empty list == the build file and the main source agree on a runnable distribution."""
    problems = []
    plugins = re.search(r"^plugins\s*\{(.*?)^\}", build_text, re.S | re.M)
    if not plugins or not re.search(r"(?m)^\s*application\s*$", plugins.group(1)):
        problems.append("the plugins block does not apply the bare `application` plugin")
    main = re.search(r'mainClass\.set\(\s*"([^"]+)"\s*\)', build_text)
    if not main:
        problems.append("no application.mainClass.set(...)")
        return problems
    package = re.search(r"(?m)^package\s+([\w.]+)\s*$", source_text)
    if not package:
        problems.append("the main source declares no package")
    else:
        stem = os.path.splitext(os.path.basename(MAIN_SOURCE_REL))[0]
        expected = package.group(1) + "." + stem + "Kt"
        if main.group(1) != expected:
            problems.append("mainClass is %r, source declares %r" % (main.group(1), expected))
    if not re.search(r"(?m)^fun\s+main\s*\(", source_text):
        problems.append("the main source has no top-level fun main(")
    if re.search(r"(?m)^@file:JvmName\(", source_text):
        problems.append("a @file:JvmName file's class is not <package>.<stem>Kt")
    return problems


class ServerModulesSyncApiContractTest(contract_support.ModuleContractTest):
    MODULE = "server_sync_api"


class SyncApiDistributionPinsTest(unittest.TestCase):
    """The module ships a runnable distribution: the build file and the main source agree on it.

    The expected entry class is derived from the source (package + file stem + `Kt`), so a renamed
    file or package turns these red; a hard-coded string that merely matches would not.
    """

    def _read(self, rel):
        with open(os.path.join(contract_support.ROOT, rel), encoding="utf-8") as fh:
            return fh.read()

    def test_the_real_build_and_source_agree(self):
        """The real build file and the real main source agree on the entry class."""
        problems = _distribution_problems(
            self._read("server/modules/sync-api/build.gradle.kts"),
            self._read(MAIN_SOURCE_REL),
        )
        self.assertEqual(problems, [], "the build file and the main source disagree: %r" % problems)

    def test_a_build_without_the_application_plugin_fails_the_check(self):
        """A build file missing the bare `application` plugin fails the check.

        Red on a deliberate break: the plugins line is removed. The sample is the real build
        file mutated in memory by exactly one edit (never a file on disk), so the
        `application { mainClass.set(...) }` block is still present and the only problem the
        control can fire on is the missing plugin.
        """
        real_build = self._read("server/modules/sync-api/build.gradle.kts")
        no_plugin = real_build.replace("    application\n", "", 1)
        self.assertNotEqual(
            no_plugin,
            real_build,
            "removing the `application` plugins line must change the sample",
        )
        problems = _distribution_problems(no_plugin, self._read(MAIN_SOURCE_REL))
        self.assertIn(
            "the plugins block does not apply the bare `application` plugin",
            problems,
            "a build file without the `application` plugin must report the plugins-block problem: %r" % problems,
        )

    def test_a_build_without_a_main_class_fails_the_check(self):
        """A build file with no `mainClass.set(...)` fails the check.

        Red on a deliberate break: the `mainClass.set(...)` line is removed. The sample is the
        real build file mutated in memory by exactly one edit (never a file on disk), so the
        `application` plugin is still present and the only problem the control can fire on is
        the missing mainClass.
        """
        real_build = self._read("server/modules/sync-api/build.gradle.kts")
        no_main_class = real_build.replace(
            '    mainClass.set("dev.breaker.server.syncapi.SyncApiMainKt")\n', "", 1
        )
        self.assertNotEqual(
            no_main_class,
            real_build,
            "removing the `mainClass.set(...)` line must change the sample",
        )
        problems = _distribution_problems(no_main_class, self._read(MAIN_SOURCE_REL))
        self.assertIn(
            "no application.mainClass.set(...)",
            problems,
            "a build file with no mainClass must report the missing-mainClass problem: %r" % problems,
        )

    def test_a_wrong_main_class_fails_the_check(self):
        """A build file whose mainClass disagrees with the source fails the check.

        Red on a deliberate break: the entry class's last segment is changed. The wrong class is
        derived from the real build file with one edit, so the sample is the real text mutated in
        memory (never a file on disk).
        """
        real_build = self._read("server/modules/sync-api/build.gradle.kts")
        bad_build = real_build.replace("SyncApiMainKt", "SyncApiMainKtx", 1)
        self.assertNotEqual(
            bad_build,
            real_build,
            "changing the entry class must change the sample",
        )
        problems = _distribution_problems(bad_build, self._read(MAIN_SOURCE_REL))
        self.assertIn(
            "mainClass is 'dev.breaker.server.syncapi.SyncApiMainKtx', "
            "source declares 'dev.breaker.server.syncapi.SyncApiMainKt'",
            problems,
            "a build file whose mainClass disagrees with the source must report the mainClass problem: %r"
            % problems,
        )

    def test_a_source_without_a_top_level_main_fails_the_check(self):
        """A main source with no top-level `fun main(` fails the check.

        Red on a deliberate break: the top-level `fun main(` line is removed. The sample is the
        real main source mutated in memory by exactly one edit (never a file on disk), so the
        package declaration and the file stem still agree with the build file's mainClass and the
        only problem the control can fire on is the missing entry point.
        """
        real_source = self._read(MAIN_SOURCE_REL)
        no_main = real_source.replace("fun main() {", "fun run() {", 1)
        self.assertNotEqual(
            no_main,
            real_source,
            "renaming the top-level main function must change the sample",
        )
        problems = _distribution_problems(
            self._read("server/modules/sync-api/build.gradle.kts"), no_main
        )
        self.assertIn(
            "the main source has no top-level fun main(",
            problems,
            "a main source with no top-level fun main( must report that problem: %r" % problems,
        )

    def test_a_source_without_a_package_fails_the_check(self):
        """A main source with no `package` declaration fails the check.

        Red on a deliberate break: the `package` line is removed. The sample is the real main
        source mutated in memory by exactly one edit (never a file on disk), so the top-level
        `fun main(` is still present and the only problem the control can fire on is the
        missing package declaration.
        """
        real_source = self._read(MAIN_SOURCE_REL)
        no_package = real_source.replace("package dev.breaker.server.syncapi\n", "", 1)
        self.assertNotEqual(
            no_package,
            real_source,
            "removing the `package` line must change the sample",
        )
        problems = _distribution_problems(
            self._read("server/modules/sync-api/build.gradle.kts"), no_package
        )
        self.assertIn(
            "the main source declares no package",
            problems,
            "a main source with no package must report the missing-package problem: %r" % problems,
        )

    def test_a_renamed_package_source_is_not_hard_coded(self):
        """A source whose package disagrees with the build file's mainClass fails the check.

        Red on a deliberate break: the package name is renamed. The sample is the real main
        source mutated in memory by exactly one edit (never a file on disk), so the top-level
        `fun main(` is still present and the only problem the control can fire on is the
        package/mainClass mismatch.
        """
        real_source = self._read(MAIN_SOURCE_REL)
        renamed = real_source.replace("package dev.breaker.server.syncapi", "package dev.breaker.server.syncapix", 1)
        self.assertNotEqual(
            renamed,
            real_source,
            "renaming the package must change the sample",
        )
        problems = _distribution_problems(
            self._read("server/modules/sync-api/build.gradle.kts"), renamed
        )
        self.assertIn(
            "mainClass is 'dev.breaker.server.syncapi.SyncApiMainKt', "
            "source declares 'dev.breaker.server.syncapix.SyncApiMainKt'",
            problems,
            "a source whose package disagrees with the build file's mainClass must report the "
            "mainClass problem: %r" % problems,
        )

    def test_a_JvmName_source_fails_the_check(self):
        """A main source carrying `@file:JvmName` fails the check.

        Red on a deliberate break: the annotation is inserted. The sample is the real main source
        mutated in memory by exactly one edit (never a file on disk), so the package declaration
        and the top-level `fun main(` are still present and the only problem the control can fire
        on is the JvmName annotation.
        """
        real_source = self._read(MAIN_SOURCE_REL)
        jvm_named = real_source.replace(
            "package dev.breaker.server.syncapi",
            '@file:JvmName("SyncApiMain")\npackage dev.breaker.server.syncapi',
            1,
        )
        self.assertNotEqual(
            jvm_named,
            real_source,
            "adding the @file:JvmName annotation must change the sample",
        )
        problems = _distribution_problems(
            self._read("server/modules/sync-api/build.gradle.kts"), jvm_named
        )
        self.assertIn(
            "a @file:JvmName file's class is not <package>.<stem>Kt",
            problems,
            "a main source carrying @file:JvmName must report the JvmName problem: %r" % problems,
        )


if __name__ == "__main__":
    unittest.main()
