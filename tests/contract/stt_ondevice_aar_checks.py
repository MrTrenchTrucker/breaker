"""Pure rules behind the sherpa-onnx compile route pins of `android/modules/stt-ondevice`.

Not a `test_*.py` file on purpose (the contract-file bijection counts every `tests/contract/test_*.py`
as one module's contract test). `stt_ondevice_aar_pins.py` holds the test class that runs these rules
on the real files; this file holds only the rules, so a rule can be shown to fail by handing it an
edited copy of the text.

Nothing here runs Gradle. Kotlin and Gradle files go through the scanner of the main contract file
(comments removed, strings kept), the pin file is read line by line. Every rule is a plain `check_*`
function over text that returns a list of problems (empty = pass).
"""
import re

MODULE = "android/modules/stt-ondevice/"
MAIN = MODULE + "src/main/kotlin/dev/breaker/dictation/stt/ondevice/"
PROPS = MODULE + "sherpa-onnx-aar.properties"
BUILD = MODULE + "build.gradle.kts"
SETTINGS = "settings.gradle.kts"
CATALOG = "gradle/libs.versions.toml"
BINDING = "SherpaOnnxBinding.kt"
KEYS = ("version", "sha256", "repositoryUrl", "artifactPattern")
TYPES = {"FeatureConfig", "OnlineModelConfig", "OnlineRecognizer", "OnlineRecognizerConfig", "OnlineStream",
         "OnlineTransducerModelConfig"}
WIRED_WORDS = ("compile", "lint", "test", "bundle", "extract", "check", "assemble", "build")
BINARIES =(".aar", ".so", ".jar")
SKIPPED_DIRS = (".git", "build", ".gradle", ".kotlin")
RELEASE_TAG = "https://github.com/MrTrenchTrucker/breaker/releases/tag/sherpa-onnx-asr-v"


def _base():
    import test_stt_ondevice_contract as base
    return base


def code_of(text):
    """Comments removed, string literals kept."""
    return _base()._scan_kotlin(text, True)


def _one(code, regex, scope=None):
    """(open, close, head start) of the one `{...}` whose head matches `regex` inside `scope`, else None."""
    lo, hi = (scope[0], scope[1]) if scope else (0, len(code))
    found = [(m.end() - 1, _base()._close_of(code, m.end() - 1), m.start())
             for m in re.finditer(regex, code[:hi]) if m.start() >= lo]
    return found[0] if len(found) == 1 and found[0][1] > 0 else None


# --- the pin file and the catalog ----------------------------------------------------------------

def _entries(text):
    out = []
    for raw in text.splitlines():
        if raw.strip() and not raw.lstrip().startswith(("#", "!")):
            key, _, value = raw.partition("=")
            out.append((key.strip(), value))
    return out


def prop(text, key):
    return [v for k, v in _entries(text) if k == key]


def check_props_keys(text):
    probs = [f"key {k} must appear exactly once with a value (found {prop(text, k)})"
             for k in KEYS if len(prop(text, k)) != 1 or not prop(text, k)[0].strip()]
    return probs + [f"unexpected key {k}" for k, _ in _entries(text) if k not in KEYS]


def check_props_digest(text):
    lines = [r for r in text.splitlines() if re.match(r"\s*sha256\s*[=:\s]", r) and not r.lstrip().startswith(("#", "!"))]
    if len(lines) != 1:
        return [f"exactly one sha256 line is required (found {len(lines)})"]
    return [] if re.fullmatch(r"sha256=[0-9a-f]{64}", lines[0]) else [f"the sha256 line must be sha256= and 64 lowercase hex digits: {lines[0]!r}"]


def check_props_https(text):
    url = prop(text, "repositoryUrl")
    ok = len(url) == 1 and url[0].lstrip().startswith("https://") and len(url[0].strip()) > len("https://")
    return [] if ok else [f"repositoryUrl must start with https:// (found {url})"]


