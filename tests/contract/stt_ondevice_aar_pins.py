"""Source pins for the sherpa-onnx compile route of `android/modules/stt-ondevice`.

Not a `test_*.py` file on purpose: the contract-file bijection counts every
`tests/contract/test_*.py` as one module's contract test. The class below is
collected through `test_stt_ondevice_contract.py`, which imports it. The rules
themselves live in `stt_ondevice_aar_checks.py`; each test here reads the real
files and pass their text to one rule.
"""
import os
import unittest

import contract_support
from stt_ondevice_aar_checks import (
    BINDING,
    BUILD,
    CATALOG,
    MAIN,
    PROPS,
    RELEASE_TAG,
    SETTINGS,
    SKIPPED_DIRS,
    code_of,
    check_binding,
    check_compile_only,
    check_dated_sources,
    check_group,
    check_jdk_names_are_imported,
    check_library_scope,
    check_no_binaries,
    check_no_native_load,
    check_no_repositories_or_files,
    check_never_up_to_date,
    check_one_digest,
    check_pattern_words,
    check_props_digest,
    check_props_https,
    check_props_keys,
    check_settings_block,
    check_settings_mode,
    check_settings_reads_pin,
    check_task,
    check_versions_equal,
    check_wired,
    code_of
)
from stt_ondevice_aar_rules import check_jdk_simple_names_have_imports, check_task_messages

