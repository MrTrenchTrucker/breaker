"""Tests for the two binding files of `android/modules/stt-ondevice`, mixed into `SttOndeviceAarPinsTest`.

Not a `test_*.py` file on purpose (the contract-file bijection counts every `tests/contract/test_*.py`
as one module's contract test). `stt_ondevice_aar_pins.py` is near its line cap, so these tests live here;
the class there inherits them, and `test_stt_ondevice_contract.py` collects them through it. The mixin
uses `_read`, `_main` and `_ok` of that class. The rules are in `stt_ondevice_aar_binding.py`.
"""
import os
import re

import contract_support
from stt_ondevice_aar_binding import KEYWORD_BINDING, check_keyword_binding, check_library_scope
from stt_ondevice_aar_checks import BINDING, BUILD, MAIN, SKIPPED_DIRS, check_compile_only, check_no_native_load, code_of

IMPORT = "import com.k2fsa.sherpa.onnx."
LIB = IMPORT + "OnlineStream\n"


class BindingPins:

    def _elsewhere(self):
        """Every main source file of any other module under android/, path to code (.kt without comments)."""
        found = {}
        top = os.path.join(contract_support.ROOT, "android")
        for cur, dirs, names in os.walk(top):
            dirs[:] = [d for d in dirs if d not in SKIPPED_DIRS]
            for name in names:
                rel = os.path.relpath(os.path.join(cur, name), contract_support.ROOT)
                if name.endswith((".kt", ".java")) and "/src/main/" in rel and not rel.startswith(MAIN):
                    text = self._read(rel)
                    found[rel] = code_of(text) if name.endswith(".kt") else text
        return found

    def test_only_the_two_binding_files_name_the_sherpa_library(self):
        """`com.k2fsa` appears in SherpaOnnxBinding.kt and KeywordSpotterBinding.kt, and in no other main source of the
        module and in no main source of any other module under android/."""
        other = self._elsewhere()
        self.assertTrue(other, "library scope: the walk found no main source of another module; it is not looking at the tree")
        self._ok(check_library_scope(self._main(), other), "library scope")

    def test_the_keyword_binding_imports_six_types_and_passes_its_fields_by_name(self):
        """Six imports and no more; internal class taking the keywords file and implementing NativeStreamingOpener;
        the fields it sets, and no other named field. The library's keyword fields are unverified, so no value of one is pinned."""
        self._ok(check_keyword_binding(self._main().get(KEYWORD_BINDING, "")), "keyword binding")

    def test_library_scope_rule_flags_a_third_file_and_another_module(self):
        """The scope rule is handed fixed texts: the two binding files alone pass; a third main file, another module's
        main file, a missing binding file and a binding file without the library name are each reported by name."""
        both = {BINDING: LIB, KEYWORD_BINDING: LIB, "Plain.kt": "class Plain\n"}
        self.assertEqual(check_library_scope(both, {"android/modules/other/src/main/kotlin/A.kt": "class A\n"}), [])
        cases = (("third main file", {**both, "Third.kt": LIB}, {}, "Third.kt"),
                 ("another module", both, {"android/modules/other/src/main/kotlin/A.kt": LIB}, "android/modules/other/src/main/kotlin/A.kt"),
                 ("inline use in a third file", {**both, "Third.kt": "val s = com.k2fsa.sherpa.onnx.OnlineStream()\n"}, {}, "Third.kt"),
                 ("no recogniser binding", {KEYWORD_BINDING: LIB}, {}, BINDING),
                 ("no keyword binding", {BINDING: LIB}, {}, KEYWORD_BINDING),
                 ("keyword binding without the name", {BINDING: LIB, KEYWORD_BINDING: "class K\n"}, {}, KEYWORD_BINDING))
        for label, main, other, named in cases:
            got = check_library_scope(main, other)
            self.assertEqual(len(got), 1, f"scope rule on '{label}': {got}")
            self.assertIn(named, got[0], f"scope rule on '{label}' does not name the file: {got}")

    def test_keyword_binding_rule_flags_each_bad_text(self):
        """The keyword pin is handed the real file with one edit at a time: an extra import, a missing import, the package
        written inline, a wrong class shape, an extra or missing or changed named field. Each edit is reported."""
        code = code_of(self._read(MAIN + KEYWORD_BINDING))
        self.assertEqual(check_keyword_binding(code), [], "the real keyword binding must pass first")
        extra = ("import com.k2fsa.sherpa.onnx.KeywordSpotter\n", "import com.k2fsa.sherpa.onnx.KeywordSpotter\n" + IMPORT + "OnlineRecognizer\n")
        edits = [extra] + [(IMPORT + t + "\n", "") for t in ("FeatureConfig", "KeywordSpotter", "KeywordSpotterConfig", "OnlineModelConfig",
                                                            "OnlineStream", "OnlineTransducerModelConfig")]
        edits += [("val config = KeywordSpotterConfig(", "val config = com.k2fsa.sherpa.onnx.KeywordSpotterConfig("),
                  ("internal class KeywordSpotterBinding", "class KeywordSpotterBinding"),
                  ("internal class KeywordSpotterBinding", "internal object KeywordSpotterBinding"),
                  (" : NativeStreamingOpener", ""), ("private val keywordsFile: File", "private val keywordsFile: String"),
                  ("            keywordsFile = keywordsFile.path,\n", ""), ("keywordsFile = keywordsFile.path,", "keywordsFile = keywordsFile.path,\n            keywordsScore = 3.0f,"),
                  ("keywordsFile = keywordsFile.path,", "keywordsFile = keywordsFile.path,\n            keywordsThreshold = 0.1f,"),
                  ('provider = "cpu"', 'provider = "xnnpack"'), ('modelType = ""', 'modelType = "zipformer"'), ("numThreads = numThreads", "numThreads = 4"),
                  ("featureDim = FEATURE_DIM", "featureDim = 40"), ("const val FEATURE_DIM = 80", "const val FEATURE_DIM = 40"),
                  ("sampleRate = SherpaOnnxRecognizer.SAMPLE_RATE_HZ", "sampleRate = 8000"), ("assetManager = null", "assetManager = manager"),
                  ("joiner = files.joiner.path", "joiner = files.decoder.path"), ("tokens = files.tokens.path", "tokens = files.encoder.path")]
        for old, new in edits:
            self.assertIn(old, code, f"the fixture lost the text {old!r}")
            self.assertNotEqual(check_keyword_binding(code.replace(old, new, 1)), [], f"keyword pin silent on {old!r} -> {new!r}")
        self.assertNotEqual(check_keyword_binding(""), [], "keyword pin silent on an empty file")

    def test_the_native_load_and_compile_only_pins_cover_the_keyword_binding(self):
        """A loadLibrary call or System.load in the keyword binding is reported by the native-load pin, and an
        `implementation(...)` declaration of the library coordinate by the compile-only pin; the real tree passes both."""
        main = self._main()
        self.assertEqual(check_no_native_load(main), [])
        marker = "override fun open("
        self.assertIn(marker, main[KEYWORD_BINDING])
        for call in ("System.loadLibrary(\"sherpa-onnx-jni\")", "System.load(path)"):
            planted = dict(main, **{KEYWORD_BINDING: main[KEYWORD_BINDING].replace(marker, f"init {{ {call} }}\n    {marker}", 1)})
            got = check_no_native_load(planted)
            self.assertEqual([p.split()[0] for p in got], [KEYWORD_BINDING], f"native-load pin on {call}: {got}")
        build = code_of(self._read(BUILD))
        self.assertEqual(check_compile_only(build), [])
        self.assertIn("compileOnly(sherpaCoordinate)", build)
        self.assertNotEqual(check_compile_only(build.replace("compileOnly(sherpaCoordinate)", "implementation(sherpaCoordinate)", 1)), [])

    def test_the_binding_pins_go_red_on_planted_files(self):
        """The real pins are handed a tree with one planted file or edit, served for that run only, and must fail: a third main
        file naming the library, a main file of another module naming it, the keyword binding with one more import, the
        keyword binding with a loadLibrary call. A pin that reads another file or drops its rule's answer stays green and fails here."""
        main, other = self._main(), self._elsewhere()
        keyword = main[KEYWORD_BINDING]
        cases = (("test_only_the_two_binding_files_name_the_sherpa_library", dict(main, Third=LIB), other),
                 ("test_only_the_two_binding_files_name_the_sherpa_library", main, dict(other, **{"android/modules/x/src/main/kotlin/X.kt": LIB})),
                 ("test_the_keyword_binding_imports_six_types_and_passes_its_fields_by_name",
                  dict(main, **{KEYWORD_BINDING: keyword.replace("import java.io.File", IMPORT + "OnlineRecognizer\nimport java.io.File", 1)}), other),
                 ("test_no_main_source_loads_a_native_library",
                  dict(main, **{KEYWORD_BINDING: keyword + "\nval x = System.loadLibrary(\"a\")\n"}), other))
        for name, planted_main, planted_other in cases:
            self.assertTrue(re.search(r"\bcom\.k2fsa|loadLibrary", "".join(planted_main.values()) + "".join(planted_other.values())))
            probe = type(self)(name)
            probe._main, probe._elsewhere = (lambda m=planted_main: m), (lambda o=planted_other: o)
            with self.assertRaises(AssertionError, msg=f"{name} stayed green on a planted file"):
                getattr(probe, name)()
