import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  createTranscriptions,
  decryptRowText,
  MESSAGES,
  UNREADABLE,
  transcriptionAad,
} from '../../main/resources/web/js/transcriptions.js';
import { createCipher } from '../../main/resources/web/js/aesgcm.js';
import {
  DEK,
  OTHER_DEK,
  CLIENT_A,
  CLIENT_B,
  aadFor,
  bodyOf,
  sealParts,
  sealFrame,
  frameOf,
  rowOf,
  deletedRowOf,
} from './transcriptions-fixtures.mjs';

// A fake transcriptions-calls object that records every call it is given.
// net methods are plain functions: they are called as (token, ...) with no `this`.
function netOf(answers = {}) {
  const calls = [];
  const answer = (value, arg) => (typeof value === 'function' ? value(arg) : value);
  const net = {
    calls,
    listTranscriptions: async (token, since) => {
      calls.push({ op: 'list', token, since });
      return answer(answers.list, since) ?? { status: 200, body: { items: [], next: null, more: false } };
    },
    listOlder: async (token, olderThan) => {
      calls.push({ op: 'older', token, olderThan });
      return answer(answers.older, olderThan) ?? { status: 200, body: { items: [], next: null, more: false } };
    },
    deleteTranscription: async (token, clientId) => {
      calls.push({ op: 'delete', token, clientId });
      return answer(answers.delete, clientId) ?? { status: 204, body: null };
    },
  };
  return net;
}

function renderOf() {
  const rows = [];
  const messages = [];
  const signedOut = [];
  const render = {
    showRows: (value) => { rows.push(value); },
    showMessage: (text) => { messages.push(text); },
    showSignedOut: () => { signedOut.push(undefined); },
  };
  return { render, rows, messages, signedOut };
}

function flowOf({ net, getToken = () => 'tok-abc', dek = DEK, copy } = {}) {
  const { render, rows, messages, signedOut } = renderOf();
  const flow = createTranscriptions({ getToken, net, dek, copy, format: (iso) => 'fmt:' + iso, render });
  return { flow, rows, messages, signedOut, net };
}

function textsOf(rows) {
  return rows[rows.length - 1].map((row) => row.text);
}

// --- the list and the decryption ------------------------------------------------

test('a 200 list decrypts each row with the DEK and shows the text', async () => {
  const net = netOf({
    list: { status: 200, body: { items: [
      rowOf({ clientId: CLIENT_A, keyVersion: 1, text: 'first words' }),
      rowOf({ clientId: CLIENT_B, keyVersion: 2, text: 'second words' }),
    ], next: null, more: false } },
  });
  const { flow, rows } = flowOf({ net });
  assert.equal(await flow.load(), 'ok');
  assert.equal(rows.length, 1);
  assert.deepEqual(textsOf(rows), ['first words', 'second words']);
  assert.deepEqual(rows[0].map((row) => row.unreadable), [false, false]);
});

test('the list follows since = next while more is true and stops on more false', async () => {
  const pages = {
    null: { status: 200, body: { items: [rowOf({ clientId: CLIENT_A, text: 'page one' })], next: '7-1', more: true } },
    '7-1': { status: 200, body: { items: [rowOf({ clientId: CLIENT_B, text: 'page two' })], next: '7-2', more: true } },
    '7-2': { status: 200, body: { items: [rowOf({ clientId: CLIENT_A, text: 'page three', keyVersion: 2 })], next: '7-3', more: false } },
  };
  const sinceSeen = [];
  const net = netOf({});
  net.listTranscriptions = async (_token, since) => {
    sinceSeen.push(since);
    return pages[String(since)];
  };
  const { flow, rows } = flowOf({ net });
  assert.equal(await flow.load(), 'ok');
  assert.deepEqual(sinceSeen, [null, '7-1', '7-2']);
  assert.deepEqual(textsOf(rows), ['page one', 'page two', 'page three']);
});

