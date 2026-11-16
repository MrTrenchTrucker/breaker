import test from 'node:test';
import assert from 'node:assert/strict';
import { createAdminTokensNet } from '../../main/resources/web/js/admin-tokens-net.js';

const BASE = 'https://breaker.example';
const TOKEN = 'session-token-7';

// A fake response: a status, a headers.get that answers nothing, and a json that parses the text
// or throws when the text is not JSON.
function fakeResponse({ status = 200, text = '{}' } = {}) {
  return {
    status,
    headers: { get() { return null; } },
    json: async () => JSON.parse(text),
  };
}

// A fake fetch that records every call with the value of this inside the call, and answers
// with the response.
function recordingFetch(response) {
  const calls = [];
  const fetchFn = function (url, init) {
    calls.push({ url, init, self: this });
    return Promise.resolve(response);
  };
  return { fetchFn, calls };
}

test('listTokens sends the exact GET with bearer token and no body', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '[]' }));
  const answer = await createAdminTokensNet({ baseUrl: BASE, fetchFn }).listTokens(TOKEN);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, BASE + '/v1/admin/agent-tokens');
  assert.equal(calls[0].init.method, 'GET');
  assert.deepEqual(calls[0].init.headers, { Authorization: 'Bearer ' + TOKEN });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal('body' in calls[0].init, false);
  assert.equal('Content-Type' in calls[0].init.headers, false);
  assert.deepEqual(answer, { status: 200, body: [] });
});

test('createToken POSTs the JSON body with the content type and the bearer token', async () => {
  const request = {
    name: 'Syn transcription token',
    owner_username: 'alice@example.com',
    scope: 'transcribe',
    expires_at: null,
  };
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 201, text: '{"token":"atk_1"}' }));
  const answer = await createAdminTokensNet({ baseUrl: BASE, fetchFn }).createToken(TOKEN, request);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, BASE + '/v1/admin/agent-tokens');
  assert.equal(calls[0].init.method, 'POST');
  assert.deepEqual(calls[0].init.headers, {
    Authorization: 'Bearer ' + TOKEN,
    'Content-Type': 'application/json',
  });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal(calls[0].init.body, JSON.stringify(request));
  assert.deepEqual(answer, { status: 201, body: { token: 'atk_1' } });
});

test('revokeToken DELETEs the id encoded into the path with no body and no Content-Type', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 204, text: '' }));
  const answer = await createAdminTokensNet({ baseUrl: BASE, fetchFn }).revokeToken(TOKEN, '7 one/ two');
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, BASE + '/v1/admin/agent-tokens/' + encodeURIComponent('7 one/ two'));
  assert.equal(calls[0].init.method, 'DELETE');
  assert.deepEqual(calls[0].init.headers, { Authorization: 'Bearer ' + TOKEN });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal('body' in calls[0].init, false);
  assert.equal('Content-Type' in calls[0].init.headers, false);
  assert.deepEqual(answer, { status: 204, body: null });
});

test('a null body is returned when the answer is not JSON', async () => {
  const { fetchFn } = recordingFetch(fakeResponse({ status: 503, text: 'plain words' }));
  assert.deepEqual(await createAdminTokensNet({ baseUrl: BASE, fetchFn }).listTokens(TOKEN), {
    status: 503,
    body: null,
  });
});

test('the fetch function is called as a plain function with no this', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse());
  await createAdminTokensNet({ baseUrl: BASE, fetchFn }).revokeToken(TOKEN, '1');
  assert.equal(calls[0].self, undefined);
});

test('a rejecting fetch rejects all three calls', async () => {
  const net = createAdminTokensNet({ baseUrl: BASE, fetchFn: () => Promise.reject(new Error('offline')) });
  await assert.rejects(net.listTokens(TOKEN), /offline/);
  await assert.rejects(net.createToken(TOKEN, { name: 'n', owner_username: 'o' }), /offline/);
  await assert.rejects(net.revokeToken(TOKEN, '1'), /offline/);
});
