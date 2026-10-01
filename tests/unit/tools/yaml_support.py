"""Shared helpers for the tests of tools/yaml_subset.py.

* `edit` builds a refused document from an accepted one and proves the edit
  landed, so a refusal test cannot pass because the text it meant to break was
  never there.
* `YamlCase.refuses` checks the whole contract of a refusal at once: the good
  document is accepted (the control), the edited one is refused, with this
  reason on this line, and `str(err)` is `<name>:<line>: <reason>`.
* `write_file` takes BYTES, so a test can build a file that is not valid UTF-8;
  a helper that took `str` could not express that shape.
* `emit` writes block YAML from a dict / list / str / None structure, so a
  round trip can start from a structure instead of from text the reader
  already understood.

Run: python3 -m unittest discover -s tests/unit/tools -t tests/unit/tools
"""
import os
import signal
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import yaml_subset  # noqa: E402

YamlSubsetError = yaml_subset.YamlSubsetError
loads = yaml_subset.loads
load = yaml_subset.load


def edit(good, old, new):
    """`good` with its one occurrence of `old` replaced by `new`."""
    if good.count(old) != 1:
        raise AssertionError(f"yaml_subset tests: {old!r} occurs {good.count(old)} times in the good document, not once")
    return good.replace(old, new)


def write_file(test, data, name="doc.yaml"):
    """Write `data` (bytes) to a temporary file and return its path."""
    if not isinstance(data, bytes):
        raise TypeError("write_file takes bytes so that a non-UTF-8 shape can be built")
    folder = tempfile.TemporaryDirectory()
    test.addCleanup(folder.cleanup)
    path = os.path.join(folder.name, name)
    with open(path, "wb") as handle:
        handle.write(data)
    return path


class YamlCase(unittest.TestCase):
    HANG_SECONDS = 5

    def setUp(self):
        """A reader that loops forever must FAIL its test, not stall the run: a red that never reports is not a gate."""
        if not hasattr(signal, "SIGALRM"):
            return

        def hung(signum, frame):
            raise AssertionError(f"yaml_subset: {self.id()} ran for more than {self.HANG_SECONDS} s; the reader is looping")

        self.addCleanup(signal.signal, signal.SIGALRM, signal.signal(signal.SIGALRM, hung))
        # repeating: a `subTest` swallows the first alarm, and the next subtest would loop unwatched
        signal.setitimer(signal.ITIMER_REAL, self.HANG_SECONDS, 0.2)
        self.addCleanup(signal.setitimer, signal.ITIMER_REAL, 0)

    def refuses_doc(self, text, line, reason, name="t.yaml"):
        """`text` is refused with `reason` (a fragment) on `line`."""
        with self.assertRaises(YamlSubsetError) as caught:
            loads(text, name)
        err = caught.exception
        self.assertIsInstance(err, ValueError)
        self.assertEqual(err.line, line, f"yaml_subset: wrong line for {text!r}: {err}")
        self.assertIn(reason, err.reason, f"yaml_subset: wrong reason for {text!r}: {err}")
        self.assertEqual(err.name, name)
        self.assertEqual(str(err), f"{name}:{line}: {err.reason}")
        return err

    def refuses(self, good, old, new, line, reason, name="t.yaml"):
        """`good` is accepted; `good` with `old` -> `new` is refused with `reason` on `line`."""
        loads(good, name)  # the control: the same document without the defect reads fine
        return self.refuses_doc(edit(good, old, new), line, reason, name)


# -- a test-only emitter -------------------------------------------------------

_ESCAPED = {"\\": "\\\\", '"': '\\"', "\n": "\\n", "\t": "\\t"}


def quote(text):
    return '"' + "".join(_ESCAPED.get(char, char) for char in text) + '"'


def emit(node, blocks=False):
    """Block YAML for a dict / list / str / None structure (a list holds strings and non-empty mappings)."""
    return _emit(node, 0, blocks) + "\n"


def _emit(node, indent, blocks):
    pad = " " * indent
    lines = []
    if isinstance(node, dict):
        for key, value in node.items():
            head = pad + quote(key) + ":"
            if isinstance(value, dict) and value or isinstance(value, list) and value:
                lines.append(head)
                lines.append(_emit(value, indent + 2, blocks))
            elif isinstance(value, dict):
                lines.append(head + " {}")
            elif isinstance(value, list):
                lines.append(head + " []")
            elif value is None:
                lines.append(head)
            elif blocks and _block_safe(value):
                lines.append(head + " |")
                lines.extend((" " * (indent + 2) + row) if row else "" for row in value[:-1].split("\n"))
            else:
                lines.append(head + " " + quote(value))
    else:
        for item in node:
            if isinstance(item, dict) and item:
                first, *rest = _emit(item, indent + 2, blocks).split("\n")
                lines.append(pad + "- " + first[indent + 2:])
                lines.extend(rest)
            elif isinstance(item, dict):
                lines.append(pad + "- {}")
            elif isinstance(item, (list, type(None))):
                raise TypeError("a sequence item is a string or a non-empty mapping in this emitter")
            else:
                lines.append(pad + "- " + quote(item))
    return "\n".join(lines)


def _block_safe(value):
    """A string a literal block scalar can carry back unchanged."""
    return (value.endswith("\n") and not value.endswith("\n\n") and not value.startswith((" ", "\n"))
            and "\t" not in value and "\r" not in value
            and all(row == row.rstrip(" ") for row in value.split("\n")))