test('more true with an empty next ends the pull after that page: one call, rows shown, no list_failed', async () => {
  const net = netOf({
    list: { status: 200, body: { items: [rowOf({ clientId: CLIENT_A, text: 'only page' })], next: '', more: true } },
  });
  const { flow, rows, messages } = flowOf({ net });
  assert.equal(await flow.load(), 'ok');
  assert.equal(net.calls.length, 1, 'an empty cursor must not be followed');
  assert.equal(net.calls[0].op, 'list');
  assert.equal(net.calls[0].since, null);
  assert.deepEqual(textsOf(rows), ['only page']);
  assert.deepEqual(messages, []);
});

test('a row sealed under another client id shows exactly cannot be read and is still present', async () => {
  const net = netOf({
    list: { status: 200, body: { items: [
      rowOf({ clientId: CLIENT_A, keyVersion: 1, text: 'secret', aad: aadFor(CLIENT_B, 1) }),
    ], next: null, more: false } },
  });
  const { flow, rows } = flowOf({ net });
  await flow.load();
  assert.equal(rows[0].length, 1, 'the row must not vanish');
  assert.equal(rows[0][0].text, 'cannot be read');
  assert.equal(rows[0][0].text, UNREADABLE);
  assert.notEqual(rows[0][0].text, '', 'a row that cannot decrypt must never render blank');
  assert.equal(rows[0][0].unreadable, true);
});

test('a row sealed under another key version shows exactly cannot be read', async () => {
  const net = netOf({
    list: { status: 200, body: { items: [
      rowOf({ clientId: CLIENT_A, keyVersion: 1, text: 'secret', aad: aadFor(CLIENT_A, 2) }),
    ], next: null, more: false } },
  });
  const { flow, rows } = flowOf({ net });
  await flow.load();
  assert.equal(rows[0].length, 1);
  assert.equal(rows[0][0].text, 'cannot be read');
});

test('a row sealed under another data key shows exactly cannot be read', async () => {
  const net = netOf({
    list: { status: 200, body: { items: [rowOf({ clientId: CLIENT_A, text: 'secret' })], next: null, more: false } },
  });
  const { flow, rows } = flowOf({ net, dek: OTHER_DEK });
  await flow.load();
  assert.equal(rows[0].length, 1);
  assert.equal(rows[0][0].text, 'cannot be read');
});

test('a row with a flipped tag shows exactly cannot be read', async () => {
  const parts = sealParts(bodyOf('secret'), aadFor(CLIENT_A, 1));
  const tag = new Uint8Array(parts.tag);
  tag[tag.length - 1] ^= 0x01;
  const row = {
    client_id: CLIENT_A,
    change: 7,
    deleted: false,
    key_version: 1,
    encrypted: frameOf({ nonce: parts.nonce, ciphertext: parts.ciphertext, tag }),
    stored_at: '2026-10-11T12:00:05.000Z',
  };
  const net = netOf({ list: { status: 200, body: { items: [row], next: null, more: false } } });
  const { flow, rows } = flowOf({ net });
  await flow.load();
  assert.equal(rows[0].length, 1);
  assert.equal(rows[0][0].text, 'cannot be read');
});

test('a row whose encrypted is not strict base64 shows cannot be read and is still present', async () => {
  const row = rowOf({ clientId: CLIENT_A, text: 'secret' });
  row.encrypted = row.encrypted.substring(0, row.encrypted.length - 1); // drop one padding character
  const net = netOf({ list: { status: 200, body: { items: [row], next: null, more: false } } });
  const { flow, rows } = flowOf({ net });
  await flow.load();
  assert.equal(rows[0].length, 1);
  assert.equal(rows[0][0].text, 'cannot be read');
});

const B64_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

// Rewrites the LAST data character of a padded base64 group into another character
// that differs only in the bits that are not part of the decoded bytes. The string
// changes; the bytes it encodes do not. Which bits those are follows from the pad:
// one '=' leaves a two-byte remainder whose last character carries four real bits,
// two '=' leave a one-byte remainder whose last character carries two.
function nonCanonicalSameBytes(frame) {
  const pad = frame.length - frame.replace(/=+$/, '').length;
  const dataLength = frame.length - pad;
  const usedBits = 2 * (3 - pad);
  const value = B64_CHARS.indexOf(frame.charAt(dataLength - 1));
  const lowMask = (1 << (6 - usedBits)) - 1;
  const next = (value & ~lowMask) | ((value & lowMask) ^ 1);
  return frame.slice(0, dataLength - 1) + B64_CHARS.charAt(next) + frame.slice(dataLength);
}

