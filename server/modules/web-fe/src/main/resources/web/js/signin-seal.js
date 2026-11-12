// The one place where the sign-in page seals a package to a recipient. The output is the libsodium crypto_box_seal
// layout: the ephemeral public key, then the 16-byte tag, then the ciphertext. The primitives come from the vendored,
// unmodified noble files. This file touches no page, no network and no storage, and it keeps no state.
import { x25519 } from './vendor/noble-curves/ed25519.js';
import { hsalsa, xsalsa20poly1305 } from './vendor/noble-ciphers/salsa.js';
import { randomBytes } from './vendor/noble-ciphers/utils.js';
import { blake2b } from './vendor/noble-hashes/blake2.js';

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

// Returns the sealed box, or null for any bad input or failure. Without a third argument a new ephemeral private
// key is generated and wiped here; a supplied one is used as it is and never wiped. Nothing leaves this function.
export function seal(plaintext, recipientPublic, ephemeralPrivate) {
  if (!(plaintext instanceof Uint8Array) || plaintext.length < 1) return null;
  if (!(recipientPublic instanceof Uint8Array) || recipientPublic.length !== 32) return null;
  if (ephemeralPrivate !== undefined && (!(ephemeralPrivate instanceof Uint8Array) || ephemeralPrivate.length !== 32)) return null;
  const createdHere = ephemeralPrivate === undefined;
  let ephemeral = null;
  let shared = null;
  let key = null;
  let sharedWords = null;
  let keyWords = null;
  try {
    ephemeral = createdHere ? randomBytes(32) : ephemeralPrivate;
    const epk = x25519.getPublicKey(ephemeral);
    shared = x25519.getSharedSecret(ephemeral, recipientPublic);
    if (shared.every((byte) => byte === 0)) return null;
    sharedWords = wordsLE(shared);
    keyWords = new Uint32Array(8);
    hsalsa(SIGMA, sharedWords, new Uint32Array(4), keyWords);
    key = bytesLE(keyWords);
    const nonce = blake2b(concat(epk, recipientPublic), { dkLen: 24 });
    const body = xsalsa20poly1305(key, nonce).encrypt(plaintext);
    return concat(epk, body);
  } catch {
    return null;
  } finally {
    if (createdHere && ephemeral !== null) ephemeral.fill(0);
    if (shared !== null) shared.fill(0);
    if (sharedWords !== null) sharedWords.fill(0);
    if (key !== null) key.fill(0);
    if (keyWords !== null) keyWords.fill(0);
  }
}
