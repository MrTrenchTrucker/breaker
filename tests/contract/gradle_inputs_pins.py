"""Pin test: a Kotlin test that reads a repo file outside its own module's sources
must have that file declared as a Gradle input of the module's test tasks.

Why: Gradle treats a test task as up to date when only such a file changed (a card, an ADR, a
registry), so a docs edit can break a test while the build says nothing has to run.

How a module declares its reads (module build.gradle.kts, paths relative to the repo root):

    extra["repoFileInputs"] = listOf("decisions/ADR-010-retention.md")
    extra["repoFolderInputs"] = listOf("android/modules/phrases")

The shared helper gradle/test-reads-repo-files.gradle.kts turns the two lists into task inputs.

What this test does:
  1. scans every Kotlin test under src/test of android/, shared/ and server/ for string
     literals that name a repo file outside the module's own src/ (a repoFile(...) argument,
     "AGENTS.md" / "README.md" of the module itself, a "../" path, a decisions/, modules.toml,
     libs.versions, models.yaml, docs/ path) and resolves each against the repo root and the
     module folder; a literal that names nothing that exists is ignored (a fixture string);
  2. fails when a resolved path is not declared by the module (as a file, or inside a declared folder);
  3. fails when a declared path does not exist (a typo);
  4. fails when the shared helper is missing, is not applied from the root build, reads the lists
     at apply time, or declares the whole repository.
The scan lists the test files it found a literal in, so a new reader shows up by name.

Not a `test_*.py` file on purpose: the contract-file bijection counts every `test_*.py` as a module's
contract test, so this class is imported into test_android_contract.py and collected there.
"""
import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOPS = ("android", "shared", "server")
SKIP_DIRS = {"build", ".gradle", "node_modules", ".git"}
HELPER = "gradle/test-reads-repo-files.gradle.kts"

# A path literal counts only when it is an argument of a call that opens a file or resolves a path.
_LITERAL = re.compile(
    r'(?:\bFile|Paths\.get|Path\.of|\.resolve|\brepoFile|\bmainFile)\(\s*'
    r'(?:([A-Za-z_][A-Za-z0-9_.()]*)\s*,\s*)?"([^"\n]*)"'
)
# The base of a two-argument File(base, "x") is trusted only when it names a module or repo root;
# a File(tempDir, "README.md") is a fixture, not a read of the repository.
_ROOT_BASE = re.compile(r"(?i)module|root|repo|projectdir")
_REPO_HINT = re.compile(
    r"^(?:\.\./)*(?:AGENTS\.md|README\.md)$"
    r"|(?:^|/)(?:decisions|docs|gradle|modules|shared|android|server)/"
    r"|(?:^|/)(?:modules\.toml|models\.yaml|openapi\.yaml|ARCHITECTURE\.md|NOTICE|settings\.gradle\.kts"
    r"|build\.gradle\.kts|libs\.versions\.toml|tokens\.css)$"
    r"|^(?:\.\./)+[A-Za-z0-9_.-]"
)


def _walk(top):
    for dirpath, dirnames, filenames in os.walk(os.path.join(ROOT, top)):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        yield dirpath, filenames


def _modules():
    """Module folders (repo-relative) holding a build.gradle.kts and a src/test tree."""
    found = []
    for top in TOPS:
        for dirpath, filenames in _walk(top):
            if "build.gradle.kts" in filenames and os.path.isdir(os.path.join(dirpath, "src", "test")):
                found.append(os.path.relpath(dirpath, ROOT).replace(os.sep, "/"))
    return sorted(found)


def _declared(module):
    """(files, folders) a module declares in its build.gradle.kts."""
    with open(os.path.join(ROOT, module, "build.gradle.kts"), encoding="utf-8") as fh:
        text = fh.read()

    def lst(key):
        m = re.search(r'extra\["%s"\]\s*=\s*listOf\((.*?)\)' % key, text, re.S)
        return re.findall(r'"([^"]*)"', m.group(1)) if m else []

    return lst("repoFileInputs"), lst("repoFolderInputs")


def _test_files(module):
    base = os.path.join(ROOT, module, "src", "test")
    for dirpath, filenames in _walk(os.path.relpath(base, ROOT)):
        for name in filenames:
            if name.endswith(".kt"):
                yield os.path.join(dirpath, name)


def _normalise(path):
    return os.path.normpath(path).replace(os.sep, "/")