class SttOndeviceAarPinsTest(unittest.TestCase):

    def _read(self, rel):
        path = os.path.join(contract_support.ROOT, rel)
        self.assertTrue(os.path.isfile(path), f"missing: {rel}")
        with open(path, "r", encoding="utf-8") as fh:
            return fh.read()

    def _main(self):
        found = {}
        for root, _, names in os.walk(os.path.join(contract_support.ROOT, MAIN)):
            for name in names:
                if name.endswith(".kt"):
                    found[name] = code_of(self._read(os.path.relpath(os.path.join(root, name), contract_support.ROOT)))
        return found

    def _ok(self, problems, label):
        self.assertEqual(problems, [], f"aar pin ({label}): {problems}")

    def test_aar_pin_file_has_exactly_the_four_keys(self):
        """The pin file exists and holds version, sha256, repositoryUrl and artifactPattern once each, with a value."""
        self._ok(check_props_keys(self._read(PROPS)), "keys")

    def test_aar_pin_digest_is_one_line_of_64_lowercase_hex(self):
        """Exactly one `sha256=` line, 64 lowercase hex digits, no spaces (java.util.Properties keeps the last of two)."""
        self._ok(check_props_digest(self._read(PROPS)), "digest")

    def test_aar_pin_version_equals_the_catalog_version(self):
        """`version=` in the pin file and `sherpa-onnx = "..."` in the catalog are the same text."""
        self._ok(check_versions_equal(self._read(PROPS), self._read(CATALOG)), "version")

    def test_aar_pin_and_catalog_name_a_dated_source(self):
        """Both carry `# read YYYY-MM-DD from https://github.com/MrTrenchTrucker/breaker/releases/tag/sherpa-onnx-asr-v<version>`,
        with an optional `-rN` revision suffix (N from 1, no letters) right after the version; the suffix must equal
        the one in the tag part of artifactPattern in the pin file."""
        self._ok(check_dated_sources(self._read(PROPS), self._read(CATALOG)), "dated source")

    def test_dated_source_rule_accepts_and_refuses_fixed_texts(self):
        """The dated-source rule is handed small in-memory pin file and catalog texts: an exact tag or a `-rN` tag that
        matches the artifactPattern tag part passes; the upstream shape, a letter or zero suffix, a suffix the pattern
        lacks, a pattern suffix the lines lack, a pin file and a catalog that disagree, and a pattern with another tag
        shape are all refused."""
        base, tail = "https://github.com/MrTrenchTrucker/breaker/releases/tag/", "/[artifact]-v[revision]-asr-only.[ext]"
        tag = RELEASE_TAG + "1.13.8"
        plain, rev = "sherpa-onnx-asr-v[revision]" + tail, "sherpa-onnx-asr-v[revision]-r2" + tail
        cases = (  # (label, pin file url, catalog url, artifactPattern, expected problem count)
            ("exact tag, pattern without suffix", tag, tag, plain, 0),
            ("-r2 tag, pattern -r2", tag + "-r2", tag + "-r2", rev, 0),
            ("-r10 tag, pattern -r10", tag + "-r10", tag + "-r10", rev.replace("-r2", "-r10"), 0),
            ("upstream shape", base + "v1.13.8", base + "v1.13.8", plain, 2),
            ("letter suffix", tag + "-rX", tag + "-rX", plain, 2),
            ("letter suffix, pattern with the same letter", tag + "-rX", tag + "-rX", rev.replace("-r2", "-rX"), 3),
            ("zero suffix", tag + "-r0", tag + "-r0", plain, 2),
            ("zero suffix, pattern with the same zero", tag + "-r0", tag + "-r0", rev.replace("-r2", "-r0"), 3),
            ("two suffixes", tag + "-r2-r3", tag + "-r2-r3", rev, 2),
            ("lines -r2, pattern without suffix", tag + "-r2", tag + "-r2", plain, 2),
            ("lines without suffix, pattern -r2", tag, tag, rev, 2),
            ("pin file -r2, catalog without suffix", tag + "-r2", tag, rev, 1),
            ("pin file without suffix, catalog -r2", tag, tag + "-r2", rev, 1),
            ("pin file -r2, catalog -r3, pattern -r2", tag + "-r2", tag + "-r3", rev, 1),
            ("pattern with another tag shape", tag, tag, "sherpa-onnx-asr-v[revision]-x" + tail, 1),
            ("pattern with no slash", tag, tag, "sherpa-onnx-asr-v[revision]", 1),
        )
        for label, pin_url, cat_url, pattern, count in cases:
            props = f"# read 2026-10-08 from {pin_url}\nversion=1.13.8\nartifactPattern={pattern}\n"
            catalog = f'sherpa-onnx = "1.13.8"   # read 2026-10-08 from {cat_url}\n'
            got = check_dated_sources(props, catalog)
            self.assertEqual(len(got), count, f"dated source rule on '{label}': {got}")

    def test_aar_pin_address_is_fetched_over_https(self):
        """`repositoryUrl` in the pin file starts with `https://`; a plain `http://` address is refused."""
        self._ok(check_props_https(self._read(PROPS)), "https")

    def test_settings_keeps_the_strict_repository_mode(self):
        """repositoriesMode stays FAIL_ON_PROJECT_REPOS."""
        self._ok(check_settings_mode(code_of(self._read(SETTINGS))), "mode")

    def test_settings_serves_the_aar_from_one_exclusive_ivy_repository(self):
        """One exclusiveContent { forRepository { ivy } filter { includeModule } } before google(); nothing else is added."""
        self._ok(check_settings_block(code_of(self._read(SETTINGS))), "settings block")

    def test_settings_takes_the_address_from_the_pin_file(self):
        """The ivy url and pattern are the values of repositoryUrl and artifactPattern; a missing key throws."""
        self._ok(check_settings_reads_pin(code_of(self._read(SETTINGS))), "settings reads the pin")

    def test_aar_group_is_the_same_in_settings_and_the_module_build(self):
        """The group string of the filter equals sherpaGroup, and the coordinate is group:sherpa-onnx:catalog version@aar."""
        self._ok(check_group(code_of(self._read(SETTINGS)), code_of(self._read(BUILD))), "group")

    def test_verify_task_is_registered_and_refuses_every_bad_case(self):
        """VerifySherpaAar exists, is registered as verifySherpaAar with the file, the pin and the catalog version,
        and its body holds each refusal, the SHA-256 hash and the message with both ends and the full digests."""
        self._ok(check_task(self._read(BUILD), code_of(self._read(BUILD))), "task")

    def test_verify_task_runs_before_the_build_steps(self):
        """preBuild depends on the task, and every task named compile*, lint*, test*, bundle* (and four more) does too."""
        self._ok(check_wired(code_of(self._read(BUILD))), "wiring")

    def test_task_name_pattern_names_all_eight_words(self):
        """The pattern holds compile, lint, test, bundle, extract, check, assemble and build, each as a whole word."""
        self._ok(check_pattern_words(code_of(self._read(BUILD))), "name pattern words")

    def test_verify_task_is_never_skipped_as_up_to_date(self):
        """VerifySherpaAar holds `init { outputs.upToDateWhen { false } }` once, so it hashes the file on every build."""
        self._ok(check_never_up_to_date(code_of(self._read(BUILD))), "never up to date")

    def test_build_file_uses_imports_for_jdk_names(self):
        """A dotted java.util or java.security name in the build file resolves against the build's `java` extension
        and does not compile; the class must be imported and written by its simple name."""
        self._ok(check_jdk_names_are_imported(code_of(self._read(BUILD))), "jdk names")

    def test_aar_coordinate_is_declared_compile_only(self):
        """The coordinate appears in add(sherpaAar.name, ...) and compileOnly(...) and in no other call."""
        self._ok(check_compile_only(code_of(self._read(BUILD))), "compile only")

    def test_module_build_declares_no_repository_and_no_file_dependency(self):
        """No `repositories {`, `flatDir`, `files(` or `fileTree(` in the module build file."""
        self._ok(check_no_repositories_or_files(code_of(self._read(BUILD))), "build file")

    def test_the_pinned_digest_is_written_once(self):
        """The pinned digest text is in no other file: not the settings file, not the module build file, not a main source."""
        named = {SETTINGS: code_of(self._read(SETTINGS)), BUILD: code_of(self._read(BUILD))}
        named.update(self._main())
        self._ok(check_one_digest(self._read(PROPS), named), "one digest")

    def test_only_the_binding_names_the_sherpa_library(self):
        """`com.k2fsa` appears in SherpaOnnxBinding.kt and in no other main source."""
        self._ok(check_library_scope(self._main()), "library scope")

    def test_no_main_source_loads_a_native_library(self):
        """No `loadLibrary` and no `System.load(` in code."""
        self._ok(check_no_native_load(self._main()), "native load")

    def test_the_binding_imports_six_types_and_sets_the_decode_fields(self):
        """Six imports and no more; internal object implementing NativeStreamingOpener; the nine config values."""
        self._ok(check_binding(self._main().get(BINDING, "")), "binding")

    def test_no_binary_is_kept_under_android(self):
        """No .aar, .so or .jar file anywhere under android/ (build output directories are not repository files)."""
        found = []
        top = os.path.join(contract_support.ROOT, "android")
        for cur, dirs, names in os.walk(top):
            dirs[:] = [d for d in dirs if d not in SKIPPED_DIRS]
            found += [os.path.relpath(os.path.join(cur, n), contract_support.ROOT) for n in names]
        self.assertTrue(any(p.endswith(".kt") for p in found), "binary pin: the walk found no Kotlin file; it is not looking at the tree")
        self._ok(check_no_binaries(found), "binaries")

    def test_build_file_imports_every_jdk_class_it_names(self):
        """Properties and MessageDigest are written by their simple names in the build file, so each one's
        import line must be there; without it the script does not compile."""
        self._ok(check_jdk_simple_names_have_imports(code_of(self._read(BUILD))), "jdk imports")

    def test_verify_task_prints_the_pinned_messages(self):
        """The mismatch message (both short digests, the file name, the full digests, the closing sentence) and the
        success line are the pinned words, each once, in the VerifySherpaAar body."""
        self._ok(check_task_messages(code_of(self._read(BUILD))), "task messages")

    def test_jdk_name_rule_flags_dotted_names_and_ignores_the_rest(self):
        """The dotted-name rule is handed fixed texts: a dotted name in any listed package is a problem; an import
        line, a string literal and a comment are not. A rule that always passes goes red here."""
        for dotted in ("java.util.Properties()", 'java.security.MessageDigest.getInstance("SHA-256")', "java.io.File(p)",
                       "java.net.URI.create(u)", "java.nio.file.Paths.get(p)", "java.lang.Math.abs(n)", "java.time.Instant.now()"):
            self.assertNotEqual(check_jdk_names_are_imported(code_of(f"val x = {dotted}")), [], f"dotted name not flagged: {dotted}")
        for fine in ("import java.util.Properties\nval p = Properties()", 'val s = "java.util.Properties"',
                     "// java.util.Properties\nval p = Properties()", "/* java.security.MessageDigest */\nval p = 1"):
            self.assertEqual(check_jdk_names_are_imported(code_of(fine)), [], f"wrongly flagged: {fine!r}")

    def test_jdk_import_and_task_message_rules_flag_their_bad_texts(self):
        """The simple-name import rule and the message rule are handed fixed texts, so a rule that always passes
        goes red here instead of staying quiet on the real, correct file."""
        imports = "import java.security.MessageDigest\nimport java.util.Properties\n"
        use = 'val p = Properties()\nval m = MessageDigest.getInstance("SHA-256")\n'
        self.assertEqual(check_jdk_simple_names_have_imports(code_of(imports + use)), [])
        self.assertEqual(check_jdk_simple_names_have_imports(code_of("import java.security.MessageDigest\nimport java.util.*\n" + use)), [])
        for name, bad in (("Properties", "import java.security.MessageDigest\n" + use), ("MessageDigest", "import java.util.Properties\n" + use),
                          ("Properties", "import java.security.MessageDigest\nimport java.sql.Properties\n" + use),
                          ("Properties", "import java.security.MessageDigest\n// import java.util.Properties\n" + use)):
            got = check_jdk_simple_names_have_imports(code_of(bad))
            self.assertEqual([p.split()[0] for p in got], [name], f"simple-name rule on {bad!r}: {got}")
        self.assertEqual(check_jdk_simple_names_have_imports(code_of('val s = "Properties() MessageDigest."\n')), [])
        for other in ('val s = "Properties() MessageDigest."\n', "val q = other.Properties()\n", "val q = MyProperties()\n", "val q = PropertiesFoo()\n"):
            self.assertEqual(check_jdk_simple_names_have_imports(code_of(other)), [], f"wrongly flagged: {other!r}")
        for lax in ("myimport java.util.Properties\nval p = Properties()\n", "import java.util.PropertiesFoo\nval p = Properties()\n",
                    "import java.utilx.Properties\nval p = Properties()\n",
                    "import javaxutil.Properties\nval p = Properties()\n"):
            self.assertNotEqual(check_jdk_simple_names_have_imports(code_of(lax + "import java.security.MessageDigest\n")), [], f"import accepted: {lax!r}")
        task = ("abstract class VerifySherpaAar : DefaultTask() {\n  fun verify() {\n    throw GradleException(\n"
                '      "sherpa-onnx AAR SHA-256 mismatch for ${file.name}: expected ${short(expected)}, actual ${short(actual)} " +\n'
                '        "(full expected $expected, actual $actual). The file in the Gradle cache is not the pinned release; build stopped."\n'
                '    )\n    logger.lifecycle("sherpa-onnx AAR verified: ${file.name} sha256 ${short(actual)}")\n  }\n}\n')
        self.assertEqual(check_task_messages(code_of(task)), [])
        for old, new in (("SHA-256 mismatch for", "checksum problem with"), ("mismatch for ${file.name}", "mismatch for ${pinFile.name}"),
                         ("pinned release; build stopped.", "pinned release."), ("verified: ${file.name} sha256 ${short(actual)}", "verified"),
                         ('    logger.lifecycle("sherpa-onnx AAR verified: ${file.name} sha256 ${short(actual)}")\n', ""),
                         ("sha256 ${short(actual)}", "sha256 ${short(expected)}"), ("expected ${short(expected)}, actual ${short(actual)} ", "actual ${short(actual)}, expected ${short(expected)} ")):
            self.assertIn(old, task, f"the fixture lost the text {old!r}")
            self.assertNotEqual(check_task_messages(code_of(task.replace(old, new, 1))), [], f"message rule silent on {new!r}")
        success = '    logger.lifecycle("sherpa-onnx AAR verified: ${file.name} sha256 ${short(actual)}")\n'
        self.assertNotEqual(check_task_messages(code_of(task.replace(success, success + success, 1))), [], "a doubled line is not 'once'")
        self.assertNotEqual(check_task_messages(code_of("class Other {}\n")), [])

    def test_task_message_rule_looks_only_inside_the_verify_task(self):
        """The message rule searches the VerifySherpaAar body only: both messages printed in a function outside the
        task are not counted, so the rule still reports the task as missing them."""
        outside = ("abstract class VerifySherpaAar : DefaultTask() {\n}\nfun other() {\n"
                   '  throw GradleException("sherpa-onnx AAR SHA-256 mismatch for ${file.name}: expected ${short(expected)}, actual ${short(actual)} " +\n'
                   '    "(full expected $expected, actual $actual). The file in the Gradle cache is not the pinned release; build stopped.")\n'
                   '  logger.lifecycle("sherpa-onnx AAR verified: ${file.name} sha256 ${short(actual)}")\n}\n')
        self.assertNotEqual(check_task_messages(code_of(outside)), [], "messages printed outside the task body were counted")

    def test_the_build_file_pins_go_red_on_a_bad_build_file(self):
        """Each pin that reads the module build file is handed a bad copy of that file, served for that one path only,
        and must fail. A pin that reads another file, or drops the answer of its rule, stays green and fails here."""
        real = self._read(BUILD)
        bad = {
            "test_build_file_imports_every_jdk_class_it_names": real.replace("import java.util.Properties\n", "", 1),
            "test_build_file_uses_imports_for_jdk_names": real.replace("Properties()", "java.util.Properties()", 1),
            "test_verify_task_prints_the_pinned_messages": real.replace("SHA-256 mismatch for", "checksum problem with", 1),
        }
        for name, text in bad.items():
            self.assertNotEqual(text, real, f"the bad copy for {name} is the real file")
            probe = SttOndeviceAarPinsTest(name)
            probe._read = lambda rel, t=text: t if rel == BUILD else self._read(rel)
            with self.assertRaises(AssertionError, msg=f"{name} stayed green on a bad build file"):
                getattr(probe, name)()
