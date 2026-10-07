"""Structural validator tests: openapi.yaml is structurally valid for the
OpenAPI 3.x subset.

Every check the validator's docstring claims is backed by code in
validate_spec_structure AND by a test watched RED on a mutated copy of the
spec:
- openapi version is 3.x
- info has title and version
- paths has at least one path
- every schema in components/schemas has a type
- every required name is listed in the schema's properties
- every $ref in the WHOLE spec resolves (responses, items.$ref,
  allOf members -- not just response schemas)
"""
import unittest
import sys
import copy
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "tools"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from api_contracts_support import load_spec, validate_spec_structure


class StructuralValidatorTest(unittest.TestCase):
    """Tests that the spec is structurally valid, and that each claimed
    check fires on a mutated copy."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()

    def test_spec_is_structurally_valid(self):
        """The spec passes all structural checks."""
        validate_spec_structure(self.spec)

    def test_missing_openapi_version_fails(self):
        """Spec without openapi version fails validation."""
        spec = copy.deepcopy(self.spec)
        del spec["openapi"]
        with self.assertRaises(ValueError) as ctx:
            validate_spec_structure(spec)
        self.assertIn("openapi version", str(ctx.exception))

    def test_missing_info_title_fails(self):
        """Spec without info.title fails validation."""
        spec = copy.deepcopy(self.spec)
        del spec["info"]["title"]
        with self.assertRaises(ValueError) as ctx:
            validate_spec_structure(spec)
        self.assertIn("info.title", str(ctx.exception))

    def test_missing_info_version_fails(self):
        """Spec without info.version fails validation."""
        spec = copy.deepcopy(self.spec)
        del spec["info"]["version"]
        with self.assertRaises(ValueError) as ctx:
            validate_spec_structure(spec)
        self.assertIn("info.version", str(ctx.exception))

    def test_empty_paths_fails(self):
        """Spec with empty paths fails validation."""
        spec = copy.deepcopy(self.spec)
        spec["paths"] = {}
        with self.assertRaises(ValueError) as ctx:
            validate_spec_structure(spec)
        self.assertIn("paths is empty", str(ctx.exception))

    def test_unresolved_response_ref_fails(self):
        """Spec with an unresolved response $ref fails validation."""
        spec = copy.deepcopy(self.spec)
        spec["paths"]["/v1/audio/transcriptions"]["post"]["responses"]["202"][
            "content"]["application/json"]["schema"] = {
            "$ref": "#/components/schemas/NonExistent"
        }
        with self.assertRaises(ValueError) as ctx:
            validate_spec_structure(spec)
        self.assertIn("Unresolved $ref", str(ctx.exception))

    # ------------------------------------------------------------------
    # The checks the docstring used to claim without implementing.
    # Each is RED on the earlier validator (which had none of them) and
    # green on the fixed validator.
    # ------------------------------------------------------------------

    def test_validator_refuses_missing_type(self):
        """[CHECK-TYPE] A schema without a type is refused."""
        spec = copy.deepcopy(self.spec)
        del spec["components"]["schemas"]["Health"]["type"]
        with self.assertRaises(ValueError) as ctx:
            validate_spec_structure(spec)
        self.assertIn("has no type", str(ctx.exception))

    def test_validator_refuses_required_name_absent_from_properties(self):
        """[CHECK-REQUIRED-IN-PROPERTIES] A required name with no property is refused."""
        spec = copy.deepcopy(self.spec)
        spec["components"]["schemas"]["Job"]["required"].append("ghost_field")
        with self.assertRaises(ValueError) as ctx:
            validate_spec_structure(spec)
        self.assertIn(
            "schema Job: required field 'ghost_field' is not in properties",
            str(ctx.exception),
        )

    def test_validator_refuses_dangling_items_ref(self):
        """[CHECK-REFS] A dangling items.$ref (NOT in a response schema) is refused."""
        spec = copy.deepcopy(self.spec)
        segments = spec["components"]["schemas"]["TranscriptionResult"][
            "properties"]["segments"]
        segments["items"]["$ref"] = "#/components/schemas/Bogus"
        with self.assertRaises(ValueError) as ctx:
            validate_spec_structure(spec)
        self.assertIn("Unresolved $ref: #/components/schemas/Bogus", str(ctx.exception))

    def test_validator_refuses_dangling_allof_ref(self):
        """[CHECK-REFS] A dangling allOf member $ref is refused."""
        spec = copy.deepcopy(self.spec)
        result = spec["components"]["schemas"]["Job"]["properties"]["result"]
        result["allOf"][0]["$ref"] = "#/components/schemas/Bogus"
        with self.assertRaises(ValueError) as ctx:
            validate_spec_structure(spec)
        self.assertIn("Unresolved $ref: #/components/schemas/Bogus", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
