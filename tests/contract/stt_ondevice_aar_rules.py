"""More pure rules behind the sherpa-onnx compile route pins of `android/modules/stt-ondevice`.

Not a `test_*.py` file on purpose (the contract-file bijection counts every `tests/contract/test_*.py`
as one module's contract test). `stt_ondevice_aar_checks.py` holds the first rules and is near its line
cap, so these live here; `stt_ondevice_aar_pins.py` runs them on the real build file. Same shape: a plain
`check_*` function over text that returns a list of problems (empty = pass). Nothing here runs Gradle.
"""
import re

from stt_ondevice_aar_checks import _one

# (simple class name, package) of each JDK class the module build file uses by its simple name.
JDK_SIMPLE_NAMES = (("Properties", "util"), ("MessageDigest", "security"))

TASK_HEAD = r"\babstract\s+class\s+VerifySherpaAar\s*:\s*DefaultTask\s*\(\s*\)\s*\{"

# Each must match exactly once in the task body: the words a person reads when the build stops or passes.
MESSAGE_RULES = (
    ("exact mismatch message",
     r'"sherpa-onnx AAR SHA-256 mismatch for \$\{file\.name\}: expected \$\{short\(expected\)\}, '
     r'actual \$\{short\(actual\)\} "\s*\+\s*"\(full expected \$expected, actual \$actual\)\. '
     r'The file in the Gradle cache is not the pinned release; build stopped\."'),
    ("success line",
     r'logger\.lifecycle\(\s*"sherpa-onnx AAR verified: \$\{file\.name\} sha256 \$\{short\(actual\)\}"\s*\)'),
)


def check_jdk_simple_names_have_imports(code):
    """A JDK class written by its simple name in the build file needs its import line, or the script does not
    compile. `code` has its comments removed; text inside a string literal is not code and is ignored;
    `import java.<package>.*` also counts."""
    bare = re.sub(r'"[^"\n]*"', '""', code)
    probs = []
    for name, pkg in JDK_SIMPLE_NAMES:
        used = re.search(r"(?<![\w.])" + name + r"\b", bare)
        imported = re.search(r"^[ \t]*import\s+java\.%s\.(?:%s|\*)[ \t]*$" % (pkg, name), bare, re.M)
        if used and not imported:
            probs.append(f"{name} is used by its simple name but `import java.{pkg}.{name}` is missing")
    return probs


def check_task_messages(code):
    """The verify task holds the mismatch message and the success line word for word, each once."""
    cls = _one(code, TASK_HEAD)
    if not cls:
        return ["abstract class VerifySherpaAar : DefaultTask() is missing"]
    body = code[cls[0]:cls[1]]
    return [f"the task must print the pinned {name} exactly once" for name, rx in MESSAGE_RULES
            if len(re.findall(rx, body)) != 1]


TAG_PART = re.compile(r"sherpa-onnx-asr-v\[revision\](-r[1-9][0-9]*)?/")


def dated_suffix_problems(pin, cat, patterns):
    """The optional `-rN` revision suffix of the dated source lines must equal the one in the tag part of
    artifactPattern (the text before its first '/'). `pin` and `cat` hold the suffix captured from the pin file
    line and from the catalog line (one entry each when that line was found); `patterns` holds the artifactPattern values."""
    got = TAG_PART.match(patterns[0].strip()) if len(patterns) == 1 else None
    if not got:
        return [f"artifactPattern must start with sherpa-onnx-asr-v[revision], an optional -rN suffix (N from 1), then '/' (found {patterns})"]
    want = got.group(1) or ""
    return [f"the {name} dated line has revision suffix {sfx[0]!r} but the artifactPattern tag part has {want!r}"
            for name, sfx in (("pin file", pin), ("catalog", cat)) if len(sfx) == 1 and sfx[0] != want]
