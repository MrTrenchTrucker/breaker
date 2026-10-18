"""Source pins for the model unpack of `android/modules/stt-ondevice`.

Not a `test_*.py` file on purpose: the contract-file bijection counts every
`tests/contract/test_*.py` as one module's contract test. The class below is
collected through `test_stt_ondevice_contract.py`, which imports it, so the
card's one contract test file still runs these pins.

Every pin reads CODE only, through the scanner of the main contract file:
comments (KDoc included) are removed and string literals are reduced to a
marker. Identifiers are matched as whole words. The scanner and the bracket
helpers are borrowed lazily from that file, so there is one copy of each.
"""
import os
import re
import unittest

import contract_support

MODULE = "android/modules/stt-ondevice/"
MAIN = MODULE + "src/main/kotlin/dev/breaker/dictation/stt/ondevice/"
TESTS = MODULE + "src/test/kotlin/dev/breaker/dictation/stt/ondevice/"
EXTRACTOR = MAIN + "ModelExtractor.kt"
OUTCOME = MAIN + "ExtractionOutcome.kt"
BUILD = MODULE + "build.gradle.kts"
CATALOG = "gradle/libs.versions.toml"
NEW_MAIN = ("ExtractionOutcome.kt", "ExtractionProfiles.kt", "EntryRules.kt", "BoundedStream.kt", "ModelExtractor.kt")
PURE = tuple(n for n in NEW_MAIN if n != "ModelExtractor.kt")
ARCHIVE_LIB = r"\borg\s*\.\s*apache\s*\.\s*commons\b"
SINK = (r"\bFile\s*\(|\bFileOutputStream\b|\bRandomAccessFile\b|\bFiles\s*\.|\.\s*outputStream\s*\(|"
        r"\.\s*(?:writeBytes|writeText|appendBytes|appendText|copyTo|mkdirs?|renameTo)\s*\(")
LINKS = (r"\b(?:createSymbolicLink|createLink|createLinkTo|readSymbolicLink|symlink|link|toRealPath|"
         r"canonicalFile|canonicalPath|linkName|FileSystems|Paths|Path)\b|\.\s*(?:toPath|resolve)\s*\(")
THREADS = r"\b(?:Thread|CountDownLatch|Timer|Executors?|Atomic\w*|[Ss]ynchronized)\b|\bsleep\s*\("
KEYWORDS = frozenset(("if", "else", "when", "val", "var", "return", "throw", "null", "true", "false", "it", "this"))


def _base():
    """The main contract module and its test class (imported on first use)."""
    import test_stt_ondevice_contract as base
    return base, base.AndroidModulesSttOndeviceContractTest


def _scan(text, keep_strings=False):
    return _base()[0]._scan_kotlin(text, keep_strings)


