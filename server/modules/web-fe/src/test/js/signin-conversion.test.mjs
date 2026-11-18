import test from 'node:test';
import assert from 'node:assert/strict';
import { MESSAGES, conversionSummaryText } from '../../main/resources/web/js/signin.js';
import { encodeBase64Padded } from '../../main/resources/web/js/signin-link.js';
import { seal } from '../../main/resources/web/js/signin-seal.js';
import { x25519 } from '../../main/resources/web/js/vendor/noble-curves/ed25519.js';
import {
  BOX_KEY, BOX_PUB, CODE_TEXT, bootstrapAnswer, bootstrapDeps, flipped, isZero, loginAnswer, makeInput,
  readySignin, sessionText,
} from './signin-fixtures.mjs';
import {
  fakeCrypto, fillForm, happyRoutes, mountPage, response, submitEvent, submitHandler,
} from './signin-page-fixtures.mjs';
import { CLIENT_ID as PENDING_ID } from './conversion-fixtures.mjs';

const SIGNED_IN = 'Signed in. Returning to the app.';
const ZERO_LINE = SIGNED_IN + ' Pending transcriptions: 0 converted, 0 skipped, 0 failed.';
const ONE_LINE = SIGNED_IN + ' Pending transcriptions: 1 converted, 0 skipped, 0 failed.';
const ONE_FAILURE_LINE = SIGNED_IN + ' Pending transcriptions: 0 converted, 0 skipped, 1 failed.';

// One pending row that the real encrypt adapter accepts: the six keys in the strict order, source server.
// Its client id is the shared fixture UUID, imported above: the adapter requires a lowercase canonical UUID,
// whose first group is an 8-char hex run, so the fixture builds it instead of writing it out.
const PENDING_BODY = new TextEncoder().encode(JSON.stringify({
  v: 1,
  text: 'a pending transcription',
  source: 'server',
  model: 'whisper',
  duration_ms: 12,
  created_at: '2026-01-02T03:04:05.000Z',
}));

// The two long routes, written whole: the source pin's literal rule exempts a root-relative
// route -- a literal that starts with /v1/ -- so neither of these is a violation.
const PENDING_SEALED_ROUTE = '/v1/sync/pending-sealed';
const HANDOFF_START_ROUTE = '/v1/auth/handoff/start';

// The flow-level tests drive the injected conversion dep. The page-level tests drive the real wiring.
async function runFlow(overrides = {}) {
  const made = readySignin(overrides);
  const result = await made.signin.run(makeInput());
  return { ...made, result };
}

test('conversionSummaryText builds the frozen sentence', () => {
  assert.equal(conversionSummaryText({ converted: 2, skipped: 1, failed: 0 }),
    'Pending transcriptions: 2 converted, 1 skipped, 0 failed.');
  assert.equal(conversionSummaryText({ converted: 0, skipped: 0, failed: 1 }),
    'Pending transcriptions: 0 converted, 0 skipped, 1 failed.');
});

test('a converted pending row puts its counts on the result and the hand-off completes', async () => {
  const { signin, rec } = readySignin({
    convert: async () => ({ status: 'ok', converted: 1, skipped: 0, failed: 0 }),
  });
  const result = await signin.run(makeInput());
  assert.deepEqual(result, { ok: true, summary: 'Pending transcriptions: 1 converted, 0 skipped, 0 failed.' });
  assert.deepEqual(rec.replaced, ['breaker://handoff?code=' + CODE_TEXT]);
});

test('a conversion with one bad row shows that row as skipped', async () => {
  const { signin } = readySignin({
    convert: async () => ({ status: 'ok', converted: 2, skipped: 1, failed: 0 }),
  });
  const result = await signin.run(makeInput());
  assert.equal(result.summary, 'Pending transcriptions: 2 converted, 1 skipped, 0 failed.');
});

test('a pending list 401 or 403 counts one failure and never signs the hand-off out', async () => {
  const { signin, rec } = readySignin({
    convert: async () => ({ status: 'signed_out', converted: 0, skipped: 0, failed: 0 }),
  });
  const result = await signin.run(makeInput());
  assert.deepEqual(result, { ok: true, summary: 'Pending transcriptions: 0 converted, 0 skipped, 1 failed.' });
  assert.deepEqual(rec.replaced.length, 1);
  assert.equal(rec.logout.filter((token) => token === sessionText).length, 1);
});

test('a throwing conversion still completes the hand-off with 0/0/1', async () => {
  const { signin, rec } = readySignin({
    convert: async () => { throw new Error('conversion exploded'); },
  });
  const result = await signin.run(makeInput());
  assert.deepEqual(result, { ok: true, summary: 'Pending transcriptions: 0 converted, 0 skipped, 1 failed.' });
  assert.deepEqual(rec.replaced.length, 1);
});

test('the conversion runs after the tamper check and before the seal', async () => {
  const made = readySignin({
    convert: async () => {
      made.rec.log.push('convert');
      return { status: 'ok', converted: 0, skipped: 0, failed: 0 };
    },
  });
  await made.signin.run(makeInput());
  assert.deepEqual(made.rec.log,
    ['salt', 'derive', 'login', 'box', 'convert', 'seal', 'start', 'logout', 'replace']);
});

test('a box key mismatch stops before the conversion runs', async () => {
  let called = 0;
  const { signin } = readySignin({
    login: async () => loginAnswer({ box_pubkey: encodeBase64Padded(flipped(BOX_PUB)) }),
    convert: async () => { called += 1; return { status: 'ok', converted: 0, skipped: 0, failed: 0 }; },
  });
  const result = await signin.run(makeInput());
  assert.equal(result.code, 'box_key_mismatch');
  assert.equal(called, 0);
});

