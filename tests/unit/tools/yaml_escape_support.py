"""Helpers of the no-escape tests: the alphabet of awkward characters, the single edits of a text, and the sweep.

* `single_edits` lists EVERY text one edit away from a text.
* `sweep` reads each distinct text with a loader and sorts out what happened: read, refused, refused with a broken
  contract, or escaped as another exception type.
* `lines_in` and `problems_with` say what a refusal must keep: a line inside the text and `<name>:<line>: <reason>`.
"""
import re
from typing import NamedTuple

from yaml_support import YamlSubsetError

NAME = "t.yaml"

ALPHABET = (
    "\u00a0", "\u2028", "\u2029", "\u200b", "\u3000", "\u0085", "\t", " ", "  ", ":", ": ", "#", " #", "?", "? ", "-", "- ",
    "[", "]", "{", "}", ",", "'", '"', "\\", "|", ">", "&", "*", "!", "\n", "\r", "\r\n", "\x00", "\x0e", "\x1a", "\x1f",
    "\x7f", "\x9f", "---", "...", "\ufeff", "\u00e9", "\u65e5", "\U0001f600", "\ufffe", "\uffff", "\ud800", "@", "`", "%",
)

_LINE_BREAK = re.compile("\r\n|\r|\n")


def single_edits(text, alphabet=ALPHABET):
    """EVERY text one edit away from `text`: an item inserted at each position, each character replaced by each
    item, each character deleted, the text cut short at each position (`text` itself only when an edit leaves it
    unchanged)."""
    for position in range(len(text) + 1):
        for item in alphabet:
            yield text[:position] + item + text[position:]
    for position in range(len(text)):
        for item in alphabet:
            yield text[:position] + item + text[position + 1:]
        yield text[:position] + text[position + 1:]
    for position in range(len(text)):
        yield text[:position]


def lines_in(text):
    """How many lines `text` has when LF, CRLF and a lone CR each end one (the reader itself splits on LF only)."""
    return len(_LINE_BREAK.findall(text)) + 1


def problems_with(err, text):
    """What is wrong with the refusal `err` of `text`, as words; empty when it keeps the contract of a refusal."""
    problems = []
    line, reason = getattr(err, "line", None), getattr(err, "reason", None)
    total = lines_in(text)
    if type(line) is not int or not 1 <= line <= total:
        problems.append(f"the line {line!r} is not inside the text (1 to {total})")
    if not isinstance(reason, str) or not reason:
        problems.append(f"the reason {reason!r} is not a message")
    if str(err) != f"{NAME}:{line}: {reason}":
        problems.append(f"str(err) is {str(err)!r}, not '<name>:<line>: <reason>'")
    return problems


class Outcome(NamedTuple):
    escapes: list  # (text, "Type: message") for each text that raised something other than YamlSubsetError
    broken: list  # (text, problems) for each refusal that breaks the contract of a refusal
    refused: int  # distinct texts refused
    read: int  # distinct texts read


def sweep(loader, texts):
    """Read each distinct text of `texts` with `loader(text, NAME)` and sort out what happened."""
    escapes, broken, refused, read = [], [], 0, 0
    seen = set()
    for text in texts:
        if text in seen:
            continue
        seen.add(text)
        try:
            loader(text, NAME)
            read += 1
        except YamlSubsetError as err:
            refused += 1
            problems = problems_with(err, text)
            if problems:
                broken.append((text, problems))
        except AssertionError as err:
            # the alarm of a looping reader: stop here, do not go on to count the next text as one more escape
            err.add_note(f"yaml_subset: the text being read was {text!r}")
            raise
        except Exception as err:  # the point of the probe: anything else is an escape
            escapes.append((text, f"{type(err).__name__}: {err}"))
    return Outcome(escapes, broken, refused, read)
