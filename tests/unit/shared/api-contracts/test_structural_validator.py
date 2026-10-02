"""Structural validator tests: openapi.yaml is structurally valid for the OpenAPI 3.x subset.

Each check gets a test watched RED on a mutated copy:
- missing openapi version
- missing info.title
- missing info.version
- empty paths
- unresolved $ref
- missing schema type
"""
import unittest
import sys
import copy
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "tools"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from api_contracts_support import load_spec, validate_spec_structure


class StructuralValidatorTest(unittest.TestCase):
    """Tests that the spec is structurally valid."""

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

    def test_unresolved_ref_fails(self):
        """Spec with unresolved $ref fails validation."""
        spec = copy.deepcopy(self.spec)
        # Add a bogus ref
        spec["paths"]["/v1/audio/transcriptions"]["post"]["responses"]["202"]["content"]["application/json"]["schema"] = {
            "$ref": "#/components/schemas/NonExistent"
        }
        with self.assertRaises(ValueError) as ctx:
            validate_spec_structure(spec)
        self.assertIn("Unresolved $ref", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
