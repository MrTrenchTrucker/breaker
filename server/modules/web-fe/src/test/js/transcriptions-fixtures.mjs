// Fixtures for the transcription dashboard tests. Every ciphertext here is made
// by the real aesgcm module (createCipher(DEK).seal) under the associated data the
// Android side builds; no ciphertext is hand-typed. The data key is a synthetic
// patterned test key, not a real key.
import { createCipher } from '../../main/resources/web/js/aesgcm.js';
import { encodeBase64Padded } from '../../main/resources/web/js/signin-link.js';

// A synthetic 32-byte test data key: byte i is 0xA0 + i.
export const DEK = Uint8Array.from({ length: 32 }, (_, i) => 0xA0 + i);

export const OTHER_DEK = Uint8Array.from({ length: 32 }, (_, i) => 0x50 + i);

export const CLIENT_A = '3f2a9c1e-7b4d-4e86-a1c5-9d0e2b7f6a48';
export const CLIENT_B = 'c6a1e4b0-52d9-4f37-8b1a-e07c93d5a2f6';

// The associated data of a record: the UTF-8 bytes of the client id, one LF, the
// decimal key version. Mirrors RecordFrame.aad of the shared api-contracts module.
export function aadFor(clientId, keyVersion) {
  return new TextEncoder().encode(clientId + '\n' + String(keyVersion));
}

// The R1 body a transcription is stored as, with the text under test.
export function bodyOf(text) {
  return JSON.stringify({
    v: 1,
    text,
    source: 'local',
    model: 'test-model',
    duration_ms: 1200,
    created_at: '2026-10-11T12:00:00.000Z',
  });
}

// Seals text under DEK with the given AAD through the real aesgcm module. A fixed
// nonce is only ever supplied here, in tests, through the documented test hook.
export function sealParts(text, aad, nonce) {
  const random = nonce === undefined ? undefined : () => new Uint8Array(nonce);
  const cipher = random === undefined ? createCipher(DEK) : createCipher(DEK, { random });
  return cipher.seal(new TextEncoder().encode(text), aad);
}

// The standard padded base64 frame: nonce, ciphertext, tag.
export function frameOf(parts) {
  const frame = new Uint8Array(12 + parts.ciphertext.length + 16);
  frame.set(parts.nonce, 0);
  frame.set(parts.ciphertext, 12);
  frame.set(parts.tag, 12 + parts.ciphertext.length);
  return encodeBase64Padded(frame);
}

export function sealFrame(text, aad, nonce) {
  return frameOf(sealParts(text, aad, nonce));
}

// A live TranscriptionRecord whose encrypted field was produced by the real
// aesgcm module. `aad` may be overridden to build a row sealed under the wrong
// associated data; `nonce` fixes the nonce for a deterministic frame.
export function rowOf({
  clientId,
  keyVersion = 1,
  text = 'hello',
  storedAt = '2026-10-11T12:00:05.000Z',
  nonce,
  aad,
} = {}) {
  const associated = aad === undefined ? aadFor(clientId, keyVersion) : aad;
  return {
    client_id: clientId,
    change: 7,
    deleted: false,
    key_version: keyVersion,
    encrypted: sealFrame(bodyOf(text), associated, nonce),
    stored_at: storedAt,
  };
}

// A delete record: client_id, change, deleted true and stored_at only.
export function deletedRowOf({ clientId, storedAt = '2026-10-11T12:00:06.000Z' } = {}) {
  return { client_id: clientId, change: 8, deleted: true, stored_at: storedAt };
}
