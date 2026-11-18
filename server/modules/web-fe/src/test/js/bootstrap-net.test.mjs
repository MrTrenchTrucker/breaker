import test from 'node:test';
import assert from 'node:assert/strict';
import { MESSAGES, createBootstrapNet } from '../../main/resources/web/js/bootstrap-net.js';
import { decodeBase64Padded } from '../../main/resources/web/js/signin-link.js';
import { createBootstrapCrypto } from '../../main/resources/web/js/bootstrap-crypto.js';

const BASE = 'https://breaker.example';
const JSON_TYPE = 'application/json';
const TOKEN = 'session sample text';
const patterned = (base, size) => Uint8Array.from({ length: size }, (_, i) => (base + i) & 255);
// The three members the crypto module hands over: 60, 60 and 32 bytes.
const MEMBERS = {
  wrappedDek: patterned(0x50, 60),
  wrappedBoxPrivkey: patterned(0x70, 60),
  boxPubkey: patterned(0x90, 32),
};
const BODY_KEYS = ['wrapped_dek', 'wrapped_box_privkey', 'box_pubkey'];
const RESET_PENDING_TEXT = 'The account is waiting for a reset to be completed';

// A fake response: a status, a headers.get that answers nothing, and a json that parses the text.
function fakeResponse({ status = 200, text = '{}' } = {}) {
  return { status, headers: { get() { return null; } }, json: async () => JSON.parse(text) };
}

// A fake fetch that records every call with the value of this inside the call, and answers with the response.
function recordingFetch(response) {
  const calls = [];
  const fetchFn = function (url, init) {
    calls.push({ url, init, self: this });
    return Promise.resolve(response);
  };
  return { fetchFn, calls };
}

function netWith(response) {
  const { fetchFn, calls } = recordingFetch(response);
  return { net: createBootstrapNet({ baseUrl: BASE, fetchFn }), calls };
}

test('a 200 answer returns ok with the key version and posts exactly the three members', async () => {
  const { net, calls } = netWith(fakeResponse({ status: 200, text: '{"key_version":1}' }));
  assert.deepEqual(await net.postKeys(TOKEN, MEMBERS), { ok: true, keyVersion: 1 });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, BASE + '/v1/auth/keys');
  assert.equal(calls[0].init.method, 'POST');
  assert.deepEqual(calls[0].init.headers, { 'Content-Type': JSON_TYPE, Authorization: 'Bearer ' + TOKEN });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  const body = JSON.parse(calls[0].init.body);
  assert.deepEqual(Object.keys(body).sort(), [...BODY_KEYS].sort());
  assert.equal(body.wrapped_dek.length, 80);
  assert.equal(body.wrapped_box_privkey.length, 80);
  assert.equal(body.box_pubkey.length, 44);
  assert.deepEqual(decodeBase64Padded(body.wrapped_dek, 60), MEMBERS.wrappedDek);
  assert.deepEqual(decodeBase64Padded(body.wrapped_box_privkey, 60), MEMBERS.wrappedBoxPrivkey);
  assert.deepEqual(decodeBase64Padded(body.box_pubkey, 32), MEMBERS.boxPubkey);
});

test('the posted members are the ones the crypto module generated', async () => {
  const dek = patterned(0xa0, 32);
  const boxPrivate = patterned(0xc0, 32);
  const queue = [dek, boxPrivate, patterned(0x50, 12), patterned(0x60, 12)];
  let at = 0;
  const generated = createBootstrapCrypto({ random: () => queue[at++] }).bootstrap(patterned(0x10, 32));
  const { net, calls } = netWith(fakeResponse({ status: 200, text: '{"key_version":7}' }));
  const answer = await net.postKeys(TOKEN, generated);
  assert.deepEqual(answer, { ok: true, keyVersion: 7 });
  const body = JSON.parse(calls[0].init.body);
  assert.deepEqual(decodeBase64Padded(body.wrapped_dek, 60), generated.wrappedDek);
  assert.deepEqual(decodeBase64Padded(body.wrapped_box_privkey, 60), generated.wrappedBoxPrivkey);
  assert.deepEqual(decodeBase64Padded(body.box_pubkey, 32), generated.boxPubkey);
});

for (const status of [400, 401, 403, 413]) {
  test('status ' + status + ' gives one fixed message and nothing is reported stored', async () => {
    const { net } = netWith(fakeResponse({ status, text: '{"error":"x"}' }));
    const answer = await net.postKeys(TOKEN, MEMBERS);
    assert.equal(answer.ok, false);
    assert.equal(typeof answer.code, 'string');
    assert.equal(answer.message, MESSAGES[answer.code]);
    assert.notEqual(answer.message, undefined);
  });
}

test('status 409 with the already-keyed body gives the conflict message', async () => {
  const { net } = netWith(fakeResponse({ status: 409, text: '{"error":"conflict","message":"The account already has keys"}' }));
  const answer = await net.postKeys(TOKEN, MEMBERS);
  assert.deepEqual(answer, { ok: false, code: 'conflict', message: MESSAGES.conflict });
});

