"""The reader on documents shaped like the specs and registries it exists for,
and on structures written out and read back.

The fixtures are written here as text, next to the structure each must read as.
They mirror the SHAPES the real specs use (quoted status codes, `$ref` flow
mappings, `security: []`, a nested `enum` in a flow mapping, a server-sent-event
example in a literal block, a registry of mappings with `null` values and a
`notes: |` block); they are not copies of any spec.

Run: python3 -m unittest discover -s tests/unit/tools -t tests/unit/tools
"""
import random
import unittest

from yaml_support import YamlCase, emit, loads

SPEC = '''openapi: 3.0.3

# The contract of record.
info:
  title: Example API
  version: "1.0.0"
  description: |
    First paragraph, with a colon: and a "quote".

    Second paragraph.
servers:
  - url: "https://{host}"
    description: |
      Reached over the VPN.
    variables:
      host:
        default: server.invalid
security:
  - bearerAuth: []
tags:
  - name: sync
    description: Offline-first push and pull.
paths:
  /health:
    get:
      security: []
      tags: [ops]
      responses:
        "200":
          description: Up.
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Health" }
  /v1/stream:
    post:
      responses:
        "200":
          content:
            text/event-stream:
              schema: { type: string }
              examples:
                partial:
                  value: |
                    event: segment
                    data: {"id":0,"start":0.0,"text":"three things"}

                    event: result
                    data: {"text":"three things","segments":[]}
        "401":
          description: Missing token.
components:
  schemas:
    Row:
      type: object
      required: [client_id, text]
      properties:
        client_id: { type: string, format: uuid, description: The client's id for this row. }
        source: { type: string, enum: [local, server], description: Which engine produced it. }
        deleted:
          type: boolean
'''

