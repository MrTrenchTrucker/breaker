"""Gate: no speech model id appears in the app's main Kotlin.

Models are data: no main Kotlin (android/ `/src/main/` `.kt`, plus the registry
module's main tree) may name a speech model. Ids come from models.yaml's `models:`
list plus the five known today (KNOWN_IDS); `keyword_models:` is not scanned.

Rule 1, case-sensitive, per line (ID is one speech id), all ids:
    "ID"   'ID'   `ID`   quoted or backticked, in code, comment or KDoc
    id = ID   id: ID     an id-style assignment, ID a whole word
Rule 2 (word_regex), case-INsensitive, only for ids that are not ordinary English
words (not small/tiny/base/medium: today `parakeet`, plus such ids from the data).
Whole word = not touching a letter or digit: `(?<![A-Za-z0-9])ID(?![A-Za-z0-9])`.
`parakeet-tdt`, `Parakeet`, `PARAKEET`, `parakeet_v3`, `parakeet.onnx` hit, in code,
comment, KDoc or string; `parakeets`, `MyParakeetsX` do not. Blind to an id glued
into camelCase (`isParakeetModel`). The four English-word ids keep rule 1 only.

Model CONSTANTS (scan_constants): `ModelRegistry.SMALL`, ... also name a model.
Names come from `val NAME: ModelEntry = ModelEntry(` lines (registry_constants). Hits:
    ModelRegistry.NAME      dotted, whole identifier, in code, comment or KDoc; an
                            `import ...ModelRegistry.NAME [as ALIAS]` line is one
    bare NAME (or ALIAS)    whole identifier, not after `.` or a word char, ONLY in
                            a file importing that constant (named, aliased or `.*`)
`ModelRegistry.ALL` is not a constant. Not covered: `ModelRegistry as MR` and
`with(ModelRegistry) { SMALL }`. Same allowlist.

Allowlist, exactly two entries (ALLOWLIST_*): (a) the generated registry file,
whole; (b) the core Settings.kt text `modelSize: String = "small"`, matched by file
AND exact text, cut out of the line; the rest of the line and file is scanned.

GATE_ROOT=<dir> runs it on another tree. Needs no compiler.
"""
import os
import re
import tempfile
import unittest

REPO_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)
ROOT = os.environ.get("GATE_ROOT") or REPO_ROOT

ENGLISH_IDS = ("small", "tiny", "base", "medium")  # prose words: rule 1 only
KNOWN_IDS = ENGLISH_IDS + ("parakeet",)

REGISTRY_REL = "shared/modules/model-registry/src/main/kotlin/dev/breaker/shared/models/ModelRegistry.kt"
SETTINGS_REL = "android/modules/core/src/main/kotlin/dev/breaker/dictation/core/model/Settings.kt"
ALLOWLIST_FILES = (REGISTRY_REL,)  # (a) the generated registry, whole file
ALLOWLIST_LINES = {SETTINGS_REL: 'modelSize: String = "small"'}  # (b) file + exact text

SCAN_DIRS = ("android", "shared/modules/model-registry/src/main")
ID_LINE_RE = re.compile(r"^\s*-\s*id:\s*(\S+)\s*$")


def speech_ids(yaml_text):
    """The `id:` of every entry in the `models:` list (not `keyword_models:`).

    A plain line read, so it needs no YAML library: an entry opens with
    `  - id: <id>`, and the list ends at the next top-level key.
    """
    ids, in_models = [], False
    for line in yaml_text.splitlines():
        if re.match(r"^[A-Za-z_]+:", line):
            in_models = line.startswith("models:")
            continue
        match = ID_LINE_RE.match(line)
        if in_models and match:
            ids.append(match.group(1).strip("'\""))
    return ids


def id_regex(ids):
    alt = "|".join(re.escape(i) for i in sorted(set(ids)))
    return re.compile(
        r"""(["'`])(?:%s)\1|\bid\s*[=:]\s*(?:%s)\b""" % (alt, alt)
    )


