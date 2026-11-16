import test from 'node:test';
import assert from 'node:assert/strict';
import { createStatusNet } from '../../main/resources/web/js/status-net.js';

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

test('getStatus sends the exact GET with bearer token and returns status and body', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"services":[]}' }));
  const answer = await createStatusNet({ baseUrl: BASE, fetchFn }).getStatus(TOKEN);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, BASE + '/v1/status');
  assert.equal(calls[0].init.method, 'GET');
  assert.deepEqual(calls[0].init.headers, { Authorization: 'Bearer ' + TOKEN });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal('body' in calls[0].init, false);
  assert.equal('Content-Type' in calls[0].init.headers, false);
  assert.deepEqual(answer, { status: 200, body: { services: [] } });
});

test('getStatus puts the token only in the Authorization header and never in the URL', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"services":[]}' }));
  await createStatusNet({ baseUrl: BASE, fetchFn }).getStatus(TOKEN);
  assert.equal(calls[0].url, BASE + '/v1/status');
  assert.equal(calls[0].url.includes(TOKEN), false);
  assert.equal(calls[0].init.headers.Authorization, 'Bearer ' + TOKEN);
});

test('getStatus gives a null body when the answer is not JSON', async () => {
  const { fetchFn } = recordingFetch(fakeResponse({ status: 200, text: 'plain words' }));
  assert.deepEqual(await createStatusNet({ baseUrl: BASE, fetchFn }).getStatus(TOKEN), { status: 200, body: null });
});

test('getStatus passes a 401 through unchanged for the banner to mark unknown', async () => {
  const { fetchFn } = recordingFetch(fakeResponse({ status: 401, text: '{"error":"signed out"}' }));
  assert.deepEqual(
    await createStatusNet({ baseUrl: BASE, fetchFn }).getStatus(TOKEN),
    { status: 401, body: { error: 'signed out' } },
  );
});

test('a throwing fetchFn rejects and getStatus never retries', async () => {
  let calls = 0;
  const fetchFn = () => {
    calls += 1;
    return Promise.reject(new Error('offline'));
  };
  await assert.rejects(createStatusNet({ baseUrl: BASE, fetchFn }).getStatus(TOKEN), /offline/);
  assert.equal(calls, 1);
});

test('the fetch function is called as a plain function with no this', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse());
  await createStatusNet({ baseUrl: BASE, fetchFn }).getStatus(TOKEN);
  assert.equal(calls[0].self, undefined);
});
