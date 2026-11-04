import test from 'node:test';
import assert from 'node:assert/strict';
import { MESSAGES } from '../../main/resources/web/js/signin.js';
import { encodeBase64Padded } from '../../main/resources/web/js/signin-link.js';
import {
  BOX_PUB, CODE_TEXT, SEALED, VERIFIER, deferred, flipped, isZero, loginAnswer, makeInput, patterned,
  readySignin, saltAnswer, sessionText, settle,
} from './signin-fixtures.mjs';

const secondSessionText = sessionText.toUpperCase();

// Runs one sign-in with the given overrides. The record carries the fakes and the result of the run.
async function runWith(overrides = {}, hash) {
  const made = readySignin(overrides, hash);
  const result = await made.signin.run(makeInput());
  return { ...made, result };
}

// Failures before a session token exists: logout is never called, and the answer bodies carry a token-looking text.
async function assertNoLogout(overrides, code) {
  const { rec, result } = await runWith(overrides);
  assert.equal(result.ok, false);
  assert.equal(result.code, code);
  assert.deepEqual(rec.logout, []);
  assert.equal(rec.log.includes('logout'), false);
}

// Failures after a session token exists: logout is called once, with that exact token, before the run ends.
async function assertOneLogout(overrides, code) {
  const { rec, result } = await runWith(overrides);
  assert.equal(result.ok, false);
  assert.equal(result.code, code);
  assert.deepEqual(rec.logout, [sessionText]);
  assert.equal(rec.log.filter((name) => name === 'logout').length, 1);
  assert.equal(rec.logoutZero.length, 1);
  assert.equal(rec.logoutZero[0].allZero, true);
}

test('run without a link never calls logout', async () => {
  const { rec, result } = await runWith({}, '');
  assert.deepEqual(result, { ok: false, code: 'bad_link', message: MESSAGES.bad_link });
  assert.deepEqual(rec.logout, []);
});

test('salt status 500 with a token body never calls logout', () =>
  assertNoLogout({ getSalt: async () => ({ status: 500, body: { token: sessionText } }) }, 'bad_credentials'));

test('salt status 200 with no key data never calls logout', () =>
  assertNoLogout({ getSalt: async () => ({ status: 200, body: { token: sessionText } }) }, 'bad_credentials'));

test('a salt of the wrong length never calls logout', () =>
  assertNoLogout({
    getSalt: async () => saltAnswer({ salt: encodeBase64Padded(patterned(0x01, 8)), token: sessionText }),
  }, 'bad_salt'));

test('a kdf version other than one never calls logout', () =>
  assertNoLogout({ getSalt: async () => saltAnswer({ kdf_version: 2, token: sessionText }) }, 'kdf_refused'));

test('a derive with short arrays never calls logout', () =>
  assertNoLogout({
    derive: async () => ({ kek: new Uint8Array(31), verifier: new Uint8Array(VERIFIER) }),
  }, 'derive_refused'));

test('login status 401 with a token body never calls logout', () =>
  assertNoLogout({ login: async () => ({ ...loginAnswer(), status: 401 }) }, 'bad_credentials'));

test('login status 423 with a token body never calls logout', () =>
  assertNoLogout({ login: async () => ({ ...loginAnswer(), status: 423, retryAfter: '30' }) }, 'locked'));

test('login status 500 with a token body never calls logout', () =>
  assertNoLogout({ login: async () => ({ ...loginAnswer(), status: 500 }) }, 'network'));

test('login status 200 with an empty token never calls logout', () =>
  assertNoLogout({ login: async () => loginAnswer({ token: '' }) }, 'network'));

test('login status 200 with a number token never calls logout', () =>
  assertNoLogout({ login: async () => loginAnswer({ token: 4242 }) }, 'network'));

test('keyless account calls logout once with the token', () =>
  assertOneLogout({ login: async () => loginAnswer({ wrapped_dek: null, wrapped_box_privkey: null }) }, 'keyless'));

test('a bad wrapped key calls logout once with the token', () =>
  assertOneLogout({
    login: async () => loginAnswer({ wrapped_dek: encodeBase64Padded(patterned(0x01, 60)) }),
  }, 'keys_refused'));

