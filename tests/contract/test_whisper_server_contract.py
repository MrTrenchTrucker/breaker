"""Contract test for the `server/modules/whisper-server` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code.

The three pins below read the shared api-contracts spec, so a revert of either
contract edit lands here as a red failure. Assertion style matches contract_support.py.
"""
import os
import re
import sys
import unittest

import contract_support

ROOT = contract_support.ROOT
OPENAPI = os.path.join(ROOT, "shared/modules/api-contracts/openapi.yaml")
MAIN_SOURCE_REL = "server/modules/whisper-server/src/main/kotlin/dev/breaker/server/whisper/WhisperServer.kt"
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import yaml_subset

# The shared Kotlin comment scanner, borrowed from app_sync_wiring_pins.py. Only the two
# helpers are bound into this module's namespace: a plain `from app_sync_wiring_pins import
# ...` would also bind AppSyncWiringPinsTest here, and unittest would then collect the
# android-app pins during THIS module's contract run.
import app_sync_wiring_pins

_scan_kotlin = app_sync_wiring_pins._scan_kotlin
_close_of = app_sync_wiring_pins._close_of


def _load_openapi():
    return yaml_subset.load(OPENAPI)


def _open_of(text, close_idx):
    """Index of the bracket that opens the one at `close_idx`, or -1.

    The backward mirror of app_sync_wiring_pins._close_of: same depth walk, scanned from
    `close_idx` toward the start of the text.
    """
    close_c = text[close_idx]
    open_c = {")": "(", "}": "{", "]": "["}[close_c]
    depth = 0
    for k in range(close_idx, -1, -1):
        if text[k] == close_c:
            depth += 1
        elif text[k] == open_c:
            depth -= 1
            if depth == 0:
                return k
    return -1


def _identifier_before(text, idx):
    """The identifier that ends immediately before `idx`, or "" when there is none."""
    end = idx
    start = end
    while start > 0 and (text[start - 1].isalnum() or text[start - 1] == "_"):
        start -= 1
    return text[start:end]


def _top_level_args(text):
    """Split an argument list on commas that are not nested in (), {} or []."""
    parts = []
    current = []
    depth = 0
    for ch in text:
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    parts.append("".join(current))
    return [part for part in parts if part.strip()]


