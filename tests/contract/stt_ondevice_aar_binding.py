"""Rules for the three files of `android/modules/stt-ondevice` that name the sherpa-onnx library.

Not a `test_*.py` file on purpose (the contract-file bijection counts every `tests/contract/test_*.py`
as one module's contract test). `stt_ondevice_aar_checks.py` is at its line cap, so the scope rule and the
keyword and offline binding pins live here; `stt_ondevice_aar_binding_pins.py` runs them on the real files and on bad
copies. Same shape as the other rules: a plain `check_*` function over text that returns a list of
problems (empty = pass). Nothing here runs Gradle.

Exactly three files may name `com.k2fsa`: the recogniser binding, the keyword spotter binding and the offline
recogniser binding, all in the module's main source. The keyword spotter and the offline recogniser are further
native entry points; the narrow-surface property is unchanged (the library's classes are named in no other file
and in no other module).

The keyword binding pin below mirrors the first binding's pin in strength: its six imports and nothing
else, `internal class` implementing the opener, and the exact set of fields the file passes by name. It
does NOT pin any value of a keyword field of the library: those shapes are unverified (read from the
library's strings only), so the pin names only what the file really sets today, and a new named field is a
rule change that someone must look at.

The offline binding pin has the same strength and records the offline file's imports AS WRITTEN. That file is
compiled against the library by the project's CI build, so its class and field names are confirmed; what stays
UNVERIFIED is its behaviour at runtime, because it has never run on a device, and the file must say so (see
`check_offline_binding_says_unverified`).
"""
import re

from stt_ondevice_aar_checks import BINDING

KEYWORD_BINDING = "KeywordSpotterBinding.kt"
OFFLINE_BINDING = "OfflineRecognizerBinding.kt"
BINDINGS = (BINDING, KEYWORD_BINDING, OFFLINE_BINDING)
KEYWORD_TYPES = {"FeatureConfig", "KeywordSpotter", "KeywordSpotterConfig", "OnlineModelConfig", "OnlineStream",
                 "OnlineTransducerModelConfig"}
OFFLINE_TYPES = {"FeatureConfig", "OfflineModelConfig", "OfflineRecognizer", "OfflineRecognizerConfig", "OfflineStream",
                 "OfflineTransducerModelConfig"}
LIBRARY = r"\bcom\s*\.\s*k2fsa\b"
# Every name the keyword binding gives a value by name (`name = value`), in open(); nothing else may appear.
KEYWORD_FIELDS = {"config", "assetManager", "featConfig", "sampleRate", "featureDim", "modelConfig", "transducer",
                  "encoder", "decoder", "joiner", "tokens", "numThreads", "provider", "modelType", "keywordsFile"}
# Every name the offline binding gives a value by name in open(). No featureDim: the offline file sets the sample rate only.
OFFLINE_FIELDS = {"config", "assetManager", "featConfig", "sampleRate", "modelConfig", "transducer", "encoder", "decoder",
                  "joiner", "tokens", "numThreads", "provider", "modelType", "decodingMethod"}


def check_library_scope(main, elsewhere):
    """`main` maps file name to code for the module's main source; `elsewhere` maps path to code for every other
    main source file under android/ (any other module). Only the three binding files of the module may name com.k2fsa."""
    probs = [f"{n} names com.k2fsa; only {BINDING}, {KEYWORD_BINDING} and {OFFLINE_BINDING} may" for n, c in sorted(main.items())
             if n not in BINDINGS and re.search(LIBRARY, c)]
    probs += [f"{p} names com.k2fsa outside the module; only the three binding files of stt-ondevice may"
              for p, c in sorted(elsewhere.items()) if re.search(LIBRARY, c)]
    probs += [f"{b} must exist and name com.k2fsa" for b in BINDINGS if b not in main or not re.search(LIBRARY, main[b])]
    return probs