test('status 409 with the reset-pending body gives the reset message instead', async () => {
  const { net } = netWith(fakeResponse({ status: 409, text: JSON.stringify({ error: 'conflict', message: RESET_PENDING_TEXT }) }));
  const answer = await net.postKeys(TOKEN, MEMBERS);
  assert.deepEqual(answer, { ok: false, code: 'reset_pending', message: MESSAGES.reset_pending });
});

test('status 409 with no body gives the conflict message', async () => {
  const { net } = netWith(fakeResponse({ status: 409, text: 'plain words' }));
  assert.deepEqual(await net.postKeys(TOKEN, MEMBERS), { ok: false, code: 'conflict', message: MESSAGES.conflict });
});

for (const status of [200, 204, 418, 503]) {
  test('status ' + status + ' outside the named set gives the unknown message', async () => {
    const { net } = netWith(fakeResponse({ status, text: '{}' }));
    const answer = await net.postKeys(TOKEN, MEMBERS);
    assert.deepEqual(answer, { ok: false, code: 'unknown', message: MESSAGES.unknown });
  });
}

test('a 200 answer without an integer key version gives the unknown message', async () => {
  const { net } = netWith(fakeResponse({ status: 200, text: '{}' }));
  assert.deepEqual(await net.postKeys(TOKEN, MEMBERS), { ok: false, code: 'unknown', message: MESSAGES.unknown });
});

for (const [name, text] of [
  ['zero', '{"key_version":0}'],
  ['negative', '{"key_version":-3}'],
  ['fractional', '{"key_version":1.5}'],
  ['text', '{"key_version":"1"}'],
  ['missing', '{}'],
]) {
  test('a 200 answer with key_version ' + name + ' gives the unknown message', async () => {
    const { net } = netWith(fakeResponse({ status: 200, text }));
    assert.deepEqual(await net.postKeys(TOKEN, MEMBERS), { ok: false, code: 'unknown', message: MESSAGES.unknown });
  });
}

test('status 413 gives exactly the too large message', async () => {
  const { net } = netWith(fakeResponse({ status: 413, text: '{}' }));
  assert.deepEqual(await net.postKeys(TOKEN, MEMBERS), { ok: false, code: 'too_large', message: MESSAGES.too_large });
});

test('a rejecting fetch gives the network message', async () => {
  const net = createBootstrapNet({ baseUrl: BASE, fetchFn: () => Promise.reject(new Error('offline')) });
  assert.deepEqual(await net.postKeys(TOKEN, MEMBERS), { ok: false, code: 'network', message: MESSAGES.network });
});

test('a throwing fetch gives the network message', async () => {
  const net = createBootstrapNet({ baseUrl: BASE, fetchFn: () => { throw new Error('offline'); } });
  assert.deepEqual(await net.postKeys(TOKEN, MEMBERS), { ok: false, code: 'network', message: MESSAGES.network });
});

for (const [name, token] of [['empty', ''], ['not text', 7]]) {
  test('a session token that is ' + name + ' is refused before any call', async () => {
    const { net, calls } = netWith(fakeResponse({ status: 200, text: '{"key_version":1}' }));
    assert.deepEqual(await net.postKeys(token, MEMBERS), { ok: false, code: 'unauthorized', message: MESSAGES.unauthorized });
    assert.equal(calls.length, 0);
  });
}

for (const [name, member, size] of [
  ['wrapped_dek', 'wrappedDek', 59],
  ['wrapped_box_privkey', 'wrappedBoxPrivkey', 61],
  ['box_pubkey', 'boxPubkey', 31],
]) {
  test('a ' + member + ' of ' + size + ' bytes is refused before any call', async () => {
    const members = { ...MEMBERS, [member]: patterned(0x50, size) };
    const { net, calls } = netWith(fakeResponse({ status: 200, text: '{"key_version":1}' }));
    assert.deepEqual(await net.postKeys(TOKEN, members), { ok: false, code: 'bad_request', message: MESSAGES.bad_request });
    assert.equal(calls.length, 0);
  });
}

for (const [name, members] of [
  ['null', null],
  ['text', 'not a member set'],
  ['an array', [MEMBERS.wrappedDek]],
]) {
  test('a member set that is ' + name + ' is refused before any call', async () => {
    const { net, calls } = netWith(fakeResponse({ status: 200, text: '{"key_version":1}' }));
    assert.deepEqual(await net.postKeys(TOKEN, members), { ok: false, code: 'bad_request', message: MESSAGES.bad_request });
    assert.equal(calls.length, 0);
  });
}

test('exports verified', async () => {
  const m = await import('../../main/resources/web/js/bootstrap-net.js');
  assert.equal(typeof m.createBootstrapNet, 'function');
  assert.equal(typeof m.MESSAGES, 'object');
  assert.equal(Object.isFrozen(m.MESSAGES), true);
});