test('a frame whose base64 is non-canonical is refused while the untouched frame decrypts', async () => {
  const row = rowOf({ clientId: CLIENT_A, keyVersion: 1, text: 'alpha beta' });
  const frame = row.encrypted;
  const variant = nonCanonicalSameBytes(frame);
  assert.notEqual(variant, frame, 'the variant must be a different string');
  assert.equal(atob(variant), atob(frame), 'the variant must encode the same bytes');
  const net = netOf({
    list: { status: 200, body: { items: [
      { ...row, encrypted: variant },
      row,
    ], next: null, more: false } },
  });
  const { flow, rows } = flowOf({ net });
  await flow.load();
  assert.equal(rows[0].length, 2);
  assert.equal(rows[0][0].text, 'cannot be read');
  assert.equal(rows[0][0].unreadable, true);
  assert.equal(rows[0][1].text, 'alpha beta');
});

test('a row at key_version 0 is never decrypted and shows cannot be read', async () => {
  const row = rowOf({ clientId: CLIENT_A, keyVersion: 1, text: 'plain' });
  row.key_version = 0;
  const net = netOf({ list: { status: 200, body: { items: [row], next: null, more: false } } });
  const { flow, rows } = flowOf({ net });
  await flow.load();
  assert.equal(rows[0].length, 1);
  assert.equal(rows[0][0].text, 'cannot be read');
});

test('a delete record is never shown and never decrypted', async () => {
  const live = rowOf({ clientId: CLIENT_A, text: 'live words' });
  const gone = deletedRowOf({ clientId: CLIENT_B });
  const net = netOf({ list: { status: 200, body: { items: [live, gone], next: null, more: false } } });
  const { flow, rows } = flowOf({ net });
  await flow.load();
  assert.deepEqual(rows[0].map((row) => row.id), [CLIENT_A]);
});

test('a malformed live row is present as cannot be read while a good row still decrypts', async () => {
  const good = rowOf({ clientId: CLIENT_A, text: 'good words' });
  const bad = { client_id: CLIENT_B, change: 9, deleted: false, key_version: 1, encrypted: 'not base64', stored_at: '2026-10-11T12:00:07.000Z' };
  const net = netOf({ list: { status: 200, body: { items: [good, bad], next: null, more: false } } });
  const { flow, rows } = flowOf({ net });
  await flow.load();
  assert.deepEqual(rows[0].map((row) => row.id), [CLIENT_A, CLIENT_B]);
  assert.deepEqual(textsOf(rows), ['good words', 'cannot be read']);
});

test('a row whose decrypted body is not an R1 body shows cannot be read, never blank', async () => {
  const row = {
    client_id: CLIENT_A,
    change: 7,
    deleted: false,
    key_version: 1,
    encrypted: null,
    stored_at: '2026-10-11T12:00:05.000Z',
  };
  // Sealed through the real aesgcm module, but the plaintext is not a JSON body.
  row.encrypted = sealFrame('this is not json', aadFor(CLIENT_A, 1));
  const net = netOf({ list: { status: 200, body: { items: [row], next: null, more: false } } });
  const { flow, rows } = flowOf({ net });
  await flow.load();
  assert.equal(rows[0].length, 1);
  assert.equal(rows[0][0].text, 'cannot be read');
});

// --- the list failures -----------------------------------------------------------

test('a 200 whose body is not a TranscriptionPage shows list_failed', async () => {
  const net = netOf({ list: { status: 200, body: [{ client_id: CLIENT_A }] } });
  const { flow, messages } = flowOf({ net });
  assert.equal(await flow.load(), 'list_failed');
  assert.equal(messages[0], MESSAGES.list_failed);
});

test('401 and 403 on the list sign out', async () => {
  for (const status of [401, 403]) {
    const net = netOf({ list: { status, body: null } });
    const { flow, messages, signedOut } = flowOf({ net });
    assert.equal(await flow.load(), 'signed_out', 'status ' + status);
    assert.equal(signedOut.length, 1);
    assert.equal(messages.length, 0);
  }
});

