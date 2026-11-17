"""Contract test for the `server/modules/sync-api` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code.
"""
import os
import re
import unittest

import app_sync_wiring_pins
import contract_support
from sync_api_main_wiring_pins import SyncApiMainWiringPinsTest  # noqa: F401  (start-up wiring pin, collected here)
from sync_api_expiry_scheduler_pins import SyncApiExpirySchedulerPinsTest  # noqa: F401  (expiry scheduler source pins, collected here)

MAIN_SOURCE_REL = "server/modules/sync-api/src/main/kotlin/dev/breaker/server/syncapi/SyncApiMain.kt"

# The module is imported and only the two comment-scanner helpers are bound into
# this namespace (`_scan_kotlin`, `_close_of`); the android-app pin class is not
# imported, so unittest does not collect it during this module's run.
_scan_kotlin = app_sync_wiring_pins._scan_kotlin
_close_of = app_sync_wiring_pins._close_of

# One module-level constant per distinct branch message of `_distribution_problems`:
# the guard appends these and the controls assert these, so no message is pasted twice.
_PLUGINS_WITHOUT_APPLICATION = "the plugins block does not apply the bare `application` plugin"
_NO_APPLICATION_BLOCK = "the build file has no `application { ... }` block"
_NO_MAIN_CLASS = "the application block declares no mainClass.set(...)"
_MAIN_CLASS_MISMATCH = "mainClass is %r, source declares %r"
_NO_PACKAGE = "the main source declares no package"
_NO_TOP_LEVEL_MAIN = "the main source has no top-level fun main("
_JVM_NAME = "a @file:JvmName file's class is not <package>.<stem>Kt"