def check_versions_equal(props_text, catalog_text):
    cat = re.findall(r'^sherpa-onnx\s*=\s*"([^"]*)"', catalog_text, re.M)
    pin = prop(props_text, "version")
    ok = len(cat) == 1 and len(pin) == 1 and cat[0] == pin[0] and cat[0] != ""
    return [] if ok else [f"properties version {pin} must equal the catalog sherpa-onnx version {cat}"]


def check_dated_sources(props_text, catalog_text):
    from stt_ondevice_aar_rules import dated_suffix_problems
    version = (prop(props_text, "version") or [""])[0]
    dated = r"# read \d{4}-\d{2}-\d{2} from " + re.escape(RELEASE_TAG + version) + r"(-r[1-9][0-9]*)?"
    pin = re.findall(r"^" + dated + r"[ \t]*$", props_text, re.M)
    cat = re.findall(r'^sherpa-onnx\s*=\s*"[^"]+"[ \t]+' + dated + r"[ \t]*$", catalog_text, re.M)
    probs = []
    if len(pin) != 1:
        probs.append("the pin file needs one line '# read YYYY-MM-DD from <release tag url>' for its version")
    if len(cat) != 1:
        probs.append("the catalog sherpa-onnx line needs '# read YYYY-MM-DD from <release tag url>' for the pinned version")
    return probs + dated_suffix_problems(pin, cat, prop(props_text, "artifactPattern"))


# --- settings.gradle.kts -------------------------------------------------------------------------

def settings_group(code):
    got = re.findall(r'\bincludeModule\s*\(\s*"([^"]+)"\s*,\s*"sherpa-onnx"\s*\)', code)
    return got[0] if len(got) == 1 else None


def check_settings_mode(code):
    ok = (len(re.findall(r"\brepositoriesMode\s*\.\s*set\s*\(\s*RepositoriesMode\s*\.\s*FAIL_ON_PROJECT_REPOS\s*\)", code)) == 1
          and not re.search(r"RepositoriesMode\s*\.\s*PREFER", code))
    return [] if ok else ["repositoriesMode must stay FAIL_ON_PROJECT_REPOS, set exactly once"]


def check_settings_block(code):
    drm = _one(code, r"\bdependencyResolutionManagement\s*\{")
    repos = drm and _one(code, r"\brepositories\s*\{", drm)
    ex = repos and _one(code, r"\bexclusiveContent\s*\{", repos)
    fr = ex and _one(code, r"\bforRepository\s*\{", ex)
    ivy = fr and _one(code, r"\bivy\s*\{", fr)
    flt = ex and _one(code, r"\bfilter\s*\{", ex)
    if not (ivy and flt):
        return ["dependencyResolutionManagement.repositories needs one exclusiveContent block with forRepository { ivy { } } and filter { }"]
    probs = []
    if flt[0] < fr[1]:
        probs.append("filter must come after forRepository, inside exclusiveContent")
    if not re.search(r'\bincludeModule\s*\(\s*"[^"]+"\s*,\s*"sherpa-onnx"\s*\)', code[flt[0]:flt[1]]):
        probs.append('filter must call includeModule("<group>", "sherpa-onnx")')
    if re.search(r'"https?:', code[ex[0]:ex[1]]):
        probs.append("the exclusiveContent block must not hold an address literal; it comes from the pin file")
    rest = re.sub(r"\s+", " ", code[repos[0] + 1:ex[2]] + " " + code[ex[1] + 1:repos[1]]).strip()
    if rest != "google() mavenCentral()":
        probs.append(f"the repositories block must hold exclusiveContent, google() and mavenCentral() and nothing else (rest: {rest!r})")
    if code.find("google()", repos[0]) < ex[1]:
        probs.append("exclusiveContent must come before google()")
    return probs