test('a box key mismatch calls logout once with the token', () =>
  assertOneLogout({
    login: async () => loginAnswer({ box_pubkey: encodeBase64Padded(flipped(BOX_PUB)) }),
  }, 'box_key_mismatch'));

test('a box public key of the wrong length calls logout once with the token', () =>
  assertOneLogout({ boxPublicKey: async () => new Uint8Array(31) }, 'keys_refused'));

test('a sealed package of the wrong length calls logout once with the token', () =>
  assertOneLogout({ seal: async () => new Uint8Array(112) }, 'seal_refused'));

for (const status of [400, 401, 403, 413, 503]) {
  test('start status ' + status + ' calls logout once with the token', () =>
    assertOneLogout({ startHandoff: async () => ({ status, body: { error: 'refused' } }) }, 'start_refused'));
}

test('a start with a bad code calls logout once with the token', () =>
  assertOneLogout({ startHandoff: async () => ({ status: 201, body: { code: 'short code' } }) }, 'bad_code'));

test('a throwing location replace logs out once and the result is network', async () => {
  const { rec, result } = await runWith({
    location: { replace() { throw new Error('no redirect'); } },
  });
  assert.deepEqual(result, { ok: false, code: 'network', message: MESSAGES.network });
  assert.deepEqual(rec.logout, [sessionText]);
  assert.deepEqual(rec.log, ['salt', 'derive', 'login', 'box', 'seal', 'start', 'logout']);
});

test('a wipeAll during the box key wait logs out once after the wipe', async () => {
  const hold = deferred();
  let boxRef = null;
  const { signin, rec } = readySignin({
    boxPublicKey: (boxPrivateKey) => { boxRef = boxPrivateKey; return hold.promise; },
  });
  const pending = signin.run(makeInput());
  await settle();
  assert.equal(isZero(boxRef), false);
  signin.wipeAll();
  hold.resolve(new Uint8Array(BOX_PUB));
  assert.equal((await pending).code, 'network');
  assert.deepEqual(rec.logout, [sessionText]);
  assert.equal(rec.logoutZero[0].allZero, true);
});

test('a wipeAll during the seal wait logs out once after the wipe', async () => {
  const hold = deferred();
  let pkgRef = null;
  const { signin, rec } = readySignin({
    seal: (pkg) => { pkgRef = pkg; return hold.promise; },
  });
  const pending = signin.run(makeInput());
  await settle();
  assert.equal(isZero(pkgRef), false);
  signin.wipeAll();
  hold.resolve(new Uint8Array(SEALED));
  assert.equal((await pending).code, 'network');
  assert.deepEqual(rec.logout, [sessionText]);
  assert.equal(rec.logoutZero[0].allZero, true);
});

test('a wipeAll during the start wait logs out once', async () => {
  const hold = deferred();
  const { signin, rec } = readySignin({ startHandoff: () => hold.promise });
  const pending = signin.run(makeInput());
  await settle();
  signin.wipeAll();
  hold.resolve({ status: 201, body: { code: CODE_TEXT } });
  assert.equal((await pending).code, 'network');
  assert.deepEqual(rec.logout, [sessionText]);
  assert.equal(rec.replaced.length, 0);
});

test('success logs out once with the token between start and replace', async () => {
  const { signin, rec } = readySignin();
  assert.deepEqual(await signin.run(makeInput()), { ok: true });
  assert.deepEqual(rec.logout, [sessionText]);
  assert.deepEqual(rec.log, ['salt', 'derive', 'login', 'box', 'seal', 'start', 'logout', 'replace']);
  assert.equal(rec.replaced.length, 1);
});

test('replace does not happen until a held logout settles', async () => {
  const { signin, rec } = readySignin();
  rec.logoutMode = 'hold';
  const pending = signin.run(makeInput());
  await settle();
  await settle();
  assert.deepEqual(rec.logout, [sessionText]);
  assert.equal(rec.replaced.length, 0);
  rec.logoutHold.resolve();
  assert.deepEqual(await pending, { ok: true });
  assert.equal(rec.replaced.length, 1);
});

