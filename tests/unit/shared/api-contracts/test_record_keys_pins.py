"""Text pins for the RecordKeys port and the way the sync module uses it.

Standard library only. The Kotlin sources are read as text, with // and /* */ comments blanked out
first (the same rule the sync module's hygiene test uses), so a sentence in a comment is not a hit.
"""
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
for _ in range(4):
    ROOT = os.path.dirname(ROOT)

CORE_MAIN = os.path.join(
    ROOT, "android", "modules", "core", "src", "main", "kotlin", "dev", "breaker", "dictation", "core",
)
SYNC_MAIN = os.path.join(
    ROOT, "android", "modules", "sync", "src", "main", "kotlin", "dev", "breaker", "dictation", "sync",
)
PORT_PATH = os.path.join(CORE_MAIN, "port", "RecordKeys.kt")
RECORD_PATH = os.path.join(CORE_MAIN, "model", "SealedRecord.kt")
SERVICE_PATH = os.path.join(SYNC_MAIN, "QueuedSyncService.kt")
PLAIN_PATH = os.path.join(SYNC_MAIN, "PlainPush.kt")
PUSH_ROW_PATH = os.path.join(SYNC_MAIN, "PushRow.kt")
DECODER_PATH = os.path.join(SYNC_MAIN, "PendingSealedDecoder.kt")
CONVERSION_PATH = os.path.join(SYNC_MAIN, "PendingConversion.kt")
PENDING_JSON_PATH = os.path.join(SYNC_MAIN, "PendingJson.kt")
OPENAPI_PATH = os.path.join(ROOT, "shared", "modules", "api-contracts", "openapi.yaml")

IMPORT_RE = re.compile(r"^import\s+(javax\b|java\.security\b|dev\.breaker\.dictation\.crypto\b)", re.M)