def check_settings_reads_pin(code):
    probs = []
    if len(re.findall(r'\bFile\s*\(\s*settingsDir\s*,\s*"android/modules/stt-ondevice/sherpa-onnx-aar\.properties"\s*\)', code)) != 1:
        probs.append("settings must read android/modules/stt-ondevice/sherpa-onnx-aar.properties through File(settingsDir, ...)")
    if not re.search(r"\.\s*load\s*\(", code):
        probs.append("settings must load the pin file")
    if len(re.findall(r"\?:\s*throw\s+GradleException\s*\(", code)) != 1:
        probs.append("a missing key must stop the build: `?: throw GradleException(` exactly once")
    ivy = _one(code, r"\bivy\s*\{")
    body = code[ivy[0]:ivy[1]] if ivy else ""
    for key, use in (("repositoryUrl", r"\burl\s*=\s*java\.net\.URI\.create\s*\(\s*%s\s*\)"),
                     ("artifactPattern", r"\bpatternLayout\s*\{\s*artifact\s*\(\s*%s\s*\)\s*\}")):
        var = re.findall(r'\bval\s+(\w+)\s*=\s*\w+\s*\(\s*"' + key + r'"\s*\)', code)
        if len(var) != 1 or not re.search(use % re.escape(var[0] if var else "?"), body):
            probs.append(f"the ivy block must use the value read from the key {key}")
    if not re.search(r"\bmetadataSources\s*\{\s*artifact\s*\(\s*\)\s*\}", body):
        probs.append("the ivy block must read artifacts only: metadataSources { artifact() }")
    return probs


# --- the module build file -----------------------------------------------------------------------

def check_group(settings_code, build_code):
    s = settings_group(settings_code)
    b = re.findall(r'\bval\s+sherpaGroup\s*=\s*"([^"]+)"', build_code)
    coord = re.findall(r'\bval\s+sherpaCoordinate\s*=\s*"\$sherpaGroup:sherpa-onnx:\$sherpaVersion@aar"', build_code)
    ver = re.findall(r"\bval\s+sherpaVersion\s*=\s*libs\.versions\.sherpa\.onnx\.get\(\)", build_code)
    ok = s and len(b) == 1 and s == b[0] and len(coord) == 1 and len(ver) == 1
    return [] if ok else [f"settings group {s!r} must equal the build group {b} and the coordinate must be built from group, name, catalog version and @aar"]


TASK_RULES = (
    ("missing key", r"\?:\s*throw\s+GradleException\s*\("),
    ("digest shape", r'if\s*\(\s*!\s*Regex\s*\(\s*"\[0-9a-f\]\{64\}"\s*\)\s*\.\s*matches\s*\(\s*expected\s*\)\s*\)\s*\{\s*throw\s+GradleException'),
    ("catalog version", r"if\s*\(\s*version\s*!=\s*catalogVersion\.get\(\)\s*\)\s*\{\s*throw\s+GradleException"),
    ("file count", r"if\s*\(\s*files\.size\s*!=\s*1\s*\)\s*\{\s*throw\s+GradleException"),
    ("regular file", r"if\s*\(\s*!\s*file\.isFile\s*\)\s*\{\s*throw\s+GradleException"),
    ("file name", r'if\s*\(\s*file\.name\s*!=\s*"sherpa-onnx-\$version\.aar"\s*\)\s*\{\s*throw\s+GradleException'),
    ("hash", r'MessageDigest\.getInstance\(\s*"SHA-256"\s*\)'),
    ("mismatch", r"if\s*\(\s*actual\s*!=\s*expected\s*\)\s*\{\s*throw\s+GradleException"),
    ("short digest", r'd\.take\(12\)\s*\+\s*"\.\."\s*\+\s*d\.takeLast\(12\)'),
    ("both ends and full digests", r"short\(expected\).*short\(actual\).*full expected \$expected, actual \$actual"),
)