test('503 on the list shows retry', async () => {
  const net = netOf({ list: { status: 503, body: null } });
  const { flow, messages } = flowOf({ net });
  assert.equal(await flow.load(), 'retry');
  assert.equal(messages[0], MESSAGES.retry);
});

test('a thrown fetch on the list shows retry', async () => {
  const net = netOf({});
  net.listTranscriptions = async () => { throw new Error('down'); };
  const { flow, messages } = flowOf({ net });
  assert.equal(await flow.load(), 'retry');
  assert.equal(messages[0], MESSAGES.retry);
});

test('a null or empty token signs out and sends nothing', async () => {
  for (const token of [null, '']) {
    const net = netOf();
    const { flow, signedOut } = flowOf({ net, getToken: () => token });
    assert.equal(await flow.load(), 'signed_out', 'token ' + JSON.stringify(token));
    assert.equal(signedOut.length, 1);
    assert.equal(net.calls.length, 0);
  }
});

// --- loadOlder -------------------------------------------------------------------

test('loadOlder pages older_than and appends the rows to the list', async () => {
  const first = rowOf({ clientId: CLIENT_A, text: 'newest' });
  const olderOne = rowOf({ clientId: CLIENT_B, text: 'older one', keyVersion: 2, storedAt: '2026-10-10T10:00:00.000Z' });
  const olderTwo = rowOf({ clientId: CLIENT_A, text: 'older two', storedAt: '2026-10-09T10:00:00.000Z' });
  const bounds = [];
  const net = netOf({
    list: { status: 200, body: { items: [first], next: null, more: false } },
  });
  net.listOlder = async (_token, olderThan) => {
    bounds.push(olderThan);
    if (olderThan === '1700000000000') {
      return { status: 200, body: { items: [olderOne], next: '1600000000000', more: true } };
    }
    return { status: 200, body: { items: [olderTwo], next: '1500000000000', more: false } };
  };
  const { flow, rows } = flowOf({ net });
  await flow.load();
  assert.equal(await flow.loadOlder('1700000000000'), 'ok');
  assert.deepEqual(bounds, ['1700000000000', '1600000000000']);
  assert.deepEqual(textsOf(rows), ['newest', 'older one', 'older two']);
});

test('loadOlder 401 signs out', async () => {
  const net = netOf({ older: { status: 401, body: null } });
  const { flow, signedOut } = flowOf({ net });
  assert.equal(await flow.loadOlder('1700000000000'), 'signed_out');
  assert.equal(signedOut.length, 1);
});

// --- the AAD oracle --------------------------------------------------------------

// The associated-data rows of shared/modules/api-contracts/examples/record_frame_vectors.json,
// written in pieces of six hex characters. The flow must build exactly these bytes.
const VECTOR_AAD = {
  'client-a-version-1': ['336632', '613963', '31652d', '376234', '642d34', '653836', '2d6131', '63352d', '396430', '653262', '376636', '613438', '0a31'].join(''),
  'client-a-version-2': ['336632', '613963', '31652d', '376234', '642d34', '653836', '2d6131', '63352d', '396430', '653262', '376636', '613438', '0a32'].join(''),
  'client-a-version-10': ['336632', '613963', '31652d', '376234', '642d34', '653836', '2d6131', '63352d', '396430', '653262', '376636', '613438', '0a3130'].join(''),
  'client-b-version-1': ['633661', '316534', '62302d', '353264', '392d34', '663337', '2d3862', '31612d', '653037', '633933', '643561', '326636', '0a31'].join(''),
  'client-b-version-max-int': ['633661', '316534', '62302d', '353264', '392d34', '663337', '2d3862', '31612d', '653037', '633933', '643561', '326636', '0a32313437343833363437'].join(''),
};

function bytesOf(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i += 1) out[i] = parseInt(hex.substring(i * 2, i * 2 + 2), 16);
  return out;
}