def read_text(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def code_of(text):
    """The source with block comments, KDoc and line comments blanked out."""
    return re.sub(r"//[^\n]*", " ", re.sub(r"(?s)/\*.*?\*/", " ", text))


def sync_main_files():
    names = sorted(n for n in os.listdir(SYNC_MAIN) if n.endswith(".kt"))
    if not names:
        raise AssertionError("no sync main source found under " + SYNC_MAIN)
    return {n: read_text(os.path.join(SYNC_MAIN, n)) for n in names}


def between(text, start, end):
    first = text.index(start) + len(start)
    return text[first:text.index(end, first)]


def last_parameter(block):
    lines = [line.strip() for line in block.split("\n") if line.strip()]
    return lines[-1]


def second_to_last_parameter(block):
    lines = [line.strip() for line in block.split("\n") if line.strip()]
    return lines[-2]


def allowed_members(code):
    """The names in the decoder's `ALLOWED = setOf(...)` line, or None when there is none."""
    found = re.search(r"\bALLOWED = setOf\(([^)]*)\)", code)
    return re.findall(r'"(\w+)"', found.group(1)) if found else None


def required_members(yaml_text, schema):
    """The `required:` list of one schema under components, read as text."""
    start = yaml_text.index("\n    " + schema + ":\n") + 1
    rest = yaml_text[start:]
    end = re.search(r"\n    \S", rest[len(schema) + 6:])
    block = rest if end is None else rest[:end.start() + len(schema) + 6]
    found = re.search(r"\n      required:\n((?:        - \w+\n)+)", block)
    return re.findall(r"- (\w+)", found.group(1)) if found else None


class RecordKeysPinsTest(unittest.TestCase):
    # Permitted re-point (the manager confirmed the widening): the port gains its fifth member,
    # convertSealed. The port is now complete for this phase; a sixth member needs a new ruling.
    def test_the_port_has_exactly_the_five_members(self):
        code = code_of(read_text(PORT_PATH))
        self.assertEqual(
            ["currentKeyVersion", "seal", "open", "reseal", "convertSealed"],
            re.findall(r"^\s+fun (\w+)\(", code, re.M),
        )
        self.assertIn("fun currentKeyVersion(): Int", code)
        self.assertIn("fun seal(clientId: String, body: ByteArray): SealedRecord?", code)
        self.assertIn("fun open(clientId: String, keyVersion: Int, encrypted: String): ByteArray?", code)
        self.assertIn(
            "fun reseal(clientId: String, keyVersion: Int, encrypted: String): SealedRecord?", code,
        )
        self.assertIn("fun convertSealed(clientId: String, sealedBox: ByteArray): SealedRecord?", code)
        self.assertEqual(1, len(re.findall(r"^interface RecordKeys\b", code, re.M)))

    def test_the_two_stop_functions_are_defined_once_each_and_called_from_the_service(self):
        files = sync_main_files()
        for name in ("stopBecausePortHasNoKeys", "stopBecauseKeyVersionRefused"):
            definitions = sum(len(re.findall(r"\bfun %s\(" % name, code_of(t))) for t in files.values())
            self.assertEqual(1, definitions, name)
            self.assertEqual(0, len(re.findall(r"\bfun %s\(" % name, code_of(files["QueuedSyncService.kt"]))))
            calls = len(re.findall(r"\breturn %s\(" % name, code_of(files["QueuedSyncService.kt"])))
            self.assertGreaterEqual(calls, 1, name)

    # Permitted re-point (the manager confirmed the widening): the pending transport is now the last
    # parameter of the constructor and of create, and the port is the one before it.
    def test_the_port_is_the_last_parameter_of_the_constructor_and_of_create(self):
        text = code_of(read_text(SERVICE_PATH))
        ctor = between(text, "class QueuedSyncService internal constructor(", ") : SyncService {")
        self.assertEqual(
            "private val pendingTransport: PendingSealedTransport? = null,", last_parameter(ctor),
        )
        self.assertEqual("private val recordKeys: RecordKeys? = null,", second_to_last_parameter(ctor))
        create = between(text, "fun create(", "): QueuedSyncService")
        self.assertEqual("pendingTransport: PendingSealedTransport? = null,", last_parameter(create))
        self.assertEqual("recordKeys: RecordKeys? = null,", second_to_last_parameter(create))
        # One in the constructor and one in create: no other place declares either parameter.
        self.assertEqual(2, text.count("recordKeys: RecordKeys? = null"))
        self.assertEqual(2, text.count("pendingTransport: PendingSealedTransport? = null"))

    def test_no_crypto_import_in_sync_main_or_in_the_two_new_core_files(self):
        sample = "import javax.crypto.Cipher\nimport java.security.MessageDigest\n"
        sample += "import dev.breaker.dictation.crypto.X\n"
        self.assertEqual(3, len(IMPORT_RE.findall(sample)), "control: the scan must fire on a bad sample")
        found = {}
        paths = {n: read_text(os.path.join(SYNC_MAIN, n)) for n in sync_main_files()}
        paths["RecordKeys.kt"] = read_text(PORT_PATH)
        paths["SealedRecord.kt"] = read_text(RECORD_PATH)
        for name, text in paths.items():
            hits = IMPORT_RE.findall(code_of(text))
            if hits:
                found[name] = hits
        self.assertEqual({}, found)

    # Permitted re-point (the manager confirmed the move): buildPush lives in PlainPush.kt, so the
    # one plainPush is there, and the push of a row now lives in PushRow.kt, which holds the one call
    # of buildPush on the null-port path. The service holds no buildPush at all.
    def test_plain_push_is_called_once_and_only_on_the_null_port_path(self):
        sample = "// plainPush in a comment\n/* plainPush in a block */\nval x = SyncBodyCodec.plainPush(a, b)\n"
        self.assertEqual(1, code_of(sample).count("plainPush"), "control: comments must not count")
        files = sync_main_files()
        total = sum(code_of(t).count("plainPush") for t in files.values())
        self.assertEqual(1, total)
        self.assertEqual(1, code_of(files["PlainPush.kt"]).count("plainPush"))
        text = code_of(read_text(PUSH_ROW_PATH))
        self.assertEqual(0, text.count("plainPush"))
        self.assertEqual(0, code_of(read_text(SERVICE_PATH)).count("buildPush("))
        # The only function that holds it is buildPush, and the only call of buildPush sits between
        # the "keys == null" test and its else branch.
        self.assertEqual(1, text.count("buildPush(entry)"))
        null_branch = between(text, "if (keys == null) {", "} else {")
        self.assertIn("buildPush(entry)", null_branch)
        start = text.index("if (keys == null) {")
        port_branch = text[text.index("} else {", start):text.index("if (push == null) {", start)]
        self.assertNotIn("plainPush", port_branch)
        self.assertNotIn("buildPush(", port_branch)
        plain = code_of(read_text(PLAIN_PATH))
        build = plain[plain.index("internal fun buildPush(entry: QueueEntry): SyncPush? {"):]
        self.assertIn("plainPush", build)

    def test_the_migration_class_is_defined_once_and_called_once_from_the_service(self):
        files = sync_main_files()
        pattern = re.compile(r"\bclass RecordMigration\b")
        sample = "// class RecordMigration in a comment\nclass RecordMigration(\n"
        self.assertEqual(1, len(pattern.findall(code_of(sample))), "control: comments")
        self.assertEqual(1, sum(len(pattern.findall(code_of(t))) for t in files.values()))
        self.assertEqual(1, len(pattern.findall(code_of(files["RecordMigration.kt"]))))
        service = code_of(files["QueuedSyncService.kt"])
        self.assertEqual(1, service.count("migration?.pass()"))
        self.assertEqual(1, service.count("RecordMigration("))

    def test_the_migration_pass_is_one_guard_over_passonce(self):
        sample = "// catch (t: Throwable) in a comment\nfun passOnce() {}\n"
        self.assertEqual(0, code_of(sample).count("catch (t: Throwable)"), "control: comments")
        text = code_of(read_text(os.path.join(SYNC_MAIN, "RecordMigration.kt")))
        self.assertEqual(1, text.count("catch (t: Throwable)"))
        self.assertEqual(1, len(re.findall(r"\bfun passOnce\b", text)))
        self.assertEqual(1, len(re.findall(r"\bfun pass\(\)", text)))

    def test_the_service_file_stays_within_the_line_cap(self):
        text = read_text(SERVICE_PATH)
        lines = text.split("\n")
        count = len(lines) - 1 if lines[-1] == "" else len(lines)
        self.assertLessEqual(count, 300)

    def test_the_migration_never_names_the_pull_cursor_port(self):
        sample = "// PullCursorPort in a comment\nval x = 1\nval p: PullCursorPort = a\n"
        self.assertEqual(1, code_of(sample).count("PullCursorPort"), "control: comments must not count")
        text = code_of(read_text(os.path.join(SYNC_MAIN, "RecordMigration.kt")))
        self.assertNotIn("PullCursorPort", text)

    def test_the_decoder_allowed_members_are_the_openapi_required_members(self):
        sample = '// ALLOWED = setOf("zzz")\nprivate val ALLOWED = setOf("id", "sealed_box")\n'
        self.assertEqual(["id", "sealed_box"], allowed_members(code_of(sample)), "control: comments")
        yaml = "\n    Other:\n      required:\n        - zzz\n    Wanted:\n      required:\n        - a\n"
        yaml += "        - b\n      properties:\n    Later:\n      required:\n        - c\n"
        self.assertEqual(["a", "b"], required_members(yaml, "Wanted"), "control: the named schema only")
        allowed = allowed_members(code_of(read_text(DECODER_PATH)))
        required = required_members(read_text(OPENAPI_PATH), "PendingSealed")
        self.assertEqual(["id", "sealed_box", "created_at"], required)
        self.assertEqual(sorted(required), sorted(allowed))
        self.assertEqual(len(allowed), len(set(allowed)))

    def test_the_decoder_body_cap_is_8_mib_and_defined_once(self):
        constant = "const val MAX_BODY_CHARS = 8 * 1024 * 1024"
        sample = "// " + constant + "\n/* " + constant + " */\n" + constant + "\n"
        self.assertEqual(1, code_of(sample).count(constant), "control: comments must not count")
        self.assertEqual(1, code_of(read_text(DECODER_PATH)).count("MAX_BODY_CHARS = 8 * 1024 * 1024"))
        self.assertEqual(1, len(re.findall(r"\bMAX_BODY_CHARS =", code_of(read_text(DECODER_PATH)))))

    def test_the_pending_conversion_class_is_defined_once_and_called_once_from_the_service(self):
        files = sync_main_files()
        pattern = re.compile(r"\bclass PendingConversion\b")
        sample = "// class PendingConversion in a comment\nclass PendingConversion(\n"
        self.assertEqual(1, len(pattern.findall(code_of(sample))), "control: comments")
        self.assertEqual(1, sum(len(pattern.findall(code_of(t))) for t in files.values()))
        self.assertEqual(1, len(pattern.findall(code_of(files["PendingConversion.kt"]))))
        service = code_of(files["QueuedSyncService.kt"])
        self.assertEqual(1, service.count("pendingConversion?.pass()"))
        self.assertEqual(1, service.count("PendingConversion("))

    def test_the_pending_pass_is_one_guard_over_passonce(self):
        sample = "// catch (t: Throwable) in a comment\nfun passOnce() {}\n"
        self.assertEqual(0, code_of(sample).count("catch (t: Throwable)"), "control: comments")
        text = code_of(read_text(CONVERSION_PATH))
        self.assertEqual(1, text.count("catch (t: Throwable)"))
        self.assertEqual(1, len(re.findall(r"\bfun passOnce\b", text)))
        self.assertEqual(1, len(re.findall(r"\bfun pass\(\)", text)))

    def test_the_pending_conversion_never_names_the_pull_cursor_port(self):
        sample = "// PullCursorPort in a comment\nval x = 1\nval p: PullCursorPort = a\n"
        self.assertEqual(1, code_of(sample).count("PullCursorPort"), "control: comments must not count")
        self.assertNotIn("PullCursorPort", code_of(read_text(CONVERSION_PATH)))

    def test_the_row_cap_is_gone_from_the_decoder_and_its_reader(self):
        sample = "// MAX_ROWS in a comment\nconst val MAX_ROWS = 1000\n"
        self.assertEqual(1, code_of(sample).count("MAX_ROWS"), "control: the scan sees code, not comments")
        for path in (DECODER_PATH, PENDING_JSON_PATH):
            code = code_of(read_text(path))
            self.assertNotIn("MAX_ROWS", code)
            self.assertNotIn("too_many_rows", read_text(path))

    def test_the_marked_set_cap_is_10000_and_defined_once(self):
        constant = "const val MAX_MARKED = 10000"
        sample = "// " + constant + "\n" + constant + "\n"
        self.assertEqual(1, code_of(sample).count(constant), "control: comments must not count")
        code = code_of(read_text(CONVERSION_PATH))
        self.assertEqual(1, code.count("MAX_MARKED = 10000"))
        self.assertEqual(1, len(re.findall(r"\bMAX_MARKED =", code)))

    def test_the_fingerprint_code_uses_no_digest_and_no_security_import(self):
        sample = "import java.security.MessageDigest\nval d = MessageDigest.getInstance(x)\n"
        self.assertEqual(1, code_of(sample).count("MessageDigest.getInstance"), "control: the scan fires")
        for path in (CONVERSION_PATH, DECODER_PATH, PENDING_JSON_PATH):
            code = code_of(read_text(path))
            self.assertNotIn("java.security", code)
            self.assertNotIn("MessageDigest", code)

    def test_every_sync_main_file_stays_within_300_lines(self):
        for name, text in sync_main_files().items():
            lines = text.split("\n")
            count = len(lines) - 1 if lines[-1] == "" else len(lines)
            self.assertLessEqual(count, 300, name)


if __name__ == "__main__":
    unittest.main()
