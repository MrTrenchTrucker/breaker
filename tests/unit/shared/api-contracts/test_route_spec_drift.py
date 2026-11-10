"""Route-to-spec drift guard: every literal Ktor route registration in the two server
modules must have a matching openapi.yaml path+method, and every spec path+method must
have code. No Gradle, no .kt.

Run: python3 -m unittest tests.unit.shared.api-contracts.test_route_spec_drift
"""
import os
import re
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve()
ROOT = HERE
for _ in range(5):
    ROOT = os.path.dirname(ROOT)
ROOT = Path(ROOT)
TOOLS = str(ROOT / "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)
TEST_DIR = str(Path(__file__).resolve().parent)
if TEST_DIR not in sys.path:
    sys.path.insert(0, TEST_DIR)

import yaml_subset  # stdlib-compatible reader the tier already uses (test_updates_contract)

SYNC_MAIN = ROOT / "server" / "modules" / "sync-api" / "src" / "main" / "kotlin"
WHISPER_MAIN = ROOT / "server" / "modules" / "whisper-server" / "src" / "main" / "kotlin"
SPEC = ROOT / "shared" / "modules" / "api-contracts" / "openapi.yaml"

# Ktor DSL: get/post/put/delete/patch("<literal path>"){ ... }
# A trailing "{" distinguishes Ktor route DSL calls from other .get()/.post()
# method calls (e.g. Map.get). Internal listener-only token-check routes
# (/internal/v1/*) are excluded by the stated rule.
_ROUTE_RE = re.compile(
    r'\b(get|post|put|delete|patch)\s*\(\s*"([^"]*)"\s*\)\s*\{'
)

# Path templates compared with parameter names normalised ({id} == {job_id} == {x}).
_PARAM = re.compile(r"\{[^}]+\}")


def _normalise(path: str) -> str:
    return _PARAM.sub("{x}", path)


def _collect_code_routes() -> set[tuple[str, str]]:
    """Scan src/main of sync-api and whisper-server for literal route registrations.

    Returns a set of (METHOD.upper(), normalised_path).
    Excludes the internal /internal/v1 token-check routes: they are mounted only on
    the internal listener (ServiceCheckRoutes.kt:50-52), never on the public listener
    or the VPN interface, and they are not in openapi.yaml.
    """
    found: set[tuple[str, str]] = set()
    for root_dir in (SYNC_MAIN, WHISPER_MAIN):
        for kt in root_dir.rglob("*.kt"):
            text = kt.read_text(encoding="utf-8")
            for method, raw_path in _ROUTE_RE.findall(text):
                if raw_path.startswith("/internal/v1/"):
                    # internal listener only; excluded by stated rule
                    continue
                found.add((method.upper(), _normalise(raw_path)))
    return found


def _collect_spec_routes() -> set[tuple[str, str]]:
    """Parse openapi.yaml paths block -> set of (METHOD.upper(), normalised_path).

    Only get/post/put/delete/patch/head/options keys count; parameters/summary/etc.
    are ignored.
    """
    spec = yaml_subset.loads(SPEC.read_text(encoding="utf-8"), "openapi.yaml")
    paths = spec.get("paths", {})
    found: set[tuple[str, str]] = set()
    for raw_path, item in paths.items():
        if not isinstance(item, dict):
            continue
        for method in item:
            if method in ("get", "post", "put", "delete", "patch", "head", "options"):
                found.add((method.upper(), _normalise(raw_path)))
    return found


# Allow list: short, explicit, one reason per line. Every entry carries a kind and a
# cite. A stale entry fails the test both ways:
#  - a code route now exists for it -> stale (nothing left to allow)
#  - the spec path no longer exists -> stale (nothing left to allow)
# UNBUILT = the sync-api card says "Planned, not built" for it (card line cited).
# DRIFT = spec and code disagree and the card does not say planned
#   (reason "drift found 11-10, fix pending"; entry in DRIFT.md).
ALLOW_LIST: dict[tuple[str, str], str] = {
    ("POST", "/v1/admin/roles"): "UNBUILT: sync-api AGENTS.md:285 'Planned, not built: no roles route is installed.'",
    ("GET", "/v1/admin/health"): "UNBUILT: sync-api AGENTS.md:286-287 'GET /v1/admin/health ... Planned, not built: only the unauthenticated GET /health ... is installed.'",
    ("POST", "/v1/admin/test-service"): "UNBUILT: sync-api AGENTS.md:291-293 'POST /v1/admin/test-service ... Planned, not built: no such route is installed.'",
    ("GET", "/v1/admin/transcription-service"): "UNBUILT: sync-api AGENTS.md:289-290 'GET/PUT /v1/admin/transcription-service ... Planned, not built: no such route is installed.'",
    ("PUT", "/v1/admin/transcription-service"): "UNBUILT: sync-api AGENTS.md:289-290 (see GET entry above).",
    ("GET", "/v1/sync/pending-sealed"): "UNBUILT: sync-api AGENTS.md:274 'Planned, not built: the pending-sealed table and this route do not exist yet (the route is defined in openapi.yaml only).'",
}


class RouteSpecDriftTest(unittest.TestCase):
    """Fails when a code route has no spec entry, or a spec entry has no code route."""

    def setUp(self):
        self.code = _collect_code_routes()
        self.spec = _collect_spec_routes()

    def test_vacuity_guard_scanner_finds_known_routes(self):
        """The scanner must not pass by matching nothing.

        On this base it finds 30 literal registrations including POST /v1/auth/login,
        GET /health and GET /v1/jobs/{job_id}.
        """
        self.assertGreaterEqual(len(self.code), 27, "scanner found no routes — broken regex")
        self.assertIn(("POST", "/v1/auth/login"), self.code)
        self.assertIn(("GET", "/health"), self.code)
        self.assertIn(("GET", "/v1/jobs/{x}"), self.code)

    def test_code_route_has_spec_entry(self):
        """Every code route names a spec path+method (missing = drift)."""
        missing = sorted(self.code - self.spec - set(ALLOW_LIST))
        self.assertFalse(
            missing,
            "code routes with no spec entry: " + ", ".join(f"{m} {p}" for m, p in missing),
        )

    def test_spec_entry_has_code_route(self):
        """Every spec path+method has code (missing = drift)."""
        missing = sorted(self.spec - self.code - set(ALLOW_LIST))
        self.assertFalse(
            missing,
            "spec entries with no code route: " + ", ".join(f"{m} {p}" for m, p in missing),
        )

    def test_allow_list_is_not_stale(self):
        """An allow-list entry fails when a code route exists for it or its spec path is gone."""
        stale_code = sorted(set(ALLOW_LIST) & self.code)
        stale_spec = sorted(e for e in ALLOW_LIST if e not in self.spec)
        self.assertFalse(
            stale_code,
            "stale allow-list entries (code route now exists): "
            + ", ".join(f"{m} {p}" for m, p in stale_code),
        )
        self.assertFalse(
            stale_spec,
            "stale allow-list entries (spec path removed): "
            + ", ".join(f"{m} {p}" for m, p in stale_spec),
        )


if __name__ == "__main__":
    unittest.main()
