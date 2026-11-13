import test from 'node:test';
import assert from 'node:assert/strict';
import { createPendingSealedNet } from '../../main/resources/web/js/pending-sealed-net.js';

const BASE = 'https://breaker.example';
const TOKEN = 'session-token-7';

function fakeResponse({ status = 200, text = '{}' } = {}) {
  return {
    status,
    headers: { get() { return null; } },
    json: async () => JSON.parse(text),
  };
}

function recordingFetch(response) {
  const calls = [];
  const fetchFn = function (url, init) {
    calls.push({ url, init, self: this });
    return Promise.resolve(response);
  };
  return { fetchFn, calls };
}

test('fetchPendingSealed sends exact GET with bearer token and returns status and body', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '[{"id":"rec-1","sealed_box":"AA==","created_at":"2026-10-11T12:00:00Z"}]' }));
  const answer = await createPendingSealedNet({ baseUrl: BASE, fetchFn }).fetchPendingSealed(TOKEN);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, BASE + '/v1/sync/pending-sealed');
  assert.equal(calls[0].init.method, 'GET');
  assert.deepEqual(calls[0].init.headers, { Authorization: 'Bearer ' + TOKEN });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal('body' in calls[0].init, false);
  assert.deepEqual(answer, { status: 200, body: [{ id: 'rec-1', sealed_box: 'AA==', created_at: '2026-10-11T12:00:00Z' }] });
});

test('fetchPendingSealed gives null body when answer is not JSON', async () => {
  const { fetchFn } = recordingFetch(fakeResponse({ status: 500, text: 'plain words' }));
  assert.deepEqual(await createPendingSealedNet({ baseUrl: BASE, fetchFn }).fetchPendingSealed(TOKEN), { status: 500, body: null });
});

test('the fetch function is called as a plain function with no this', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse());
  await createPendingSealedNet({ baseUrl: BASE, fetchFn }).fetchPendingSealed(TOKEN);
  assert.equal(calls[0].self, undefined);
});

test('a rejecting fetch rejects the call', async () => {
  const net = createPendingSealedNet({ baseUrl: BASE, fetchFn: () => Promise.reject(new Error('offline')) });
  await assert.rejects(net.fetchPendingSealed(TOKEN), /offline/);
});