test('a wipeAll while the success logout is held stops the run with network and no redirect', async () => {
  const { signin, rec } = readySignin();
  rec.logoutMode = 'hold';
  const pending = signin.run(makeInput());
  await settle();
  await settle();
  assert.deepEqual(rec.logout, [sessionText]);
  signin.wipeAll();
  rec.logoutHold.resolve();
  assert.equal((await pending).code, 'network');
  assert.equal(rec.replaced.length, 0);
  assert.equal(rec.logout.length, 1);
});

test('a rejecting logout does not change the success result', async () => {
  const { signin, rec } = readySignin();
  rec.logoutMode = 'reject';
  assert.deepEqual(await signin.run(makeInput()), { ok: true });
  assert.equal(rec.replaced.length, 1);
  assert.equal(rec.logout.length, 1);
});

test('a throwing logout does not change the success result', async () => {
  const { signin, rec } = readySignin();
  rec.logoutMode = 'throw';
  assert.deepEqual(await signin.run(makeInput()), { ok: true });
  assert.equal(rec.replaced.length, 1);
  assert.equal(rec.logout.length, 1);
});

test('a rejecting logout does not change the keyless failure result', async () => {
  const { signin, rec } = readySignin({ login: async () => loginAnswer({ wrapped_dek: null, wrapped_box_privkey: null }) });
  rec.logoutMode = 'reject';
  assert.deepEqual(await signin.run(makeInput()), { ok: false, code: 'keyless', message: MESSAGES.keyless });
  assert.equal(rec.logout.length, 1);
});

test('a throwing logout does not change the keyless failure result', async () => {
  const { signin, rec } = readySignin({ login: async () => loginAnswer({ wrapped_dek: null, wrapped_box_privkey: null }) });
  rec.logoutMode = 'throw';
  assert.deepEqual(await signin.run(makeInput()), { ok: false, code: 'keyless', message: MESSAGES.keyless });
  assert.equal(rec.logout.length, 1);
});

test('the success result carries no token', async () => {
  const { signin } = readySignin();
  const result = await signin.run(makeInput());
  assert.deepEqual(Object.keys(result), ['ok']);
  assert.deepEqual(result, { ok: true });
});

test('the failure result carries no token', async () => {
  const { signin } = readySignin({ login: async () => loginAnswer({ wrapped_dek: null, wrapped_box_privkey: null }) });
  const result = await signin.run(makeInput());
  assert.deepEqual(Object.keys(result), ['ok', 'code', 'message']);
});

test('the logout on a keyless failure sees the secret arrays already zero', async () => {
  let liveAtLogin = false;
  const made = readySignin({
    login: async () => {
      liveAtLogin = made.rec.verifierRefs.length === 1 && !isZero(made.rec.verifierRefs[0]);
      return loginAnswer({ wrapped_dek: null, wrapped_box_privkey: null });
    },
  });
  assert.equal((await made.signin.run(makeInput())).code, 'keyless');
  assert.equal(liveAtLogin, true);
  assert.equal(made.rec.logoutZero[0].count > 0, true);
  assert.equal(made.rec.logoutZero[0].allZero, true);
});

test('two runs with two tokens each log out their own token', async () => {
  let nextToken = sessionText;
  const { signin, rec } = readySignin({ login: async () => loginAnswer({ token: nextToken }) });
  assert.deepEqual(await signin.run(makeInput()), { ok: true });
  nextToken = secondSessionText;
  assert.deepEqual(await signin.run(makeInput()), { ok: true });
  assert.deepEqual(rec.logout, [sessionText, secondSessionText]);
});

test('a held logout on a failure path does not hold the result', async () => {
  const { signin, rec } = readySignin({ login: async () => loginAnswer({ wrapped_dek: null, wrapped_box_privkey: null }) });
  rec.logoutMode = 'hold';
  let finished = false;
  const pending = signin.run(makeInput()).then((result) => { finished = true; return result; });
  await settle();
  await settle();
  assert.deepEqual(rec.logout, [sessionText]);
  assert.equal(finished, true);
  rec.logoutHold.resolve();
  assert.equal((await pending).code, 'keyless');
});
