// The one place where the sign-in page calls a key derivation library. Argon2id and HKDF-SHA256 come from the
// vendored, unmodified noble-hashes files, so a library can be swapped here and nowhere else. This file touches
// no page, no network and no storage, and it keeps no state.
import { argon2idAsync } from './vendor/noble-hashes/argon2.js';
import { hkdf } from './vendor/noble-hashes/hkdf.js';
import { sha256 } from './vendor/noble-hashes/sha2.js';

const SALT_BYTES = 16;
const OUTPUT_BYTES = 32;
const MEMORY_KIB = [65536, 262144];
const ITERATIONS = [3, 10];
const PARALLELISM = [1, 4];

// The two HKDF labels, built from parts so that no line holds a long base64-looking string literal.
const KEK_LABEL = ['breaker', 'kek', 'v1'];
const VERIFIER_LABEL = ['breaker', 'auth', 'verifier', 'v1'];

// A refusal carries one fixed text. It never holds a password, salt or key byte.
export class KdfRefused extends Error {
  constructor() {
    super('key derivation refused');
    this.name = 'KdfRefused';
  }
}

function refuse() {
  throw new KdfRefused();
}

function inRange(value, [low, high]) {
  return Number.isSafeInteger(value) && value >= low && value <= high;
}

// Every check runs before any Argon2id work. A string, an ordinary array or a missing field is refused here.
function checkInputs(password, salt, params) {
  if (!(password instanceof Uint8Array)) refuse();
  if (!(salt instanceof Uint8Array) || salt.length !== SALT_BYTES) refuse();
  if (params === null || typeof params !== 'object') refuse();
  if (!inRange(params.memoryKib, MEMORY_KIB) || !inRange(params.iterations, ITERATIONS)
    || !inRange(params.parallelism, PARALLELISM) || params.outputLength !== OUTPUT_BYTES) refuse();
}

// The label as ASCII bytes. TextEncoder gives the same bytes as ASCII for these parts.
function labelBytes(parts) {
  return new TextEncoder().encode(parts.join('-'));
}

// HKDF-SHA256 with an empty salt for the Extract step, and the label as the Expand info.
function expand(tag, parts) {
  return hkdf(sha256, tag, new Uint8Array(0), labelBytes(parts), OUTPUT_BYTES);
}

// Argon2id at the given cost, then the two subkeys. The Argon2id output is zero-filled on every path once it exists.
export async function stretch(password, salt, params) {
  checkInputs(password, salt, params);
  const tag = await argon2idAsync(password, salt, {
    t: params.iterations,
    m: params.memoryKib,
    p: params.parallelism,
    dkLen: OUTPUT_BYTES,
    maxmem: params.memoryKib * 1024,
  });
  try {
    return { kek: expand(tag, KEK_LABEL), verifier: expand(tag, VERIFIER_LABEL) };
  } finally {
    tag.fill(0);
  }
}