def _distribution_problems(build_text, source_text):
    """Empty list == the build file and the main source agree on a runnable distribution.

    The `mainClass.set(...)` must be the LIVE line inside the `application { ... }` block:
    the build file is COMMENT-SCANNED first (`_scan_kotlin(..., keep_strings=True)`), so a
    commented-out line cannot satisfy it, and the block is brace-matched, so a
    `mainClass.set(...)` in another block cannot satisfy it. Three distinct messages:
    no `application` block / no `mainClass` inside it / wrong value.
    """
    problems = []
    plugins = re.search(r"^plugins\s*\{(.*?)^\}", build_text, re.S | re.M)
    if not plugins or not re.search(r"(?m)^\s*application\s*$", plugins.group(1)):
        problems.append(_PLUGINS_WITHOUT_APPLICATION)
    code = _scan_kotlin(build_text, keep_strings=True)
    app = re.search(r"(?m)^application\s*\{", code)
    if not app:
        problems.append(_NO_APPLICATION_BLOCK)
        return problems
    brace = app.end() - 1
    close = _close_of(code, brace)
    block = code[brace + 1:close]
    main = re.search(r'mainClass\.set\(\s*"([^"]+)"\s*\)', block)
    if not main:
        problems.append(_NO_MAIN_CLASS)
        return problems
    package = re.search(r"(?m)^package\s+([\w.]+)\s*$", source_text)
    if not package:
        problems.append(_NO_PACKAGE)
    else:
        stem = os.path.splitext(os.path.basename(MAIN_SOURCE_REL))[0]
        expected = package.group(1) + "." + stem + "Kt"
        if main.group(1) != expected:
            problems.append(_MAIN_CLASS_MISMATCH % (main.group(1), expected))
    if not re.search(r"(?m)^fun\s+main\s*\(", source_text):
        problems.append(_NO_TOP_LEVEL_MAIN)
    if re.search(r"(?m)^@file:JvmName\(", source_text):
        problems.append(_JVM_NAME)
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
            _PLUGINS_WITHOUT_APPLICATION,
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
            _NO_MAIN_CLASS,
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
            _MAIN_CLASS_MISMATCH % ("dev.breaker.server.syncapi.SyncApiMainKtx", "dev.breaker.server.syncapi.SyncApiMainKt"),
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
            _NO_TOP_LEVEL_MAIN,
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
            _NO_PACKAGE,
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
            _MAIN_CLASS_MISMATCH % ("dev.breaker.server.syncapi.SyncApiMainKt", "dev.breaker.server.syncapix.SyncApiMainKt"),
            problems,
            "a source whose package disagrees with the build file's mainClass must report the "
            "mainClass problem: %r" % problems,
        )

    def test_a_JvmName_source_fails_the_check(self):
        """A main source carrying `@file:JvmName` fails the check.

        Red on a deliberate break: the annotation is inserted above the package line. The sample
        is the real main source mutated in memory by exactly one edit (never a file on disk), so
        the top-level `fun main(` is still present and the only problem the control can fire on
        is the JvmName annotation. The `old` is anchored with its newline, and the count
        assertions pin both the anchor and the added marker to exactly one: applying this control
        to a source that already carries `@file:JvmName` inserts a second marker and fails instead
        of passing.
        """
        real_source = self._read(MAIN_SOURCE_REL)
        jvm_named = real_source.replace(
            "package dev.breaker.server.syncapi\n",
            '@file:JvmName("SyncApiMain")\npackage dev.breaker.server.syncapi\n',
            1,
        )
        self.assertNotEqual(
            jvm_named,
            real_source,
            "adding the @file:JvmName annotation must change the sample",
        )
        self.assertEqual(
            real_source.count("package dev.breaker.server.syncapi\n"),
            1,
            "the anchored `old` must occur exactly once in the real main source",
        )
        self.assertEqual(
            jvm_named.count("@file:JvmName"),
            1,
            "the JvmName control must add exactly one @file:JvmName line, so it cannot be "
            "re-applied to a source that already carries one",
        )
        problems = _distribution_problems(
            self._read("server/modules/sync-api/build.gradle.kts"), jvm_named
        )
        self.assertIn(
            _JVM_NAME,
            problems,
            "a main source carrying @file:JvmName must report the JvmName problem: %r" % problems,
        )

    def test_a_main_class_moved_into_another_block_fails_the_check(self):
        """A `mainClass.set(...)` moved out of `application { }` fails the check.

        Red on a deliberate break: the line is moved into the following
        `dependencies { }` block in one edit. The sample is the real build file
        mutated in memory, so the `application` block is left empty and the only
        problem the control can fire on is the missing mainClass.
        """
        real_build = self._read("server/modules/sync-api/build.gradle.kts")
        moved = real_build.replace(
            'application {\n'
            '    mainClass.set("dev.breaker.server.syncapi.SyncApiMainKt")\n'
            "}\n"
            "\n"
            "dependencies {",
            "application {\n"
            "}\n"
            "\n"
            "dependencies {\n"
            '    mainClass.set("dev.breaker.server.syncapi.SyncApiMainKt")',
            1,
        )
        self.assertNotEqual(moved, real_build, "moving the mainClass line must change the sample")
        problems = _distribution_problems(moved, self._read(MAIN_SOURCE_REL))
        self.assertIn(
            _NO_MAIN_CLASS,
            problems,
            "a mainClass.set(...) outside the application block must not satisfy the pin: %r" % problems,
        )

    def test_a_commented_out_main_class_fails_the_check(self):
        """A commented-out `mainClass.set(...)` line fails the check.

        Red on a deliberate break: the live line is commented out. The sample is
        the real build file mutated in memory; the comment text still contains
        `mainClass.set(`, so only the comment scan can reject it.
        """
        real_build = self._read("server/modules/sync-api/build.gradle.kts")
        commented = real_build.replace(
            '    mainClass.set("dev.breaker.server.syncapi.SyncApiMainKt")\n',
            '    // mainClass.set("dev.breaker.server.syncapi.SyncApiMainKt")\n',
            1,
        )
        self.assertNotEqual(commented, real_build, "commenting the line must change the sample")
        problems = _distribution_problems(commented, self._read(MAIN_SOURCE_REL))
        self.assertIn(
            _NO_MAIN_CLASS,
            problems,
            "a commented-out mainClass.set(...) must not satisfy the pin: %r" % problems,
        )

    def test_a_renamed_application_block_fails_the_check(self):
        """Renaming the `application { }` block fails the check.

        Red on a deliberate break: the block header is renamed. The sample is the
        real build file mutated in memory, so the mainClass line is still present
        but lives in a block that is no longer the application block.
        """
        real_build = self._read("server/modules/sync-api/build.gradle.kts")
        renamed = real_build.replace("application {", "applicationX {", 1)
        self.assertNotEqual(renamed, real_build, "renaming the block must change the sample")
        problems = _distribution_problems(renamed, self._read(MAIN_SOURCE_REL))
        self.assertIn(
            _NO_APPLICATION_BLOCK,
            problems,
            "a build file with no application block must report it: %r" % problems,
        )

    def test_a_comment_inside_the_block_with_a_different_value_fails_the_check(self):
        """A comment inside `application { }` carrying a different value fails the check.

        Red on a deliberate break: the live line is replaced by a comment carrying
        a DIFFERENT entry class. The sample is the real build file mutated in
        memory. Its raw text still matches `mainClass.set(...)`, so a guard that
        skipped the comment scan would read the comment's value and report the
        mismatch instead; the assertion below proves it did not.
        """
        real_build = self._read("server/modules/sync-api/build.gradle.kts")
        commented_value = real_build.replace(
            '    mainClass.set("dev.breaker.server.syncapi.SyncApiMainKt")\n',
            '    // mainClass.set("dev.breaker.server.syncapi.SyncApiOtherKt")\n',
            1,
        )
        self.assertNotEqual(commented_value, real_build, "commenting the value must change the sample")
        self.assertIn(
            'mainClass.set("dev.breaker.server.syncapi.SyncApiOtherKt")',
            commented_value,
            "the mutant must still carry a mainClass.set value in its raw text",
        )
        problems = _distribution_problems(commented_value, self._read(MAIN_SOURCE_REL))
        self.assertIn(
            _NO_MAIN_CLASS,
            problems,
            "a comment inside the block must not satisfy the pin: %r" % problems,
        )
        self.assertNotIn(
            _MAIN_CLASS_MISMATCH % ("dev.breaker.server.syncapi.SyncApiOtherKt", "dev.breaker.server.syncapi.SyncApiMainKt"),
            problems,
            "the comment's value must be ignored, not reported as the mainClass: %r" % problems,
        )


