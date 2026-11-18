import test from 'node:test';
import assert from 'node:assert/strict';
import { createTranscriptionsNet } from '../../main/resources/web/js/transcriptions-net.js';

const BASE = 'https://breaker.example';
const TOKEN = 'fake-session-token';
const CLIENT = '3f2a9c1e-7b4d-4e86-a1c5-9d0e2b7f6a48';

// A fake response: a status and a json that parses the text or throws when the
// text is not JSON (a 204 has no body, so its json throws).
function fakeResponse({ status = 200, text = '{}' } = {}) {
  return {
    status,
    json: async () => JSON.parse(text),
  };
}

// A fake fetch that records every call with the value of this inside the call.
function recordingFetch(response) {
  const calls = [];
  const fetchFn = function (url, init) {
    calls.push({ url, init, self: this });
    return Promise.resolve(response);
  };
  return { fetchFn, calls };
}

test('listTranscriptions sends the exact GET with bearer token and no body', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"items":[],"next":null,"more":false}' }));
  const answer = await createTranscriptionsNet({ baseUrl: BASE, fetchFn }).listTranscriptions(TOKEN);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, BASE + '/v1/transcriptions');
  assert.equal(calls[0].init.method, 'GET');
  assert.deepEqual(calls[0].init.headers, { Authorization: 'Bearer ' + TOKEN });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal('body' in calls[0].init, false);
  assert.equal('Content-Type' in calls[0].init.headers, false);
  assert.equal(calls[0].url.includes(TOKEN), false);
  assert.deepEqual(answer, { status: 200, body: { items: [], next: null, more: false } });
});

test('listTranscriptions sends the since cursor in the query, encoded', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"items":[]}' }));
  await createTranscriptionsNet({ baseUrl: BASE, fetchFn }).listTranscriptions(TOKEN, '7-42');
  assert.equal(calls[0].url, BASE + '/v1/transcriptions?since=7-42');
  assert.equal(calls[0].init.method, 'GET');
});

test('listTranscriptions with no cursor sends no query at all', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"items":[]}' }));
  await createTranscriptionsNet({ baseUrl: BASE, fetchFn }).listTranscriptions(TOKEN, null);
  assert.equal(calls[0].url, BASE + '/v1/transcriptions');
});

test('listOlder sends the required older_than in the query, encoded', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"items":[],"next":"123-4","more":true}' }));
  const answer = await createTranscriptionsNet({ baseUrl: BASE, fetchFn }).listOlder(TOKEN, '1700000000000-9');
  assert.equal(calls[0].url, BASE + '/v1/transcriptions/older?older_than=1700000000000-9');
  assert.equal(calls[0].init.method, 'GET');
  assert.deepEqual(calls[0].init.headers, { Authorization: 'Bearer ' + TOKEN });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal('body' in calls[0].init, false);
  assert.deepEqual(answer, { status: 200, body: { items: [], next: '123-4', more: true } });
});

test('listOlder encodes a bound holding & and = as one query value', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"items":[]}' }));
  await createTranscriptionsNet({ baseUrl: BASE, fetchFn }).listOlder(TOKEN, '1700000000000&x=1');
  assert.equal(calls[0].url, BASE + '/v1/transcriptions/older?older_than=1700000000000%26x%3D1');
  assert.equal(calls[0].url.split('&').length, 1, 'the bound must stay one query value');
  assert.equal(calls[0].url.includes('?older_than=1700000000000&'), false);
  assert.equal(calls[0].init.method, 'GET');
});

test('deleteTranscription DELETEs the client id in the path with no body', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 204, text: '' }));
  const answer = await createTranscriptionsNet({ baseUrl: BASE, fetchFn }).deleteTranscription(TOKEN, CLIENT);
  assert.equal(calls[0].url, BASE + '/v1/transcriptions/' + CLIENT);
  assert.equal(calls[0].init.method, 'DELETE');
  assert.deepEqual(calls[0].init.headers, { Authorization: 'Bearer ' + TOKEN });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal('body' in calls[0].init, false);
  assert.equal('Content-Type' in calls[0].init.headers, false);
  // A 204 has no body: the answer is a null body, never a parse error.
  assert.deepEqual(answer, { status: 204, body: null });
});

test('the client id is encoded into the path', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 204, text: '' }));
  await createTranscriptionsNet({ baseUrl: BASE, fetchFn }).deleteTranscription(TOKEN, 'a/b?c');
  assert.equal(calls[0].url, BASE + '/v1/transcriptions/' + encodeURIComponent('a/b?c'));
});

test('a non-JSON answer gives a null body for all three calls', async () => {
  const net = createTranscriptionsNet({ baseUrl: BASE, fetchFn: recordingFetch(fakeResponse({ status: 500, text: 'plain words' })).fetchFn });
  assert.deepEqual(await net.listTranscriptions(TOKEN), { status: 500, body: null });
  assert.deepEqual(await net.listOlder(TOKEN, '1700000000000'), { status: 500, body: null });
  assert.deepEqual(await net.deleteTranscription(TOKEN, CLIENT), { status: 500, body: null });
});

test('the fetch function is called as a plain function with no this', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 204, text: '' }));
  await createTranscriptionsNet({ baseUrl: BASE, fetchFn }).deleteTranscription(TOKEN, CLIENT);
  assert.equal(calls[0].self, undefined);
});

test('a rejecting fetch rejects all three calls', async () => {
  const net = createTranscriptionsNet({ baseUrl: BASE, fetchFn: () => Promise.reject(new Error('offline')) });
  await assert.rejects(net.listTranscriptions(TOKEN), /offline/);
  await assert.rejects(net.listOlder(TOKEN, '1700000000000'), /offline/);
  await assert.rejects(net.deleteTranscription(TOKEN, CLIENT), /offline/);
});

test('the net module exposes exactly the three single-row calls and no delete-all surface', async () => {
  const fs = await import('node:fs');
  const source = fs.readFileSync(new URL('../../main/resources/web/js/transcriptions-net.js', import.meta.url), 'utf8');
  assert.match(source, /return \{ listTranscriptions, listOlder, deleteTranscription \};/);
  const deleteCalls = [...source.matchAll(/delete([A-Za-z]+)/g)].map((match) => match[0]);
  assert.deepEqual([...new Set(deleteCalls)].sort(), ['deleteTranscription']);
});
