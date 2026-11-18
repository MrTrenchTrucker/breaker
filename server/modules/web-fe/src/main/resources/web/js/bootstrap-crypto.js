// The first-login key bootstrap crypto: a fresh 32-byte data key and a fresh X25519 box key pair, both
// drawn from an injected random source, wrapped with aesgcm.js. This file touches no page, no network and
// no storage, and it keeps no state. The `random` option is a test hook, the same hook createCipher has:
// page code must never pass it. The `wipe` option is a second test hook, the real wipe by default; page
// code must never pass it either.
import { createCipher } from './aesgcm.js';
import { x25519 } from './vendor/noble-curves/ed25519.js';
import { randomBytes } from './vendor/noble-ciphers/utils.js';
import { wipeBytes } from './signin-link.js';

const KEY_BYTES = 32;

// The purpose of the wrapped box private key blob, as ASCII bytes. It is built from parts, so the whole
// text sits in no one literal, the same shape signin.js uses for its unwrap.
const BOX_PURPOSE_PARTS = Object.freeze(['breaker', 'box', 'privkey', 'v1']);
const BOX_KEY_PURPOSE = new TextEncoder().encode(BOX_PURPOSE_PARTS.join('-'));

// One refusal for every bootstrap refusal. It never holds a key byte.
export class BootstrapRefused extends Error {
  constructor() {
    super('key bootstrap refused');
    this.name = 'BootstrapRefused';
  }
}

function refuse() {
  throw new BootstrapRefused();
}

// Draws one key from the source and copies it, so a buffer the caller still holds cannot change under us.
function draw(source, size) {
  let raw;
  try {
    raw = source(size);
  } catch {
    refuse();
  }
  if (!(raw instanceof Uint8Array) || raw.length !== size) {
    if (raw instanceof Uint8Array) wipeBytes(raw);
    refuse();
  }
  const copy = new Uint8Array(raw);
  wipeBytes(raw);
  return copy;
}

// Returns { bootstrap }. bootstrap(kek) answers the five values of one bootstrap: the two secrets the
// caller keeps and wipes (dek, boxPrivkey), the plain box public key, and the two 60-byte wrapped blobs
// the upload takes. The data key wraps under the KEK with empty associated data; the box private key
// wraps under the data key with the purpose string, never under the KEK and never with empty data.
export function createBootstrapCrypto(options = {}) {
  const random = options.random || randomBytes;
  // The wipe hook defaults to the real zero-fill; a test may substitute its own to observe the copies.
  const wipe = options.wipe || wipeBytes;

  function bootstrap(kek) {
    if (!(kek instanceof Uint8Array) || kek.length !== KEY_BYTES) refuse();
    // The drawn keys live in these locals so the catch can zero them: a later step (a bad nonce, a wrap
    // refusal) throws after they exist, and neither copy may outlive the refusal.
    let dek = null;
    let boxPrivkey = null;
    try {
      dek = draw(random, KEY_BYTES);
      boxPrivkey = draw(random, KEY_BYTES);
      const wrappedDek = createCipher(kek, { random }).wrapDek(dek);
      const wrappedBoxPrivkey = createCipher(dek, { random }).wrapDek(boxPrivkey, BOX_KEY_PURPOSE);
      const boxPubkey = x25519.getPublicKey(boxPrivkey);
      return { dek, boxPrivkey, boxPubkey, wrappedDek, wrappedBoxPrivkey };
    } catch {
      wipe(dek);
      wipe(boxPrivkey);
      refuse();
    }
  }

  return { bootstrap };
}