class ServerModulesWhisperServerContractTest(contract_support.ModuleContractTest):
    MODULE = "server_whisper"

    def test_404_reason_is_unknown_or_expired(self):
        """a GET on an unknown/expired job_id answers 404 reason "unknown or expired".

        The contract's 404 for /v1/jobs/{job_id} carries the one reason the server actually
        sends (TranscriptionRoutes.UNKNOWN_OR_EXPIRED), not the old "not_found"/"Job does not
        exist". The behaviour lives in the Kotlin route test; this pins the spec the client reads.
        """
        spec = _load_openapi()
        example = (
            spec["paths"]["/v1/jobs/{job_id}"]["get"]["responses"]["404"]
            ["content"]["application/json"]["example"]
        )
        assert example["error"] == "unknown or expired", (
            f"whisper-server contract: the job 404 reason must be 'unknown or expired', "
            f"but the spec says {example['error']!r}"
        )

    def test_job_id_example_is_a_real_value_not_the_placeholder(self):
        """the job_id example is a value the server really produces, not "job-12345".

        The server emits an opaque numeric-ish id (its SQLite row id), so the 202 example is a
        numeric string and never the old literal placeholder.
        """
        spec = _load_openapi()
        example = (
            spec["paths"]["/v1/audio/transcriptions"]["post"]["responses"]["202"]
            ["content"]["application/json"]["example"]
        )
        job_id = example["job_id"]
        assert job_id != "job-12345", (
            "whisper-server contract: the job_id example must not be the old 'job-12345' placeholder"
        )
        assert isinstance(job_id, str) and job_id.isdigit(), (
            f"whisper-server contract: the job_id example must be an opaque numeric-ish id string, "
            f"got {job_id!r}"
        )

    def test_job_id_parameter_is_documented_as_opaque(self):
        """the job_id path parameter is documented as opaque — echoed back, never parsed.

        The client treats job_id as an opaque token it echoes on the next GET; the contract says
        so on the parameter description.
        """
        spec = _load_openapi()
        params = spec["paths"]["/v1/jobs/{job_id}"]["get"]["parameters"]
        job_id_param = next(p for p in params if p["name"] == "job_id")
        description = job_id_param["description"].lower()
        assert "opaque" in description, (
            "whisper-server contract: the job_id parameter must say it is opaque"
        )
        assert "echo" in description and "unchanged" in description, (
            "whisper-server contract: the job_id parameter must say the client echoes it back unchanged"
        )

    def test_transcription_request_requires_only_the_file_part(self):
        """the transcription request schema requires only `file`; `model` and `language` are optional.

        whisper-server accepts a missing or blank `model` part and ignores it (the forwarder sends
        its configured downstream model), so the contract must not list `model` as required.
        """
        spec = _load_openapi()
        schema = (
            spec["paths"]["/v1/audio/transcriptions"]["post"]["requestBody"]["content"]
            ["multipart/form-data"]["schema"]
        )
        assert schema["required"] == ["file"], (
            f"whisper-server contract: only the file part is required, got {schema['required']!r}"
        )

    def test_job_accepted_job_id_is_a_string(self):
        """the enqueue answer carries job_id as a JSON string, the type the schema names.

        whisper-server writes the id quoted and the phone client reads a string, so the schema
        must not describe a number.
        """
        spec = _load_openapi()
        job_id = spec["components"]["schemas"]["JobAccepted"]["properties"]["job_id"]
        assert job_id["type"] == "string", (
            f"whisper-server contract: job_id must be a string, got {job_id['type']!r}"
        )

    def test_transcription_result_language_may_be_empty(self):
        """the done answer always carries language; it is the empty string when none was reported.

        The server writes the key on every done answer, so the contract field says when it is empty.
        """
        spec = _load_openapi()
        language = spec["components"]["schemas"]["TranscriptionResult"]["properties"]["language"]
        assert "empty when the service reported none" in language["description"], (
            "whisper-server contract: the language description must say it is empty when the "
            "service reported none"
        )


    def test_a_commented_out_gradle_edge_is_not_a_dependency(self):
        """`depends_on` is proven by a LIVE `project(":…")` edge, not by its text.

        The registry and the Gradle build are compared as sets; a commented-out edge still
        contains the text, so it used to satisfy the bijection while this module could not
        compile against the seal module.
        """
        text = '    // implementation(project(":shared:modules:seal"))\n'
        assert contract_support._project_dependencies_in(text) == set(), (
            "a commented-out project() edge must not count as a declared dependency"
        )

    def test_a_block_commented_out_gradle_edge_is_not_a_dependency(self):
        text = '/*\n    implementation(project(":shared:modules:seal"))\n*/\n'
        assert contract_support._project_dependencies_in(text) == set(), (
            "a block-commented project() edge must not count as a declared dependency"
        )

    def test_a_trailing_comment_leaves_the_live_edge_visible(self):
        text = '    implementation(project(":shared:modules:seal")) // moved here\n'
        assert contract_support._project_dependencies_in(text) == {":shared:modules:seal"}, (
            "stripping comments must not hide a live edge that merely carries a trailing comment"
        )

    def test_a_line_commented_out_settings_include_is_not_an_include(self):
        assert contract_support._settings_includes_in(
            'include(":shared")\n// include(":web-fe")\n'
        ) == {":shared"}, "a commented-out include must not count as one"

    def test_the_real_seal_edge_is_declared_by_this_build_file(self):
        """Green on the tip: the edge this module actually compiles against."""
        declared = contract_support._project_dependencies(
            os.path.join("server", "modules", "whisper-server", "build.gradle.kts")
        )
        assert ":shared:modules:seal" in declared, (
            f"whisper-server must wire the shared seal module with a live project() edge, "
            f"got {sorted(declared)}"
        )

    def _distribution_problems(self, build_text, source_text):
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

    def _main_blocks_problems(self, source_text):
        """Empty list == main()'s last statement blocks on the worker job it launched.

        Reads the COMMENT-SCANNED source (`keep_strings=False`): every guard keys on
        identifiers and punctuation (`fun main(`, the seam's `join()`, `runBlocking`, the
        launch-bound job variable), so a log line or KDoc carrying the seam name cannot
        satisfy a guard and no guard reads a literal's content. G1/G2/G3 report and RETURN,
        so each one-edit control isolates its own guard; G4/G5/G6 accumulate.
        """
        problems = []
        code = _scan_kotlin(source_text, keep_strings=False)

        # G1: a top-level `fun main(` must exist.
        main_decl = re.search(r"(?m)^fun\s+main\s*\(", code)
        if not main_decl:
            problems.append("the main source has no top-level fun main(")
            return problems

        brace = code.find("{", main_decl.end())
        body = code[brace + 1:_close_of(code, brace)]

        # G2: main's body, rstripped, must end with a call's closing paren.
        tail = body.rstrip()
        if not tail.endswith(")"):
            problems.append(
                "main does not end with a call, so it can return while the service is up"
            )
            return problems
        close_idx = len(tail) - 1
        open_idx = _open_of(tail, close_idx)
        call_name = _identifier_before(tail, open_idx)

        # G3: that call's name must be declared `internal fun <name>(` in this file.
        decl = re.search(
            r"(?m)^internal\s+fun\s+" + re.escape(call_name) + r"\s*\(", code
        )
        if not decl:
            problems.append(
                "main's final call %s is not declared in this file as internal fun" % call_name
            )
            return problems

        # G4/G5: the seam's own body must join the worker job and block on it.
        seam_brace = code.find("{", decl.end())
        seam_body = code[seam_brace + 1:_close_of(code, seam_brace)]
        if "join()" not in seam_body:
            problems.append(
                "the block-until-shutdown seam %s does not join the worker job" % call_name
            )
        if "runBlocking" not in seam_body:
            problems.append(
                "the block-until-shutdown seam %s does not block (no runBlocking)" % call_name
            )

        # G6: main must pass exactly the launch-bound worker job variable to the seam.
        launch = re.search(r"(?m)^\s*val\s+(\w+)\s*=\s*[\w.]+\.launch\s*\{", body)
        parts = _top_level_args(tail[open_idx + 1:close_idx])
        if launch is None:
            problems.append(
                "main does not pass the worker job (%s) to %s"
                % ("<no launch-bound job>", call_name)
            )
        elif len(parts) != 1 or parts[0].strip() != launch.group(1):
            problems.append(
                "main does not pass the worker job (%s) to %s"
                % (launch.group(1), call_name)
            )
        return problems

    def test_the_real_build_and_source_agree(self):
        """The build file and the main source agree on a runnable distribution."""
        with open(os.path.join(ROOT, "server", "modules", "whisper-server", "build.gradle.kts"), encoding="utf-8") as fh:
            build_text = fh.read()
        with open(os.path.join(ROOT, MAIN_SOURCE_REL), encoding="utf-8") as fh:
            source_text = fh.read()
        problems = self._distribution_problems(build_text, source_text)
        assert problems == [], (
            "whisper-server contract: the build file and the main source must agree on a "
            "runnable distribution, got %r" % (problems,)
        )

    def test_a_build_without_the_application_plugin_fails_the_check(self):
        """Removing only the application plugin line fails the check for that reason."""
        with open(os.path.join(ROOT, "server", "modules", "whisper-server", "build.gradle.kts"), encoding="utf-8") as fh:
            real_build = fh.read()
        with open(os.path.join(ROOT, MAIN_SOURCE_REL), encoding="utf-8") as fh:
            real_source = fh.read()
        sample = real_build.replace("    application\n", "", 1)
        assert sample != real_build, "the no-plugin sample must differ from the real build file"
        problems = self._distribution_problems(sample, real_source)
        assert "the plugins block does not apply the bare `application` plugin" in problems, (
            "whisper-server contract: a build file without the application plugin must be "
            "reported for the missing plugin, got %r" % (problems,)
        )

    def test_a_build_without_a_main_class_fails_the_check(self):
        """Removing only the mainClass line fails the check for that reason."""
        with open(os.path.join(ROOT, "server", "modules", "whisper-server", "build.gradle.kts"), encoding="utf-8") as fh:
            real_build = fh.read()
        with open(os.path.join(ROOT, MAIN_SOURCE_REL), encoding="utf-8") as fh:
            real_source = fh.read()
        sample = real_build.replace('    mainClass.set("dev.breaker.server.whisper.WhisperServerKt")', "", 1)
        assert sample != real_build, "the no-mainClass sample must differ from the real build file"
        problems = self._distribution_problems(sample, real_source)
        assert "no application.mainClass.set(...)" in problems, (
            "whisper-server contract: a build file without a mainClass must be reported for the "
            "missing mainClass, got %r" % (problems,)
        )

    def test_a_source_without_a_package_fails_the_check(self):
        """Removing only the package line fails the check for that reason."""
        with open(os.path.join(ROOT, "server", "modules", "whisper-server", "build.gradle.kts"), encoding="utf-8") as fh:
            real_build = fh.read()
        with open(os.path.join(ROOT, MAIN_SOURCE_REL), encoding="utf-8") as fh:
            real_source = fh.read()
        sample = real_source.replace("package dev.breaker.server.whisper", "", 1)
        assert sample != real_source, "the no-package sample must differ from the real source"
        problems = self._distribution_problems(real_build, sample)
        assert "the main source declares no package" in problems, (
            "whisper-server contract: a source without a package must be reported for the missing "
            "package, got %r" % (problems,)
        )

    def test_a_wrong_main_class_fails_the_check(self):
        """Changing only the mainClass last segment fails the check for that reason."""
        with open(os.path.join(ROOT, "server", "modules", "whisper-server", "build.gradle.kts"), encoding="utf-8") as fh:
            real_build = fh.read()
        with open(os.path.join(ROOT, MAIN_SOURCE_REL), encoding="utf-8") as fh:
            real_source = fh.read()
        sample = real_build.replace("WhisperServerKt", "WhisperServerKtx", 1)
        assert sample != real_build, "the wrong-mainClass sample must differ from the real build file"
        problems = self._distribution_problems(sample, real_source)
        assert "mainClass is" in " ".join(problems), (
            "whisper-server contract: a wrong mainClass must be reported as a mismatch, got %r" % (problems,)
        )

    def test_a_source_without_a_top_level_main_fails_the_check(self):
        """Removing only the top-level main fails the check for that reason."""
        with open(os.path.join(ROOT, "server", "modules", "whisper-server", "build.gradle.kts"), encoding="utf-8") as fh:
            real_build = fh.read()
        with open(os.path.join(ROOT, MAIN_SOURCE_REL), encoding="utf-8") as fh:
            real_source = fh.read()
        sample = real_source.replace("fun main() {", "fun mainRemoved() {", 1)
        assert sample != real_source, "the no-main sample must differ from the real source"
        problems = self._distribution_problems(real_build, sample)
        assert "the main source has no top-level fun main(" in problems, (
            "whisper-server contract: a source without a top-level main must be reported, got %r" % (problems,)
        )

    def test_a_renamed_package_source_is_not_hard_coded(self):
        """A source with a different package fails the check, so the class name is derived."""
        with open(os.path.join(ROOT, "server", "modules", "whisper-server", "build.gradle.kts"), encoding="utf-8") as fh:
            real_build = fh.read()
        with open(os.path.join(ROOT, MAIN_SOURCE_REL), encoding="utf-8") as fh:
            real_source = fh.read()
        sample = real_source.replace("package dev.breaker.server.whisper", "package dev.breaker.server.whisperx", 1)
        assert sample != real_source, "the renamed-package sample must differ from the real source"
        problems = self._distribution_problems(real_build, sample)
        assert "source declares" in " ".join(problems), (
            "whisper-server contract: a renamed package must be reported as a mismatch, got %r" % (problems,)
        )

    def test_a_JvmName_source_fails_the_check(self):
        """A source carrying @file:JvmName fails the check for that reason."""
        with open(os.path.join(ROOT, "server", "modules", "whisper-server", "build.gradle.kts"), encoding="utf-8") as fh:
            real_build = fh.read()
        with open(os.path.join(ROOT, MAIN_SOURCE_REL), encoding="utf-8") as fh:
            real_source = fh.read()
        sample = real_source.replace(
            "package dev.breaker.server.whisper",
            '@file:JvmName("WhisperServer")\npackage dev.breaker.server.whisper',
            1,
        )
        assert sample != real_source, "the JvmName sample must differ from the real source"
        problems = self._distribution_problems(real_build, sample)
        assert "a @file:JvmName file's class is not" in " ".join(problems), (
            "whisper-server contract: a @file:JvmName source must be reported, got %r" % (problems,)
        )

    def _real_source(self):
        with open(os.path.join(ROOT, MAIN_SOURCE_REL), encoding="utf-8") as fh:
            return fh.read()

    def test_the_real_main_blocks_on_the_worker_job(self):
        """Green on the tip: main()'s last statement is the blocking seam call."""
        problems = self._main_blocks_problems(self._real_source())
        assert problems == [], (
            "whisper-server contract: main must end by blocking on the worker job, "
            "got %r" % (problems,)
        )

    def test_a_renamed_main_fails_the_block_check(self):
        """Removing only the top-level `fun main(` fails for that reason (G1)."""
        real_source = self._real_source()
        sample = real_source.replace("fun main() {", "fun mainX() {", 1)
        assert sample != real_source, "the renamed-main sample must differ from the real source"
        problems = self._main_blocks_problems(sample)
        assert "the main source has no top-level fun main(" in problems, (
            "whisper-server contract: a main that is not top-level `fun main(` must be "
            "reported, got %r" % (problems,)
        )

    def test_a_main_whose_last_statement_is_not_a_call_fails_the_block_check(self):
        """Deleting only the seam call leaves main falling off the end (G2)."""
        real_source = self._real_source()
        sample = real_source.replace("    blockUntilShutdown(workerJob)\n", "", 1)
        assert sample != real_source, "the fallen-off-main sample must differ from the real source"
        problems = self._main_blocks_problems(sample)
        assert (
            "main does not end with a call, so it can return while the service is up" in problems
        ), "whisper-server contract: a main that does not end in a call must be reported, got %r" % (
            problems,
        )

    def test_an_undeclared_seam_name_fails_the_block_check(self):
        """Renaming only the declaration leaves the final call undeclared (G3)."""
        real_source = self._real_source()
        sample = real_source.replace(
            "internal fun blockUntilShutdown(", "internal fun blockUntilShutdownX(", 1
        )
        assert sample != real_source, "the renamed-seam sample must differ from the real source"
        problems = self._main_blocks_problems(sample)
        assert (
            "main's final call blockUntilShutdown is not declared in this file as internal fun"
            in problems
        ), "whisper-server contract: an undeclared seam name must be reported, got %r" % (problems,)

    def test_a_seam_that_does_not_join_fails_the_block_check(self):
        """Replacing only the seam's `workerJob.join()` with `workerJob.cancel()` fails (G4).

        The `old` string is the whole seam expression, not bare `workerJob.join()`: bare
        `workerJob.join()` also occurs in WhisperShutdown.run(), so it is not unique in the
        tree. Both forms describe the same one-edit neutralisation of the seam.
        """
        real_source = self._real_source()
        sample = real_source.replace(
            "runBlocking { workerJob.join() }", "runBlocking { workerJob.cancel() }", 1
        )
        assert sample != real_source, "the non-joining seam sample must differ from the real source"
        problems = self._main_blocks_problems(sample)
        assert (
            "the block-until-shutdown seam blockUntilShutdown does not join the worker job"
            in problems
        ), "whisper-server contract: a seam that does not join must be reported, got %r" % (problems,)

    def test_a_seam_that_does_not_block_fails_the_block_check(self):
        """Dropping only `runBlocking` from the seam body fails for that reason (G5)."""
        real_source = self._real_source()
        sample = real_source.replace(
            "    runBlocking { workerJob.join() }", "    { workerJob.join() }", 1
        )
        assert sample != real_source, "the non-blocking seam sample must differ from the real source"
        problems = self._main_blocks_problems(sample)
        assert (
            "the block-until-shutdown seam blockUntilShutdown does not block (no runBlocking)"
            in problems
        ), "whisper-server contract: a seam that does not block must be reported, got %r" % (problems,)

    def test_a_main_that_passes_the_wrong_argument_fails_the_block_check(self):
        """Passing `api` instead of the launch-bound job variable fails (G6)."""
        real_source = self._real_source()
        sample = real_source.replace(
            "blockUntilShutdown(workerJob)", "blockUntilShutdown(api)", 1
        )
        assert sample != real_source, "the wrong-argument sample must differ from the real source"
        problems = self._main_blocks_problems(sample)
        assert (
            "main does not pass the worker job (workerJob) to blockUntilShutdown" in problems
        ), "whisper-server contract: passing anything but the worker job must be reported, got %r" % (
            problems,
        )

    def test_a_main_without_a_launch_bound_job_fails_the_block_check(self):
        """Removing only the `val workerJob = ` binding fails the G6 `launch is None` branch.

        That branch appends its own placeholder message (`<no launch-bound job>`); the
        expected string is built by concatenation and pinned whole so the wrong-argument
        branch's message (which names the real `workerJob`) cannot satisfy it.
        """
        real_source = self._real_source()
        sample = real_source.replace(
            "    val workerJob = workerScope.launch {", "    workerScope.launch {", 1
        )
        assert sample != real_source, "the no-binding sample must differ from the real source"
        problems = self._main_blocks_problems(sample)
        expected = (
            "main does not pass the worker job ("
            + "<"
            + "no launch-bound job"
            + ">"
            + ") to blockUntilShutdown"
        )
        assert expected in problems, (
            "whisper-server contract: a main with no launch-bound job must be reported with "
            "the placeholder message, got %r" % (problems,)
        )

    def test_main_does_not_just_fall_off_the_end(self):
        """A main that simply ends after `stopServer = ...` is reported: the pin is not tautological."""
        real_source = self._real_source()
        sample = real_source.replace("    blockUntilShutdown(workerJob)\n", "", 1)
        assert sample != real_source, "the fall-off sample must differ from the real source"
        problems = self._main_blocks_problems(sample)
        assert problems, (
            "whisper-server contract: a main that falls off the end must report a problem"
        )
        assert "main does not end with a call" in " ".join(problems), (
            "whisper-server contract: the fall-off-end source must be named as not ending in "
            "a call, got %r" % (problems,)
        )


if __name__ == "__main__":
    unittest.main()
