import test from 'node:test';
import assert from 'node:assert/strict';
import { createNet } from '../../main/resources/web/js/signin-net.js';
import { CHALLENGE_TEXT, sessionText, settle } from './signin-fixtures.mjs';

const usernameText = 'account one';
const verifierText = 'verifier sample text';
const sealedText = 'sealed sample text';
const JSON_TYPE = 'application/json';

// A fake response: a status, a headers.get that answers only Retry-After, and a json that parses the text.
function fakeResponse({ status = 200, text = '{}', retryAfter } = {}) {
  return {
    status,
    headers: { get(name) { return name === 'Retry-After' ? (retryAfter ?? null) : null; } },
    json: async () => JSON.parse(text),
  };
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

test('getSalt sends the exact POST with no authorization and returns status and body', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ text: '{"salt":"abc"}' }));
  const answer = await createNet(fetchFn).getSalt(usernameText);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, '/v1/auth/salt');
  assert.equal(calls[0].init.method, 'POST');
  assert.deepEqual(calls[0].init.headers, { 'Content-Type': JSON_TYPE });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal(calls[0].init.body, JSON.stringify({ username: usernameText }));
  assert.deepEqual(answer, { status: 200, body: { salt: 'abc' } });
});

test('login sends the username and the verifier and passes the Retry-After text through', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 423, text: '{"error":"locked"}', retryAfter: '30' }));
  const answer = await createNet(fetchFn).login(usernameText, verifierText);
  assert.equal(calls[0].url, '/v1/auth/login');
  assert.equal(calls[0].init.method, 'POST');
  assert.deepEqual(calls[0].init.headers, { 'Content-Type': JSON_TYPE });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal(calls[0].init.body, JSON.stringify({ username: usernameText, auth_verifier: verifierText }));
  assert.deepEqual(answer, { status: 423, body: { error: 'locked' }, retryAfter: '30' });
});

test('login gives a null Retry-After when the header is absent', async () => {
  const { fetchFn } = recordingFetch(fakeResponse({ text: '{"token":"t"}' }));
  const answer = await createNet(fetchFn).login(usernameText, verifierText);
  assert.equal(answer.retryAfter, null);
  assert.deepEqual(answer, { status: 200, body: { token: 't' }, retryAfter: null });
});

test('getSalt and login give a null body when the response is not JSON', async () => {
  const net = createNet(recordingFetch(fakeResponse({ status: 500, text: 'plain words' })).fetchFn);
  assert.deepEqual(await net.getSalt(usernameText), { status: 500, body: null });
  assert.deepEqual(await net.login(usernameText, verifierText), { status: 500, body: null, retryAfter: null });
});

test('startHandoff sends exactly the two keys and the bearer token', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 201, text: '{"code":"c"}' }));
  const answer = await createNet(fetchFn).startHandoff(CHALLENGE_TEXT, sealedText, sessionText);
  assert.equal(calls[0].url, '/v1/auth/handoff/start');
  assert.equal(calls[0].init.method, 'POST');
  assert.deepEqual(calls[0].init.headers, { 'Content-Type': JSON_TYPE, Authorization: 'Bearer ' + sessionText });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal(calls[0].init.body, JSON.stringify({ challenge: CHALLENGE_TEXT, sealed_package: sealedText }));
  assert.deepEqual(answer, { status: 201, body: { code: 'c' } });
});

test('logout sends a bearer POST with no body key and keepalive and resolves with nothing', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 204 }));
  assert.equal(await createNet(fetchFn).logout(sessionText), undefined);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/v1/auth/logout');
  assert.equal(calls[0].init.method, 'POST');
  assert.deepEqual(calls[0].init.headers, { 'Content-Type': JSON_TYPE, Authorization: 'Bearer ' + sessionText });
  assert.equal(calls[0].init.keepalive, true);
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal('body' in calls[0].init, false);
});

test('a rejecting fetch rejects getSalt login and startHandoff', async () => {
  const net = createNet(() => Promise.reject(new Error('offline')));
  await assert.rejects(net.getSalt(usernameText), /offline/);
  await assert.rejects(net.login(usernameText, verifierText), /offline/);
  await assert.rejects(net.startHandoff(CHALLENGE_TEXT, sealedText, sessionText), /offline/);
});

test('a throwing fetch rejects getSalt login and startHandoff', async () => {
  const net = createNet(() => { throw new Error('offline'); });
  await assert.rejects(net.getSalt(usernameText), /offline/);
  await assert.rejects(net.login(usernameText, verifierText), /offline/);
  await assert.rejects(net.startHandoff(CHALLENGE_TEXT, sealedText, sessionText), /offline/);
});

test('logout swallows a rejecting fetch', async () => {
  const net = createNet(() => Promise.reject(new Error('offline')));
  assert.equal(await net.logout(sessionText), undefined);
});

test('logout swallows a throwing fetch', async () => {
  const net = createNet(() => { throw new Error('offline'); });
  assert.equal(await net.logout(sessionText), undefined);
});

test('logout does not wait for a fetch that never settles', async () => {
  const net = createNet(() => new Promise(() => {}));
  let resolved = false;
  net.logout(sessionText).then(() => { resolved = true; });
  await settle();
  assert.equal(resolved, true);
});
