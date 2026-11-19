"""Pins the training wording and numbers: the upload (id charset, idempotent
repeat, the 21st distinct sample), the limits read out of the spec text and out
of the Kotlin constants, the model headers, the start and delete wording, and
the six schemas (required lists, enum value lists and order) against the Kotlin
enums read as text. Shares its loader with test_training_contract.py.

Run: python3 -B -m unittest discover -s tests/unit/shared/api-contracts -p 'test_training_*.py'
"""
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_training_contract import (  # noqa: E402
    KOTLIN_API, MODEL_GET, TrainingContractBase, UPLOAD, op_of, read_text,
)

CONTRACT_KT = os.path.join(KOTLIN_API, "training", "TrainingContract.kt")
SAMPLE_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$"
HEADERS = {"X-Model-Sha256": ("string", "^[0-9a-f]{64}$"),
           "X-Model-Size-Bytes": ("integer", None),
           "X-Model-Samples-Used": ("integer", None)}
ENUMS = {"TrainingKind": ["wake", "send"],
         "TrainingJobState": ["queued", "running", "done", "failed"],
         "TrainingJobFailure": ["too_few_samples", "training_error"]}
REQUIRED = {"SampleReceipt": ["sample_id", "kind", "size_bytes", "count"],
            "TrainingJob": ["job_id", "state"],
            "TrainingJobStatus": ["job_id", "state"]}


def kotlin_consts():
    found = dict(re.findall(r'const val (\w+) = (\d+|"[^"]*")', read_text(CONTRACT_KT)))
    return {k: (v.strip('"') if v.startswith('"') else int(v)) for k, v in found.items()}


def kotlin_enum_values(name):
    text = read_text(KOTLIN_API, name + ".kt")
    body = re.search(r"enum class %s \{\s*(.*?)(?:;|\n\})" % name, text, re.S).group(1)
    return [v.strip().lower() for v in body.split(",") if v.strip()]


def first_int(pattern, text):
    return int(re.search(pattern, text).group(1))


class TrainingUploadTest(TrainingContractBase):
    def setUp(self):
        super().setUp()
        self.put = self.spec["paths"][UPLOAD]["put"]
        self.params = {p["name"]: p for p in self.put["parameters"]}

    def test_sample_id_pattern_is_the_phone_store_charset(self):
        self.assertEqual(self.params["sample_id"]["schema"]["pattern"], SAMPLE_ID_PATTERN)
        self.assertEqual(self.params["sample_id"]["in"], "path")

    def test_kind_path_parameter_is_the_kind_enum(self):
        self.assertEqual(self.params["kind"]["schema"], {"$ref": "#/components/schemas/TrainingKind"})
        self.assertEqual(self.params["kind"]["in"], "path")

    def test_body_is_octet_stream_binary(self):
        content = self.put["requestBody"]["content"]
        self.assertEqual(list(content), ["application/octet-stream"])
        schema = content["application/octet-stream"]["schema"]
        self.assertEqual((schema["type"], schema["format"]), ("string", "binary"))
        self.assertEqual((schema.get("minLength"), schema.get("maxLength")), ("1", "131072"))

    def test_200_description_says_a_repeat_answers_200(self):
        text = self.put["responses"]["200"]["description"]
        self.assertIn("same sample_id was already stored", text)
        self.assertIn("a repeat is also 200", text)

    def test_409_names_the_21st_distinct_sample_and_clears_the_repeat(self):
        text = self.put["responses"]["409"]["description"]
        self.assertIn("A 21st distinct sample of this kind is refused", text)
        self.assertIn("A repeat of a stored sample_id is not this case", text)
        self.assertIn("answers 200", text)

    def test_upload_description_says_idempotent_per_sample_id(self):
        self.assertIn("idempotent per sample_id", self.put["description"])


class TrainingLimitsTest(TrainingContractBase):
    def spec_numbers(self):
        put = self.spec["paths"][UPLOAD]["put"]
        get = self.spec["paths"][MODEL_GET]["get"]
        schemas = self.spec["components"]["schemas"]
        body = put["requestBody"]["content"]["application/octet-stream"]["schema"]
        sha_headers = get["responses"]["200"]["headers"]
        return {
            "sample_body_max": int(body["maxLength"]),
            "sample_description_max": first_int(r"1 to (\d+) bytes", put["description"]),
            "sample_413_cap": first_int(r"over the (\d+) byte cap", put["responses"]["413"]["description"]),
            "receipt_size_max": int(schemas["SampleReceipt"]["properties"]["size_bytes"]["maximum"]),
            "model_description_max": first_int(r"at most (\d+) bytes", get["description"]),
            "model_size_header_max": int(sha_headers["X-Model-Size-Bytes"]["schema"]["maximum"]),
            "samples_per_kind_max": first_int(r"At most (\d+) distinct samples", put["description"]),
            "receipt_count_max": int(schemas["SampleReceipt"]["properties"]["count"]["maximum"]),
            "samples_per_kind_min": first_int(r"fewer than (\d+) as", put["description"]),
        }

    def test_limits_in_the_spec_equal_the_literals(self):
        self.assertEqual(self.spec_numbers(), {
            "sample_body_max": 131072, "sample_description_max": 131072,
            "sample_413_cap": 131072, "receipt_size_max": 131072,
            "model_description_max": 33554432, "model_size_header_max": 33554432,
            "samples_per_kind_max": 20, "receipt_count_max": 20,
            "samples_per_kind_min": 10})

    def test_limits_in_the_kotlin_constants_equal_the_literals(self):
        consts = kotlin_consts()
        self.assertEqual((consts["MAX_SAMPLE_BYTES"], consts["MAX_MODEL_BYTES"],
                          consts["MIN_SAMPLES_PER_KIND"], consts["MAX_SAMPLES_PER_KIND"]),
                         (131072, 33554432, 10, 20))

    def test_kotlin_constants_equal_the_spec_numbers(self):
        numbers, consts = self.spec_numbers(), kotlin_consts()
        self.assertEqual(numbers["sample_body_max"], consts["MAX_SAMPLE_BYTES"])
        self.assertEqual(numbers["model_size_header_max"], consts["MAX_MODEL_BYTES"])
        self.assertEqual(numbers["samples_per_kind_min"], consts["MIN_SAMPLES_PER_KIND"])
        self.assertEqual(numbers["samples_per_kind_max"], consts["MAX_SAMPLES_PER_KIND"])