SPEC_EXPECTED = {
    "openapi": "3.0.3",
    "info": {"title": "Example API", "version": "1.0.0",
             "description": 'First paragraph, with a colon: and a "quote".\n\nSecond paragraph.\n'},
    "servers": [{"url": "https://{host}", "description": "Reached over the VPN.\n",
                 "variables": {"host": {"default": "server.invalid"}}}],
    "security": [{"bearerAuth": []}],
    "tags": [{"name": "sync", "description": "Offline-first push and pull."}],
    "paths": {
        "/health": {"get": {"security": [], "tags": ["ops"], "responses": {"200": {
            "description": "Up.",
            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Health"}}}}}}},
        "/v1/stream": {"post": {"responses": {
            "200": {"content": {"text/event-stream": {"schema": {"type": "string"}, "examples": {"partial": {
                "value": 'event: segment\ndata: {"id":0,"start":0.0,"text":"three things"}\n\n'
                         'event: result\ndata: {"text":"three things","segments":[]}\n'}}}}},
            "401": {"description": "Missing token."}}}},
    },
    "components": {"schemas": {"Row": {
        "type": "object",
        "required": ["client_id", "text"],
        "properties": {
            "client_id": {"type": "string", "format": "uuid", "description": "The client's id for this row."},
            "source": {"type": "string", "enum": ["local", "server"], "description": "Which engine produced it."},
            "deleted": {"type": "boolean"}}}}},
}

REGISTRY = '''# The registry.
registry_version: 1

families:
  - sherpa-onnx
  - whisper

pinning:
  require_immutable_release_asset: true
  refused_url_substrings:
    - /latest/
    - "?ref="
  require_url_substring: /releases/download/

hosting:
  enabled: true

models:
  - id: tiny
    family: sherpa-onnx
    params: null
    size_mb: null
    sha256: null
    hosted: false
    pin_state: unpinned
    notes: |
      Recommended for weaker hardware.
      Not pinned yet.
  - id: small
    family: whisper
    params: 244
    size_mb: 461.5
    pin_state: pinned
    notes: |-
      One line.
'''

REGISTRY_EXPECTED = {
    "registry_version": "1",
    "families": ["sherpa-onnx", "whisper"],
    "pinning": {"require_immutable_release_asset": "true", "refused_url_substrings": ["/latest/", "?ref="],
                "require_url_substring": "/releases/download/"},
    "hosting": {"enabled": "true"},
    "models": [
        {"id": "tiny", "family": "sherpa-onnx", "params": "null", "size_mb": "null", "sha256": "null",
         "hosted": "false", "pin_state": "unpinned", "notes": "Recommended for weaker hardware.\nNot pinned yet.\n"},
        {"id": "small", "family": "whisper", "params": "244", "size_mb": "461.5", "pin_state": "pinned",
         "notes": "One line."},
    ],
}


class SpecShapeTest(YamlCase):
    def test_a_spec_shaped_document_reads_as_written(self):
        self.assertEqual(loads(SPEC), SPEC_EXPECTED)

    def test_a_spec_shaped_document_reads_the_same_with_crlf(self):
        self.assertEqual(loads(SPEC.replace("\n", "\r\n")), SPEC_EXPECTED)

    def test_status_codes_and_versions_are_strings_for_the_consumers_that_convert_them(self):
        got = loads(SPEC)
        codes = got["paths"]["/v1/stream"]["post"]["responses"]
        self.assertEqual([int(code) for code in codes], [200, 401])
        self.assertIs(type(got["info"]["version"]), str)

    def test_a_flow_enum_nested_in_a_flow_mapping_is_a_list(self):
        source = loads(SPEC)["components"]["schemas"]["Row"]["properties"]["source"]
        self.assertEqual(source["enum"], ["local", "server"])

    def test_text_inside_a_literal_block_is_never_read_as_flow(self):
        value = loads(SPEC)["paths"]["/v1/stream"]["post"]["responses"]["200"]["content"]["text/event-stream"]["examples"]["partial"]["value"]
        self.assertIs(type(value), str)
        self.assertIn('data: {"id":0', value)


class RegistryShapeTest(YamlCase):
    def test_a_registry_shaped_document_reads_as_written(self):
        self.assertEqual(loads(REGISTRY), REGISTRY_EXPECTED)

    def test_a_question_mark_url_fragment_is_a_quoted_string(self):
        self.assertEqual(loads(REGISTRY)["pinning"]["refused_url_substrings"][1], "?ref=")

    def test_a_registry_shaped_document_keeps_model_order(self):
        self.assertEqual([m["id"] for m in loads(REGISTRY)["models"]], ["tiny", "small"])

    def test_null_is_a_string_here_and_the_caller_converts(self):
        got = loads(REGISTRY)["models"][0]
        self.assertEqual((got["params"], got["size_mb"], got["sha256"], got["hosted"]), ("null", "null", "null", "false"))


# -- round trips: a structure written as block YAML reads back as itself --------

AWKWARD = [
    "", " ", "  leading", "trailing  ", "a: b", "a:b", "#hash", "a # b", "- dash", "-", "? q", "& x", "*star", "!bang",
    "'single'", '"double"', "back\\slash", "[a, b]", "{k: v}", "a,b", "pipe |", ">fold", "%pct", "@at", "`tick",
    "null", "~", "true", "12", "0.0", "é—└─", "line1\nline2", "tab\there", "\n", "ends\n", "\n\nstarts",
]


class RoundTripTest(YamlCase):
    def test_awkward_strings_as_values_keys_and_items(self):
        for text in AWKWARD:
            with self.subTest(text=text):
                for node in ({"k": text}, {text: "v"}, {"l": [text, "z"]}, {"l": [{text: text}]}):
                    self.assertEqual(loads(emit(node)), node, emit(node))

    def test_none_and_empty_collections(self):
        node = {"a": None, "b": {}, "c": [], "d": {"e": None, "f": {}}, "g": [{"h": None}, {}]}
        self.assertEqual(loads(emit(node)), node)

    def test_a_structure_with_every_kind_of_nesting(self):
        node = {"a": {"b": {"c": [{"d": "x", "e": [{"f": "y"}, "z"]}, "w"]}}, "g": [{"h": {"i": [{"j": "k"}]}}]}
        self.assertEqual(loads(emit(node)), node)

    def test_literal_blocks_round_trip(self):
        for text in ("one\n", "one\ntwo\n", "one\n\ntwo\n", "one\n\n\nthree\n", "#c\n- x\nk: v\n", "a\n  b\n    c\nd\n",
                     "data: {\"x\": 1}\n", "é\n"):
            with self.subTest(text=text):
                node = {"k": text, "l": [{"m": text}]}
                self.assertEqual(loads(emit(node, blocks=True)), node, emit(node, blocks=True))

    def test_the_emitter_really_wrote_a_block_scalar(self):
        self.assertIn(": |\n", emit({"k": "one\ntwo\n"}, blocks=True))
        self.assertNotIn("|", emit({"k": "one\ntwo\n"}))

    def test_seeded_random_structures(self):
        rng = random.Random(20260930)
        alphabet = list("abZ 09:#-'\"\\[]{},|>!&*?%@é—")

        def text(allow_block):
            if allow_block and rng.random() < 0.3:  # a string a literal block can carry
                rows = ["".join(rng.choice(alphabet) for _ in range(rng.randint(0, 8))).rstrip(" ") for _ in range(rng.randint(1, 4))]
                rows[0] = rows[0].lstrip(" ") or "x"
                return "\n".join(rows) + "\n"
            return "".join(rng.choice(alphabet + ["\t"]) for _ in range(rng.randint(0, 10)))

        def value(depth):
            kind = rng.random()
            if depth == 0 or kind < 0.3:
                return text(True)
            if kind < 0.4:
                return None
            return mapping(depth - 1, 0) if kind < 0.7 else sequence(depth - 1)

        def mapping(depth, least):
            return {text(False): value(depth) for _ in range(rng.randint(least, 4))}

        def sequence(depth):
            return [text(False) if rng.random() < 0.5 else mapping(depth, 1) if rng.random() < 0.8 else {}
                    for _ in range(rng.randint(0, 4))]

        checked = blocks_written = 0
        for index in range(300):
            document = {text(False): value(3), "z": value(2)}
            for blocks in (False, True):
                written = emit(document, blocks=blocks)
                self.assertEqual(loads(written), document, f"seed 20260930, document {index}, blocks={blocks}:\n{written}")
                checked += 1
                blocks_written += blocks and ": |\n" in written
        self.assertEqual(checked, 600)
        self.assertGreater(blocks_written, 30, "the random documents should exercise literal blocks")


if __name__ == "__main__":
    unittest.main()