def word_regex(ids):
    """Rule 2: case-insensitive whole word, for the ids that are not English words."""
    names = sorted({i for i in ids if i.lower() not in ENGLISH_IDS})
    if not names:
        return None
    alt = "|".join(re.escape(i) for i in names)
    return re.compile(r"(?<![A-Za-z0-9])(?:%s)(?![A-Za-z0-9])" % alt, re.IGNORECASE)


def main_kotlin_files(root):
    for top in SCAN_DIRS:
        base = os.path.join(root, *top.split("/"))
        for cur, dirs, files in os.walk(base):
            dirs[:] = [d for d in dirs if d not in ("build", ".gradle", ".git")]
            for name in sorted(files):
                path = os.path.join(cur, name)
                rel = os.path.relpath(path, root).replace(os.sep, "/")
                if name.endswith(".kt") and "/src/main/" in "/" + rel:
                    yield path, rel


def scan(root, ids):
    """Every speech-id hit under root as 'rel:line: text', allowlist applied."""
    rx, word = id_regex(ids), word_regex(ids)
    hits = []
    for path, rel in main_kotlin_files(root):
        if rel in ALLOWLIST_FILES:
            continue
        allowed = ALLOWLIST_LINES.get(rel)
        with open(path, encoding="utf-8") as fh:
            for number, line in enumerate(fh, 1):
                text = line.rstrip("\n")
                if allowed and allowed in text:
                    text_to_scan = text.replace(allowed, "", 1)
                else:
                    text_to_scan = text
                if rx.search(text_to_scan) or (word and word.search(text_to_scan)):
                    hits.append("%s:%d: %s" % (rel, number, text.strip()))
    return hits


def current_ids(root=ROOT):
    path = os.path.join(root, "shared", "modules", "model-registry", "models.yaml")
    with open(path, encoding="utf-8") as fh:
        return list(KNOWN_IDS) + speech_ids(fh.read())


KNOWN_CONSTS = ("SMALL", "TINY", "BASE", "MEDIUM", "PARAKEET")
CONST_DECL_RE = re.compile(r"^\s*val\s+([A-Z][A-Z0-9_]*)\s*(?::\s*ModelEntry)?\s*=\s*ModelEntry\(")


def registry_constants(kotlin_text):
    """Names of the `val NAME: ModelEntry = ModelEntry(` constants in the generated file."""
    return [m.group(1) for m in map(CONST_DECL_RE.match, kotlin_text.splitlines()) if m]


def current_constants(root=ROOT):
    path = os.path.join(root, *REGISTRY_REL.split("/"))
    with open(path, encoding="utf-8") as fh:
        return list(KNOWN_CONSTS) + registry_constants(fh.read())


def _alt(names):
    return "|".join(re.escape(c) for c in sorted(set(names)))


def scan_constants(root, consts=KNOWN_CONSTS):
    """Every model-constant hit under root as 'rel:line: text', allowlist applied."""
    alt = _alt(consts)
    dotted = re.compile(r"\bModelRegistry\.(?:%s)\b" % alt)
    imp = re.compile(r"^\s*import\s+[\w.]*\bModelRegistry\.(%s|\*)(?:\s+as\s+(\w+))?\s*$" % alt)
    hits = []
    for path, rel in main_kotlin_files(root):
        if rel in ALLOWLIST_FILES:
            continue
        allowed = ALLOWLIST_LINES.get(rel)
        with open(path, encoding="utf-8") as fh:
            lines = [raw.rstrip("\n") for raw in fh]
        names = set()  # bare names in scope through an import of this file
        for text in lines:
            m = imp.match(text)
            if m:
                names.update(set(consts) if m.group(1) == "*" else {m.group(2) or m.group(1)})
        bare = re.compile(r"(?<![\w.])(?:%s)(?!\w)" % _alt(names)) if names else None
        for number, text in enumerate(lines, 1):
            scanned = text.replace(allowed, "", 1) if allowed and allowed in text else text
            if dotted.search(scanned) or (bare and bare.search(scanned)):
                hits.append("%s:%d: %s" % (rel, number, text.strip()))
    return hits


