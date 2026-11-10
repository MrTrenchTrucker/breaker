# shared — README

The single source of truth that the Android app and the server both read: the API contract, the model list, the formatting prompts and the design tokens. Neither side keeps its own copy, so a change here must be made for both.

**Sub-modules:** api-contracts, model-registry, format-prompts, ui-tokens, web-libs — each with its own AGENTS.md + README.md. web-libs is a grouping module; its sub-modules are noble-ciphers, noble-hashes, noble-curves, libsodium, each with its own AGENTS.md + README.md + VERSION.

Full module card: `AGENTS.md` in this folder.
