"""Refusal pin tests: every production refusal that a sweep can switch off
without turning any test red gets a test here that plants the case and
asserts THAT refusal's own message (and, for the reader, the line number).

Rule: for each production refusal, either a test pins its own message or
the branch and its docstring claim are deleted. The sweep that found the
gap left 20 refusals unpinned (reader 16, validator 3, comparator 1); the
file-ends-unfinished refuse (one more, the sweep's last-line arithmetic)
is pinned here too. All 22 are pinned, none deleted: each is reachable
and its message is the documented behaviour.

Every test runs the PRODUCTION function (parse_kotlin_file / load_spec /
get_schemas / validate_spec_structure / check_coverage /
map_spec_type_to_kotlin / parse_all_kotlin) on a planted input and asserts
the exact text of the refusal it pins.
"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "tools"))

import api_contracts_support as S
import api_kotlin_reader as R
from api_comparator import check_coverage, map_spec_type_to_kotlin
from api_contracts_support import get_schemas, validate_spec_structure, load_spec


class ReaderRefusalPins(unittest.TestCase):
    """The 16 reader refusals, each pinned by its own message and line."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self._tmp.cleanup()

    def _refuse(self, text, expected_msg, line_no=None):
        p = Path(self._tmp.name) / "Pin.kt"
        p.write_text(text)
        with self.assertRaises(ValueError) as ctx:
            R.parse_kotlin_file(p)
        msg = str(ctx.exception)
        self.assertIn(expected_msg, msg, f"pinned message missing; got: {msg!r}")
        self.assertIn("Pin.kt", msg, "refusal must name the file")
        if line_no is not None:
            self.assertIn(f":{line_no}:", msg,
                          f"refusal must name line {line_no}; got: {msg!r}")

    def test_pin_malformed_package_declaration(self):
        self._refuse(
            "package dev breaker\n\ndata class Foo(\n    val a: Int\n)\n",
            "malformed package declaration", 1)

    def test_pin_second_package_declaration(self):
        self._refuse(
            "package dev.breaker.shared.api\n\npackage dev.breaker.other\n\n"
            "data class Foo(\n    val a: Int\n)\n",
            "second package declaration", 3)

    def test_pin_malformed_enum_class_declaration(self):
        self._refuse(
            "package dev.breaker.shared.api\n\nenum class (\n",
            "malformed enum class declaration", 3)

    def test_pin_malformed_data_class_declaration(self):
        self._refuse(
            "package dev.breaker.shared.api\n\ndata class (Foo(\n"
            "    val a: Int\n)\n",
            "malformed data class declaration", 3)

    def test_pin_data_class_line_must_end_with_open_paren(self):
        self._refuse(
            "package dev.breaker.shared.api\n\ndata class Foo(val a: Int)\n",
            "data class line must end with '('", 3)

    def test_pin_companion_object_at_top_level(self):
        self._refuse(
            "package dev.breaker.shared.api\n\ndata class Foo(\n"
            "    val a: Int\n)\ncompanion object {\n}\n",
            "companion object is only recognised inside an enum class", 6)

    def test_pin_unrecognised_top_level_construct(self):
        self._refuse(
            "package dev.breaker.shared.api\n\ndata class Foo(\n"
            "    val a: Int\n)\ninterface Bar {\n}\n",
            "unrecognised top-level construct", 6)

    def test_pin_var_parameter_message(self):
        # The test asserts the 'var' MESSAGE (the specific refusal), not
        # merely that a refusal happens -- the generic constructor-line
        # refusal would refuse the same plant with different text.
        self._refuse(
            "package dev.breaker.shared.api\n\ndata class Foo(\n"
            "    val a: Int,\n    var retries: Int = 0\n)\n",
            "'var' is not a recognised constructor parameter (use 'val')", 5)

    def test_pin_unrecognised_construct_in_class_body(self):
        self._refuse(
            "package dev.breaker.shared.api\n\ndata class Foo(\n"
            "    val a: Int\n) {\n    fun f(): Int = 1\n}\n",
            "unrecognised construct in class body (only 'init {') is recognised)",
            6)

    def test_pin_only_require_in_init(self):
        self._refuse(
            "package dev.breaker.shared.api\n\ndata class Foo(\n"
            "    val a: Int\n) {\n    init {\n        println(\"\")\n    }\n}\n",
            "only require(...) / check(...) calls are recognised in init", 7)

    def test_pin_multiline_require_rejected(self):
        self._refuse(
            "package dev.breaker.shared.api\n\ndata class Foo(\n"
            "    val a: Int\n) {\n    init {\n        require(\n"
            "            a > 0\n        )\n    }\n}\n",
            "multi-line require/check conditions are not in the closed grammar",
            8)

    def test_pin_unrecognised_line_in_enum(self):
        self._refuse(
            "package dev.breaker.shared.api\n\nenum class E {\n"
            "    val x: Int\n}\n",
            "unrecognised line in enum class", 4)

    def test_pin_unrecognised_construct_in_companion(self):
        self._refuse(
            "package dev.breaker.shared.api\n\nenum class E {\n    A\n"
            "    companion object {\n        val X = 1\n    }\n}\n",
            "unrecognised construct in companion object", 6)

    def test_pin_empty_single_expression_fun_body(self):
        self._refuse(
            "package dev.breaker.shared.api\n\nenum class E {\n    A\n"
            "    companion object {\n        fun f(): Int =\n    }\n}\n",
            "empty single-expression fun body", 7)

    def test_pin_file_ends_unfinished(self):
        # truncated mid-constructor: the final state is CTOR, not TOP
        self._refuse(
            "package dev.breaker.shared.api\n\ndata class Foo(\n    val a: Int\n",
            "file ends in an unfinished construct (state CTOR)", 4)

    def test_pin_missing_package_declaration(self):
        # a file with a data class but NO package line: the file-level
        # check after the state machine refuses (file name, no line number)
        self._refuse(
            "data class Foo(\n    val a: Int\n)\n",
            "no package declaration found", None)

    def test_pin_file_vacuity_guard(self):
        # a file with a package but no enums or data classes
        self._refuse(
            "package dev.breaker.shared.api\n\n// nothing else\n",
            "no enums or data classes found (vacuity guard)", None)


