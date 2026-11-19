"""Pins the training response schemas and the job_id path parameters on the
parsed openapi spec: the upload 200 receipt, the start 200 and 202 job, the
status 200 job status, the status and model job_id pattern, and the
SampleReceipt size_bytes and count lower bounds.

Run: nice -n 19 python3 -B -m unittest discover -s tests/unit/shared/api-contracts -t tests/unit/shared/api-contracts -p 'test_training_response_pins.py'
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_training_contract import (  # noqa: E402
    TrainingContractBase,
    op_of,
)

ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$"


class TrainingResponsePinsTest(TrainingContractBase):
    def test_upload_200_response_schema_is_sample_receipt(self):
        schema = op_of(self.spec, "upload")["responses"]["200"]["content"][
            "application/json"
        ]["schema"]
        self.assertEqual(schema, {"$ref": "#/components/schemas/SampleReceipt"})

    def test_start_200_response_schema_is_training_job(self):
        schema = op_of(self.spec, "start")["responses"]["200"]["content"][
            "application/json"
        ]["schema"]
        self.assertEqual(schema, {"$ref": "#/components/schemas/TrainingJob"})

    def test_start_202_response_schema_is_training_job(self):
        schema = op_of(self.spec, "start")["responses"]["202"]["content"][
            "application/json"
        ]["schema"]
        self.assertEqual(schema, {"$ref": "#/components/schemas/TrainingJob"})

    def test_status_200_response_schema_is_training_job_status(self):
        schema = op_of(self.spec, "status")["responses"]["200"]["content"][
            "application/json"
        ]["schema"]
        self.assertEqual(schema, {"$ref": "#/components/schemas/TrainingJobStatus"})

    def test_status_job_id_path_parameter_keeps_the_id_pattern(self):
        params = op_of(self.spec, "status")["parameters"]
        job_id = next(p for p in params if p["name"] == "job_id")
        self.assertEqual(job_id["in"], "path")
        self.assertEqual(job_id["schema"]["pattern"], ID_PATTERN)

    def test_model_job_id_path_parameter_keeps_the_id_pattern(self):
        params = op_of(self.spec, "model")["parameters"]
        job_id = next(p for p in params if p["name"] == "job_id")
        self.assertEqual(job_id["in"], "path")
        self.assertEqual(job_id["schema"]["pattern"], ID_PATTERN)

    def test_sample_receipt_size_bytes_minimum_is_one(self):
        minimum = (
            self.spec["components"]["schemas"]["SampleReceipt"]["properties"][
                "size_bytes"
            ]["minimum"]
        )
        self.assertEqual(minimum, "1")

    def test_sample_receipt_count_minimum_is_one(self):
        minimum = (
            self.spec["components"]["schemas"]["SampleReceipt"]["properties"][
                "count"
            ]["minimum"]
        )
        self.assertEqual(minimum, "1")


if __name__ == "__main__":
    unittest.main()
