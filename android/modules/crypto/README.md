# crypto — README

Encryption by default, done on the phone. It turns the user's password into a key-encryption key and a login value, creates and wraps the user's data key, and encrypts and decrypts transcriptions with AES-256-GCM. It also opens results that an agent's job sealed to the user. The password, the key-encryption key and the plain data key never leave the device (ADR-006, ADR-018).

Full module card, including how to run its tests: `AGENTS.md` in this folder.

The module ships key derivation so far. One Argon2id pass over the password is split by HKDF into the key-encryption key and the login verifier, and both come out of a single call. It refuses stored cost settings outside fixed limits before doing any work, and the password is normalised to Unicode NFC before it is encoded as UTF-8. The data key, the encryption and the sealed-box parts are added later.
