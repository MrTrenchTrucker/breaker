# transport — README

One question, one answer: is the configured Local Server reachable right now?
A TCP connect that succeeds within 1.5 s means reachable; refused, timed out or no
network all mean not reachable. The answer is cached for 30 s and can be
refreshed on demand, so a dictation never waits on the server.

The probe contacts the configured Local Server and nothing else, so there is no
cloud path to fall through to.

Which engine actually runs is core's decision, not this module's. Core's
dictation use case reads the probe's answer, tries the server first and falls
back to the phone's own engine, and owns the clear refusal when local mode is
asked for and no model is installed. This module holds no audio and never
blocks the UI: core records the whole clip before it asks, so nothing waits on a
probe and nothing is dropped.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
