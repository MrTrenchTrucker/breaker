// End-to-end node test of the sign-in chain with the real crypto: the real Argon2id derive (run once per input,
// memoized), the two key unwraps in signin.js with the real aesgcm.js, the real boxPublicKey and seal from the
// facade. Only the network and the page history are faked. This file starts no process, opens no socket and
// imports nothing that spawns one, so it runs in the node tier on one core.
import test from 'node:test';
import assert from 'node:assert/strict';
import { x25519 } from '../../main/resources/web/js/vendor/noble-curves/ed25519.js';
import { hsalsa, xsalsa20poly1305 } from '../../main/resources/web/js/vendor/noble-ciphers/salsa.js';
import { blake2b } from '../../main/resources/web/js/vendor/noble-hashes/blake2.js';
import * as facade from '../../main/resources/web/js/signin-crypto.js';
import { createSignin, MESSAGES } from '../../main/resources/web/js/signin.js';
import { decodeBase64Padded, encodeBase64Padded } from '../../main/resources/web/js/signin-link.js';
import { KDF_ANDROID, SEAL_VECTORS, WRAP_VECTORS, X25519_BOX } from './signin-vectors.mjs';
import {
  CHALLENGE_TEXT, CODE_TEXT, makeDeps, makeInput, patterned, saltAnswer, sessionText, toUrlSafe43, wrapped,
} from './signin-fixtures.mjs';

// Test-only opener: the recipient side of the sealed box, the same construction as in signin-seal.test.mjs.
const wordsOf = (bytes) => {
  const words = new Uint32Array(bytes.length / 4);
  for (let i = 0; i < words.length; i += 1) {
    words[i] = (bytes[4 * i] | (bytes[4 * i + 1] << 8) | (bytes[4 * i + 2] << 16) | (bytes[4 * i + 3] << 24)) >>> 0;
  }
  return words;
};
const bytesOf = (words) => {
  const bytes = new Uint8Array(words.length * 4);
  for (let i = 0; i < words.length; i += 1) {
    for (let j = 0; j < 4; j += 1) bytes[4 * i + j] = (words[i] >>> (8 * j)) & 255;
  }
  return bytes;
};
const concat = (a, b) => {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
};
const SIGMA = wordsOf(new TextEncoder().encode('expand 32-byte k'));
const sharedKey = (shared) => {
  const out = new Uint32Array(8);
  hsalsa(SIGMA, wordsOf(shared), wordsOf(new Uint8Array(16)), out);
  return bytesOf(out);
};
const openBox = (box, recipientPrivate) => {
  const recipientPublic = x25519.getPublicKey(recipientPrivate);
  const ephemeral = box.subarray(0, 32);
  const key = sharedKey(x25519.getSharedSecret(recipientPrivate, ephemeral));
  const nonce = blake2b(concat(ephemeral, recipientPublic), { dkLen: 24 });
  return xsalsa20poly1305(key, nonce).decrypt(box.subarray(32));
};

// Vector values only: every expected value comes from the shared vector file, never from the code under test.
const entry = (rows, name) => rows.find((row) => row.name === name);
const fromHex = (text) => Uint8Array.from(text.match(/../g), (pair) => parseInt(pair, 16));
const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
const KDF = KDF_ANDROID[0];
const SALT = fromHex(KDF.saltHex);
const KEK = fromHex(KDF.kek);
const KDF_PARAMS = { memory_kib: KDF.memoryKib, iterations: KDF.iterations, parallelism: KDF.parallelism };
const DEK = fromHex(entry(WRAP_VECTORS, 'dek-under-kek').plainHex);
const BOX_ROW = entry(WRAP_VECTORS, ['box', 'private', 'key', 'under', 'dek'].join('-'));
const BOX_PRIV = fromHex(BOX_ROW.plainHex);
const BOX_WRAPPED = fromHex(BOX_ROW.wrappedHex);
const ALICE_PUB = fromHex(entry(X25519_BOX, 'rfc7748-6.1-alice').publicHex);
const BOB = entry(X25519_BOX, 'rfc7748-6.1-bob');
const BOB_PRIV = fromHex(BOB.privateHex);
const BOB_PUB = fromHex(BOB.publicHex);
const OPENER = entry(SEAL_VECTORS, ['signin', 'package', '65'].join('-'));
const LINK_HASH = '#challenge=' + CHALLENGE_TEXT + '&app_key=' + toUrlSafe43(BOB_PUB);
const ORDER = ['salt', 'derive', 'login', 'box', 'seal', 'start', 'logout', 'replace'];