# --- sweep launch block pin -------------------------------------------------

#
# `SyncApiMain.kt` starts its four background sweeps from one
# `val sweepJob = CoroutineScope(...).launch { ... }` block. The block is found the
# SAME way `sync_api_expiry_scheduler_pins.py` finds it: the identical marker text
# plus `_close_of`. There is no second definition of "the sweep block" here.
#
# Each required call must be a DEPTH-0 STATEMENT of its own `launch { ... }` block:
# at brace depth 0 inside that block, and the first token of its statement. A call
# nested in `if (...) { call() }`, or guarded by `if (false) call()`, or reached only
# through `&&`/`||`, is at depth > 0 or mid-statement and does NOT satisfy the pin.

SWEEP_BLOCK_MARKER = "val sweepJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {"

_NO_SWEEP_BLOCK = (
    "SyncApiMain.kt has no sweepJob launch block "
    "(val sweepJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {); "
    "a fifth sweep needs a deliberate pin update"
)
_SWEEP_COUNT = (
    "the sweepJob block must launch exactly four sweeps, found %d; "
    "a fifth sweep needs a deliberate pin update"
)
_HANDOFF_NOT_LAUNCHED = (
    "the sweepJob block does not launch runHandoffSweep as a depth-0 statement of a "
    "launch { } block; a fifth sweep needs a deliberate pin update"
)
_SIGNIN_NOT_LAUNCHED = (
    "the sweepJob block does not launch runSigninAttemptSweep as a depth-0 statement of a "
    "launch { } block; a fifth sweep needs a deliberate pin update"
)
_RATELIMIT_NOT_LAUNCHED = (
    "the sweepJob block does not launch runRateLimitCounterSweep as a depth-0 statement of a "
    "launch { } block; a fifth sweep needs a deliberate pin update"
)
_EXPIRY_NOT_LAUNCHED = (
    "the sweepJob block does not launch ExpiryScheduler.runExpiryScheduler as a depth-0 "
    "statement of a launch { } block; a fifth sweep needs a deliberate pin update"
)
_STOP_SWEEP_JOB = (
    "stopSweep must cancel the job bound by the sweepJob launch block with "
    "runBlocking { sweepJob.cancelAndJoin() }; a fifth sweep needs a deliberate pin update"
)

