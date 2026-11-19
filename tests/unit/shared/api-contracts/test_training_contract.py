"""Pins the six training operations in openapi.yaml: paths, methods, security,
the exact response-code sets, the error answers, the typed-result sentence of
every response, and the words and secrets no training text may carry.

Each operation is reached by PATH + METHOD; the operationId is asserted too.
OPENAPI_SPEC_PATH lets a scratch copy of the spec be pinned. The sibling file
test_training_limits.py pins the upload, the limits, the model headers, the
start and delete wording, and the schemas against the Kotlin sources.

Run: python3 -B -m unittest discover -s tests/unit/shared/api-contracts -p 'test_training_*.py'
"""
import os
import re
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import yaml_subset

MODULE_DIR = os.path.join(ROOT, "shared", "modules", "api-contracts")
KOTLIN_API = os.path.join(MODULE_DIR, "src", "main", "kotlin", "dev", "breaker", "shared", "api")
SPEC_PATH = os.environ.get("OPENAPI_SPEC_PATH") or os.path.join(MODULE_DIR, "openapi.yaml")

UPLOAD = "/v1/training/samples/{kind}/{sample_id}"
SAMPLES = "/v1/training/samples"
JOBS = "/v1/training/jobs"
JOB = "/v1/training/jobs/{job_id}"
MODEL_GET = "/v1/training/jobs/{job_id}/model"
MODEL_DEL = "/v1/training/model"

# name -> (path, method, operationId, exact response-code set)
OPS = {
    "upload": (UPLOAD, "put", "uploadTrainingSample",
               {"200", "400", "401", "403", "409", "413", "429", "503"}),
    "delete_samples": (SAMPLES, "delete", "deleteTrainingSamples",
                       {"204", "401", "403", "429", "503"}),
    "start": (JOBS, "post", "startTrainingJob",
              {"200", "202", "401", "403", "429", "503"}),
    "status": (JOB, "get", "getTrainingJob",
               {"200", "400", "401", "403", "404", "429", "503"}),
    "model": (MODEL_GET, "get", "downloadTrainingModel",
              {"200", "400", "401", "403", "404", "409", "429", "503"}),
    "delete_model": (MODEL_DEL, "delete", "deleteTrainingModel",
                     {"204", "401", "403", "429", "503"}),
}
TRAINING_SCHEMAS = ("TrainingKind", "TrainingJobState", "TrainingJobFailure",
                    "SampleReceipt", "TrainingJob", "TrainingJobStatus")

FORBIDDEN_403 = "User session token required; an agent token is refused"
ERROR_VOCABULARY = {"unauthorized", "forbidden", "bad_request", "not_found",
                    "conflict", "payload_too_large", "rate_limited", "unavailable"}
TYPED = {"200": "Ok", "202": "Ok", "204": "Ok", "401": "NotSignedIn",
         "400": "Refused", "403": "Refused", "404": "Refused", "409": "Refused",
         "413": "TooLarge", "429": "Unavailable", "503": "Unavailable"}
RETENTION = re.compile(r"\b(retention|retain|retained|days?|hours?|expire[sd]?|expiry|window)\b", re.I)
SECRET_SHAPES = (re.compile(r"[0-9a-fA-F]{32,}"), re.compile(r"[A-Za-z0-9+/=_-]{32,}"),
                 re.compile(r"bearer", re.I), re.compile(r"eyJ"))
USER_ID_NAME = re.compile(r"(user|owner|account)", re.I)


def load_spec():
    if not os.path.isfile(SPEC_PATH):
        raise AssertionError(f"openapi.yaml not found at {SPEC_PATH}")
    with open(SPEC_PATH, encoding="utf-8") as fh:
        return yaml_subset.loads(fh.read(), SPEC_PATH)


def op_of(spec, key):
    path, method, _op_id, _codes = OPS[key]
    return spec["paths"][path][method]


