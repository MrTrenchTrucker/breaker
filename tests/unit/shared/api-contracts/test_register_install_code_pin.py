"""Pin: the OpenAPI RegisterRequest declares the optional `install_code`
member (ADR-043 slice C), type string, and NOT in the schema's `required`
list; and the five real members stay required.

Run: python3 -m unittest tests.unit.shared.api-contracts.test_register_install_code_pin -v
"""
import os
import re
import sys
import unittest

HERE = os.path.abspath(__file__)
ROOT = HERE
for _ in range(5):
    ROOT = os.path.dirname(ROOT)
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import yaml_subset

# Defaults to the repo spec; OPENAPI_SPEC_PATH lets a scratch copy be pinned
# (the mutant check uses it, the same hook as test_sync_api_status_gaps_pin).
SPEC_PATH = os.environ.get("OPENAPI_SPEC_PATH") or os.path.join(
    ROOT, "shared", "modules", "api-contracts", "openapi.yaml")

# The server's own install-code length: one source of truth, read from the
# Kotlin source text (InstallCode.kt), never a second literal for N in this
# test. The alphabet and the anchors are pinned literally, as the brief says.
INSTALL_CODE_KOTLIN = os.path.join(
    ROOT, "server", "modules", "sync-api", "src", "main", "kotlin",
    "dev", "breaker", "server", "syncapi", "accounts", "InstallCode.kt",
)


def _code_length():
    """Read CODE_LENGTH from the InstallCode.kt source text."""
    if not os.path.isfile(INSTALL_CODE_KOTLIN):
        raise AssertionError(f"InstallCode.kt not found at {INSTALL_CODE_KOTLIN}")
    with open(INSTALL_CODE_KOTLIN, encoding="utf-8") as fh:
        text = fh.read()
    match = re.search(r"CODE_LENGTH\s*=\s*([0-9]+)", text)
    if not match:
        raise AssertionError("CODE_LENGTH constant not found in InstallCode.kt")
    return int(match.group(1))


def _register_request():
    """Load the spec and return the RegisterRequest schema."""
    if not os.path.isfile(SPEC_PATH):
        raise AssertionError(f"openapi.yaml not found at {SPEC_PATH}")
    with open(SPEC_PATH, encoding="utf-8") as fh:
        spec = yaml_subset.loads(fh.read(), SPEC_PATH)
    schemas = spec.get("components", {}).get("schemas", {})
    if "RegisterRequest" not in schemas:
        raise AssertionError("RegisterRequest schema missing from components/schemas")
    return schemas["RegisterRequest"]


class RegisterInstallCodePinTest(unittest.TestCase):
    """Pins the install_code member of the RegisterRequest schema."""

    def test_register_request_declares_install_code(self):
        """(1) RegisterRequest declares a property named `install_code`."""
        schema = _register_request()
        props = schema.get("properties", {})
        self.assertIn(
            "install_code",
            props,
            f"RegisterRequest properties must declare install_code; got {sorted(props)}",
        )

    def test_install_code_is_optional_string(self):
        """(2) That property is a string, and is NOT in `required`."""
        schema = _register_request()
        props = schema.get("properties", {})
        self.assertIn("install_code", props, "RegisterRequest must declare install_code")
        prop = props["install_code"]
        self.assertEqual(
            prop.get("type"),
            "string",
            f"install_code must be type string; got {prop.get('type')!r}",
        )
        self.assertNotIn(
            "install_code",
            schema.get("required", []),
            "install_code is optional (required only while zero accounts exist), "
            "so it must NOT be in RegisterRequest.required",
        )

    def test_install_code_pattern_length_is_the_kotlin_code_length(self):
        """(3) The install_code pattern is exactly `^[A-Za-z0-9_-]{N}$`, with N
        read from CODE_LENGTH in InstallCode.kt; the alphabet and anchors are
        pinned literally, as the brief specifies."""
        n = _code_length()
        schema = _register_request()
        prop = schema.get("properties", {})["install_code"]
        self.assertEqual(
            prop.get("pattern"),
            f"^[A-Za-z0-9_-]{{{n}}}$",
            f"install_code pattern must be ^[A-Za-z0-9_-]{{{n}}}$ (CODE_LENGTH in "
            f"InstallCode.kt); got {prop.get('pattern')!r}",
        )

    def test_register_request_requires_its_five_real_members(self):
        """(4) CONTROL: RegisterRequest DOES require its five real members."""
        schema = _register_request()
        self.assertEqual(
            schema.get("required"),
            ["username", "salt", "kdf_params", "kdf_version", "auth_verifier"],
            "RegisterRequest.required must be exactly the five real members",
        )


if __name__ == "__main__":
    unittest.main()
