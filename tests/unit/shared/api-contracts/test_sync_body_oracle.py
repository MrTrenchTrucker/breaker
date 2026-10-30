"""Deeply nested sync body is refused as syntax, never by blowing the parser stack.

Standard library only. The oracle (sync_body_oracle.py) scans a body without json.loads. A
member value that is an object or array is refused at its opening bracket, before it is
entered, so the recursion never starts. A two-thousand-level body would otherwise recurse
past sys.getrecursionlimit() and raise RecursionError; it must raise Refusal("syntax").
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from sync_body_oracle import Refusal, body  # noqa: E402

# Far past the recursion limit (measured 1000); the unchanged oracle raises RecursionError at
# nested-array depth 495, so this body must be refused by the depth rule, not by the stack.
# Derived from the limit (2x) so the body always overflows an UNGUARDED oracle no matter what
# limit a harness sets; with the default 1000 this is 2000.
DEEP_DEPTH = sys.getrecursionlimit() * 2


def deep_body():
    nested = "[" * DEEP_DEPTH + "]" * DEEP_DEPTH
    raw = ("{" + '"v":1,"text":' + nested +
           ',"source":"local","model":"m","duration_ms":1,"created_at":"2026-10-21T12:00:00.000Z"}')
    return raw.encode("utf-8")


class DeepBodyTest(unittest.TestCase):
    def test_deep_nested_body_is_refused_syntax_not_recursion(self):
        with self.assertRaises(Refusal) as ctx:
            body(deep_body())
        self.assertEqual(str(ctx.exception), "syntax")

    def test_deep_depth_exceeds_the_recursion_limit(self):
        self.assertGreater(DEEP_DEPTH, sys.getrecursionlimit())


if __name__ == "__main__":
    unittest.main()
