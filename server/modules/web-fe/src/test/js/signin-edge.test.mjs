import test from 'node:test';
import assert from 'node:assert/strict';
import { MESSAGES } from '../../main/resources/web/js/signin.js';
import { encodeBase64Padded } from '../../main/resources/web/js/signin-link.js';
import {
  APP_KEY, BOX_KEY, BOX_PUB, EXPECTED_PACKAGE, KEK, SEALED, VERIFIER, deferred, flipped, isZero, loginAnswer,
  makeInput, readySignin, saltAnswer, settle, typedText,
} from './signin-fixtures.mjs';

// Each KDF value at its ceiling, alone and together, with the other two at their floors.
const KDF_CEILING_CASES = [
  ['all three', { memory_kib: 262144, iterations: 10, parallelism: 4 },
    { memoryKib: 262144, iterations: 10, parallelism: 4, outputLength: 32 }],
  ['memory', { memory_kib: 262144, iterations: 3, parallelism: 1 },
    { memoryKib: 262144, iterations: 3, parallelism: 1, outputLength: 32 }],
  ['iterations', { memory_kib: 65536, iterations: 10, parallelism: 1 },
    { memoryKib: 65536, iterations: 10, parallelism: 1, outputLength: 32 }],
  ['parallelism', { memory_kib: 65536, iterations: 3, parallelism: 4 },
    { memoryKib: 65536, iterations: 3, parallelism: 4, outputLength: 32 }],
];

for (const [name, kdf, expected] of KDF_CEILING_CASES) {
  test('kdf ceiling for ' + name + ' reaches derive with exactly those values', async () => {
    const { signin, rec } = readySignin({ getSalt: async () => saltAnswer({ kdf_params: kdf }) });
    assert.deepEqual(await signin.run(makeInput()), { ok: true });
    assert.equal(rec.derive.length, 1);
    assert.deepEqual(rec.derive[0].kdfParams, expected);
  });
}

for (const status of [500, 204]) {
  test('login status ' + status + ' with a full valid body gives network and calls nothing after login', async () => {
    const { signin, rec } = readySignin({ login: async () => ({ ...loginAnswer(), status }) });
    assert.deepEqual(await signin.run(makeInput()), { ok: false, code: 'network', message: MESSAGES.network });
    assert.equal(rec.box.length, 0);
    assert.equal(rec.seal.length, 0);
    assert.equal(rec.start.length, 0);
    assert.equal(rec.replaced.length, 0);
  });
}

test('wipeAll while derive is held zeroes the password array derive was given', async () => {
  const hold = deferred();
  let passwordRef = null;
  const { signin, rec } = readySignin({
    derive: (password) => { passwordRef = password; return hold.promise; },
  });
  const pending = signin.run(makeInput());
  await settle();
  assert.deepEqual(passwordRef, new TextEncoder().encode(typedText));
  signin.wipeAll();
  assert.equal(isZero(passwordRef), true);
  hold.resolve({ kek: new Uint8Array(KEK), verifier: new Uint8Array(VERIFIER) });
  assert.equal((await pending).code, 'network');
  assert.equal(rec.login.length, 0);
});

test('wipeAll while login is held zeroes the kek and verifier arrays derive handed over', async () => {
  const hold = deferred();
  const kek = new Uint8Array(KEK);
  const verifier = new Uint8Array(VERIFIER);
  const { signin, rec } = readySignin({
    derive: async () => ({ kek, verifier }),
    login: () => hold.promise,
  });
  const pending = signin.run(makeInput());
  await settle();
  assert.deepEqual(kek, new Uint8Array(KEK));
  assert.deepEqual(verifier, new Uint8Array(VERIFIER));
  signin.wipeAll();
  assert.equal(isZero(kek), true);
  assert.equal(isZero(verifier), true);
  hold.resolve(loginAnswer());
  assert.equal((await pending).code, 'network');
  assert.equal(rec.box.length, 0);
  assert.equal(rec.replaced.length, 0);
});

test('wipeAll while the box public key is held zeroes the box key array it was given', async () => {
  const hold = deferred();
  let boxRef = null;
  const { signin, rec } = readySignin({
    boxPublicKey: (boxPrivateKey) => { boxRef = boxPrivateKey; return hold.promise; },
  });
  const pending = signin.run(makeInput());
  await settle();
  assert.deepEqual(boxRef, BOX_KEY);
  signin.wipeAll();
  assert.equal(isZero(boxRef), true);
  hold.resolve(new Uint8Array(BOX_PUB));
  assert.equal((await pending).code, 'network');
  assert.equal(rec.seal.length, 0);
  assert.equal(rec.start.length, 0);
});

test('wipeAll while seal is held zeroes the package and the app key copy it was given', async () => {
  const hold = deferred();
  let pkgRef = null;
  let appRef = null;
  const { signin, rec } = readySignin({
    seal: (pkg, app) => { pkgRef = pkg; appRef = app; return hold.promise; },
  });
  const pending = signin.run(makeInput());
  await settle();
  assert.deepEqual(pkgRef, EXPECTED_PACKAGE);
  assert.deepEqual(appRef, APP_KEY);
  signin.wipeAll();
  assert.equal(isZero(pkgRef), true);
  assert.equal(isZero(appRef), true);
  hold.resolve(new Uint8Array(SEALED));
  assert.equal((await pending).code, 'network');
  assert.equal(rec.start.length, 0);
  assert.equal(rec.replaced.length, 0);
});

test('the array boxPublicKey returned is zeroed after a finished run', async () => {
  const { signin, rec } = readySignin();
  assert.deepEqual(await signin.run(makeInput()), { ok: true });
  assert.equal(rec.boxResultRefs.length, 1);
  assert.equal(rec.boxResultRefs[0].length, 32);
  assert.equal(isZero(rec.boxResultRefs[0]), true);
});

test('the array boxPublicKey returned is zeroed after a box key mismatch', async () => {
  const { signin, rec } = readySignin({
    login: async () => loginAnswer({ box_pubkey: encodeBase64Padded(flipped(BOX_PUB)) }),
  });
  assert.equal((await signin.run(makeInput())).code, 'box_key_mismatch');
  assert.equal(rec.boxResultRefs.length, 1);
  assert.equal(rec.boxResultRefs[0].length, 32);
  assert.equal(isZero(rec.boxResultRefs[0]), true);
});