test('the AAD the flow builds equals the shared record_frame_vectors rows', () => {
  const cases = [
    [CLIENT_A, 1, VECTOR_AAD['client-a-version-1']],
    [CLIENT_A, 2, VECTOR_AAD['client-a-version-2']],
    [CLIENT_A, 10, VECTOR_AAD['client-a-version-10']],
    [CLIENT_B, 1, VECTOR_AAD['client-b-version-1']],
    [CLIENT_B, 2147483647, VECTOR_AAD['client-b-version-max-int']],
  ];
  for (const [clientId, version, hex] of cases) {
    assert.deepEqual(
      Array.from(transcriptionAad(clientId, version)),
      Array.from(bytesOf(hex)),
      'AAD differs for ' + clientId + ' at version ' + version,
    );
  }
});

test('the AAD is the client id UTF-8, one LF byte, then the decimal key version', () => {
  const aad = transcriptionAad(CLIENT_A, 10);
  const text = new TextDecoder('utf-8', { fatal: true }).decode(aad);
  assert.equal(text, CLIENT_A + '\n10');
  assert.equal(aad.length, CLIENT_A.length + 1 + 2);
  assert.equal(aad[CLIENT_A.length], 0x0a);
});

// --- client-side search ----------------------------------------------------------

test('search filters the decrypted rows and calls no server', async () => {
  const net = netOf({
    list: { status: 200, body: { items: [
      rowOf({ clientId: CLIENT_A, text: 'Truck engine oil' }),
      rowOf({ clientId: CLIENT_B, text: 'Grocery list' }),
      rowOf({ clientId: CLIENT_A, text: 'engine noise at the depot', keyVersion: 2 }),
    ], next: null, more: false } },
  });
  const { flow, rows, net: used } = flowOf({ net });
  await flow.load();
  const callsAfterLoad = used.calls.length;
  const shown = flow.search('ENGINE');
  assert.deepEqual(shown.map((row) => row.text), ['Truck engine oil', 'engine noise at the depot']);
  assert.equal(rows[rows.length - 1].length, 2);
  assert.equal(used.calls.length, callsAfterLoad, 'search must not call the server');
});

test('search is case-insensitive in both directions', async () => {
  const net = netOf({
    list: { status: 200, body: { items: [
      rowOf({ clientId: CLIENT_A, text: 'Alpha beta' }),
      rowOf({ clientId: CLIENT_B, text: 'alpha' }),
    ], next: null, more: false } },
  });
  const { flow } = flowOf({ net });
  await flow.load();
  const byText = (list) => list.map((row) => row.text);
  assert.deepEqual(byText(flow.search('ALPHA')), ['Alpha beta', 'alpha'], 'an upper-case term finds a mixed-case row');
  assert.deepEqual(byText(flow.search('alpha')), ['Alpha beta', 'alpha'], 'a lower-case term finds a mixed-case row');
  assert.deepEqual(byText(flow.search('ALPHA BETA')), ['Alpha beta'], 'the term itself is folded, not just the row');
  assert.deepEqual(byText(flow.search('beta')), ['Alpha beta'], 'a lower-case term finds an upper-case row');
});

test('search with an empty term shows every row', async () => {
  const net = netOf({
    list: { status: 200, body: { items: [
      rowOf({ clientId: CLIENT_A, text: 'one' }),
      rowOf({ clientId: CLIENT_B, text: 'two' }),
    ], next: null, more: false } },
  });
  const { flow } = flowOf({ net });
  await flow.load();
  assert.equal(flow.search('').length, 2);
  assert.equal(flow.search(null).length, 2);
});

// --- copy text -------------------------------------------------------------------

test('copyText copies the given text through the injected copier and shows the copied message', async () => {
  const copied = [];
  const copy = async (text) => { copied.push(text); return 'COPIED'; };
  const net = netOf();
  const { flow, messages } = flowOf({ net, copy });
  assert.equal(await flow.copyText('hello there'), 'copied');
  assert.deepEqual(copied, ['hello there']);
  assert.equal(messages[0], MESSAGES.copied);
});

test('copyText with a copier that answers FAILED shows copy_failed', async () => {
  const net = netOf();
  const { flow, messages } = flowOf({ net, copy: async () => 'FAILED' });
  assert.equal(await flow.copyText('hello'), 'copy_failed');
  assert.equal(messages[0], MESSAGES.copy_failed);
});

