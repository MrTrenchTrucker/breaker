"""Shared constants and plant helpers for the keyword-model unit tests.
Standard library only; imports the same support module the other tests use."""
import model_registry_support as ts
import gen_model_registry as gmr

KW_ID = "kws-gigaspeech-3m"
KW_PREFIX = f"entry '{KW_ID}' line "
KW_URL = ("https://api.github.com/repos/k2-fsa/sherpa-onnx"
          "/releases/assets/166405338")
KW_SHA = "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a"
KW_NAME = ("sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
           ".tar.bz2")
KW_SIZE_MB = 17
CHECKSUM_TXT_SHA = (
    "284637b2b9fec1287aca10315dcc960710c6ec14224fb1dfa9fe427e77eb6c18")
SPEECH_IDS = ["small", "tiny", "base", "medium", "parakeet"]


def replace_once(text, old, new):
    assert text.count(old) == 1, f"expected exactly one {old!r}"
    return text.replace(old, new)


def refused_kw(old, new):
    """The refusal message for one planted change inside the committed text
    (`old` must occur once). It must name the keyword entry (by the planted
    id, when the change is to the id line itself)."""
    msg = ts.refuses(replace_once(ts.models_text(), old, new))
    prefix = "entry '" if old.startswith("id:") else KW_PREFIX
    assert msg.startswith(prefix), f"refusal did not name the entry: {msg!r}"
    return msg


def accepted_kw(old, new):
    """The converted keyword entry of the committed text with one change."""
    _, kw = gmr.check_registry(replace_once(ts.models_text(), old, new))
    return kw[0]
