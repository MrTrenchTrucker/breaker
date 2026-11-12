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
APP_MAIN = os.path.join(contract_support.ROOT, "android", "app", "src", "main")
APP_WIRING = os.path.join(
    APP_MAIN, "kotlin", "dev", "breaker", "dictation", "wiring", "AuthServiceWiring.kt"
)


def passes_key_ports(text, words=KEY_PORT_WORDS):
    """True when `text` names no key ports: none of the words is found. Comments count on purpose."""
    return words.search(text) is None


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


class AuthClientAppPassesNoKeyPortsTest(unittest.TestCase):
    """The app passes no key ports to `ServerAuthService` in this slice.

    This pin flips in the later slice that wires the app. It is meant to be deleted then, by a
    ruled change, and not edited to pass.
    """

    def test_the_wiring_file_builds_the_service_with_no_key_ports(self):
        self.assertTrue(os.path.isfile(APP_WIRING), APP_WIRING + " is missing")
        text = _read(APP_WIRING)
        self.assertIn("ServerAuthService(", text)
        self.assertTrue(
            passes_key_ports(text),
            "the wiring names the key ports (the word keyPorts or AccountKeyPorts)",
        )

    def test_no_app_source_names_account_key_ports(self):
        scanned = 0
        offenders = []
        for folder, _dirs, names in os.walk(APP_MAIN):
            for name in names:
                if not name.endswith(".kt"):
                    continue
                path = os.path.join(folder, name)
                scanned += 1
                if not passes_key_ports(_read(path), ACCOUNT_KEY_PORTS_WORD):
                    offenders.append(os.path.relpath(path, contract_support.ROOT))
        self.assertGreater(scanned, 0, "no .kt file was scanned under " + APP_MAIN)
        self.assertEqual([], offenders)

    def test_the_check_can_fail(self):
        self.assertFalse(passes_key_ports("val s = ServerAuthService(a, b, keyPorts = ports)"))
        self.assertFalse(passes_key_ports("keyPorts = ports"))
        self.assertFalse(passes_key_ports("val ports = AccountKeyPorts(a, b, c)"))
        self.assertTrue(passes_key_ports("val s = ServerAuthService(a, b, c, d)"))
        # the app-wide scan looks for the class name only
        self.assertFalse(passes_key_ports("AccountKeyPorts(", ACCOUNT_KEY_PORTS_WORD))
        self.assertTrue(passes_key_ports("keyPorts = ports", ACCOUNT_KEY_PORTS_WORD))


if __name__ == "__main__":
    unittest.main()