class SttOndeviceExtractPinsTest(unittest.TestCase):

    def _read(self, rel):
        path = os.path.join(contract_support.ROOT, rel)
        self.assertTrue(os.path.isfile(path), f"missing: {rel}")
        with open(path, "r", encoding="utf-8") as fh:
            return fh.read()

    def _code(self, rel):
        return _scan(self._read(rel))

    def _tree(self, rel_dir, keep_strings=False, loose=False):
        """Kotlin files under `rel_dir` as {name: code}. `loose` strips comments with two plain patterns:
        the exact scanner stops at some character literals the test sources use (`'\\u0001'`)."""
        found = {}
        for root, _, names in os.walk(os.path.join(contract_support.ROOT, rel_dir)):
            for name in names:
                if name.endswith(".kt"):
                    with open(os.path.join(root, name), "r", encoding="utf-8") as fh:
                        raw = fh.read()
                        found[name] = (re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", raw, flags=re.S))
                                       if loose else _scan(raw, keep_strings))
        self.assertTrue(found, f"no Kotlin sources under {rel_dir}")
        return found

    # --- 1. the archive library has one importer -------------------------

    def test_archive_library_is_imported_by_the_extractor_only(self):
        """Among main sources `org.apache.commons` appears in ModelExtractor.kt only, and there only as
        `org.apache.commons.compress.`; among test sources only TarFixtures.kt names it."""
        main = self._tree(MAIN)
        users = sorted(n for n, t in main.items() if re.search(ARCHIVE_LIB, t))
        self.assertEqual(users, ["ModelExtractor.kt"], "library pin: only ModelExtractor.kt may use the archive library")
        paths = re.findall(r"\borg\s*\.\s*apache\s*\.\s*commons\s*\.\s*(\w+)", main["ModelExtractor.kt"])
        self.assertTrue(paths and set(paths) == {"compress"},
                        f"library pin: ModelExtractor.kt may use only commons.compress (found {sorted(set(paths))})")
        tests = sorted(n for n, t in self._tree(TESTS, loose=True).items() if re.search(ARCHIVE_LIB, t))
        self.assertEqual(tests, ["TarFixtures.kt"], "library pin: only TarFixtures.kt may use the archive library in tests")

    # --- 2. written names come from the profile --------------------------

    def test_written_file_names_come_from_the_profile_not_from_the_archive(self):
        """In ModelExtractor.kt `writeFile(` is called with `File(<dir>, N)` where N is bound once, to
        `[if (...)] profile.files.firstOrNull { ... } [else null]`; every `File(` call names only
        directory variables or N; `openOutput.invoke(` gets the one file parameter; the four other new
        main files hold no file-creating call at all."""
        code = self._code(EXTRACTOR)
        calls = [m for m in re.finditer(r"(?<!\bfun )\bwriteFile\s*\(", code)]
        self.assertTrue(calls, "name pin: writeFile( is never called")
        bound = set()
        for call in calls:
            end = _base()[0]._close_of(code, call.end() - 1)
            args = _base()[0]._top_level_parts(code[call.end():end])
            self.assertGreaterEqual(len(args), 2, "name pin: writeFile( needs the output file as its second argument")
            shape = re.fullmatch(r"\s*File\s*\(\s*(\w+)\s*,\s*(\w+)\s*\)\s*", args[1])
            self.assertIsNotNone(shape, f"name pin: the file written must be File(<dir>, <profile name>), got {args[1].strip()!r}")
            bound.add(shape.group(2))
        self.assertEqual(len(bound), 1, f"name pin: one name variable expected, got {sorted(bound)}")
        name = next(iter(bound))
        decls = list(re.finditer(r"\b(?:val|var)\s+" + name + r"\b\s*(?::[^=\n]*)?=\s*", code))
        self.assertEqual(len(decls), 1, f"name pin: `{name}` must be bound exactly once (found {len(decls)})")
        self.assertEqual(len(re.findall(r"(?<![.\w])" + name + r"\s*=(?!=)", code)), 1,
                         f"name pin: `{name}` must never be assigned again")
        value = code[decls[0].end():_base()[0]._expression_end(code, decls[0].end())].strip()
        guard = re.match(r"if\s*\(", value)
        if guard:
            value = value[_base()[0]._close_of(value, guard.end() - 1) + 1:].strip()
        pick = re.match(r"profile\s*\.\s*files\s*\.\s*(?:firstOrNull|first|find)\s*\{", value)
        self.assertIsNotNone(pick, f"name pin: `{name}` must be an element picked from profile.files, got {value!r}")
        rest = value[_base()[0]._close_of(value, pick.end() - 1) + 1:]
        self.assertRegex(rest, r"\A\s*(?:else\s+null\s*)?\Z", f"name pin: nothing may follow the profile pick ({rest!r})")
        allowed = {"workDir", "target", "parent", "staging", name}
        for m in re.finditer(r"\bFile\s*\(", code):
            args = code[m.end():_base()[0]._close_of(code, m.end() - 1)]
            words = {w for w in re.findall(r"(?<![.\w])[A-Za-z_]\w*", args)} - KEYWORDS
            self.assertLessEqual(words, allowed, f"name pin: File({args.strip()}) uses a name that may come from the archive: {sorted(words - allowed)}")
        outs = list(re.finditer(r"\bopenOutput\s*\.\s*invoke\s*\(", code))
        self.assertTrue(outs, "name pin: openOutput.invoke( is missing")
        decl = re.search(r"\bfun\s+writeFile\s*\(", code)
        self.assertIsNotNone(decl, "name pin: fun writeFile( is missing")
        params = [p.split(":")[0].strip() for p in _base()[0]._top_level_parts(code[decl.end():_base()[0]._close_of(code, decl.end() - 1)])]
        self.assertGreaterEqual(len(params), 2, "name pin: writeFile needs a file parameter")
        for m in outs:
            self.assertEqual(code[m.end():_base()[0]._close_of(code, m.end() - 1)].strip(), params[1],
                             "name pin: openOutput must open the file parameter of writeFile and nothing else")
        for n in PURE:
            self.assertIsNone(re.search(SINK, self._code(MAIN + n)), f"name pin: {n} must not create or write files")

    # --- 3. no links, no path following ----------------------------------

    def test_extractor_creates_no_links_and_follows_no_entry_path(self):
        """ModelExtractor.kt names no link creation, no `linkName`, no `Path`/`Paths`/`.toPath(`/`.resolve(`,
        no canonical path call; no main file of the module creates a link."""
        code = self._code(EXTRACTOR)
        self.assertIsNone(re.search(LINKS, code), f"link pin: ModelExtractor.kt uses a link or path call: {re.search(LINKS, code)}")
        self.assertIsNotNone(re.search(r"\bFile\s*\(", code), "link pin: the File( control did not match anything")
        for name, text in sorted(self._tree(MAIN).items()):
            self.assertNotRegex(text, r"\b(?:createSymbolicLink|createLink|createLinkTo)\b", f"link pin: {name} creates a link")

    # --- 4. every reason is produced -------------------------------------

    def _reasons(self):
        code = self._code(OUTCOME)
        head = re.search(r"\benum\s+class\s+ExtractionReason\s*\(", code)
        self.assertIsNotNone(head, "reason pin: enum class ExtractionReason is missing")
        body_at = code.index("{", _base()[0]._close_of(code, head.end() - 1))
        body = code[body_at:_base()[0]._close_of(code, body_at)]
        names = re.findall(r"\b([A-Z][A-Z_]+)\s*\(\s*ExtractionFault\s*\.", body)
        self.assertGreaterEqual(len(names), 21, f"reason pin: the table has 21 reasons, parsed {len(names)}")
        self.assertEqual(len(set(names)), len(names), "reason pin: a reason is declared twice")
        return names

    def test_every_extraction_reason_is_produced_by_the_extractor_or_the_entry_rules(self):
        """Each ExtractionReason is written as `ExtractionReason.NAME` in ModelExtractor.kt or EntryRules.kt."""
        made = self._code(EXTRACTOR) + "\n" + self._code(MAIN + "EntryRules.kt")
        missing = [n for n in self._reasons() if not re.search(r"\bExtractionReason\s*\.\s*" + n + r"\b", made)]
        self.assertEqual(missing, [], f"reason pin: these reasons are never produced: {missing}")

    def test_every_extraction_reason_is_named_in_a_test(self):
        """Each ExtractionReason is named, as `ExtractionReason.NAME` or bare, in at least one test source."""
        text = "\n".join(self._tree(TESTS, loose=True).values())
        missing = [n for n in self._reasons() if not re.search(r"\b" + n + r"\b", text)]
        self.assertEqual(missing, [], f"reason pin: these reasons are named in no test: {missing}")

    # --- 5. the catalog --------------------------------------------------

    def test_catalog_pins_the_archive_library_with_a_dated_source(self):
        """`commons-compress = "X.Y.Z"` under [versions] (stable, no suffix) ends in
        `# read YYYY-MM-DD from https://repo1.maven.org/maven2/.../commons-compress/maven-metadata.xml`; the
        library entry names org.apache.commons:commons-compress; the three transitive libraries are
        comment lines with their own dated source and have no entry."""
        text = self._read(CATALOG)
        dated = r'#\s*read\s+\d{4}-\d{2}-\d{2}\s+from\s+https://repo1\.maven\.org/maven2/'
        ver = re.findall(r'^commons-compress\s*=\s*"(\d+\.\d+\.\d+)"[ \t]+' + dated
                         + r'org/apache/commons/commons-compress/maven-metadata\.xml[ \t]*$', text, re.M)
        self.assertEqual(len(ver), 1, "catalog pin: commons-compress needs one stable version line with a dated source")
        lib = re.findall(r'^commons-compress\s*=\s*\{\s*module\s*=\s*"org\.apache\.commons:commons-compress"\s*,'
                         r'\s*version\.ref\s*=\s*"commons-compress"\s*\}\s*$', text, re.M)
        self.assertEqual(len(lib), 1, "catalog pin: commons-compress needs one library entry on its version")
        for art, path in (("commons-codec", "commons-codec/commons-codec"), ("commons-io", "commons-io/commons-io"),
                          ("commons-lang3", "org/apache/commons/commons-lang3")):
            line = re.findall(r'^#[^\n]*\b' + art + r'\s+\d+\.\d+\.\d+[^\n]*' + dated + re.escape(path)
                              + r'/maven-metadata\.xml[ \t]*$', text, re.M)
            self.assertEqual(len(line), 1, f"catalog pin: {art} needs one comment line with a version and a dated source")
            self.assertIsNone(re.search(r'^' + art + r'\s*=', text, re.M), f"catalog pin: {art} must stay a comment, not an entry")

    def test_catalog_version_is_the_release_the_unpack_was_written_against(self):
        """The `commons-compress` version line reads exactly "1.28.0". The tar and bzip2 behaviour that
        `ModelExtractor.kt` and its tests rely on was read against that release, so moving to another
        release is an edit of this pin as well, made on purpose."""
        found = re.findall(r'^commons-compress\s*=\s*"([^"]*)"', self._read(CATALOG), re.M)
        self.assertEqual(found, ["1.28.0"], f"catalog pin: the commons-compress version must be 1.28.0 (found {found})")

    # --- 6. the build file -----------------------------------------------

    def test_build_file_adds_exactly_one_external_dependency(self):
        """The module build file lists these dependencies and no others: the two project edges,
        `libs.kotlinx.coroutines.core`, `libs.commons.compress` (once), `libs.junit` for tests, and
        `compileOnly(sherpaCoordinate)`. That last one is the release file of the speech engine, used to
        compile the one binding file only and never packaged: the app supplies it at run time. The same
        coordinate is also added to the verify configuration with `add(...)`, which this pin does not see."""
        code = _scan(self._read(BUILD), True)
        found = re.findall(r"\b(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|"
                           r"androidTestImplementation|debugImplementation|kapt|ksp|annotationProcessor)\s*\(\s*([^()]*(?:\([^()]*\))?[^()]*)\)",
                           code)
        got = sorted((k, " ".join(v.split())) for k, v in found)
        want = sorted([("implementation", 'project(":android:modules:core")'),
                       ("implementation", 'project(":shared:modules:model-registry")'),
                       ("implementation", "libs.kotlinx.coroutines.core"),
                       ("implementation", "libs.commons.compress"),
                       ("testImplementation", "libs.junit"),
                       ("compileOnly", "sherpaCoordinate")])
        self.assertEqual(got, want, "build pin: the dependency list changed")
        self.assertEqual(len(re.findall(r"commons", code)), 1, "build pin: commons-compress must be named on exactly one line")
        self.assertEqual(len(re.findall(r"\bimplementation\s*\(\s*libs\.commons\.compress\s*\)", code)), 1,
                         "build pin: implementation(libs.commons.compress) must appear exactly once")

    # --- 7. no thread primitives -----------------------------------------

    def test_new_main_files_use_no_thread_primitive(self):
        """The five new main files hold no `Thread`, `synchronized`, `Atomic*`, `CountDownLatch`, `Timer`,
        `Executor*` and no `sleep(`."""
        for name in NEW_MAIN:
            text = self._code(MAIN + name)
            self.assertIsNone(re.search(THREADS, text), f"thread pin: {name} uses a thread primitive: {re.search(THREADS, text)}")

    # --- 8. sparse entries -----------------------------------------------

    def test_sparse_entries_are_refused_before_any_file_is_written(self):
        """In ModelExtractor.kt `if (entry.isSparse) throw Stop(ExtractionReason.NOT_REGULAR, ...)` is
        present once and sits before the first call of `writeFile(`."""
        code = self._code(EXTRACTOR)
        guard = list(re.finditer(r"\bif\s*\(\s*entry\s*\.\s*isSparse\s*(?:\(\s*\))?\s*\)\s*(?:\{\s*)?throw\s+Stop\s*\(\s*"
                                 r"ExtractionReason\s*\.\s*NOT_REGULAR\b", code))
        self.assertEqual(len(guard), 1, f"sparse pin: expected one sparse refusal with NOT_REGULAR (found {len(guard)})")
        call = re.search(r"(?<!\bfun )\bwriteFile\s*\(", code)
        self.assertIsNotNone(call, "sparse pin: writeFile( is never called")
        self.assertLess(guard[0].start(), call.start(), "sparse pin: the sparse refusal must come before the first writeFile(")