test('copyText with a copier that throws shows copy_failed', async () => {
  const net = netOf();
  const { flow, messages } = flowOf({ net, copy: async () => { throw new Error('no clipboard'); } });
  assert.equal(await flow.copyText('hello'), 'copy_failed');
  assert.equal(messages[0], MESSAGES.copy_failed);
});

test('copyText with an empty or non-string text shows copy_failed and never copies', async () => {
  let calls = 0;
  const net = netOf();
  const { flow, messages } = flowOf({ net, copy: async () => { calls += 1; return 'COPIED'; } });
  assert.equal(await flow.copyText(''), 'copy_failed');
  assert.equal(await flow.copyText(null), 'copy_failed');
  assert.equal(calls, 0);
  assert.equal(messages.length, 2);
  assert.equal(messages[0], MESSAGES.copy_failed);
});

test('copyText calls only the injected copier and never the server', async () => {
  const net = netOf();
  const { flow, net: used } = flowOf({ net, copy: async () => 'COPIED' });
  const callsAtStart = used.calls.length;
  assert.equal(await flow.copyText('hello there'), 'copied');
  assert.equal(used.calls.length, callsAtStart, 'copy must not call the server');
});

test('search calls only the render object and never the server', async () => {
  const net = netOf({
    list: { status: 200, body: { items: [
      rowOf({ clientId: CLIENT_A, text: 'one' }),
      rowOf({ clientId: CLIENT_B, text: 'two' }),
    ], next: null, more: false } },
  });
  const { flow, net: used } = flowOf({ net });
  await flow.load();
  const callsAfterLoad = used.calls.length;
  flow.search('one');
  assert.equal(used.calls.length, callsAfterLoad, 'search must not call the server');
});

test('no transcriptions source file exposes a delete-all call', () => {
  const files = [
    new URL('../../main/resources/web/js/transcriptions.js', import.meta.url),
    new URL('../../main/resources/web/js/transcriptions-net.js', import.meta.url),
    new URL('../../main/resources/web/js/transcriptions-view.js', import.meta.url),
  ];
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    assert.equal(source.includes('deleteAll'), false, file.pathname + ' contains deleteAll');
  }
});

// --- delete one ------------------------------------------------------------------

test('deleteOne sends DELETE with the client id and reloads the list on 204', async () => {
  const net = netOf({
    list: { status: 200, body: { items: [rowOf({ clientId: CLIENT_A, text: 'doomed' })], next: null, more: false } },
    delete: { status: 204, body: null },
  });
  const { flow, net: used, rows } = flowOf({ net });
  await flow.load();
  const listsBefore = used.calls.filter((call) => call.op === 'list').length;
  assert.equal(await flow.deleteOne(CLIENT_A), 'ok');
  const deletes = used.calls.filter((call) => call.op === 'delete');
  assert.equal(deletes.length, 1);
  assert.equal(deletes[0].clientId, CLIENT_A);
  assert.equal(used.calls.filter((call) => call.op === 'list').length, listsBefore + 1, 'a 204 reloads the list');
  assert.equal(rows.length, 2);
});

test('deleteOne 401 and 403 sign out', async () => {
  for (const status of [401, 403]) {
    const net = netOf({ delete: { status, body: null } });
    const { flow, signedOut, messages } = flowOf({ net });
    assert.equal(await flow.deleteOne(CLIENT_A), 'signed_out', 'status ' + status);
    assert.equal(signedOut.length, 1);
    assert.equal(messages.length, 0);
  }
});

test('deleteOne 503 shows retry and does not reload', async () => {
  const net = netOf({ delete: { status: 503, body: null } });
  const { flow, messages, net: used } = flowOf({ net });
  assert.equal(await flow.deleteOne(CLIENT_A), 'retry');
  assert.equal(messages[0], MESSAGES.retry);
  assert.equal(used.calls.filter((call) => call.op === 'list').length, 0);
});

// --- the fixed tables and the source scan ----------------------------------------