test('the stored path passes the login key_version to the conversion', async () => {
  const seen = [];
  const { signin } = readySignin({
    login: async () => loginAnswer({ key_version: 7 }),
    convert: async (cur, keys) => {
      seen.push(keys.keyVersion);
      return { status: 'ok', converted: 0, skipped: 0, failed: 0 };
    },
  });
  await signin.run(makeInput());
  assert.deepEqual(seen, [7]);
});

for (const [label, value] of [['a missing key_version', undefined], ['a text key_version', '1'], ['a negative key_version', -1]]) {
  test('the stored path refuses ' + label + ' with keys_refused', async () => {
    const { signin } = readySignin({ login: async () => loginAnswer({ key_version: value }) });
    const result = await signin.run(makeInput());
    assert.deepEqual(result, { ok: false, code: 'keys_refused', message: MESSAGES.keys_refused });
  });
}

test('the keyless path passes the postKeys key_version to the conversion', async () => {
  const seen = [];
  const answer = bootstrapAnswer();
  const { signin } = readySignin({
    ...bootstrapDeps(answer, { ok: true, keyVersion: 2 }),
    convert: async (cur, keys) => {
      seen.push(keys.keyVersion);
      return { status: 'ok', converted: 0, skipped: 0, failed: 0 };
    },
  });
  await signin.run(makeInput());
  assert.deepEqual(seen, [2]);
});

test('the box key and the dek are zero-filled after a finished run', async () => {
  let dekRef = null;
  let boxRef = null;
  const { signin } = readySignin({
    convert: async (cur, keys) => {
      dekRef = keys.dek;
      boxRef = keys.boxKey;
      return { status: 'ok', converted: 0, skipped: 0, failed: 0 };
    },
  });
  await signin.run(makeInput());
  assert.equal(dekRef === null, false);
  assert.equal(boxRef === null, false);
  assert.equal(isZero(dekRef), true);
  assert.equal(isZero(boxRef), true);
});

test('the keyless dek and box key are zero-filled after a finished run', async () => {
  const answer = bootstrapAnswer();
  const { signin } = readySignin({
    ...bootstrapDeps(answer),
    convert: async () => ({ status: 'ok', converted: 0, skipped: 0, failed: 0 }),
  });
  await signin.run(makeInput());
  assert.equal(isZero(answer.dek), true);
  assert.equal(isZero(answer.boxPrivkey), true);
});

test('the page writes the signed-in sentence and the summary as one line', async () => {
  const m = mountPage();
  fillForm(m);
  await submitHandler(m)(submitEvent());
  assert.equal(m.els['signin-status'].textContent, ZERO_LINE);
  assert.equal(m.loc.replaced.length, 1);
});

test('a real pending row is converted through the page wiring and uploaded', async () => {
  // The page stand-in answers BOX_PUB for boxPublicKey, so the sealed box is made for the public key of
  // the fixture box key the stored path unwraps.
  const recipientPublic = x25519.getPublicKey(new Uint8Array(BOX_KEY));
  const sealedBox = seal(PENDING_BODY, recipientPublic, new Uint8Array(BOX_KEY));
  const m = mountPage({
    answers: happyRoutes({
      [PENDING_SEALED_ROUTE]: async () => response(200, [{
        id: PENDING_ID,
        sealed_box: encodeBase64Padded(sealedBox),
        created_at: '2026-01-02T03:04:05.000Z',
      }]),
      '/v1/sync': async () => response(204),
    }),
  });
  fillForm(m);
  await submitHandler(m)(submitEvent());
  assert.equal(m.els['signin-status'].textContent, ONE_LINE);
  const upload = m.calls.filter((call) => call.url === '/v1/sync');
  assert.equal(upload.length, 1);
  assert.equal(upload[0].body.client_id, PENDING_ID);
  assert.equal(upload[0].body.key_version, 1);
  assert.equal(typeof upload[0].body.encrypted, 'string');
});

test('a pending list 401 through the page wiring still completes the hand-off', async () => {
  const m = mountPage({
    answers: happyRoutes({ [PENDING_SEALED_ROUTE]: async () => response(401, { error: 'refused' }) }),
  });
  fillForm(m);
  await submitHandler(m)(submitEvent());
  assert.equal(m.els['signin-status'].textContent, ONE_FAILURE_LINE);
  assert.equal(/signed out/i.test(m.els['signin-status'].textContent), false);
  assert.equal(m.loc.replaced.length, 1);
});

test('a pending list that throws still completes the hand-off with one failure', async () => {
  const m = mountPage({
    answers: happyRoutes({ [PENDING_SEALED_ROUTE]: async () => { throw new Error('offline'); } }),
  });
  fillForm(m);
  await submitHandler(m)(submitEvent());
  assert.equal(m.els['signin-status'].textContent, ONE_FAILURE_LINE);
  assert.equal(m.loc.replaced.length, 1);
});

test('a keyless account also converts: the pending list follows the keys upload', async () => {
  const crypto = fakeCrypto([], { boxPublicKey: (boxKey) => x25519.getPublicKey(boxKey) });
  const m = mountPage({
    crypto,
    answers: happyRoutes({
      '/v1/auth/login': async () => response(200, loginAnswer({ wrapped_dek: null, wrapped_box_privkey: null }).body),
      '/v1/auth/keys': async () => response(200, { key_version: 1 }),
    }),
  });
  fillForm(m);
  await submitHandler(m)(submitEvent());
  assert.deepEqual(m.calls.map((call) => call.url), [
    '/v1/auth/salt',
    '/v1/auth/login',
    '/v1/auth/keys',
    PENDING_SEALED_ROUTE,
    HANDOFF_START_ROUTE,
    '/v1/auth/logout',
  ]);
  assert.equal(m.els['signin-status'].textContent, ZERO_LINE);
});
