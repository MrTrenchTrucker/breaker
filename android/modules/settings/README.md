# Settings — README

Typed settings persistence + access to the shared model registry.

The module saves the nine plain settings keys to one properties file; the API key reference is kept by the Keystore port and never written into that file. A save writes a temp file in the same folder and moves it over the live file in one step, so a failed save keeps the previous file, and a failure of the credential-reference step puts the previous file and the previous reference back.

Full module card: `AGENTS.md` in this folder.