def read_text(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as fh:
        return fh.read()


def strings_in(node):
    """Every string value anywhere in a parsed structure."""
    if isinstance(node, str):
        yield node
    elif isinstance(node, dict):
        for value in node.values():
            yield from strings_in(value)
    elif isinstance(node, list):
        for value in node:
            yield from strings_in(value)


def examples_of(spec):
    """(where, example) for every example in every training response."""
    for key, (path, method, _id, _codes) in OPS.items():
        for status, resp in op_of(spec, key)["responses"].items():
            for media, body in resp.get("content", {}).items():
                if "example" in body:
                    yield f"{method} {path} {status} {media}", body["example"]


class TrainingContractBase(unittest.TestCase):
    def setUp(self):
        self.spec = load_spec()


class TrainingOperationShapeTest(TrainingContractBase):
    def test_each_operation_exists_by_path_and_method(self):
        for key, (path, method, _id, _codes) in OPS.items():
            with self.subTest(op=key):
                self.assertIn(path, self.spec["paths"])
                self.assertIn(method, self.spec["paths"][path])

    def test_each_training_path_has_only_the_expected_methods(self):
        training = {p: sorted(m for m in item if m in ("get", "put", "post", "delete", "patch"))
                    for p, item in self.spec["paths"].items() if p.startswith("/v1/training")}
        expected = {OPS[k][0]: [OPS[k][1]] for k in OPS}
        self.assertEqual(training, expected)

    def test_operation_ids(self):
        for key, (_path, _method, op_id, _codes) in OPS.items():
            with self.subTest(op=key):
                self.assertEqual(op_of(self.spec, key)["operationId"], op_id)

    def test_every_operation_requires_bearer_auth(self):
        for key in OPS:
            with self.subTest(op=key):
                self.assertEqual(op_of(self.spec, key).get("security"), [{"bearerAuth": []}])

    def test_no_user_id_in_a_parameter_a_path_or_a_schema_property(self):
        for key, (path, _m, _id, _codes) in OPS.items():
            with self.subTest(op=key):
                self.assertIsNone(USER_ID_NAME.search(path), path)
                for param in op_of(self.spec, key).get("parameters", []):
                    self.assertIsNone(USER_ID_NAME.search(param["name"]), param["name"])
        schemas = self.spec["components"]["schemas"]
        for name in TRAINING_SCHEMAS:
            with self.subTest(schema=name):
                for prop in schemas[name].get("properties", {}):
                    self.assertIsNone(USER_ID_NAME.search(prop), f"{name}.{prop}")

    def test_the_six_training_schemas_exist(self):
        schemas = self.spec["components"]["schemas"]
        for name in TRAINING_SCHEMAS:
            self.assertIn(name, schemas)


class TrainingResponseTest(TrainingContractBase):
    def test_exact_response_code_set_of_each_operation(self):
        for key, (path, method, _id, codes) in OPS.items():
            with self.subTest(op=key):
                self.assertEqual(set(op_of(self.spec, key)["responses"]), codes,
                                 f"{method.upper()} {path}")

    def test_413_only_on_the_upload(self):
        with_413 = [k for k in OPS if "413" in op_of(self.spec, k)["responses"]]
        self.assertEqual(with_413, ["upload"])

    def test_403_answer_is_the_agent_token_refusal_everywhere(self):
        for key in OPS:
            with self.subTest(op=key):
                example = op_of(self.spec, key)["responses"]["403"]["content"]["application/json"]["example"]
                self.assertEqual(example["error"], "forbidden")
                self.assertEqual(example["message"], FORBIDDEN_403)

    def test_every_non_2xx_is_an_error_ref_with_an_example(self):
        for key in OPS:
            for status, resp in op_of(self.spec, key)["responses"].items():
                if status.startswith("2"):
                    continue
                with self.subTest(op=key, status=status):
                    body = resp["content"]["application/json"]
                    self.assertEqual(body["schema"], {"$ref": "#/components/schemas/Error"})
                    self.assertIn("message", body["example"])

    def test_error_codes_in_examples_come_from_the_vocabulary(self):
        seen = set()
        for where, example in examples_of(self.spec):
            if "error" in example:
                seen.add(example["error"])
                self.assertIn(example["error"], ERROR_VOCABULARY, where)
        self.assertEqual(seen, ERROR_VOCABULARY)

    def test_error_code_matches_the_status_it_sits_under(self):
        by_status = {"400": "bad_request", "401": "unauthorized", "403": "forbidden",
                     "404": "not_found", "409": "conflict", "413": "payload_too_large",
                     "429": "rate_limited", "503": "unavailable"}
        for key in OPS:
            for status, resp in op_of(self.spec, key)["responses"].items():
                if status in by_status:
                    with self.subTest(op=key, status=status):
                        example = resp["content"]["application/json"]["example"]
                        self.assertEqual(example["error"], by_status[status])

    def test_typed_result_sentence_in_every_response(self):
        for key in OPS:
            for status, resp in op_of(self.spec, key)["responses"].items():
                with self.subTest(op=key, status=status):
                    named = re.findall(r"phone's (\w+)", resp["description"])
                    self.assertEqual(named, [TYPED[status]], resp["description"])

    def test_the_204_answers_carry_no_content_body(self):
        for key in ("delete_samples", "delete_model"):
            with self.subTest(op=key):
                self.assertNotIn("content", op_of(self.spec, key)["responses"]["204"])


class TrainingTextTest(TrainingContractBase):
    def test_no_retention_word_in_a_training_description(self):
        # The 429 answers carry the shared rate-limit text (a fixed 60 second
        # window), which is not a retention statement; every other description is checked.
        for key in OPS:
            op = op_of(self.spec, key)
            texts = [("operation", op["description"]), ("summary", op["summary"])]
            texts += [(s, r["description"]) for s, r in op["responses"].items() if s != "429"]
            for param in op.get("parameters", []):
                texts.append((param["name"], param.get("description", "")))
            for where, text in texts:
                with self.subTest(op=key, where=where):
                    self.assertIsNone(RETENTION.search(text), text)
        for name in TRAINING_SCHEMAS:
            for text in strings_in(self.spec["components"]["schemas"][name]):
                with self.subTest(schema=name):
                    self.assertIsNone(RETENTION.search(text), text)

    def test_no_example_holds_a_secret_shaped_value(self):
        count = 0
        for where, example in examples_of(self.spec):
            for text in strings_in(example):
                count += 1
                for shape in SECRET_SHAPES:
                    self.assertIsNone(shape.search(text), f"{where}: {shape.pattern}")
        self.assertGreater(count, 30, "the secret scan must have looked at the examples")


if __name__ == "__main__":
    unittest.main()
