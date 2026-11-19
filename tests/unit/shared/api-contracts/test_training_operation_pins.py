"""Pins four facts on the parsed training part of openapi.yaml: every training
429 answer carries a Retry-After header whose schema is {type: integer,
minimum: 1, maximum: 3600} (six operations, looped and named per op); the
TrainingJobStatus description carries the done-job sentence; the
TrainingJobFailure description names both failure reasons; and only the upload
declares a requestBody - the other five operations declare none.

Run: nice -n 19 python3 -B -m unittest discover -s tests/unit/shared/api-contracts -t tests/unit/shared/api-contracts -p 'test_training_operation_pins.py'
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_training_contract import (  # noqa: E402
    TrainingContractBase,
    op_of,
    OPS,
)

NO_REQUEST_BODY = (
    "delete_samples",
    "start",
    "status",
    "model",
    "delete_model",
)

RETRY_AFTER_SCHEMA = {"type": "integer", "minimum": "1", "maximum": "3600"}

DONE_MODEL_SENTENCE = "A done job always has its model ready to download."

FAILURE_REASONS = ("too_few_samples", "training_error")


class TrainingOperationPinsTest(TrainingContractBase):
    def test_every_training_429_carries_retry_after_integer_1_to_3600(self):
        for key in OPS:
            with self.subTest(op=key):
                header = op_of(self.spec, key)["responses"]["429"]["headers"]["Retry-After"]
                self.assertEqual(header["schema"], RETRY_AFTER_SCHEMA)

    def test_training_job_status_description_holds_the_done_model_sentence(self):
        text = self.spec["components"]["schemas"]["TrainingJobStatus"]["description"]
        self.assertIn(DONE_MODEL_SENTENCE, text)

    def test_training_job_failure_description_names_both_failure_reasons(self):
        text = self.spec["components"]["schemas"]["TrainingJobFailure"]["description"]
        for reason in FAILURE_REASONS:
            with self.subTest(reason=reason):
                self.assertIn(reason, text)

    def test_only_the_upload_declares_a_request_body(self):
        for key in NO_REQUEST_BODY:
            with self.subTest(op=key):
                self.assertNotIn("requestBody", op_of(self.spec, key))
        self.assertIn("requestBody", op_of(self.spec, "upload"))


if __name__ == "__main__":
    unittest.main()