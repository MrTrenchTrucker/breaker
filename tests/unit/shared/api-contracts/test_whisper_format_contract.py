"""Whisper format contract pins: the `format` parameter and the two subtitle bodies.

GET /v1/jobs/{job_id} gained one query parameter (`format`) and two 200 content
types (application/x-subrip, text/vtt). These five pins hold the spec text the
built server really serves:

  1. `format` is optional and its enum is exactly json, srt, vtt;
  2. the 400 example is {"error": "unknown format"};
  3. the application/x-subrip example is the SubRip rendering of the spec's own
     200 example segments (comma millisecond separator, cue numbered from one);
  4. the text/vtt example is the WebVTT rendering of the same segments (WEBVTT
     header, blank line, dot separator, no cue index);
  5. the 200 content keys are exactly application/json, application/x-subrip and
     text/vtt.

The two subtitle literals are the forms the writers themselves are pinned to in
their own unit tests (the comma form of SrtWriterTest's "srt renders the comma
millisecond separator", and the WEBVTT header plus blank line and dot form of
VttWriterTest's "vtt starts with the header and a blank line"), so the spec
example and the writer cannot drift apart.

Run: python3 -m unittest tests.unit.shared.api-contracts.test_whisper_format_contract
"""
import os
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve()
ROOT = HERE
for _ in range(5):
    ROOT = os.path.dirname(ROOT)
ROOT = Path(ROOT)
TOOLS = str(ROOT / "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import yaml_subset  # stdlib-compatible reader the other spec tiers use

SPEC = ROOT / "shared" / "modules" / "api-contracts" / "openapi.yaml"

# The SubRip rendering of the spec's own 200 example segments: cue 1 is
# 0.0-1.5 "Hello,", cue 2 is 1.5-3.0 "world!", comma before the millis.
SRT_EXAMPLE = (
    "1\n00:00:00,000 --> 00:00:01,500\nHello,\n"
    "\n"
    "2\n00:00:01,500 --> 00:00:03,000\nworld!\n\n"
)

# The WebVTT rendering of the same segments: the WEBVTT header and a blank line,
# no cue index, dot before the millis.
VTT_EXAMPLE = (
    "WEBVTT\n\n"
    "00:00:00.000 --> 00:00:01.500\nHello,\n"
    "\n"
    "00:00:01.500 --> 00:00:03.000\nworld!\n\n"
)


def _is_true(value):
    """Read a YAML flag the way this repository's spec tier does.

    tools/yaml_subset.py reads every plain scalar as a string, so `required:
    false` arrives as the string "false" and `required: true` as "true". Accept
    both the reader's string form and a real boolean, as api_contracts_support
    does, so a pin cannot pass by comparing a string to False.
    """
    if isinstance(value, str):
        return value.strip().lower() == "true"
    return bool(value)


def _load_spec():
    return yaml_subset.load(str(SPEC))


def _get_operation(spec):
    return spec["paths"]["/v1/jobs/{job_id}"]["get"]


class WhisperFormatContractTest(unittest.TestCase):
    """The `format` parameter and the two subtitle examples, pinned to the spec."""

    @classmethod
    def setUpClass(cls):
        cls.spec = _load_spec()
        cls.operation = _get_operation(cls.spec)

    def test_format_parameter_is_optional_with_enum_json_srt_vtt(self):
        """`format` is an optional query parameter with exactly json, srt, vtt.

        The server treats an absent value as json, so the parameter is not
        required; JobFormat parses exactly json, srt and vtt (case sensitive) and
        rejects every other value with 400.
        """
        params = self.operation["parameters"]
        format_param = next(p for p in params if p.get("name") == "format")
        self.assertFalse(
            _is_true(format_param.get("required")),
            "the format parameter must be optional (an absent value is json), "
            f"but required is {format_param.get('required')!r}",
        )
        self.assertEqual(
            format_param["schema"]["enum"],
            ["json", "srt", "vtt"],
            "the format enum must be exactly json, srt, vtt",
        )

    def test_400_example_is_unknown_format(self):
        """The 400 body example is {"error": "unknown format"}.

        The server answers 400 with exactly that body for any value outside
        json, srt, vtt on a job that exists.
        """
        example = self.operation["responses"]["400"]["content"]["application/json"]["example"]
        self.assertEqual(
            example,
            {"error": "unknown format"},
            "the 400 example must be the unknown-format error body",
        )

    def test_srt_example_equals_render_srt(self):
        """The application/x-subrip example is the SubRip rendering of the 200 example segments.

        The two cues are the spec's own 200 example segments (0.0-1.5 "Hello,"
        and 1.5-3.0 "world!"), rendered with the comma millisecond separator and
        a cue number from one, which is the form SrtWriterTest pins.
        """
        example = self.operation["responses"]["200"]["content"]["application/x-subrip"]["example"]
        self.assertEqual(
            example,
            SRT_EXAMPLE,
            "the SRT example must be the SubRip rendering of the 200 example segments "
            "(comma separator, cue numbered from one)",
        )

    def test_vtt_example_equals_render_vtt(self):
        """The text/vtt example is the WebVTT rendering of the 200 example segments.

        The same two cues under the WEBVTT header and a blank line, with the dot
        millisecond separator and no cue index, which is the form VttWriterTest
        pins.
        """
        example = self.operation["responses"]["200"]["content"]["text/vtt"]["example"]
        self.assertEqual(
            example,
            VTT_EXAMPLE,
            "the VTT example must be the WebVTT rendering of the 200 example segments "
            "(WEBVTT header, blank line, dot separator)",
        )

    def test_200_content_types_are_json_subrip_vtt(self):
        """The 200 answer carries exactly the JSON, SubRip and WebVTT content types.

        Absent or json answers application/json; srt answers application/x-subrip;
        vtt answers text/vtt. No fourth content type exists.
        """
        content = self.operation["responses"]["200"]["content"]
        self.assertEqual(
            set(content.keys()),
            {"application/json", "application/x-subrip", "text/vtt"},
            "the 200 content types must be exactly application/json, "
            "application/x-subrip and text/vtt",
        )


if __name__ == "__main__":
    unittest.main()
