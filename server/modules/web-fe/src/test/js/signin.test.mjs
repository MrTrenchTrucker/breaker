import test from 'node:test';
import assert from 'node:assert/strict';
import { createSignin, MESSAGES } from '../../main/resources/web/js/signin.js';
import { encodeBase64Padded } from '../../main/resources/web/js/signin-link.js';
import {
  APP_KEY, APP_TEXT, BOX_KEY, BOX_PUB, CHALLENGE_TEXT, CODE_TEXT, EXPECTED_PACKAGE, KEK, SALT, SEALED,
  VERIFIER, deferred, isZero, loginAnswer, makeDeps, makeInput, patterned, poisoned,
  readySignin, saltAnswer, sessionText, settle, toUrlSafe43, typedText,
} from './signin-fixtures.mjs';

test('readLink valid clears the fragment once with null state and the clean url', () => {
  const { rec, linked } = readySignin();
  assert.deepEqual(linked, { ok: true });
  assert.deepEqual(rec.historyCalls, [[null, '', '/signin.html']]);
});

test('readLink invalid refuses with bad link and still clears the fragment once', () => {
  const { rec, linked } = readySignin({}, '#challenge=short');
  assert.equal(linked.ok, false);
  assert.equal(linked.code, 'bad_link');
  assert.equal(linked.message, MESSAGES.bad_link);
  assert.equal(rec.historyCalls.length, 1);
});

test('run before any valid readLink calls nothing', async () => {
  const { deps, rec } = makeDeps();
  const signin = createSignin(deps);
  const input = makeInput();
  const result = await signin.run(input);
  assert.equal(result.code, 'bad_link');
  assert.equal(input.reads, 0);
  assert.deepEqual(rec.log, []);
});

test('a refused readLink after a good one leaves nothing to sign in with', async () => {
  const { signin, rec } = readySignin();
  assert.equal(signin.readLink('#challenge=short').code, 'bad_link');
  const result = await signin.run(makeInput());
  assert.equal(result.code, 'bad_link');
  assert.deepEqual(rec.log, []);
});

test('a second valid readLink replaces the challenge the next run sends', async () => {
  const { signin, rec } = readySignin();
  const other = toUrlSafe43(patterned(0x66, 32));
  assert.deepEqual(signin.readLink('#app_key=' + APP_TEXT + '&challenge=' + other), { ok: true });
  assert.deepEqual(await signin.run(makeInput()), { ok: true });
  assert.equal(rec.start[0].challenge, other);
});

test('happy path calls every step in order', async () => {
  const { signin, rec } = readySignin();
  const input = makeInput();
  const result = await signin.run(input);
  assert.deepEqual(result, { ok: true });
  assert.deepEqual(rec.log, ['salt', 'derive', 'login', 'box', 'seal', 'start', 'replace']);
  assert.equal(input.reads, 1);
});

test('happy path sends the exact bytes at each step', async () => {
  const { signin, rec } = readySignin();
  await signin.run(makeInput());
  assert.deepEqual(rec.salt, ['account one']);
  assert.deepEqual(rec.derive[0].password, new TextEncoder().encode(typedText));
  assert.deepEqual(rec.derive[0].salt, SALT);
  assert.deepEqual(rec.derive[0].kdfParams, { memoryKib: 65536, iterations: 3, parallelism: 1, outputLength: 32 });
  assert.equal(rec.login[0].username, 'account one');
  assert.equal(rec.login[0].verifierBase64, encodeBase64Padded(VERIFIER));
  assert.deepEqual(rec.box[0], BOX_KEY);
  assert.deepEqual(rec.seal[0].pkg, EXPECTED_PACKAGE);
  assert.equal(rec.seal[0].pkg.length, 65);
  assert.equal(rec.seal[0].pkg[0], 1);
  assert.deepEqual(rec.seal[0].app, APP_KEY);
  assert.equal(rec.start[0].challenge, CHALLENGE_TEXT);
  assert.equal(rec.start[0].sealed, encodeBase64Padded(SEALED));
  assert.equal(rec.start[0].sealed.length, 152);
  assert.equal(rec.start[0].token, sessionText);
  assert.equal(CODE_TEXT.length, 43);
  assert.deepEqual(rec.replaced, ['breaker://handoff?code=' + CODE_TEXT]);
});

test('happy path zeroes every key array the fakes handed out', async () => {
  const { signin, rec } = readySignin();
  await signin.run(makeInput());
  assert.equal(rec.sealRefs.length, 1);
  assert.equal(rec.appRefs.length, 1);
  for (const ref of [...rec.kekRefs, ...rec.verifierRefs, ...rec.boxRefs, ...rec.sealRefs, ...rec.passwordRefs, ...rec.appRefs]) {
    assert.equal(isZero(ref), true);
  }
});