// The real derive runs once for one (password, salt, cost). Every return is a fresh copy, because the page
// zero-fills what derive returned, so a second use must not see zeroed arrays.
let memo = null;
async function realDerive(password, salt, kdf) {
  const key = [encodeBase64Padded(password), encodeBase64Padded(salt), kdf.memoryKib, kdf.iterations, kdf.parallelism].join(' ');
  if (memo === null || memo.key !== key) {
    const out = await facade.derive(password, salt, kdf);
    memo = { key, kek: new Uint8Array(out.kek), verifier: new Uint8Array(out.verifier) };
    return { kek: out.kek, verifier: out.verifier };
  }
  return { kek: new Uint8Array(memo.kek), verifier: new Uint8Array(memo.verifier) };
}

// The login reply: the wrapped DEK is built at run time under the derived KEK; the box key blob is the shared bytes.
function replyBody(changes) {
  return {
    token: sessionText,
    wrapped_dek: encodeBase64Padded(wrapped(KEK, DEK)),
    wrapped_box_privkey: encodeBase64Padded(BOX_WRAPPED),
    box_pubkey: encodeBase64Padded(ALICE_PUB),
    ...changes,
  };
}

// Builds the flow with the real facade functions as spies (each copies its arguments at call time, pushes its own
// name into the call-order log, and then returns the real result). Changes alter only the login reply.
function setup(changes = {}) {
  const spy = { kek: [], boxOut: [], sealOut: [] };
  let rec = null;
  const made = makeDeps({
    getSalt: async (username) => {
      rec.salt.push(username);
      rec.log.push('salt');
      return saltAnswer({ salt: encodeBase64Padded(SALT), kdf_params: KDF_PARAMS });
    },
    derive: async (password, salt, kdf) => {
      rec.log.push('derive');
      rec.derive.push({ password: new Uint8Array(password), salt: new Uint8Array(salt), kdfParams: { ...kdf } });
      const out = await realDerive(password, salt, kdf);
      spy.kek.push(new Uint8Array(out.kek));
      return out;
    },
    login: async (username, verifierBase64) => {
      rec.login.push({ username, verifierBase64 });
      rec.log.push('login');
      return { status: 200, retryAfter: null, body: replyBody(changes) };
    },
    boxPublicKey: async (boxPrivateKey) => {
      rec.log.push('box');
      rec.box.push(new Uint8Array(boxPrivateKey));
      const computed = facade.boxPublicKey(boxPrivateKey);
      spy.boxOut.push(new Uint8Array(computed));
      return computed;
    },
    seal: async (packageBytes, appKeyBytes) => {
      rec.log.push('seal');
      rec.seal.push({ pkg: new Uint8Array(packageBytes), app: new Uint8Array(appKeyBytes) });
      const sealed = facade.seal(packageBytes, appKeyBytes);
      spy.sealOut.push(new Uint8Array(sealed));
      return sealed;
    },
  });
  rec = made.rec;
  const signin = createSignin(made.deps);
  assert.deepEqual(signin.readLink(LINK_HASH), { ok: true });
  return { rec, spy, signin };
}

async function runFlow(changes = {}) {
  const { rec, spy, signin } = setup(changes);
  const result = await signin.run(makeInput(KDF.password));
  return { rec, spy, result };
}

function assertRefused(result, code) {
  assert.equal(result.ok, false);
  assert.equal(result.code, code);
  assert.equal(result.message, MESSAGES[code]);
}

// The package the flow sealed, opened with the recipient (Bob) private key, which is the app key's pair.
function packageOf(rec) {
  return openBox(decodeBase64Padded(rec.start[0].sealed, 113), BOB_PRIV);
}