class TrainingModelTest(TrainingContractBase):
    def setUp(self):
        super().setUp()
        self.get = self.spec["paths"][MODEL_GET]["get"]
        self.headers = self.get["responses"]["200"]["headers"]

    def test_model_200_carries_exactly_the_three_headers(self):
        self.assertEqual(sorted(self.headers), sorted(HEADERS))

    def test_header_names_equal_the_kotlin_constants(self):
        consts = kotlin_consts()
        self.assertEqual(sorted((consts["HEADER_MODEL_SHA256"], consts["HEADER_MODEL_SIZE_BYTES"],
                                 consts["HEADER_MODEL_SAMPLES_USED"])), sorted(HEADERS))

    def test_sha256_header_is_64_lowercase_hex(self):
        header = self.headers["X-Model-Sha256"]
        self.assertEqual(header["schema"]["pattern"], HEADERS["X-Model-Sha256"][1])
        self.assertEqual(header["required"], "true")

    def test_size_and_samples_headers_bounds(self):
        size = self.headers.get("X-Model-Size-Bytes", {}).get("schema", {})
        self.assertEqual((size.get("type"), size.get("minimum"), size.get("maximum")), ("integer", "1", "33554432"))
        used = self.headers.get("X-Model-Samples-Used", {}).get("schema", {})
        self.assertEqual((used.get("type"), used.get("minimum"), "maximum" in used), ("integer", "1", False))
        for name in ("X-Model-Size-Bytes", "X-Model-Samples-Used"):
            self.assertEqual(self.headers.get(name, {}).get("required"), "true")

    def test_model_body_is_octet_stream_binary(self):
        content = self.get["responses"]["200"]["content"]
        self.assertEqual(list(content), ["application/octet-stream"])
        self.assertEqual(content["application/octet-stream"]["schema"]["format"], "binary")

    def test_kind_query_is_optional_and_defaults_to_wake(self):
        kind = [p for p in self.get["parameters"] if p["name"] == "kind"][0]
        self.assertEqual(kind["in"], "query")
        self.assertNotEqual(kind["required"], "true")
        self.assertEqual(kind["schema"], {"$ref": "#/components/schemas/TrainingKind"})
        self.assertIn("the default is wake", kind["description"])
        self.assertIn("default wake", self.get["description"])

    def test_model_404_names_the_deleted_model_case(self):
        self.assertIn("or the user's model was deleted", self.get["responses"]["404"]["description"])


class TrainingStartAndDeleteTest(TrainingContractBase):
    def test_start_documents_200_and_202_and_the_retrain_rule(self):
        op = op_of(self.spec, "start")
        self.assertIn("200", op["responses"])
        self.assertIn("202", op["responses"])
        self.assertIn("A start after a finished job (done or failed) creates a new job", op["description"])
        self.assertIn("a new job answers 202", op["description"])

    def test_delete_samples_carries_the_privacy_sentence(self):
        text = op_of(self.spec, "delete_samples")["description"]
        self.assertIn("After a 204 no stored sample of this user remains", text)
        self.assertIn("the status answer never reports done with a model built from deleted samples", text)

    def test_delete_model_says_the_download_answers_404_afterwards(self):
        text = op_of(self.spec, "delete_model")["description"]
        self.assertIn("After a 204 the model download answers 404 for that job", text)


class TrainingSchemaTest(TrainingContractBase):
    def test_enum_value_lists_and_order(self):
        for name, values in ENUMS.items():
            with self.subTest(enum=name):
                self.assertEqual(self.spec["components"]["schemas"][name]["enum"], values)

    def test_enum_value_lists_and_order_equal_the_kotlin_enums(self):
        for name in ENUMS:
            with self.subTest(enum=name):
                self.assertEqual(self.spec["components"]["schemas"][name]["enum"], kotlin_enum_values(name))

    def test_required_lists(self):
        for name, required in REQUIRED.items():
            with self.subTest(schema=name):
                self.assertEqual(self.spec["components"]["schemas"][name]["required"], required)

    def test_status_failure_is_optional_and_a_failure_ref(self):
        schema = self.spec["components"]["schemas"]["TrainingJobStatus"]
        self.assertNotIn("failure", schema["required"])
        self.assertEqual(schema["properties"]["failure"], {"$ref": "#/components/schemas/TrainingJobFailure"})

    def test_status_description_says_failure_is_present_exactly_when_failed(self):
        text = self.spec["components"]["schemas"]["TrainingJobStatus"]["description"]
        self.assertIn("failure is present exactly when state is failed", text)

    def test_receipt_kind_and_state_properties_are_refs(self):
        schemas = self.spec["components"]["schemas"]
        self.assertEqual(schemas["SampleReceipt"]["properties"]["kind"], {"$ref": "#/components/schemas/TrainingKind"})
        for name in ("TrainingJob", "TrainingJobStatus"):
            self.assertEqual(schemas[name]["properties"]["state"], {"$ref": "#/components/schemas/TrainingJobState"})


if __name__ == "__main__":
    unittest.main()