test('MESSAGES holds the fixed sentences and UNREADABLE is exactly cannot be read', () => {
  assert.equal(UNREADABLE, 'cannot be read');
  assert.equal(MESSAGES.signed_out, 'You are signed out. Sign in again to see your transcriptions.');
  assert.equal(MESSAGES.retry, 'The server is unavailable. Try again in a moment.');
  assert.equal(MESSAGES.list_failed, 'The list could not be loaded. Try again in a moment.');
  assert.equal(MESSAGES.empty, 'No transcriptions to show.');
  assert.equal(MESSAGES.copied, 'Copied to the clipboard.');
  assert.equal(MESSAGES.copy_failed, 'The text could not be copied.');
});

test('the three transcriptions source files contain no DOM or storage tokens', () => {
  const files = [
    new URL('../../main/resources/web/js/transcriptions.js', import.meta.url),
    new URL('../../main/resources/web/js/transcriptions-net.js', import.meta.url),
    new URL('../../main/resources/web/js/transcriptions-view.js', import.meta.url),
  ];
  const tokens = ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'localStorage', 'sessionStorage', 'document.cookie', 'window.confirm'];
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    for (const token of tokens) {
      assert.equal(source.includes(token), false, file.pathname + ' contains forbidden token: ' + token);
    }
  }
});

// --- the strict body reader in decryptRowText -----------------------------------
//
// decryptRowText reads the decrypted plaintext with isStrictBody: the writer's
// decodeBody level (six members, types and ranges, source local or server, any
// key order). A plaintext that is not a valid R1 body is refused, so the row
// keeps its place and shows the fixed unreadable text.

// The six members as a JSON text, in the writer's order. An override replaces
// one member's RAW JSON text, so a test can plant a bad token.
function plainBody(overrides = {}) {
  const members = {
    v: '1',
    text: JSON.stringify('the words'),
    source: JSON.stringify('local'),
    model: JSON.stringify('test-model'),
    duration_ms: '1200',
    created_at: JSON.stringify('2026-10-11T12:00:00.000Z'),
    ...overrides,
  };
  return '{' + Object.keys(members).map((key) => JSON.stringify(key) + ':' + members[key]).join(',') + '}';
}

// Seal raw bytes (not a string) under the row's own AAD through the real cipher.
function sealRawFrame(bytes, aad) {
  return frameOf(createCipher(DEK).seal(bytes, aad));
}

// A live row whose encrypted field seals the given plaintext (string or bytes).
function rowSealing(plaintext, { clientId = CLIENT_A, keyVersion = 1 } = {}) {
  const bytes = typeof plaintext === 'string' ? new TextEncoder().encode(plaintext) : plaintext;
  return {
    client_id: clientId,
    change: 7,
    deleted: false,
    key_version: keyVersion,
    encrypted: sealRawFrame(bytes, aadFor(clientId, keyVersion)),
    stored_at: '2026-10-11T12:00:05.000Z',
  };
}

// Load a one-row list and hand back the texts the view was shown.
async function textsAfterLoad(plaintext) {
  const net = netOf({
    list: { status: 200, body: { items: [rowSealing(plaintext)], next: null, more: false } },
  });
  const { flow, rows } = flowOf({ net });
  await flow.load();
  return rows[0].map((row) => row.text);
}

test('a valid local body reads exactly as before', async () => {
  assert.deepEqual(await textsAfterLoad(plainBody()), ['the words']);
});

test('a valid server body reads', async () => {
  assert.deepEqual(await textsAfterLoad(plainBody({ source: JSON.stringify('server') })), ['the words']);
});

test('a valid body with the six keys in any order reads', async () => {
  const reordered = '{"text":"the words","v":1,"source":"local","model":"test-model",'
    + '"duration_ms":1200,"created_at":"2026-10-11T12:00:00.000Z"}';
  assert.deepEqual(await textsAfterLoad(reordered), ['the words']);
});

test('the hole: a plaintext that is valid JSON with a string text but not an R1 body is unreadable', async () => {
  const net = netOf({
    list: { status: 200, body: { items: [rowSealing('{"text":"x"}')], next: null, more: false } },
  });
  const { flow, rows } = flowOf({ net });
  await flow.load();
  assert.equal(rows[0].length, 1, 'the row must not vanish');
  assert.equal(rows[0][0].text, 'cannot be read');
  assert.equal(rows[0][0].text, UNREADABLE);
  assert.equal(rows[0][0].unreadable, true);
});

