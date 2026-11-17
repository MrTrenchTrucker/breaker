"""Pin the FULL response-code set of the nine sync/auth/admin operations.

Each test reaches its operation by PATH + METHOD (never by operationId) and
asserts that the operation's response-code set equals the set the server code
really sends. The sets come from the Kotlin routes; this pin is the contract.
The nine operations: registerAccount, getSalt, login, revokeAgentToken,
adminClearStore, adminSetWrongPwLimit, adminSetRateLimits,
adminSetRegistrationOpen, listPendingSealed.

Run: python3 -m unittest tests.unit.shared.api-contracts.test_sync_api_status_gaps_pin -v
"""
import os
import sys
import unittest

# Add tools directory to path for yaml_subset
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import yaml_subset

# Defaults to the repo spec; OPENAPI_SPEC_PATH lets a scratch copy be pinned.
SPEC_PATH = os.environ.get("OPENAPI_SPEC_PATH") or os.path.join(
    ROOT, "shared", "modules", "api-contracts", "openapi.yaml")


def _load_spec():
    """Load and parse the openapi.yaml file."""
    if not os.path.isfile(SPEC_PATH):
        raise AssertionError(f"openapi.yaml not found at {SPEC_PATH}")
    with open(SPEC_PATH, encoding="utf-8") as fh:
        text = fh.read()
    return yaml_subset.loads(text, SPEC_PATH)


class SyncApiStatusGapsPinTest(unittest.TestCase):
    """One test per operation: the FULL response-code set must equal the code's."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_registerAccount_status_gap(self):
        responses = self.paths["/v1/auth/register"]["post"].get("responses", {})
        self.assertEqual(set(responses.keys()),
                         {"201", "400", "403", "409", "413", "429", "503"},
                         "registerAccount (POST /v1/auth/register) must answer exactly "
                         "201/400/403/409/413/429/503")

    def test_getSalt_status_gap(self):
        responses = self.paths["/v1/auth/salt"]["post"].get("responses", {})
        self.assertEqual(set(responses.keys()),
                         {"200", "400", "404", "413", "429", "503"},
                         "getSalt (POST /v1/auth/salt) must answer exactly "
                         "200/400/404/413/429/503")

    def test_login_status_gap(self):
        responses = self.paths["/v1/auth/login"]["post"].get("responses", {})
        self.assertEqual(set(responses.keys()),
                         {"200", "400", "401", "413", "423", "503"},
                         "login (POST /v1/auth/login) must answer exactly "
                         "200/400/401/413/423/503")

    def test_revokeAgentToken_status_gap(self):
        responses = self.paths["/v1/admin/agent-tokens/{id}"]["delete"].get("responses", {})
        self.assertEqual(set(responses.keys()),
                         {"204", "401", "403", "404", "503"},
                         "revokeAgentToken (DELETE /v1/admin/agent-tokens/{id}) must answer exactly "
                         "204/401/403/404/503")

    def test_adminClearStore_status_gap(self):
        responses = self.paths["/v1/admin/clear-store"]["post"].get("responses", {})
        self.assertEqual(set(responses.keys()),
                         {"200", "400", "401", "403", "404", "413", "503"},
                         "adminClearStore (POST /v1/admin/clear-store) must answer exactly "
                         "200/400/401/403/404/413/503")

    def test_adminSetWrongPwLimit_status_gap(self):
        responses = self.paths["/v1/admin/wrong-pw-limit"]["put"].get("responses", {})
        self.assertEqual(set(responses.keys()),
                         {"200", "400", "401", "403", "413", "503"},
                         "adminSetWrongPwLimit (PUT /v1/admin/wrong-pw-limit) must answer exactly "
                         "200/400/401/403/413/503")

    def test_adminSetRateLimits_status_gap(self):
        responses = self.paths["/v1/admin/rate-limits"]["put"].get("responses", {})
        self.assertEqual(set(responses.keys()),
                         {"200", "400", "401", "403", "413", "503"},
                         "adminSetRateLimits (PUT /v1/admin/rate-limits) must answer exactly "
                         "200/400/401/403/413/503")

    def test_adminSetRegistrationOpen_status_gap(self):
        responses = self.paths["/v1/admin/registration-open"]["put"].get("responses", {})
        self.assertEqual(set(responses.keys()),
                         {"200", "400", "401", "403", "413", "503"},
                         "adminSetRegistrationOpen (PUT /v1/admin/registration-open) must answer exactly "
                         "200/400/401/403/413/503")

    def test_listPendingSealed_status_gap(self):
        responses = self.paths["/v1/sync/pending-sealed"]["get"].get("responses", {})
        self.assertEqual(set(responses.keys()),
                         {"200", "401", "403", "503"},
                         "listPendingSealed (GET /v1/sync/pending-sealed) must answer exactly "
                         "200/401/403/503")


if __name__ == "__main__":
    unittest.main()
