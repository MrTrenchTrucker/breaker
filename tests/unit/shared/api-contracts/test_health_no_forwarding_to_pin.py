"""Pin: the Health contract carries NO `forwarding_to` member anywhere.

The member was removed outright: no server writes `forwarding_to` and no client reads
it (whisper-server's GET /health no longer sends it). This pin guards the api-contracts
module (schema and example; the card prose is covered elsewhere) so the member
cannot come back.

Tests:
  1. the Health schema declares NO property named `forwarding_to`   (RED on base)
  2. the Health schema still declares `status` and `uptime`         (GREEN control)
  3. the /health 200 example carries no `forwarding_to` line        (RED on base)
  4. CONTROL: an optional member that IS supposed to be there is still
     visible to the pin -- Job.result and TranscriptionResult.words are
     present and not required                                       (GREEN control)

Run: python3 -m unittest tests.unit.shared.api-contracts.test_health_no_forwarding_to_pin -v
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


def _spec():
    if not os.path.isfile(SPEC_PATH):
        raise AssertionError(f"openapi.yaml not found at {SPEC_PATH}")
    with open(SPEC_PATH, encoding="utf-8") as fh:
        return yaml_subset.loads(fh.read(), SPEC_PATH)


def _health_schema():
    schemas = _spec().get("components", {}).get("schemas", {})
    if "Health" not in schemas:
        raise AssertionError("Health schema missing from components/schemas")
    return schemas["Health"]


def _health_example():
    """The parsed /health 200 application/json example, or {} if absent."""
    path = _spec().get("paths", {}).get("/health", {})
    media = path.get("get", {}).get("responses", {}).get("200", {}).get("content", {})
    return media.get("application/json", {}).get("example", {})


class HealthNoForwardingToPinTest(unittest.TestCase):
    """The Health contract must not advertise or example forwarding_to."""

    def test_health_schema_declares_no_forwarding_to(self):
        """(1) The Health schema has no property named `forwarding_to`."""
        schema = _health_schema()
        props = schema.get("properties", {})
        self.assertNotIn(
            "forwarding_to",
            props,
            f"Health must not declare forwarding_to; got properties {sorted(props)}",
        )
        # The prose must not advertise it either.
        self.assertNotIn(
            "forwarding_to",
            schema.get("description", ""),
            "Health description must not mention forwarding_to",
        )

    def test_health_schema_still_declares_status_and_uptime(self):
        """(2) CONTROL: `status` and `uptime` are still declared properties."""
        schema = _health_schema()
        props = schema.get("properties", {})
        self.assertIn("status", props, "Health must still declare status")
        self.assertIn("uptime", props, "Health must still declare uptime")
        self.assertEqual(
            schema.get("required"),
            ["status"],
            f"Health.required must stay exactly ['status'], got {schema.get('required')!r}",
        )

    def test_health_example_carries_no_forwarding_to_line(self):
        """(3) The /health 200 example has no forwarding_to key or line."""
        example = _health_example()
        self.assertNotIn(
            "forwarding_to",
            example,
            f"the /health example must not carry forwarding_to; got {example!r}",
        )
        with open(SPEC_PATH, encoding="utf-8") as fh:
            text = fh.read()
        start = text.find("/health:")
        end = text.find("/v1/status:", start)
        self.assertGreater(start, 0, "/health: path block must exist in openapi.yaml")
        self.assertGreater(end, start, "/v1/status: must follow /health: in openapi.yaml")
        block = text[start:end]
        offenders = [
            line for line in block.split("\n") if "forwarding_to" in line
        ]
        self.assertEqual(
            offenders,
            [],
            f"the /health block must contain no forwarding_to line, got {offenders!r}",
        )

    def test_control_optional_members_are_still_visible(self):
        """(4) CONTROL: Job.result and TranscriptionResult.words are present
        and NOT in their schema's `required` list -- the pin can see optional
        members that are supposed to be there."""
        schemas = _spec().get("components", {}).get("schemas", {})
        for schema_name, member in (("Job", "result"), ("TranscriptionResult", "words")):
            props = schemas[schema_name].get("properties", {})
            self.assertIn(
                member,
                props,
                f"{schema_name} must still declare {member} (control)",
            )
            self.assertNotIn(
                member,
                schemas[schema_name].get("required", []),
                f"{schema_name}.{member} is optional and must NOT be required (control)",
            )


if __name__ == "__main__":
    unittest.main()