def _plant(root, rel, text):
    path = os.path.join(root, *rel.split("/"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


STT_MAIN = "android/modules/stt-ondevice/src/main/kotlin/x/Fake.kt"


class NoSpeechModelIdInMainTest(unittest.TestCase):
    def test_main_kotlin_names_no_speech_model_id(self):
        print("gate tree:", ROOT, "ids:", sorted(set(current_ids())))
        hits = scan(ROOT, current_ids())
        self.assertEqual([], hits, "speech model id in main Kotlin:\n" + "\n".join(hits))

    def test_ids_come_from_the_data_and_cover_the_known_five(self):
        ids = current_ids()
        for known in KNOWN_IDS:
            self.assertIn(known, ids)
        yml = "models:\n" + "".join("  - id: %s\n" % i for i in KNOWN_IDS) + "keyword_models:\n  - id: kws-x\n"
        self.assertEqual(list(KNOWN_IDS), speech_ids(yml))

    def _scan_planted(self, text, ids=KNOWN_IDS, rel=STT_MAIN):
        with tempfile.TemporaryDirectory() as tmp:
            return (_plant(tmp, rel, text), scan(tmp, ids))[1]

    def test_fires_on_a_quoted_literal(self):
        hits = self._scan_planted('val x = "parakeet"\n')
        self.assertEqual(1, len(hits), hits)

    def test_fires_on_a_kdoc_mention_in_backticks_and_in_quotes(self):
        self.assertEqual(1, len(self._scan_planted("/** The `parakeet` model. */\n")))
        for text in ("// the id is 'tiny' here\n", "// id = medium\n"):
            self.assertEqual(1, len(self._scan_planted(text)))

    def test_fires_on_an_id_added_to_the_data_only(self):
        self.assertEqual([], self._scan_planted('val x = "zeta"\n'))
        self.assertEqual(1, len(self._scan_planted('val x = "zeta"\n', ids=KNOWN_IDS + ("zeta",))))

    def test_plain_english_words_are_not_hits(self):
        text = "// a small base for a medium tiny change, Small and `smaller`\n"
        self.assertEqual([], self._scan_planted(text))

    def test_word_rule_fires_on_suffixed_and_capitalised_forms(self):
        for text in ("/** The Parakeet model. */\n", 'val m = "parakeet-tdt"\n',
                     "const val X = PARAKEET\n", "// parakeet_v3 and parakeet.onnx\n"):
            self.assertEqual(1, len(self._scan_planted(text)), text)

    def test_word_rule_ignores_longer_words_and_english_ids(self):
        for text in ("// parakeets and MyParakeetsX\n", "// the smallest Base, Tiny, MEDIUM-sized\n",
                     "val isParakeetModel = 1\n"):  # camelCase: stated gap
            self.assertEqual([], self._scan_planted(text), text)

    def test_word_rule_covers_a_non_english_id_from_the_data(self):
        self.assertEqual([], self._scan_planted("// Zeta-v2\n"))
        self.assertEqual(1, len(self._scan_planted("// Zeta-v2\n", ids=KNOWN_IDS + ("zeta",))))
        self.assertEqual([], self._scan_planted("// Base-v2\n", ids=KNOWN_IDS + ("base",)))

    def test_non_main_and_non_kotlin_files_are_not_scanned(self):
        for rel in ("android/modules/m/src/test/kotlin/T.kt", "android/modules/m/src/main/res/a.xml"):
            self.assertEqual([], self._scan_planted('"parakeet" Parakeet\n', rel=rel))

    def test_allowlist_the_registry_file_and_the_one_settings_line_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            _plant(tmp, REGISTRY_REL, 'const val ID = "parakeet"\nid = small\n')
            _plant(tmp, SETTINGS_REL, '    val modelSize: String = "small",\n')
            self.assertEqual([], scan(tmp, KNOWN_IDS))
            _plant(tmp, SETTINGS_REL, '    val modelSize: String = "small",\n    val other = "small"\n')
            self.assertEqual(1, len(scan(tmp, KNOWN_IDS)))
            _plant(tmp, SETTINGS_REL, '    val modelSize: String = "small", val b = "tiny"\n')
            self.assertEqual(1, len(scan(tmp, KNOWN_IDS)))
            _plant(tmp, SETTINGS_REL, "// clean\n")
            _plant(tmp, STT_MAIN, '    val modelSize: String = "small",\n')
            self.assertEqual(1, len(scan(tmp, KNOWN_IDS)))

    def test_the_keyword_model_id_is_not_scanned(self):
        self.assertNotIn("kws-gigaspeech-3m", current_ids())
        self.assertEqual([], self._scan_planted('val k = "kws-gigaspeech-3m"\n', ids=current_ids()))

    def test_main_kotlin_references_no_model_constant(self):
        hits = scan_constants(ROOT, current_constants())
        self.assertEqual([], hits, "model constant in main Kotlin:\n" + "\n".join(hits))

    def test_constants_come_from_the_generated_file(self):
        sample = "  val SMALL: ModelEntry = ModelEntry(\n  val ZETA = ModelEntry(\n  val ALL: List<ModelEntry> = listOf(\n"
        self.assertEqual(["SMALL", "ZETA"], registry_constants(sample))
        zeta = "val m = ModelRegistry.ZETA\n"  # covered once it is in the list
        self.assertEqual([], self._consts(zeta))
        self.assertEqual(1, len(self._consts(zeta, consts=KNOWN_CONSTS + ("ZETA",))))
        real = current_constants()
        self.assertTrue(set(KNOWN_CONSTS) <= set(real) and "ALL" not in real, real)

    def _consts(self, text, consts=KNOWN_CONSTS, rel=STT_MAIN):
        with tempfile.TemporaryDirectory() as tmp:
            return (_plant(tmp, rel, text), scan_constants(tmp, consts))[1]

    def test_fires_on_the_dotted_constant(self):
        self.assertEqual(1, len(self._consts("val m = ModelRegistry.SMALL\n")))
        self.assertEqual(1, len(self._consts("val m = dev.breaker.shared.models.ModelRegistry.TINY.sizeMb\n")))
        self.assertEqual(1, len(self._consts("/** Falls back to ModelRegistry.BASE. */\n")))  # KDoc, dotted: flagged

    def test_fires_on_a_bare_constant_only_in_a_file_that_imports_it(self):
        imported = "import dev.breaker.shared.models.ModelRegistry.PARAKEET\n\nval m = PARAKEET.sizeMb\n"
        hits = self._consts(imported)
        self.assertEqual([1, 3], [int(h.split(":")[1]) for h in hits], hits)
        self.assertEqual([], self._consts("val m = PARAKEET.sizeMb\n/** PARAKEET is a word. */\n"))  # no import
        star = self._consts("import dev.breaker.shared.models.ModelRegistry.*\nval m = MEDIUM\n")
        self.assertEqual([2], [int(h.split(":")[1]) for h in star], star)
        alias = self._consts("import a.ModelRegistry.SMALL as S\nval m = S.sizeMb\n")
        self.assertEqual([1, 2], [int(h.split(":")[1]) for h in alias], alias)

    def test_unrelated_identifiers_are_not_hits(self):
        self.assertEqual([], self._consts("const val SMALL_FILE = 1\nval x = SMALL_FILE + Other.SMALL\n"
                                          "val a = ModelRegistry.SMALL_FILE\nval b = ModelRegistry.ALL\n"))
        mixed = self._consts("import a.ModelRegistry.TINY\nval f = SMALL_FILE\nval g = Other.TINY\nval t = TINY\n")
        self.assertEqual([1, 4], [int(h.split(":")[1]) for h in mixed], mixed)

    def test_constants_non_main_files_and_the_allowlist(self):
        self.assertEqual([], self._consts("val m = ModelRegistry.SMALL\n", rel="android/modules/m/src/test/kotlin/T.kt"))
        with tempfile.TemporaryDirectory() as tmp:
            _plant(tmp, REGISTRY_REL, "val SMALL: ModelEntry = ModelEntry(\nval a = ModelRegistry.SMALL\n")
            _plant(tmp, SETTINGS_REL, '    val modelSize: String = "small",\n')
            self.assertEqual([], scan_constants(tmp))
            for extra in ('\n    val o = ModelRegistry.SMALL\n', ' val o = ModelRegistry.TINY\n'):
                _plant(tmp, SETTINGS_REL, '    val modelSize: String = "small",' + extra)
                self.assertEqual(1, len(scan_constants(tmp)))


if __name__ == "__main__":
    unittest.main()
