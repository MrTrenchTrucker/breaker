"""A registry url carries no port and no userinfo, on either url shape.

A url is copied into the generated Kotlin, logged and handed to the
downloader, so what rides along in it matters:

* a port sends the download somewhere other than GitHub (the asset-id and
  commit routes are only served on 443);
* userinfo is a credential in a url — it lands in the generated source, in
  logs, and in anything that prints the url.

Both shapes of an accepted url (the release-asset id and the full 40-char
commit) are covered, plus the acceptance side: the plain asset url and the
plain commit url must keep working, so the refusal cannot degenerate into
"refuse every url".

One test per rule, each planting the bad url in the committed entry's own
text; a plant the generator would ACCEPT fails the test (the helper raises).

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import unittest

import model_registry_support as ts

GOOD_URL = ("url: https://api.github.com/repos/k2-fsa/sherpa-onnx/"
            "releases/assets/191972150")
COMMIT = "9a65b6ea94c311ca770c2bf895b30f456a22d703"
ASSET_PATH_TAIL = "/repos/k2-fsa/sherpa-onnx/releases/assets/191972150"


def replace_once(text, old, new):
    assert text.count(old) == 1, f"expected exactly one {old!r}"
    return text.replace(old, new)


def refuses_url(url):
    """The refusal message for `url` planted in the committed entry."""
    return ts.refuses(replace_once(ts.models_text(), GOOD_URL, f'url: "{url}"'))


def accepts_url(url):
    """The converted entry for `url` planted in the committed entry."""
    return ts.check_models(
        replace_once(ts.models_text(), GOOD_URL, f'url: "{url}"'))[0]


class PortRefusalTest(unittest.TestCase):
    def test_port_on_an_asset_url_is_refused(self):
        msg = refuses_url(f"https://api.github.com:8443{ASSET_PATH_TAIL}")
        self.assertIn("port", msg)
        self.assertIn("entry 'small' line ", msg)

    def test_port_on_a_commit_url_is_refused(self):
        msg = refuses_url(
            f"https://github.com:8443/o/r/commit/{COMMIT}")
        self.assertIn("port", msg)

    def test_port_on_the_default_https_port_is_refused(self):
        # an explicit :443 downloads from the same place; it is still a port
        # the reader has to understand, and the committed form is the bare host
        msg = refuses_url(f"https://api.github.com:443{ASSET_PATH_TAIL}")
        self.assertIn("port", msg)


class UserinfoRefusalTest(unittest.TestCase):
    def test_user_and_password_are_refused(self):
        msg = refuses_url(
            f"https://user:pw@api.github.com{ASSET_PATH_TAIL}")
        self.assertIn("userinfo", msg)

    def test_username_only_is_refused(self):
        msg = refuses_url(f"https://user@github.com/o/r/commit/{COMMIT}")
        self.assertIn("userinfo", msg)


class PlainUrlsStillAcceptedTest(unittest.TestCase):
    """The refusal must not cost the two shapes the registry ships."""

    def test_plain_asset_url_is_accepted(self):
        entry = accepts_url(f"https://api.github.com{ASSET_PATH_TAIL}")
        self.assertEqual(entry["id"], "small")

    def test_plain_commit_url_is_accepted(self):
        entry = accepts_url(
            f"https://github.com/csukuangfj/sherpa-onnx-streaming-zipformer-"
            f"en-2023-06-21/commit/{COMMIT}")
        self.assertEqual(entry["id"], "small")


class CommitHostIsPinnedTest(unittest.TestCase):
    """A commit-shaped path on another host is refused, and the refusal says
    which host it saw.

    The commit branch accepts the path shape and nothing else; the host rule
    above it is what keeps a commit url on github.com or api.github.com. Both
    halves of that are pinned here: a commit url on github.com is accepted,
    and the same path on another host is refused with that host named. A test
    that only asserted acceptance would survive weakening the host rule, and
    one that only asserted refusal would pass a generator that refused
    everything."""

    def test_commit_url_on_github_is_accepted(self):
        entry = accepts_url(f"https://github.com/o/r/commit/{COMMIT}")
        self.assertEqual(entry["url"], f"https://github.com/o/r/commit/{COMMIT}")

    def test_commit_url_on_another_host_is_refused_naming_that_host(self):
        msg = refuses_url(f"https://git.example.invalid/o/r/commit/{COMMIT}")
        self.assertIn("git.example.invalid", msg)
        self.assertIn("entry 'small' line ", msg)

    def test_commit_url_on_the_api_host_is_accepted(self):
        entry = accepts_url(f"https://api.github.com/o/r/commit/{COMMIT}")
        self.assertEqual(entry["id"], "small")


class UserinfoEchoTest(unittest.TestCase):
    """A refusal must not quote the credential back.

    The whole point of refusing userinfo is that the url carries a secret. A
    refusal is a message a human reads and every test log keeps, so echoing
    the authority moves that secret from the url into every log that ever
    prints the refusal -- strictly more exposure than the url itself.

    Both shapes are covered: username AND password, and a bare username.
    """

    def test_a_refusal_never_echoes_the_password(self):
        msg = ts.refuses(replace_once(
            ts.models_text(), GOOD_URL,
            'url: "https://admin:hunter2@api.github.com'
            '/repos/k2-fsa/sherpa-onnx/releases/assets/191972150"'))
        self.assertIn("must not carry userinfo", msg)
        self.assertNotIn("hunter2", msg)
        self.assertNotIn("admin:", msg)

    def test_a_refusal_never_echoes_a_username_only_authority(self):
        msg = ts.refuses(replace_once(
            ts.models_text(), GOOD_URL,
            'url: "https://admin@api.github.com'
            '/repos/k2-fsa/sherpa-onnx/releases/assets/191972150"'))
        self.assertIn("must not carry userinfo", msg)
        self.assertNotIn("admin", msg)


if __name__ == "__main__":
    unittest.main()
