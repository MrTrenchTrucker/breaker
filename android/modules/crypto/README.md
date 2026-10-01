# crypto — README

Encryption by default, done on the phone. It turns the user's password into a key-encryption key and a login value, creates and wraps the user's data key, and encrypts and decrypts transcriptions with AES-256-GCM. It also opens results that an agent's job sealed to the user. The password, the key-encryption key and the plain data key never leave the device (ADR-006, ADR-018).

Full module card, including how to run its tests: `AGENTS.md` in this folder.
