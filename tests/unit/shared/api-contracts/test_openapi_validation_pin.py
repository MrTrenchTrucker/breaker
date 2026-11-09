"""Pin test for the openapi-validate CI job in checks.yml.

Run: python3 -m unittest tests.unit.shared.api-contracts.test_openapi_validation_pin
"""
import os
import re
import unittest
from pathlib import Path

ROOT = str(Path(__file__).resolve().parents[4])
CHECKS_PATH = os.path.join(ROOT, ".github", "workflows", "checks.yml")

EXPECTED_VERSION = "7.26.0"
EXPECTED_SHA256 = "1760050094997b9cc790cc1350be095f15da8c08a996b184d3eadc4ccda5e35e"


def _checks_text():
    with open(CHECKS_PATH, encoding="ascii") as fh:
        return fh.read()


def _job_text():
    """The openapi-validate job block only: from its header to the next job header or the end.

    The gitleaks job above it also carries a `sha256sum -c -` line, so a check on the whole
    file would still pass when the line of THIS job is removed."""
    text = _checks_text()
    start = re.search(r"^  openapi-validate:\s*$", text, re.M)
    if start is None:
        raise AssertionError("openapi-validate job missing")
    rest = text[start.end():]
    following = re.search(r"^  [A-Za-z0-9_-]+:\s*$", rest, re.M)
    return rest if following is None else rest[:following.start()]


class OpenapiValidationPinTest(unittest.TestCase):
    def test_openapi_validation_job_exists(self):
        """Accept: 'openapi-validate:' job block present. Mutant: job removed
        or renamed -> regex find fails."""
        text = _checks_text()
        self.assertIsNotNone(re.search(r"^\s{2}openapi-validate:", text, re.M),
                             "openapi-validate job missing")

    def test_openapi_validation_version_is_7_26_0(self):
        """Accept: env var exactly 7.26.0 and no 'latest' in job block.
        Mutant: version changed or 'latest' string in job -> fail."""
        job = _job_text()
        self.assertIsNotNone(
            re.search(r'OPENAPI_GENERATOR_VERSION:\s*"7\.26\.0"', job),
            "version must be exactly 7.26.0")
        self.assertNotIn("latest", job)

    def test_openapi_validation_sha256_is_pinned(self):
        """Accept: sha256 equals expected constant and is 64 hex.
        Mutant: sha changed -> mismatch vs EXPECTED_SHA256."""
        job = _job_text()
        m = re.search(r'OPENAPI_GENERATOR_SHA256:\s*"([0-9a-f]{64})"', job)
        self.assertIsNotNone(m, "sha256 line missing or not 64 hex")
        self.assertEqual(m.group(1), EXPECTED_SHA256)

    def test_openapi_validation_sha256sum_before_java(self):
        """Accept: sha256sum -c - line exists and index < java -jar index.
        Mutant: sha256sum removed or moved after java -> fail/absent."""
        job = _job_text()
        self.assertIn("sha256sum -c -", job, "this job must check the jar digest")
        self.assertIn("java -jar", job, "this job must run the jar")
        self.assertLess(job.index("sha256sum -c -"), job.index("java -jar"))
        self.assertIn("${OPENAPI_GENERATOR_SHA256}", job.split("sha256sum -c -")[0].splitlines()[-1],
                      "the check line must use the pinned digest variable")

    def test_openapi_validation_validate_names_module_yaml(self):
        """Accept: validate command names shared/modules/api-contracts/openapi.yaml.
        Mutant: path changed/removed -> regex fail."""
        job = _job_text()
        self.assertIsNotNone(re.search(
            r"java\s+-jar\s+\S+\s+validate\s+-i\s+shared/modules/api-contracts/openapi\.yaml\s*$",
            job, re.M), "validate must name module openapi.yaml")

    def test_openapi_validation_url_built_from_version(self):
        """Accept: Maven URL contains ${OPENAPI_GENERATOR_VERSION}.
        Mutant: URL hard-coded 7.26.0 -> variable not in URL -> fail."""
        job = _job_text()
        m = re.search(r"repo1\.maven\.org.*?\$\{OPENAPI_GENERATOR_VERSION\}", job, re.S)
        self.assertIsNotNone(m, "the Maven URL must be built from the version variable")
        self.assertNotRegex(job, r"openapi-generator-cli-7\.", "no hard-coded version in the file name")

    def test_openapi_validation_jar_downloaded_is_the_jar_checked_and_run(self):
        """Accept: the file name after curl -o is the name in the sha256sum line and after java -jar.
        Mutant: the download writes another name, so the check and the run use a jar nobody fetched."""
        job = _job_text()
        out = re.search(r"curl\s+-fSL\s+-o\s+(\S+)", job)
        self.assertIsNotNone(out, "the download must use curl -fSL -o <file>")
        name = out.group(1)
        self.assertRegex(job, r"\$\{OPENAPI_GENERATOR_SHA256\}\s+" + re.escape(name) + r"\"\s*\|\s*sha256sum -c -")
        self.assertRegex(job, r"java\s+-jar\s+" + re.escape(name) + r"\s+validate")

    def test_openapi_validation_download_command_is_on_one_line(self):
        """Accept: the curl command is one line. Mutant: the URL is split over lines with a backslash;
        the indented continuation line then becomes a second argument (a host named 7.26.0) and
        curl saves the directory page as the jar."""
        job = _job_text()
        self.assertNotRegex(job, r"curl[^\n]*\\[ \t]*\n", "the curl command must not continue on the next line")


if __name__ == "__main__":
    unittest.main()