test('the test-only opener opens the shared signin-package-65 box to its plain bytes', () => {
  assert.equal(hex(openBox(fromHex(OPENER.boxHex), fromHex(OPENER.recipientPrivateHex))), OPENER.plainHex);
});

test('happy path: the flow returns ok, calls each step in order, and sends the vector verifier', async () => {
  const { rec, spy, result } = await runFlow();
  assert.deepEqual(result, { ok: true });
  assert.deepEqual(rec.log, ORDER);
  assert.equal(hex(decodeBase64Padded(rec.login[0].verifierBase64, 32)), KDF.verifier);
  assert.equal(hex(spy.kek[0]), KDF.kek);
});

test('happy path package: 113 sealed bytes open to byte 0 = 1, then the DEK, then the box key', async () => {
  const { rec, result } = await runFlow();
  assert.deepEqual(result, { ok: true });
  assert.notEqual(decodeBase64Padded(rec.start[0].sealed, 113), null);
  const pkg = packageOf(rec);
  assert.equal(pkg.length, 65);
  assert.equal(pkg[0], 1);
  assert.equal(hex(pkg.subarray(1, 33)), hex(DEK));
  assert.equal(hex(pkg.subarray(33, 65)), hex(BOX_PRIV));
});

test('the package does not carry the box key twice and does not swap the two keys', async () => {
  const { rec, result } = await runFlow();
  assert.deepEqual(result, { ok: true });
  const pkg = packageOf(rec);
  assert.notEqual(hex(pkg.subarray(1, 33)), hex(BOX_PRIV));
  assert.notEqual(hex(pkg.subarray(33, 65)), hex(DEK));
  assert.notEqual(hex(pkg.subarray(1, 33)), hex(pkg.subarray(33, 65)));
});

test('the handoff request carries the challenge, the session token and the sealed text, with one logout', async () => {
  const { rec, spy, result } = await runFlow();
  assert.deepEqual(result, { ok: true });
  assert.equal(rec.start.length, 1);
  assert.equal(rec.start[0].challenge, CHALLENGE_TEXT);
  assert.equal(rec.start[0].token, sessionText);
  assert.equal(rec.start[0].sealed, encodeBase64Padded(spy.sealOut[0]));
  assert.deepEqual(rec.logout, [sessionText]);
  assert.deepEqual(rec.replaced, ['breaker://handoff?code=' + CODE_TEXT]);
});

test('the spied boxPublicKey gets the box key and returns the Alice public key; the spied seal gets Bob public', async () => {
  const { rec, spy, result } = await runFlow();
  assert.deepEqual(result, { ok: true });
  assert.equal(rec.box.length, 1);
  assert.equal(hex(rec.box[0]), hex(BOX_PRIV));
  assert.equal(hex(spy.boxOut[0]), hex(ALICE_PUB));
  assert.equal(rec.seal.length, 1);
  assert.equal(rec.seal[0].pkg.length, 65);
  assert.equal(hex(rec.seal[0].app), hex(BOB_PUB));
});

test('a wrapped DEK under another KEK gives keys refused, with no seal and no handoff request', async () => {
  const { rec, result } = await runFlow({ wrapped_dek: encodeBase64Padded(wrapped(patterned(0x11, 32), DEK)) });
  assertRefused(result, 'keys_refused');
  assert.equal(rec.seal.length, 0);
  assert.equal(rec.start.length, 0);
});

test('a box blob under the empty AAD gives keys refused, with no seal and no handoff request', async () => {
  const { rec, result } = await runFlow({ wrapped_box_privkey: encodeBase64Padded(wrapped(DEK, BOX_PRIV)) });
  assertRefused(result, 'keys_refused');
  assert.equal(rec.seal.length, 0);
  assert.equal(rec.start.length, 0);
});

test('box_pubkey of the Bob public key gives box key mismatch, with no seal and no handoff request', async () => {
  const { rec, result } = await runFlow({ box_pubkey: encodeBase64Padded(BOB_PUB) });
  assertRefused(result, 'box_key_mismatch');
  assert.equal(rec.seal.length, 0);
  assert.equal(rec.start.length, 0);
});