# (call name, the one constant that names it): one requirement per sweep.
SWEEP_LAUNCH_REQUIREMENTS = (
    ("runHandoffSweep(", _HANDOFF_NOT_LAUNCHED),
    ("runSigninAttemptSweep(", _SIGNIN_NOT_LAUNCHED),
    ("runRateLimitCounterSweep(", _RATELIMIT_NOT_LAUNCHED),
    ("ExpiryScheduler.runExpiryScheduler(", _EXPIRY_NOT_LAUNCHED),
)

_STOP_SWEEP_PATTERN = re.compile(
    r"stopSweep\s*=\s*\{\s*runBlocking\s*\{\s*(\w+)\.cancelAndJoin\(\)\s*\}\s*\}"
)

# The four real child launch lines, used to build one-edit control samples.
_LAUNCH_HANDOFF_LINE = "        launch { runHandoffSweep(handoffStore, resetStore) }"
_LAUNCH_SIGNIN_LINE = "        launch { runSigninAttemptSweep(accountStore.attempts) }"
_LAUNCH_RATELIMIT_LINE = "        launch { runRateLimitCounterSweep(counterStore) }"
_LAUNCH_EXPIRY_LINE = (
    "        launch { ExpiryScheduler.runExpiryScheduler(transcriptionStore, accountStore) }"
)
_SWEEP_CHILDREN = (
    _LAUNCH_HANDOFF_LINE + "\n"
    + _LAUNCH_SIGNIN_LINE + "\n"
    + _LAUNCH_RATELIMIT_LINE + "\n"
    + _LAUNCH_EXPIRY_LINE + "\n"
)


def _depth0_statement_starts(body):
    """Indices in `body` where a brace-depth-0 statement begins.

    A statement begins at the start of the body or right after `{`, `}`, `;` or a
    newline at depth 0. A call preceded by `if (...)`/`else`/`&&`/`||` on the same
    statement is not a statement start, so it cannot satisfy the sweep pin.
    """
    starts = set()
    depth = 0
    at_start = True
    for i, ch in enumerate(body):
        if ch == "{":
            depth += 1
            at_start = True
        elif ch == "}":
            depth -= 1
            at_start = True
        elif ch == ";" and depth == 0:
            at_start = True
        elif ch == "\n" and depth == 0:
            at_start = True
        elif ch.isspace():
            continue
        else:
            if depth == 0 and at_start:
                starts.add(i)
            at_start = False
    return starts