def _required(module):
    """{repo path: [test file names that name it]} for one module."""
    own_src = module + "/src/"
    need = {}
    for path in _test_files(module):
        with open(path, encoding="utf-8", errors="replace") as fh:
            text = fh.read()
        for base, lit in _LITERAL.findall(text):
            if base and not _ROOT_BASE.search(base):
                continue
            if lit == "build.gradle.kts":
                continue  # only used to find the module folder (File(folder, "build.gradle.kts").isFile)
            if len(lit) > 200 or not _REPO_HINT.search(lit) or not lit.strip("./"):
                continue
            # A bare card name is the module's own card; any other path is tried against the
            # module folder first and then the repo root (repoFile(...) arguments).
            if lit in ("AGENTS.md", "README.md"):
                candidates = [_normalise(module + "/" + lit)]
            else:
                candidates = [_normalise(module + "/" + lit), _normalise(lit)]
            for cand in candidates:
                if cand.startswith(".."):
                    continue
                if cand.startswith(own_src):
                    continue
                if os.path.exists(os.path.join(ROOT, cand)) and cand != module:
                    need.setdefault(cand, set()).add(os.path.basename(path))
                    break
    return need


def _covered(path, files, folders):
    if path in files:
        return True
    return any(path == f or path.startswith(f.rstrip("/") + "/") for f in folders)


# Tests that list their whole module folder (a file added, renamed or removed changes the result). The
# literal scan above cannot see such a reader, so each one is named here; the module must declare
# its own folder as a folder input, and the named test must still exist.
FOLDER_LISTERS = {
    "android/modules/phrases": "src/test/kotlin/dev/breaker/dictation/phrases/ModuleHygieneTest.kt",
    "android/modules/commit/accessibility":
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/ModuleFilesGateTest.kt",
}


class GradleInputsContractTest(unittest.TestCase):
    def test_folder_listing_tests_declare_their_module_folder(self):
        for module, test in sorted(FOLDER_LISTERS.items()):
            with self.subTest(module=module):
                self.assertTrue(os.path.isfile(os.path.join(ROOT, module, test)),
                                f"{module}/{test} is gone: update FOLDER_LISTERS")
                _files, folders = _declared(module)
                self.assertIn(module, folders, f"{module} lists its folder in a test but does not declare it")

    def test_every_outside_read_is_declared(self):
        problems = []
        for module in _modules():
            files, folders = _declared(module)
            for path, tests in sorted(_required(module).items()):
                if not _covered(path, files, folders):
                    problems.append(f"{module}: tests read {path} ({', '.join(sorted(tests))}) "
                                    f"but the build file does not declare it")
        self.assertEqual([], problems, "\n" + "\n".join(problems))

    def test_every_declared_path_exists(self):
        problems = []
        for module in _modules():
            files, folders = _declared(module)
            for path in files:
                if not os.path.isfile(os.path.join(ROOT, path)):
                    problems.append(f"{module}: declared file {path} does not exist")
            for path in folders:
                if not os.path.isdir(os.path.join(ROOT, path)):
                    problems.append(f"{module}: declared folder {path} is not a folder")
        self.assertEqual([], problems, "\n" + "\n".join(problems))

    def test_the_scan_finds_the_known_readers(self):
        # A scan that silently found nothing would pass the two tests above for the wrong reason.
        seen = {m: _required(m) for m in _modules()}
        self.assertIn("decisions/ADR-010-retention.md", seen["android/modules/history"])
        self.assertIn("android/modules/core/AGENTS.md", seen["android/modules/core"])
        self.assertTrue(seen["android/app"], "the app module reads repo files")
        self.assertIn("android/app/build.gradle.kts", seen["android/modules/history"])

    def test_the_shared_helper_is_in_place(self):
        helper = os.path.join(ROOT, HELPER)
        self.assertTrue(os.path.isfile(helper), HELPER + " is missing")
        with open(helper, encoding="utf-8") as fh:
            body = "\n".join(l for l in fh.read().splitlines() if not l.lstrip().startswith("//"))
        self.assertIn("afterEvaluate", body, "the lists must be read after the module script ran")
        self.assertIn('"repoFileInputs"', body)
        self.assertIn('"repoFolderInputs"', body)
        self.assertNotIn("fileTree(rootDir", body, "never declare the whole repository")
        self.assertNotIn("fileTree(repoRoot)", body, "never declare the whole repository")
        with open(os.path.join(ROOT, "build.gradle.kts"), encoding="utf-8") as fh:
            root_build = fh.read()
        self.assertRegex(root_build, r"subprojects\s*\{[^}]*test-reads-repo-files\.gradle\.kts",
                         "the root build must apply the helper to every subproject")

    def test_no_module_declares_the_whole_repository(self):
        for module in _modules():
            _files, folders = _declared(module)
            for folder in folders:
                self.assertNotIn(folder.strip("/"), ("", ".", "android", "shared", "server"),
                                 f"{module} declares too much: {folder}")


if __name__ == "__main__":
    unittest.main()
