// The one place where the sign-in page opens a sealed box. The input is the libsodium crypto_box_seal layout
// from js/signin-seal.js: the ephemeral public key, then the 16-byte tag, then the ciphertext. The recipient
// private key is the account's X25519 box private key. The primitives come from the vendored, unmodified noble
// files, the same set the sealer uses. This file touches no page, no network and no storage, and it keeps no
// state. Every refusal - a wrong length, a bad ephemeral key, an open failure - is a null answer, never an
// exception, and the intermediates (shared secret, nonce, keystream key) are wiped before the answer leaves.
import { x25519 } from './vendor/noble-curves/ed25519.js';
import { hsalsa, xsalsa20poly1305 } from './vendor/noble-ciphers/salsa.js';
import { blake2b } from './vendor/noble-hashes/blake2.js';

// 32-byte ephemeral public key + 16-byte tag; the ciphertext itself may be empty.
const EPHEMERAL_PUBLIC_KEY_BYTES = 32;
const BOX_OVERHEAD = 48;

// The NaCl sigma constant "expand 32-byte k" as four little-endian 32-bit words.
const SIGMA = wordsLE(new TextEncoder().encode('expand 32-byte k'));

// Little-endian 32-bit words of a byte array, and the bytes of a word array.
function wordsLE(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const words = new Uint32Array(bytes.length / 4);
  for (let i = 0; i < words.length; i += 1) words[i] = view.getUint32(4 * i, true);
  return words;
}

function bytesLE(words) {
  const bytes = new Uint8Array(words.length * 4);
  const view = new DataView(bytes.buffer);
  for (let i = 0; i < words.length; i += 1) view.setUint32(4 * i, words[i], true);
  return bytes;
}

function concat(first, second) {
  const out = new Uint8Array(first.length + second.length);
  out.set(first, 0);
  out.set(second, first.length);
  return out;
}

// Returns the plaintext, or null for any bad input or failure. The caller's private key is used as it is and
// never wiped; the recipient public key and every derived intermediate are wiped here. Nothing else leaves.
export function unseal(box, recipientPrivate) {
  if (!(box instanceof Uint8Array) || box.length < BOX_OVERHEAD) return null;
  if (!(recipientPrivate instanceof Uint8Array) || recipientPrivate.length !== 32) return null;
  let recipientPublic = null;
  let shared = null;
  let key = null;
  let sharedWords = null;
  let keyWords = null;
  let nonce = null;
  try {
    const epk = box.subarray(0, 32);
    recipientPublic = x25519.getPublicKey(recipientPrivate);
    shared = x25519.getSharedSecret(recipientPrivate, epk);
    if (shared.every((byte) => byte === 0)) return null;
    sharedWords = wordsLE(shared);
    keyWords = new Uint32Array(8);
    hsalsa(SIGMA, sharedWords, new Uint32Array(4), keyWords);
    key = bytesLE(keyWords);
    nonce = blake2b(concat(epk, recipientPublic), { dkLen: 24 });
    return xsalsa20poly1305(key, nonce).decrypt(box.subarray(EPHEMERAL_PUBLIC_KEY_BYTES));
  } catch {
    return null;
  } finally {
    if (recipientPublic !== null) recipientPublic.fill(0);
    if (shared !== null) shared.fill(0);
    if (sharedWords !== null) sharedWords.fill(0);
    if (key !== null) key.fill(0);
    if (keyWords !== null) keyWords.fill(0);
    if (nonce !== null) nonce.fill(0);
  }
}