def check_keyword_binding(code):
    probs = []
    names = set(re.findall(r"\bimport\s+com\s*\.\s*k2fsa\s*\.\s*sherpa\s*\.\s*onnx\s*\.\s*(\w+)", code))
    if names != KEYWORD_TYPES or len(re.findall(LIBRARY, code)) != len(KEYWORD_TYPES):
        probs.append(f"the keyword binding may import only {sorted(KEYWORD_TYPES)} (found {sorted(names)}) and never write the package inline")
    head = r"\binternal\s+class\s+KeywordSpotterBinding\s*\(\s*private\s+val\s+keywordsFile\s*:\s*File\s*\)\s*:\s*NativeStreamingOpener\b"
    if len(re.findall(head, code)) != 1:
        probs.append("KeywordSpotterBinding must be an internal class taking the keywords file and implementing NativeStreamingOpener")
    for what, rx in (("feature size 80", r"\bconst\s+val\s+FEATURE_DIM\s*=\s*80\b"), ("feature size", r"\bfeatureDim\s*=\s*FEATURE_DIM\b"),
                     ("16 kHz", r"\bsampleRate\s*=\s*SherpaOnnxRecognizer\.SAMPLE_RATE_HZ\b"), ("cpu", r'\bprovider\s*=\s*"cpu"'),
                     ("model type read from the files", r'\bmodelType\s*=\s*""'), ("thread count", r"\bnumThreads\s*=\s*numThreads\b"),
                     ("no asset manager", r"\bKeywordSpotter\s*\(\s*assetManager\s*=\s*null\s*,\s*config\s*=\s*config\s*\)"),
                     ("the keywords file path", r"\bkeywordsFile\s*=\s*keywordsFile\.path\b"),
                     ("one spotter config", r"\bKeywordSpotterConfig\s*\("), ("one feature config", r"\bfeatConfig\s*=\s*FeatureConfig\s*\("),
                     ("one model config", r"\bmodelConfig\s*=\s*OnlineModelConfig\s*\("),
                     ("one transducer config", r"\btransducer\s*=\s*OnlineTransducerModelConfig\s*\("),
                     ("encoder path", r"\bencoder\s*=\s*files\.encoder\.path\b"), ("decoder path", r"\bdecoder\s*=\s*files\.decoder\.path\b"),
                     ("joiner path", r"\bjoiner\s*=\s*files\.joiner\.path\b"), ("tokens path", r"\btokens\s*=\s*files\.tokens\.path\b")):
        if len(re.findall(rx, code)) != 1:
            probs.append(f"the keyword binding config needs exactly one: {what}")
    start, end = code.find("override fun open("), code.find("private class Real")
    if start < 0 or end < start:
        return probs + ["the keyword binding must hold open() before its private class Real"]
    given = set(re.findall(r"\b(\w+)\s*=(?!=)", code[start:end]))
    if given != KEYWORD_FIELDS:
        probs.append(f"the keyword binding may give a value by name only to {sorted(KEYWORD_FIELDS)} "
                     f"(extra {sorted(given - KEYWORD_FIELDS)}, missing {sorted(KEYWORD_FIELDS - given)}); a new field is a rule change")
    return probs


def check_offline_binding(code):
    probs = []
    names = set(re.findall(r"\bimport\s+com\s*\.\s*k2fsa\s*\.\s*sherpa\s*\.\s*onnx\s*\.\s*(\w+)", code))
    if names != OFFLINE_TYPES or len(re.findall(LIBRARY, code)) != len(OFFLINE_TYPES):
        probs.append(f"the offline binding may import only {sorted(OFFLINE_TYPES)} (found {sorted(names)}) and never write the package inline")
    if len(re.findall(r"\binternal\s+object\s+OfflineRecognizerBinding\s*:\s*NativeOfflineOpener\b", code)) != 1:
        probs.append("OfflineRecognizerBinding must be an internal object implementing NativeOfflineOpener")
    for what, rx in (("16 kHz", r"\bsampleRate\s*=\s*SherpaOnnxRecognizer\.SAMPLE_RATE_HZ\b"), ("cpu", r'\bprovider\s*=\s*"cpu"'),
                     ("model type read from the files", r"\bmodelType\s*=\s*files\.modelType\b"), ("thread count", r"\bnumThreads\s*=\s*numThreads\b"),
                     ("greedy decoding", r'\bdecodingMethod\s*=\s*"greedy_search"'),
                     ("no asset manager", r"\bOfflineRecognizer\s*\(\s*assetManager\s*=\s*null\s*,\s*config\s*=\s*config\s*\)"),
                     ("one recogniser config", r"\bOfflineRecognizerConfig\s*\("), ("one feature config", r"\bfeatConfig\s*=\s*FeatureConfig\s*\("),
                     ("one model config", r"\bmodelConfig\s*=\s*OfflineModelConfig\s*\("),
                     ("one transducer config", r"\btransducer\s*=\s*OfflineTransducerModelConfig\s*\("),
                     ("encoder path", r"\bencoder\s*=\s*files\.encoder\.path\b"), ("decoder path", r"\bdecoder\s*=\s*files\.decoder\.path\b"),
                     ("joiner path", r"\bjoiner\s*=\s*files\.joiner\.path\b"), ("tokens path", r"\btokens\s*=\s*files\.tokens\.path\b")):
        if len(re.findall(rx, code)) != 1:
            probs.append(f"the offline binding config needs exactly one: {what}")
    start, end = code.find("override fun open("), code.find("private class Real")
    if start < 0 or end < start:
        return probs + ["the offline binding must hold open() before its private class Real"]
    given = set(re.findall(r"\b(\w+)\s*=(?!=)", code[start:end]))
    if given != OFFLINE_FIELDS:
        probs.append(f"the offline binding may give a value by name only to {sorted(OFFLINE_FIELDS)} "
                     f"(extra {sorted(given - OFFLINE_FIELDS)}, missing {sorted(OFFLINE_FIELDS - given)}); a new field is a rule change")
    return probs


def check_offline_binding_says_unverified(raw):
    """`raw` is the offline binding WITH its comments: it must say plainly that the project's CI build compiled it
    against the library, that it has never run on a device, and must mark the runtime behaviour UNVERIFIED."""
    return [f"the offline binding must carry the text {t!r}" for t in
            ("COMPILED AGAINST THE LIBRARY", "NEVER RUN ON A DEVICE", "UNVERIFIED") if t not in raw]
