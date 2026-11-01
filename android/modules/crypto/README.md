# crypto — README

Encryption by default, done on the phone. Built: it turns the user's password into a key-encryption key and a login value (key derivation), unwraps the user's data key with a key-encryption key, encrypts and decrypts transcriptions with AES-256-GCM and associated data, and opens the sign-in package with its private key (the sealed-box open). Not built: generating the data key or the box key pair, wrapping a new data key, and unsealing results an agent's job sealed to the user. The password, the key-encryption key and the plain data key never leave the device (ADR-006, ADR-018).

Full module card, including how to run its tests: `AGENTS.md` in this folder.
