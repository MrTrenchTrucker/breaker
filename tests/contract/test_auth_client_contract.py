"""Contract test for the `android/modules/auth-client` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code.
"""
import unittest

import contract_support


class AndroidModulesAuthClientContractTest(contract_support.ModuleContractTest):
    MODULE = "android_auth_client"


import os
import re

KEY_PORT_WORDS = re.compile(r"\b(?:keyPorts|AccountKeyPorts)\b")
ACCOUNT_KEY_PORTS_WORD = re.compile(r"\bAccountKeyPorts\b")
ACCOUNT_KEY_SERVICE_WORD = re.compile(r"\bAccountKeyServiceImpl\b")
APP_MAIN = os.path.join(contract_support.ROOT, "android", "app", "src", "main")
APP_WIRING = os.path.join(
    APP_MAIN, "kotlin", "dev", "breaker", "dictation", "wiring", "AuthServiceWiring.kt"
)
APP_ROOT = os.path.join(
    APP_MAIN, "kotlin", "dev", "breaker", "dictation", "BreakerCompositionRoot.kt"
)
APP_KEY_PORTS_WIRING = os.path.join(
    APP_MAIN, "kotlin", "dev", "breaker", "dictation", "wiring", "AccountKeyPortsWiring.kt"
)


def passes_key_ports(text, words=KEY_PORT_WORDS):
    """True when `text` names no key ports: none of the words is found. Comments count on purpose."""
    return words.search(text) is None


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _server_auth_service_construction(text):
    """The inside of the `ServerAuthService(...)` construction, or "" when there is none."""
    match = re.search(r"ServerAuthService\((.*?)\)", text, re.S)
    return match.group(1) if match else ""


def _root_passes_key_ports(text):
    """True when a `buildAuthService(...)` call in `text` passes `accountKeyPorts(`."""
    calls = [line for line in text.splitlines() if "buildAuthService(" in line]
    return any("accountKeyPorts(" in line for line in calls)


def _account_key_service_offenders(files):
    """Repo-relative paths of the given `rel -> text` files that name `AccountKeyServiceImpl`."""
    return sorted(
        rel
        for rel, text in files.items()
        if not passes_key_ports(text, ACCOUNT_KEY_SERVICE_WORD)
    )


class AuthClientAppPassesKeyPortsTest(unittest.TestCase):
    """The app passes the key ports to `ServerAuthService` in this slice.

    The inverse of the previous no-key-ports pin, deleted by a ruled change: the wiring slice
    passes `accountKeyPorts(...)` into `buildAuthService`, so the pin now asserts the ports are
    present.
    """

    def test_the_wiring_file_builds_the_service_with_the_key_ports(self):
        self.assertTrue(os.path.isfile(APP_WIRING), APP_WIRING + " is missing")
        text = _read(APP_WIRING)
        construction = _server_auth_service_construction(text)
        self.assertTrue(construction, "the wiring never constructs ServerAuthService(")
        self.assertFalse(
            passes_key_ports(construction),
            "the ServerAuthService(...) construction does not name the key ports",
        )

    def test_the_root_builds_the_account_key_ports_and_passes_them(self):
        self.assertTrue(os.path.isfile(APP_ROOT), APP_ROOT + " is missing")
        text = _read(APP_ROOT)
        self.assertIn("accountKeyPorts(", text)
        self.assertTrue(
            _root_passes_key_ports(text),
            "no buildAuthService(...) call in the root passes accountKeyPorts(",
        )

    def test_only_the_account_key_ports_wiring_names_the_account_key_service(self):
        self.assertTrue(os.path.isfile(APP_KEY_PORTS_WIRING), APP_KEY_PORTS_WIRING + " is missing")
        scanned = 0
        offenders = []
        for folder, _dirs, names in os.walk(APP_MAIN):
            for name in names:
                if not name.endswith(".kt"):
                    continue
                path = os.path.join(folder, name)
                scanned += 1
                if not passes_key_ports(_read(path), ACCOUNT_KEY_SERVICE_WORD):
                    offenders.append(os.path.relpath(path, contract_support.ROOT))
        self.assertGreater(scanned, 0, "no .kt file was scanned under " + APP_MAIN)
        self.assertEqual([os.path.relpath(APP_KEY_PORTS_WIRING, contract_support.ROOT)], offenders)

    def test_the_check_can_fail(self):
        # (a) the wiring pin: a construction with no keyPorts fails it; one that names it passes
        self.assertEqual("", _server_auth_service_construction("val s = Other()"))
        self.assertTrue(
            passes_key_ports(_server_auth_service_construction("val s = ServerAuthService(a, b, c, d)"))
        )
        self.assertFalse(
            passes_key_ports(
                _server_auth_service_construction("val s = ServerAuthService(a, b, keyPorts = ports)")
            )
        )
        # (b) the root pin: a buildAuthService call with no accountKeyPorts fails; with it passes
        self.assertFalse(
            _root_passes_key_ports(
                "buildAuthService(serverUrl, sessionVault, authHttp(serverUrl), keyDerivation())"
            )
        )
        self.assertTrue(
            _root_passes_key_ports(
                "buildAuthService(serverUrl, sessionVault, authHttp(serverUrl), keyDerivation(), "
                "accountKeyPorts(credentialVault, cryptoService()))"
            )
        )
        # (c) the app-wide scan: a second file naming AccountKeyServiceImpl fails; the wiring alone passes
        wiring = os.path.relpath(APP_KEY_PORTS_WIRING, contract_support.ROOT)
        self.assertEqual([wiring], _account_key_service_offenders({wiring: "AccountKeyServiceImpl()"}))
        self.assertEqual(
            [wiring, "android/app/src/main/kotlin/other.kt"],
            _account_key_service_offenders(
                {
                    wiring: "AccountKeyServiceImpl()",
                    "android/app/src/main/kotlin/other.kt": "AccountKeyServiceImpl()",
                }
            ),
        )


if __name__ == "__main__":
    unittest.main()
