"""Cross-check of the sign-in KDF vector fixture against the Kotlin KDF tests.

Standard library only. The KDF_ANDROID rows of
server/modules/web-fe/src/test/js/signin-vectors.mjs and the argon2id, kek and
verifier hex of android/modules/crypto/src/test/kotlin/dev/breaker/dictation/crypto/
KdfImplTest.kt must agree vector by vector, so a changed value on either side
fails by name. Both files are read at run time; this file holds no hex value.
"""
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))

FIXTURE = os.path.join(ROOT, "server", "modules", "web-fe", "src", "test", "js", "signin-vectors.mjs")
KOTLIN = os.path.join(ROOT, "android", "modules", "crypto", "src", "test", "kotlin", "dev", "breaker",
                      "dictation", "crypto", "KdfImplTest.kt")

FIELDS = ["password", "saltHex", "argon2id", "kek", "verifier"]
CHECKED = ["argon2id", "kek", "verifier"]
# The Kotlin vector that each fixture row stands for, in the order the fixture holds the rows today.
# The Kotlin file declares the alternative-cost vector before the composed one; see the notes.
FIXTURE_OWNERS = ["v1Main", "v2", "v3AltParams", "p2"]
FIELD_RE = re.compile(r"^\s+(password|saltHex|argon2id|kek|verifier): '([^']*)',\s*$", re.M)
KOTLIN_RE = re.compile(
    r'^\s*(?:private val (\w+) = R21Vector\(|(?:private val )?(\w+) = "([0-9a-f]+)",?\s*$)', re.M)
VECTOR_PREFIX_RE = re.compile(r"^([vp]2)[A-Z]")
HEX64_RE = re.compile(r"[0-9a-f]{64}")


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def kind_of(name):
    for kind in CHECKED:
        if name.lower().endswith(kind.lower()):
            return kind
    return None


def fixture_rows(text):
    """The KDF_ANDROID rows as dicts, or a problem list when the layout is not the five fields in order."""
    start = text.find("KDF_ANDROID")
    if start < 0:
        return None, ["KDF_ANDROID is not in the fixture"]
    rest = text[start + len("KDF_ANDROID"):]
    end = re.search(r"^export ", rest, re.M)
    block = rest if end is None else rest[:end.start()]
    found = FIELD_RE.findall(block)
    names = [name for name, _value in found]
    if len(names) % len(FIELDS) or names != FIELDS * (len(names) // len(FIELDS)):
        return None, ["KDF_ANDROID fields are not the five names in order"]
    return [dict(found[i:i + len(FIELDS)]) for i in range(0, len(found), len(FIELDS))], []


def kotlin_vectors(text):
    """Map each Kotlin vector name to its argon2id, kek and verifier hex."""
    found, owner = {}, None
    for match in KOTLIN_RE.finditer(text):
        decl, name, value = match.group(1), match.group(2), match.group(3)
        if decl:
            owner = decl
            continue
        kind = kind_of(name) if name else None
        if kind is None:
            continue
        prefix = VECTOR_PREFIX_RE.match(name)
        found.setdefault(prefix.group(1) if prefix else owner, {})[kind] = value
    return found


def compare(rows, found):
    """Problems by vector name and field name only; no hex value is ever printed."""
    if len(rows) != len(FIXTURE_OWNERS):
        return ["fixture holds %d vectors, expected %d" % (len(rows), len(FIXTURE_OWNERS))]
    problems = []
    for owner, row in zip(FIXTURE_OWNERS, rows):
        triple = found.get(owner)
        if triple is None or any(kind not in triple for kind in CHECKED):
            problems.append(owner + ": no argon2id, kek and verifier in KdfImplTest.kt")
            continue
        for kind in CHECKED:
            if row[kind] != triple[kind]:
                problems.append(owner + ": fixture " + kind + " differs from KdfImplTest.kt")
    return problems


def _flip(value):
    return ("1" if value[0] == "0" else "0") + value[1:]


class KotlinVectorCrossCheckTest(unittest.TestCase):

    def setUp(self):
        self.fixture = _read(FIXTURE)
        self.kotlin = _read(KOTLIN)

    def test_fixture_layout_is_five_string_fields_per_vector(self):
        rows, problems = fixture_rows(self.fixture)
        self.assertEqual(problems, [])
        self.assertEqual(len(rows), len(FIXTURE_OWNERS))

    def test_kotlin_has_argon2id_kek_and_verifier_for_each_owner(self):
        found = kotlin_vectors(self.kotlin)
        self.assertEqual(sorted(found), sorted(FIXTURE_OWNERS))
        for owner in FIXTURE_OWNERS:
            self.assertEqual(sorted(found[owner]), sorted(CHECKED), owner)

    def test_fixture_and_kotlin_hex_agree_vector_by_vector(self):
        rows, problems = fixture_rows(self.fixture)
        self.assertEqual(problems, [])
        self.assertEqual(compare(rows, kotlin_vectors(self.kotlin)), [])

    def test_hex_values_are_64_lowercase_characters(self):
        rows, _problems = fixture_rows(self.fixture)
        for row in rows:
            for kind in CHECKED:
                self.assertIsNotNone(HEX64_RE.fullmatch(row[kind]), kind)
        for owner, triple in kotlin_vectors(self.kotlin).items():
            for kind in CHECKED:
                self.assertIsNotNone(HEX64_RE.fullmatch(triple[kind]), owner + " " + kind)

    def test_control_changed_fixture_value_fails_by_name(self):
        rows, _problems = fixture_rows(self.fixture)
        bad = [dict(row) for row in rows]
        bad[0]["argon2id"] = _flip(bad[0]["argon2id"])
        self.assertEqual(compare(bad, kotlin_vectors(self.kotlin)),
                         ["v1Main: fixture argon2id differs from KdfImplTest.kt"])

    def test_control_changed_kotlin_value_fails_by_name(self):
        rows, _problems = fixture_rows(self.fixture)
        found = {owner: dict(triple) for owner, triple in kotlin_vectors(self.kotlin).items()}
        found["v3AltParams"]["kek"] = _flip(found["v3AltParams"]["kek"])
        self.assertEqual(compare(rows, found),
                         ["v3AltParams: fixture kek differs from KdfImplTest.kt"])

    def test_control_missing_kotlin_vector_fails_by_name(self):
        rows, _problems = fixture_rows(self.fixture)
        found = dict(kotlin_vectors(self.kotlin))
        del found["p2"]
        self.assertEqual(compare(rows, found),
                         ["p2: no argon2id, kek and verifier in KdfImplTest.kt"])

    def test_control_broken_fixture_layout_is_reported(self):
        self.assertIn("    argon2id: '", self.fixture, "control edit did not land")
        broken = self.fixture.replace("    argon2id: '", "    argon2idX: '", 1)
        _rows, problems = fixture_rows(broken)
        self.assertNotEqual(problems, [])


if __name__ == "__main__":
    unittest.main()