def _depth0_launch_blocks(body):
    """Brace-matched bodies of every `launch { ... }` at brace depth 0 of `body`."""
    blocks = []
    depth = 0
    i = 0
    n = len(body)
    while i < n:
        ch = body[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
        elif (
            depth == 0
            and body.startswith("launch", i)
            and (i == 0 or not (body[i - 1].isalnum() or body[i - 1] == "_"))
        ):
            k = i + len("launch")
            while k < n and body[k].isspace():
                k += 1
            if k < n and body[k] == "{":
                close = _close_of(body, k)
                if close > k:
                    blocks.append(body[k + 1:close])
                    i = close + 1
                    continue
        i += 1
    return blocks


class SyncApiSweepLaunchPinsTest(unittest.TestCase):
    """The sweepJob launch block really starts the four sweeps, and stopSweep cancels it.

    Every pin here READS the comment-scanned source; none of them runs `main`. The
    comment scanner removes comments and reduces string literals to a marker, so a
    comment cannot satisfy a pin. Runtime proof is the built-distribution run.
    """

    def _read_main(self):
        with open(os.path.join(contract_support.ROOT, MAIN_SOURCE_REL), encoding="utf-8") as fh:
            return fh.read()

    def _sweep_block_problems(self, source_text):
        """Empty list == the sweepJob block launches the four sweeps, each at depth 0."""
        problems = []
        code = _scan_kotlin(source_text, keep_strings=False)
        if SWEEP_BLOCK_MARKER not in code:
            problems.append(_NO_SWEEP_BLOCK)
            return problems
        open_idx = code.index(SWEEP_BLOCK_MARKER) + len(SWEEP_BLOCK_MARKER) - 1
        close_idx = _close_of(code, open_idx)
        body = code[open_idx + 1:close_idx]
        blocks = _depth0_launch_blocks(body)
        if len(blocks) != len(SWEEP_LAUNCH_REQUIREMENTS):
            problems.append(_SWEEP_COUNT % len(blocks))
        launched = set()
        for block in blocks:
            starts = _depth0_statement_starts(block)
            for call, _message in SWEEP_LAUNCH_REQUIREMENTS:
                if call in launched:
                    continue
                idx = block.find(call)
                while idx != -1:
                    if idx in starts:
                        launched.add(call)
                        break
                    idx = block.find(call, idx + 1)
        for call, message in SWEEP_LAUNCH_REQUIREMENTS:
            if call not in launched:
                problems.append(message)
        return problems

    def _stop_sweep_problems(self, source_text):
        """Empty list == stopSweep cancels the job the sweepJob block binds."""
        problems = []
        code = _scan_kotlin(source_text, keep_strings=False)
        expected = None
        if SWEEP_BLOCK_MARKER in code:
            expected = re.search(r"val\s+(\w+)\s*=", SWEEP_BLOCK_MARKER).group(1)
        match = _STOP_SWEEP_PATTERN.search(code)
        job = match.group(1) if match else None
        if job != expected:
            problems.append(_STOP_SWEEP_JOB)
        return problems

    def test_the_real_main_launches_the_four_sweeps_and_stops_them(self):
        """The real main launches the four sweeps in the sweepJob block and stopSweep cancels it."""
        source = self._read_main()
        problems = self._sweep_block_problems(source) + self._stop_sweep_problems(source)
        self.assertEqual(problems, [], "the sweep launch block or stopSweep is wrong: %r" % problems)

    def test_an_emptied_sweep_block_fails_the_check(self):
        """An emptied sweepJob block fails the check.

        Red on a deliberate break: the four child launch lines are removed. The sample
        is the real main source mutated in memory by exactly one edit (never a file on
        disk).
        """
        real = self._read_main()
        emptied = real.replace(_SWEEP_CHILDREN, "", 1)
        self.assertNotEqual(emptied, real, "emptying the sweep block must change the sample")
        problems = self._sweep_block_problems(emptied)
        self.assertIn(_SWEEP_COUNT % 0, problems, "an emptied block must report the count: %r" % problems)
        for _call, message in SWEEP_LAUNCH_REQUIREMENTS:
            self.assertIn(message, problems, "an emptied block must name every missing call: %r" % problems)

    def test_a_removed_handoff_sweep_fails_the_check(self):
        """Removing the runHandoffSweep child launch fails the check."""
        real = self._read_main()
        removed = real.replace(_LAUNCH_HANDOFF_LINE + "\n", "", 1)
        self.assertNotEqual(removed, real, "removing the handoff launch must change the sample")
        problems = self._sweep_block_problems(removed)
        self.assertIn(_SWEEP_COUNT % 3, problems, "three launches must report the count: %r" % problems)
        self.assertIn(_HANDOFF_NOT_LAUNCHED, problems, "the missing call must be named: %r" % problems)

    def test_a_removed_signin_sweep_fails_the_check(self):
        """Removing the runSigninAttemptSweep child launch fails the check."""
        real = self._read_main()
        removed = real.replace(_LAUNCH_SIGNIN_LINE + "\n", "", 1)
        self.assertNotEqual(removed, real, "removing the signin launch must change the sample")
        problems = self._sweep_block_problems(removed)
        self.assertIn(_SWEEP_COUNT % 3, problems, "three launches must report the count: %r" % problems)
        self.assertIn(_SIGNIN_NOT_LAUNCHED, problems, "the missing call must be named: %r" % problems)

    def test_a_removed_ratelimit_sweep_fails_the_check(self):
        """Removing the runRateLimitCounterSweep child launch fails the check."""
        real = self._read_main()
        removed = real.replace(_LAUNCH_RATELIMIT_LINE + "\n", "", 1)
        self.assertNotEqual(removed, real, "removing the ratelimit launch must change the sample")
        problems = self._sweep_block_problems(removed)
        self.assertIn(_SWEEP_COUNT % 3, problems, "three launches must report the count: %r" % problems)
        self.assertIn(_RATELIMIT_NOT_LAUNCHED, problems, "the missing call must be named: %r" % problems)

    def test_a_removed_expiry_sweep_fails_the_check(self):
        """Removing the ExpiryScheduler.runExpiryScheduler child launch fails the check."""
        real = self._read_main()
        removed = real.replace(_LAUNCH_EXPIRY_LINE + "\n", "", 1)
        self.assertNotEqual(removed, real, "removing the expiry launch must change the sample")
        problems = self._sweep_block_problems(removed)
        self.assertIn(_SWEEP_COUNT % 3, problems, "three launches must report the count: %r" % problems)
        self.assertIn(_EXPIRY_NOT_LAUNCHED, problems, "the missing call must be named: %r" % problems)

    def test_a_renamed_sweep_call_fails_the_check(self):
        """Renaming a sweep call (its launch wrapper kept) fails the check."""
        real = self._read_main()
        renamed = real.replace(
            "runHandoffSweep(handoffStore, resetStore)",
            "runHandoffSweepX(handoffStore, resetStore)",
            1,
        )
        self.assertNotEqual(renamed, real, "renaming the call must change the sample")
        problems = self._sweep_block_problems(renamed)
        self.assertIn(_HANDOFF_NOT_LAUNCHED, problems, "the renamed call must be reported: %r" % problems)

    def test_a_child_launch_moved_outside_the_block_fails_the_check(self):
        """Moving one child launch outside the sweepJob block fails the check."""
        real = self._read_main()
        moved = real.replace(
            _SWEEP_CHILDREN + "    }",
            _LAUNCH_SIGNIN_LINE + "\n" + _LAUNCH_RATELIMIT_LINE + "\n" + _LAUNCH_EXPIRY_LINE
            + "\n    }\n" + _LAUNCH_HANDOFF_LINE,
            1,
        )
        self.assertNotEqual(moved, real, "moving the launch must change the sample")
        problems = self._sweep_block_problems(moved)
        self.assertIn(_SWEEP_COUNT % 3, problems, "three launches must report the count: %r" % problems)
        self.assertIn(_HANDOFF_NOT_LAUNCHED, problems, "the moved call must be reported: %r" % problems)

    def test_a_renamed_stop_sweep_job_fails_the_check(self):
        """Renaming the job stopSweep cancels fails the check."""
        real = self._read_main()
        renamed = real.replace(
            "stopSweep = { runBlocking { sweepJob.cancelAndJoin() } },",
            "stopSweep = { runBlocking { sweepJobX.cancelAndJoin() } },",
            1,
        )
        self.assertNotEqual(renamed, real, "renaming the stopSweep job must change the sample")
        problems = self._stop_sweep_problems(renamed)
        self.assertIn(_STOP_SWEEP_JOB, problems, "the renamed job must be reported: %r" % problems)

    def test_a_plain_sweep_call_without_a_launch_wrapper_fails_the_check(self):
        """A sweep call kept but stripped of its `launch { }` wrapper fails the check."""
        real = self._read_main()
        plain = real.replace(
            _LAUNCH_HANDOFF_LINE,
            "        runHandoffSweep(handoffStore, resetStore)",
            1,
        )
        self.assertNotEqual(plain, real, "stripping the launch wrapper must change the sample")
        problems = self._sweep_block_problems(plain)
        self.assertIn(_SWEEP_COUNT % 3, problems, "three launches must report the count: %r" % problems)
        self.assertIn(_HANDOFF_NOT_LAUNCHED, problems, "the plain call must be reported: %r" % problems)

    def test_two_child_launches_moved_outside_the_block_fail_the_check(self):
        """Moving two child launches outside the sweepJob block fails the check."""
        real = self._read_main()
        moved = real.replace(
            _SWEEP_CHILDREN + "    }",
            _LAUNCH_RATELIMIT_LINE + "\n" + _LAUNCH_EXPIRY_LINE
            + "\n    }\n" + _LAUNCH_HANDOFF_LINE + "\n" + _LAUNCH_SIGNIN_LINE,
            1,
        )
        self.assertNotEqual(moved, real, "moving two launches must change the sample")
        problems = self._sweep_block_problems(moved)
        self.assertIn(_SWEEP_COUNT % 2, problems, "two launches must report the count: %r" % problems)
        self.assertIn(_HANDOFF_NOT_LAUNCHED, problems, "the moved handoff call must be reported: %r" % problems)
        self.assertIn(_SIGNIN_NOT_LAUNCHED, problems, "the moved signin call must be reported: %r" % problems)

    def test_a_guarded_sweep_call_does_not_satisfy_the_pin(self):
        """`if (false) runHandoffSweep(...)` inside the block does not satisfy the pin.

        The call is at brace depth 0 but is not the first token of its statement, so
        the depth-0 statement rule rejects it.
        """
        real = self._read_main()
        guarded = real.replace(
            _LAUNCH_HANDOFF_LINE,
            "        launch { if (false) runHandoffSweep(handoffStore, resetStore) }",
            1,
        )
        self.assertNotEqual(guarded, real, "guarding the call must change the sample")
        problems = self._sweep_block_problems(guarded)
        self.assertIn(_HANDOFF_NOT_LAUNCHED, problems, "a guarded call must not satisfy the pin: %r" % problems)

    def test_a_renamed_sweep_job_binding_fails_the_check(self):
        """Renaming the `val sweepJob =` binding fails both the block and stopSweep checks."""
        real = self._read_main()
        renamed = real.replace(
            "    val sweepJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {",
            "    val sweepJobX = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {",
            1,
        )
        self.assertNotEqual(renamed, real, "renaming the binding must change the sample")
        problems = self._sweep_block_problems(renamed) + self._stop_sweep_problems(renamed)
        self.assertIn(_NO_SWEEP_BLOCK, problems, "the missing block must be reported: %r" % problems)
        self.assertIn(_STOP_SWEEP_JOB, problems, "stopSweep must no longer match: %r" % problems)

    def test_a_removed_stop_sweep_line_fails_the_check(self):
        """Removing the `stopSweep = { ... }` line fails the check."""
        real = self._read_main()
        removed = real.replace(
            "                stopSweep = { runBlocking { sweepJob.cancelAndJoin() } },\n",
            "",
            1,
        )
        self.assertNotEqual(removed, real, "removing the stopSweep line must change the sample")
        problems = self._stop_sweep_problems(removed)
        self.assertIn(_STOP_SWEEP_JOB, problems, "the missing stopSweep must be reported: %r" % problems)


if __name__ == "__main__":
    unittest.main()
