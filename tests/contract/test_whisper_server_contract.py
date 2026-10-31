"""Contract test for the `server/modules/whisper-server` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code.

The three pins below read the shared api-contracts spec, so a revert of either
contract edit lands here as a red failure. Assertion style matches contract_support.py.
"""
import os
import sys
import unittest

import contract_support

ROOT = contract_support.ROOT
OPENAPI = os.path.join(ROOT, "shared/modules/api-contracts/openapi.yaml")
sys.path.insert(0, os.path.join(ROOT, "tools"))
import yaml_subset


def _load_openapi():
    return yaml_subset.load(OPENAPI)


class ServerModulesWhisperServerContractTest(contract_support.ModuleContractTest):
    MODULE = "server_whisper"

    def test_404_reason_is_unknown_or_expired(self):
        """a GET on an unknown/expired job_id answers 404 reason "unknown or expired".

        The contract's 404 for /v1/jobs/{job_id} carries the one reason the server actually
        sends (TranscriptionRoutes.UNKNOWN_OR_EXPIRED), not the old "not_found"/"Job does not
        exist". The behaviour lives in the Kotlin route test; this pins the spec the client reads.
        """
        spec = _load_openapi()
        example = (
            spec["paths"]["/v1/jobs/{job_id}"]["get"]["responses"]["404"]
            ["content"]["application/json"]["example"]
        )
        assert example["error"] == "unknown or expired", (
            f"whisper-server contract: the job 404 reason must be 'unknown or expired', "
            f"but the spec says {example['error']!r}"
        )

    def test_job_id_example_is_a_real_value_not_the_placeholder(self):
        """the job_id example is a value the server really produces, not "job-12345".

        The server emits an opaque numeric-ish id (its SQLite row id), so the 202 example is a
        numeric string and never the old literal placeholder.
        """
        spec = _load_openapi()
        example = (
            spec["paths"]["/v1/audio/transcriptions"]["post"]["responses"]["202"]
            ["content"]["application/json"]["example"]
        )
        job_id = example["job_id"]
        assert job_id != "job-12345", (
            "whisper-server contract: the job_id example must not be the old 'job-12345' placeholder"
        )
        assert isinstance(job_id, str) and job_id.isdigit(), (
            f"whisper-server contract: the job_id example must be an opaque numeric-ish id string, "
            f"got {job_id!r}"
        )

    def test_job_id_parameter_is_documented_as_opaque(self):
        """the job_id path parameter is documented as opaque — echoed back, never parsed.

        The client treats job_id as an opaque token it echoes on the next GET; the contract says
        so on the parameter description.
        """
        spec = _load_openapi()
        params = spec["paths"]["/v1/jobs/{job_id}"]["get"]["parameters"]
        job_id_param = next(p for p in params if p["name"] == "job_id")
        description = job_id_param["description"].lower()
        assert "opaque" in description, (
            "whisper-server contract: the job_id parameter must say it is opaque"
        )
        assert "echo" in description and "unchanged" in description, (
            "whisper-server contract: the job_id parameter must say the client echoes it back unchanged"
        )

    def test_transcription_request_requires_only_the_file_part(self):
        """the transcription request schema requires only `file`; `model` and `language` are optional.

        whisper-server accepts a missing or blank `model` part and ignores it (the forwarder sends
        its configured downstream model), so the contract must not list `model` as required.
        """
        spec = _load_openapi()
        schema = (
            spec["paths"]["/v1/audio/transcriptions"]["post"]["requestBody"]["content"]
            ["multipart/form-data"]["schema"]
        )
        assert schema["required"] == ["file"], (
            f"whisper-server contract: only the file part is required, got {schema['required']!r}"
        )


if __name__ == "__main__":
    unittest.main()
