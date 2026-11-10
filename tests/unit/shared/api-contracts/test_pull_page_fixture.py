"""The pull page fixture is the same text on all three sides.

  - shared/modules/api-contracts/examples/pull_page_vectors.json  (JSON, key "page")
  - server/modules/sync-api/.../transcriptions/PullPageFixture.kt  (internal object PullPageFixture, PAGE)
  - android/modules/sync/.../sync/PullPageFixture.kt                (internal object PullPageFixture, PAGE)

The tests read the JSON file and both Kotlin constants and compare them once the Kotlin string
escapes are undone. A failure means the sides diverge: a hand edit of one copy did not reach the
others, so the page could decode differently on the server and on the phone.

Standard library only.
"""
import json
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
for _ in range(4):
    ROOT = os.path.dirname(ROOT)

JSON_PATH = os.path.join(
    ROOT, "shared", "modules", "api-contracts", "examples", "pull_page_vectors.json",
)
SERVER_KOTLIN_PATH = os.path.join(
    ROOT, "server", "modules", "sync-api", "src", "test", "kotlin", "dev", "breaker",
    "server", "syncapi", "transcriptions", "PullPageFixture.kt",
)
ANDROID_KOTLIN_PATH = os.path.join(
    ROOT, "android", "modules", "sync", "src", "test", "kotlin", "dev", "breaker",
    "dictation", "sync", "PullPageFixture.kt",
)

STORED_AT_FORM = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$")
PAGE_CONSTANT = re.compile(r'const val PAGE = "(.*)"\n')


def unescape_kotlin_string(raw):
    """Undo the escapes of a Kotlin string literal that holds a JSON text: \\" and \\\\ only."""
    out = []
    i = 0
    while i < len(raw):
        ch = raw[i]
        if ch == "\\":
            nxt = raw[i + 1]
            if nxt not in ('"', "\\"):
                raise AssertionError("fixture constant holds an escape this test does not know: \\" + nxt)
            out.append(nxt)
            i += 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def json_page():
    with open(JSON_PATH, "r", encoding="utf-8") as f:
        doc = json.load(f)
    page = doc["page"]
    if not isinstance(page, str):
        raise AssertionError("pull_page_vectors.json: page must be a string")
    return page


def kotlin_page(path):
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    match = PAGE_CONSTANT.search(text)
    if match is None:
        raise AssertionError(path + ": no line of the form: const val PAGE = \"...\"")
    return unescape_kotlin_string(match.group(1))


class PullPageFixtureTest(unittest.TestCase):

    def test_json_page_parses_and_every_stored_at_has_three_millisecond_digits(self):
        page = json.loads(json_page())
        self.assertEqual({"items", "next", "more"}, set(page.keys()))
        self.assertGreaterEqual(len(page["items"]), 2)
        for record in page["items"]:
            self.assertRegex(record["stored_at"], STORED_AT_FORM, "stored_at form of " + record["client_id"])

    def test_json_page_equals_the_server_constant(self):
        self.assertEqual(json_page(), kotlin_page(SERVER_KOTLIN_PATH))

    def test_json_page_equals_the_android_constant(self):
        self.assertEqual(json_page(), kotlin_page(ANDROID_KOTLIN_PATH))


if __name__ == "__main__":
    unittest.main()