test('two runs in a row do not share state', async () => {
  let logins = 0;
  const { signin, rec } = readySignin({
    login: async () => {
      logins += 1;
      return logins === 1 ? loginAnswer() : { status: 401, retryAfter: null, body: null };
    },
  });
  assert.deepEqual(await signin.run(makeInput()), { ok: true });
  const second = await signin.run(makeInput('second sample text'));
  assert.equal(second.code, 'bad_credentials');
  assert.deepEqual(rec.derive[1].password, new TextEncoder().encode('second sample text'));
  assert.equal(isZero(rec.sealRefs[0]), true);
  assert.equal(isZero(rec.kekRefs[0]), true);
  assert.equal(isZero(rec.kekRefs[1]), true);
});

test('wipeAll during an in flight derive zeroes its arrays and stops with network', async () => {
  const hold = deferred();
  const kek = new Uint8Array(KEK);
  const verifier = new Uint8Array(VERIFIER);
  const { signin, rec } = readySignin({ derive: () => hold.promise });
  const pending = signin.run(makeInput());
  await settle();
  signin.wipeAll();
  hold.resolve({ kek, verifier });
  const result = await pending;
  assert.equal(result.code, 'network');
  assert.equal(isZero(kek), true);
  assert.equal(isZero(verifier), true);
  assert.equal(rec.login.length, 0);
  assert.equal(rec.replaced.length, 0);
});

test('a run after wipeAll has no link left and calls nothing', async () => {
  const { signin, rec } = readySignin();
  signin.wipeAll();
  signin.wipeAll();
  const result = await signin.run(makeInput());
  assert.equal(result.code, 'bad_link');
  assert.deepEqual(rec.log, []);
});

test('poisoned storage objects given as extra deps are never touched', async () => {
  const { signin } = readySignin({ storeA: poisoned('storeA'), storeB: poisoned('storeB') });
  assert.deepEqual(await signin.run(makeInput()), { ok: true });
});

test('composed and decomposed typed text give the same bytes to derive', async () => {
  const composed = readySignin();
  const decomposed = readySignin();
  await composed.signin.run(makeInput('\u00e9'));
  await decomposed.signin.run(makeInput('\u0065\u0301'));
  assert.deepEqual(composed.rec.derive[0].password, new Uint8Array([0xc3, 0xa9]));
  assert.deepEqual(decomposed.rec.derive[0].password, new Uint8Array([0xc3, 0xa9]));
});

test('every fixed message is frozen and is one plain ASCII sentence', () => {
  assert.equal(Object.isFrozen(MESSAGES), true);
  for (const text of Object.values(MESSAGES)) {
    assert.match(text, /^[ -~]+$/);
    assert.equal(text.split('.').length, 2);
  }
});

test('wipeAll during the salt wait stops with network before derive', async () => {
  const hold = deferred();
  const { signin, rec } = readySignin({ getSalt: () => hold.promise });
  const pending = signin.run(makeInput());
  await settle();
  signin.wipeAll();
  hold.resolve(saltAnswer());
  assert.equal((await pending).code, 'network');
  assert.equal(rec.derive.length, 0);
});

test('wipeAll during the login wait stops with network and calls nothing after it', async () => {
  const hold = deferred();
  const { signin, rec } = readySignin({ login: () => hold.promise });
  const pending = signin.run(makeInput());
  await settle();
  signin.wipeAll();
  hold.resolve(loginAnswer());
  assert.equal((await pending).code, 'network');
  assert.equal(rec.box.length, 0);
  assert.equal(rec.seal.length, 0);
  assert.equal(rec.replaced.length, 0);
  assert.equal(isZero(rec.kekRefs[0]), true);
});

test('wipeAll during the box public key wait stops with network before seal', async () => {
  const hold = deferred();
  const { signin, rec } = readySignin({ boxPublicKey: () => hold.promise });
  const pending = signin.run(makeInput());
  await settle();
  signin.wipeAll();
  hold.resolve(new Uint8Array(BOX_PUB));
  assert.equal((await pending).code, 'network');
  assert.equal(rec.seal.length, 0);
  assert.equal(rec.start.length, 0);
});

test('wipeAll during the seal wait stops with network and no return link', async () => {
  const hold = deferred();
  const { signin, rec } = readySignin({ seal: () => hold.promise });
  const pending = signin.run(makeInput());
  await settle();
  signin.wipeAll();
  hold.resolve(new Uint8Array(113));
  assert.equal((await pending).code, 'network');
  assert.equal(rec.start.length, 0);
  assert.equal(rec.replaced.length, 0);
});

test('wipeAll during the handoff start stops with network and no return link', async () => {
  const hold = deferred();
  const { signin, rec } = readySignin({ startHandoff: () => hold.promise });
  const pending = signin.run(makeInput());
  await settle();
  signin.wipeAll();
  hold.resolve({ status: 201, body: { code: CODE_TEXT } });
  assert.equal((await pending).code, 'network');
  assert.equal(rec.replaced.length, 0);
});
