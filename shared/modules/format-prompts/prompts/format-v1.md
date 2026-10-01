---
id: format-v1
version: format-v1
parameters:
  temperature: 0
  structured_output: false
  json_mode: false
  response_format: text
---

# Formatting prompt — `format-v1`

The prompt the server LLM path of the `format` module sends. It is held here so
the phone and any server-side tooling agree on one wording, and so a change is
a version bump rather than a quiet edit.

This file is a data file, not a formatter. Turning dictation into tidy text is
`android/modules/format`'s job; this module owns the words and the parameters
they are sent with, and nothing else.

## Parameters

| Parameter | Value | Why |
|-----------|-------|-----|
| `temperature` | `0` | The output must be a function of the input. Any sampling lets the model rewrite rather than reformat. |
| `structured_output` | `false` | Structured output invites a schema the model then has to fill, which is another chance to add content. |
| `json_mode` | `false` | As above. |
| `response_format` | `text` | Plain text in, plain text out — the model returns the text itself, not a description of it. |
| `max_tokens` | proportional to input | Sized from the input, never fixed, so a long dictation is not silently truncated. See `PromptParameters.maxTokensFor`. |

The rule the parameters serve: **formatting is non-destructive.** Same meaning,
better shape. The model may add punctuation, fix casing, drop filler and turn a
spoken enumeration into a list. It may not add, remove or change anything else.

## System prompt

Send this verbatim as the system message:

```text
Reformat the user's dictation into clean, well-structured text.
Preserve every fact and the exact meaning. Add punctuation, fix casing, remove
filler words, and convert spoken enumerations ('one is X, two is Y') into
numbered lists. Never add, remove, or change content. Output text only.
```

The user message is the raw transcription, unmodified and unlabelled. Do not
pre-clean it: a filler word the user actually meant must survive to the model
so the decision to drop it is made once, in one place.

## Worked example

The dictation the prompt exists for — a spoken enumeration, the case a
formatter most often gets wrong. The **input** is the raw transcription. The
**expected output** is what `android/modules/format`'s golden tests assert; this
module states the case so the prompt and the golden test can be read together,
and does not implement the transformation.

**Input**

```text
I have 3 things I want you to go over one is file a, two is file b, three is file c
```

**Expected output**

```text
I have 3 things I want you to go over:

1. is file a.
2. is file b.
3. is file c.
```

Two things to notice. The lead-in survives — it is content, not filler. And the
enumeration is reordered into a list without gaining or losing an item, which is
the whole contract: same three items, same words, better shape.

## Changing this prompt

1. Change the wording.
2. Bump the `id`/`version` above, and the filename to match: `format-v2.md`.
3. Update the golden tests in `android/modules/format` in the same change.

Never edit a shipped version in place. A prompt that changes under a version
number is drift, and drift here is invisible until output changes underneath a
user.
