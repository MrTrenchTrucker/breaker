"""Pin: the KdfParams schema is closed (`additionalProperties: false`).

KdfParams is $ref'd by four schemas: RegisterRequest.kdf_params,
SaltResponse.kdf_params, ChangePasswordRequest.new_kdf_params and
CompleteResetRequest.kdf_params. Every one of those is strict in the server
code, so closing the shared schema over-rules none of them:

- register request: AuthRegisterRoute.kt:127-136 refuses an unknown member
  in kdf_params ("unknown field in kdf_params");
- salt answer: AuthSaltRoute.kt:56-58 builds kdf_params as a literal with
  exactly memory_kib, iterations and parallelism;
- change password: ChangePasswordBody.kt:159-182 refuses an unknown member
  in new_kdf_params;
- complete reset: CompleteResetBody.kt:86-109 refuses an unknown member in
  kdf_params.

Run: python3 -m unittest tests.unit.shared.api-contracts.test_kdf_params_closed_pin -v
"""
import os
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

SPEC_PATH = os.path.join(ROOT, "shared", "modules", "api-contracts", "openapi.yaml")


def _kdf_params():
    if not os.path.isfile(SPEC_PATH):
        raise AssertionError(f"openapi.yaml not found at {SPEC_PATH}")
    with open(SPEC_PATH, encoding="utf-8") as fh:
        spec = yaml_subset.loads(fh.read(), SPEC_PATH)
    schemas = spec.get("components", {}).get("schemas", {})
    if "KdfParams" not in schemas:
        raise AssertionError("KdfParams schema missing from components/schemas")
    return schemas["KdfParams"]


class KdfParamsClosedPinTest(unittest.TestCase):
    """Pins the KdfParams schema closedness."""

    def test_kdf_params_is_closed(self):
        """KdfParams carries additionalProperties: false."""
        schema = _kdf_params()
        self.assertEqual(
            schema.get("additionalProperties"),
            "false",
            "KdfParams must be closed (additionalProperties: false)",
        )


if __name__ == "__main__":
    unittest.main()