class ValidatorRefusalPins(unittest.TestCase):
    """The 3 validator refusals the sweep found unpinned."""

    def test_pin_load_spec_missing_top_level_key(self):
        d = tempfile.mkdtemp()
        p = Path(d) / "openapi.yaml"
        # a spec WITHOUT 'paths' (openapi, info and components are present)
        p.write_text(
            "openapi: 3.0.0\n"
            "info:\n  title: T\n  version: 1.0.0\n"
            "components:\n  schemas:\n    Foo:\n      type: object\n")
        old = S.SPEC
        S.SPEC = p
        self.addCleanup(lambda: setattr(S, "SPEC", old))
        with self.assertRaises(ValueError) as ctx:
            load_spec()
        self.assertIn("Spec missing required top-level key: paths",
                      str(ctx.exception))

    def test_pin_get_schemas_no_components_schemas(self):
        spec = {"openapi": "3.0.0", "info": {"title": "T", "version": "1"},
                "paths": {"/x": {}}, "components": {"schemas": {}}}
        with self.assertRaises(ValueError) as ctx:
            get_schemas(spec)
        self.assertEqual(str(ctx.exception), "Spec has no components/schemas")

    def test_pin_unrecognised_ref_target(self):
        spec = {
            "openapi": "3.0.0", "info": {"title": "T", "version": "1"},
            "paths": {"/x": {"get": {"responses": {"200": {"description": "ok",
                     "content": {"application/json": {"schema": {
                         "$ref": "#/components/securitySchemes/Bogus"}}}}}}}},
            "components": {"schemas": {"Foo": {"type": "object"}}},
        }
        with self.assertRaises(ValueError) as ctx:
            validate_spec_structure(spec)
        self.assertIn("Unrecognised $ref target", str(ctx.exception))
        self.assertIn("#/components/securitySchemes/Bogus", str(ctx.exception))


class ComparatorRefusalPins(unittest.TestCase):
    """The 1 comparator refusal the sweep found unpinned, plus the two
    coverage refusals (the spec->kotlin direction previously had no red
    test)."""

    def setUp(self):
        self.schemas = get_schemas(load_spec())
        self.kotlin = R.parse_all_kotlin()

    def test_pin_map_spec_type_unsupported_type(self):
        # a spec property of type 'object' with no $ref: the type map
        # refuses to guess (ValueError, its own message)
        with self.assertRaises(ValueError) as ctx:
            map_spec_type_to_kotlin("object")
        self.assertIn("unsupported spec type: object", str(ctx.exception))

    def test_pin_coverage_refuses_schema_without_kotlin_type(self):
        # spec -> kotlin: plant a schema with no Kotlin type in a copy;
        # the production check_coverage must refuse with its own text
        schemas = {k: v for k, v in self.schemas.items()}
        schemas["Ghost"] = {"type": "object", "properties": {}}
        with self.assertRaises(AssertionError) as ctx:
            check_coverage(schemas, self.kotlin)
        self.assertIn(
            "spec - kotlin: schema Ghost has no mapped Kotlin type (expected Ghost)",
            str(ctx.exception))

    def test_pin_coverage_refuses_kotlin_type_without_schema(self):
        # the kotlin -> spec direction, its own message pinned for the sweep
        kotlin = {k: v for k, v in self.kotlin.items()}
        kotlin["Orphan"] = {"type": "data_class", "fields": [], "init_rules": []}
        with self.assertRaises(AssertionError) as ctx:
            check_coverage(self.schemas, kotlin)
        self.assertIn(
            "kotlin - spec: Kotlin type Orphan has no spec schema (expected Orphan)",
            str(ctx.exception))


class TreeVacuityPin(unittest.TestCase):
    """The parse_all_kotlin vacuity guard (line 403): an empty source dir
    must refuse, not return an empty registry (which would make every
    downstream comparison pass vacuously)."""

    def test_pin_no_kotlin_classes_in_tree(self):
        d = Path(tempfile.mkdtemp())
        old = R.KOTLIN_DIR
        R.KOTLIN_DIR = d
        try:
            with self.assertRaises(ValueError) as ctx:
                R.parse_all_kotlin()
            self.assertEqual(str(ctx.exception),
                             "No Kotlin classes or enums found (vacuity guard)")
        finally:
            R.KOTLIN_DIR = old


if __name__ == "__main__":
    unittest.main()