// Every plaintext the strict reader refuses, through the row path. Each shape is
// its own test so a failure names the shape.
const REFUSED_PLAINTEXTS = [
  ['a duplicated key', '{"v":1,"text":"the words","source":"local","model":"test-model","duration_ms":1200,"created_at":"2026-10-11T12:00:00.000Z","v":1}'],
  ['an unknown key', '{"v":1,"text":"the words","source":"local","model":"test-model","duration_ms":1200,"created_at":"2026-10-11T12:00:00.000Z","resave":true}'],
  ['a missing key', '{"v":1,"text":"the words","source":"local","duration_ms":1200,"created_at":"2026-10-11T12:00:00.000Z"}'],
  ['a top level that is not an object', '[{"text":"the words"}]'],
  ['v that is not the integer token 1', plainBody({ v: '"1"' })],
  ['text that is not a string', plainBody({ text: '5' })],
  ['a source that is not local or server', plainBody({ source: JSON.stringify('Server') })],
  ['a model that is not a string', plainBody({ model: '5' })],
  ['a duration_ms that is not an integer token', plainBody({ duration_ms: '1.5' })],
  ['a duration_ms above the maximum', plainBody({ duration_ms: '9007199254740992' })],
  ['a created_at that is not the exact form', plainBody({ created_at: JSON.stringify('2026-10-11T12:00:00Z') })],
  ['a created_at that is not a real calendar date', plainBody({ created_at: JSON.stringify('2026-02-30T12:00:00.000Z') })],
  ['a member value that is an object', plainBody({ model: '{"a":1}' })],
  ['a member value that is an array', plainBody({ model: '[1]' })],
  ['text after the object', plainBody() + ' x'],
  ['a lone surrogate escape', plainBody({ text: JSON.stringify('a\ud800b') })],
  ['a raw control character inside a string', '{"v":1,"text":"a\u0001b","source":"local","model":"test-model","duration_ms":1200,"created_at":"2026-10-11T12:00:00.000Z"}'],
  ['invalid UTF-8 bytes', Uint8Array.of(0x7b, 0xff, 0x7d)],
  ['a leading BOM', Uint8Array.of(0xef, 0xbb, 0xbf, ...new TextEncoder().encode(plainBody()))],
];

for (const [shape, plaintext] of REFUSED_PLAINTEXTS) {
  test('a plaintext with ' + shape + ' shows cannot be read and keeps the row', async () => {
    const net = netOf({
      list: { status: 200, body: { items: [rowSealing(plaintext)], next: null, more: false } },
    });
    const { flow, rows } = flowOf({ net });
    await flow.load();
    assert.equal(rows[0].length, 1, 'the row must not vanish');
    assert.equal(rows[0][0].text, 'cannot be read', shape);
    assert.equal(rows[0][0].unreadable, true, shape);
  });
}

test('decryptRowText returns null for a plaintext that is not a valid R1 body', () => {
  const cipher = { open: () => new TextEncoder().encode('{"text":"x"}') };
  const row = rowSealing('{"text":"x"}');
  assert.equal(decryptRowText(row, cipher), null);
});

test('decryptRowText hands the frame and the row AAD to cipher.open', () => {
  const seen = [];
  const plaintext = new TextEncoder().encode(plainBody());
  const cipher = {
    open: (frame, aad) => {
      seen.push({ frame, aad });
      return plaintext;
    },
  };
  const row = rowSealing(plainBody(), { clientId: CLIENT_A, keyVersion: 3 });
  assert.equal(decryptRowText(row, cipher), 'the words');
  assert.equal(seen.length, 1);
  assert.deepEqual(Array.from(seen[0].aad), Array.from(aadFor(CLIENT_A, 3)));
  assert.ok(seen[0].frame.nonce instanceof Uint8Array);
  assert.equal(seen[0].frame.nonce.length, 12);
  assert.ok(seen[0].frame.ciphertext instanceof Uint8Array);
  assert.ok(seen[0].frame.tag instanceof Uint8Array);
  assert.equal(seen[0].frame.tag.length, 16);
});