def check_task(raw, code):
    probs = []
    if raw.split("\n", 1)[0] != "import java.security.MessageDigest":
        probs.append("the first line of the build file must be `import java.security.MessageDigest`")
    cls = _one(code, r"\babstract\s+class\s+VerifySherpaAar\s*:\s*DefaultTask\s*\(\s*\)\s*\{")
    if not cls:
        return probs + ["abstract class VerifySherpaAar : DefaultTask() is missing"]
    body = code[cls[0]:cls[1]]
    probs += [f"the task has no refusal for: {name}" for name, rx in TASK_RULES if len(re.findall(rx, body, re.S)) < 1]
    reg = _one(code, r'\bval\s+verifySherpaAar\s*=\s*tasks\s*\.\s*register\s*<\s*VerifySherpaAar\s*>\s*\(\s*"verifySherpaAar"\s*\)\s*\{')
    inner = code[reg[0]:reg[1]] if reg else ""
    for need in (r"\baar\.from\(\s*sherpaAar\s*\)", r'\bpin\.set\(\s*layout\.projectDirectory\.file\(\s*"sherpa-onnx-aar\.properties"\s*\)\s*\)',
                 r"\bcatalogVersion\.set\(\s*sherpaVersion\s*\)"):
        if not re.search(need, inner):
            probs.append(f"verifySherpaAar is not registered with {need}")
    return probs


def check_pattern_words(code):
    """The task-name pattern must name every one of the eight words."""
    pat = re.findall(r'\bval\s+afterSherpaCheck\s*=\s*Regex\(\s*"([^"]*)"\s*\)', code)
    return [f"the name pattern must cover {word}" for word in WIRED_WORDS
            if len(pat) != 1 or not re.search(r"\b" + word + r"\b", pat[0])]


def check_wired(code):
    probs = []
    if len(re.findall(r'\btasks\s*\.\s*named\(\s*"preBuild"\s*\)\s*\{\s*dependsOn\(\s*verifySherpaAar\s*\)\s*\}', code)) != 1:
        probs.append("preBuild must depend on verifySherpaAar")
    probs += check_pattern_words(code)
    cfg = _one(code, r"\btasks\s*\.\s*configureEach\s*\{")
    if not cfg or not re.search(r'name\s*!=\s*"verifySherpaAar"\s*&&\s*afterSherpaCheck\.matches\(\s*name\s*\)\s*\)\s*dependsOn\(\s*verifySherpaAar\s*\)', code[cfg[0]:cfg[1]]):
        probs.append("tasks.configureEach must make every matching task depend on verifySherpaAar")
    return probs


def check_never_up_to_date(code):
    """The verify task hashes the file on every build: `init { outputs.upToDateWhen { false } }` in its class."""
    cls = _one(code, r"\babstract\s+class\s+VerifySherpaAar\s*:\s*DefaultTask\s*\(\s*\)\s*\{")
    body = code[cls[0]:cls[1]] if cls else ""
    if len(re.findall(r"\binit\s*\{\s*outputs\s*\.\s*upToDateWhen\s*\{\s*false\s*\}\s*\}", body)) != 1:
        return ["VerifySherpaAar must hold exactly one `init { outputs.upToDateWhen { false } }` so it is never skipped"]
    return []


def check_compile_only(code):
    calls = sorted((fn, " ".join(a.split())) for fn, a in re.findall(r"\b(\w+)\s*\(([^()]*)\)", code)
                   if re.search(r'sherpaCoordinate|sherpaGroup|"external\.|sherpa-onnx:', a))
    want = [("add", "sherpaAar.name, sherpaCoordinate"), ("compileOnly", "sherpaCoordinate")]
    probs = [] if calls == want else [f"the coordinate may be declared only as {want}, found {calls}"]
    if len(re.findall(r"\bsherpaCoordinate\b", code)) != 3:
        probs.append("sherpaCoordinate must appear exactly three times: its definition and the two declarations")
    return probs


def check_no_repositories_or_files(code):
    hit = re.search(r"\brepositories\s*\{|\bflatDir\b|\bfiles\s*\(|\bfileTree\s*\(", code)
    return [f"the module build may not declare a repository or a file dependency: {hit.group(0)}"] if hit else []


def check_one_digest(props_text, named_code):
    found = [v for k, v in _entries(props_text) if k == "sha256"]
    if len(found) != 1 or not re.fullmatch(r"[0-9a-f]{64}", found[0]):
        return ["the pin file has no usable digest to look for"]
    return [f"{n} holds the pinned digest; it is written only in the pin file" for n, c in sorted(named_code.items())
            if found[0] in c.lower()]


def check_jdk_names_are_imported(code):
    """In a Gradle build script `java` is the build's own extension, so a dotted java.util or java.security
    name does not resolve there; the JDK class must be imported and used by its simple name.
    Comments are already gone from `code`; text inside a string literal is not code and is ignored."""
    bare = re.sub(r'"[^"\n]*"', '""', code)
    probs = []
    for m in re.finditer(r"(?<![\w.])java\s*\.\s*(?:util|security|net|io|nio|lang|time)\s*\.\s*\w+", bare):
        head = bare[bare.rfind("\n", 0, m.start()) + 1:m.start()]
        if not re.fullmatch(r"\s*import\s*", head):
            probs.append(f"{m.group(0)!r} is written dotted in the build file; import it and use the simple name")
    return probs


# --- sources and files ---------------------------------------------------------------------------

def check_library_scope(main):
    probs = [f"{n} names com.k2fsa; only {BINDING} may" for n, c in sorted(main.items())
             if n != BINDING and re.search(r"\bcom\s*\.\s*k2fsa\b", c)]
    if BINDING not in main or not re.search(r"\bcom\s*\.\s*k2fsa\b", main[BINDING]):
        probs.append(f"{BINDING} must exist and name com.k2fsa")
    return probs


def check_no_native_load(main):
    probs = [f"{n} loads a native library; the library's own classes do that" for n, c in sorted(main.items())
             if re.search(r"\bloadLibrary\b|\bSystem\s*\.\s*load\s*\(", c)]
    return probs if len(main) >= 5 else probs + ["too few main files were read"]


def check_binding(code):
    probs = []
    names = set(re.findall(r"\bimport\s+com\s*\.\s*k2fsa\s*\.\s*sherpa\s*\.\s*onnx\s*\.\s*(\w+)", code))
    if names != TYPES or len(re.findall(r"\bcom\s*\.\s*k2fsa\b", code)) != len(TYPES):
        probs.append(f"the binding may import only {sorted(TYPES)} (found {sorted(names)}) and never write the package inline")
    if len(re.findall(r"\binternal\s+object\s+SherpaOnnxBinding\s*:\s*NativeStreamingOpener\b", code)) != 1:
        probs.append("SherpaOnnxBinding must be an internal object implementing NativeStreamingOpener")
    for what, rx in (("feature size 80", r"\bconst\s+val\s+FEATURE_DIM\s*=\s*80\b"), ("no asset manager", r"\bassetManager\s*=\s*null\b"), ("cpu", r'\bprovider\s*=\s*"cpu"'),
                     ("no endpoint detection", r"\benableEndpoint\s*=\s*false\b"), ("greedy decoding", r'\bdecodingMethod\s*=\s*"greedy_search"'),
                     ("model type read from the files", r'\bmodelType\s*=\s*""'), ("feature size", r"\bfeatureDim\s*=\s*FEATURE_DIM\b"),
                     ("16 kHz", r"\bsampleRate\s*=\s*SherpaOnnxRecognizer\.SAMPLE_RATE_HZ\b"), ("thread count", r"\bnumThreads\s*=\s*numThreads\b")):
        if len(re.findall(rx, code)) != 1:
            probs.append(f"the binding config needs exactly one: {what}")
    return probs


def check_no_binaries(paths):
    return [f"{p}: a binary may not be kept under android/" for p in sorted(paths) if p.lower().endswith(BINARIES)]
