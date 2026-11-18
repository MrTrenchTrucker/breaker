import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mountTranscriptions } from '../../main/resources/web/js/transcriptions-view.js';
import { MESSAGES } from '../../main/resources/web/js/transcriptions.js';
import { DEK, CLIENT_A, CLIENT_B, rowOf, deletedRowOf } from './transcriptions-fixtures.mjs';

const HOSTILE = '<img src=x onerror=alert(1)>';

// The page this view mounts into installs a minimal document. The view builds its
// own elements with document.createElement and never looks anything up by id and
// never touches window.
globalThis.document = {
  createElement(tag) {
    return fakeElement(tag);
  },
};

// A plain element: textContent records every write, the markup setters throw, so
// a view that writes markup fails the test that drives it.
function fakeElement(tag) {
  const element = {
    tag,
    children: [],
    listeners: [],
    writes: [],
    text: '',
    value: '',
    type: '',
    placeholder: '',
    disabled: false,
    hidden: false,
  };
  Object.defineProperty(element, 'textContent', {
    enumerable: true,
    get() { return element.text; },
    set(next) {
      element.writes.push(next);
      element.text = next;
    },
  });
  Object.defineProperty(element, 'innerHTML', {
    get: () => '',
    set: () => { throw new Error('innerHTML was set'); },
  });
  Object.defineProperty(element, 'outerHTML', {
    get: () => '',
    set: () => { throw new Error('outerHTML was set'); },
  });
  element.insertAdjacentHTML = () => { throw new Error('insertAdjacentHTML was called'); };
  element.appendChild = (child) => { element.children.push(child); return child; };
  Object.defineProperty(element, 'firstChild', {
    get() { return element.children[0] ?? null; },
  });
  element.removeChild = (child) => {
    const index = element.children.indexOf(child);
    if (index >= 0) element.children.splice(index, 1);
    return child;
  };
  element.addEventListener = (type, fn) => { element.listeners.push({ type, fn }); };
  element.click = () => {
    for (const listener of element.listeners) {
      if (listener.type === 'click') listener.fn();
    }
  };
  element.input = () => {
    for (const listener of element.listeners) {
      if (listener.type === 'input') listener.fn();
    }
  };
  return element;
}

// A fake transcriptions-calls object that records every call it is given.
function netOf(answers = {}) {
  const calls = [];
  const net = {
    calls,
    listTranscriptions: async (token, since) => {
      calls.push({ op: 'list', token, since });
      return answers.list ?? { status: 200, body: { items: [], next: null, more: false } };
    },
    listOlder: async (token, olderThan) => {
      calls.push({ op: 'older', token, olderThan });
      return answers.older ?? { status: 200, body: { items: [], next: null, more: false } };
    },
    deleteTranscription: async (token, clientId) => {
      calls.push({ op: 'delete', token, clientId });
      return answers.delete ?? { status: 204, body: null };
    },
  };
  return net;
}

async function flush() {
  for (let i = 0; i < 30; i += 1) await Promise.resolve();
}

// root.children[0] list, [1] message, [2] search field
function listOf(root) { return root.children[0]; }
function messageOf(root) { return root.children[1]; }
function searchOf(root) { return root.children[2]; }

// row.children[0] text, [1] stored, [2] copy, [3] delete, [4] confirm
function textCell(row) { return row.children[0]; }
function storedCell(row) { return row.children[1]; }
function copyButton(row) { return row.children[2]; }
function deleteButton(row) { return row.children[3]; }
function confirmOf(row) { return row.children[4]; }
function confirmDeleteButton(row) { return confirmOf(row).children[1]; }
function cancelButton(row) { return confirmOf(row).children[2]; }

function mount(options = {}) {
  const net = options.net ?? netOf();
  const copied = [];
  const signedOut = [];
  const root = fakeElement('root');
  mountTranscriptions(root, {
    getToken: options.getToken ?? (() => 'tok-abc'),
    onSignedOut: () => { signedOut.push(undefined); },
    net,
    dek: options.dek ?? DEK,
    copy: options.copy ?? (async (text) => { copied.push(text); return 'COPIED'; }),
    format: options.format ?? ((iso) => 'fmt:' + iso),
  });
  return { root, net, copied, signedOut };
}

test('a hostile transcription renders as literal text and the markup setters are never called', async () => {
  const { root } = mount({
    net: netOf({ list: { status: 200, body: { items: [
      rowOf({ clientId: CLIENT_A, text: HOSTILE }),
    ], next: null, more: false } } }),
  });
  await flush();
  const row = listOf(root).children[0];
  assert.equal(row.children[0].textContent, HOSTILE);
  assert.equal(row.children[0].writes.length, 1, 'the text is written once, with textContent');
});

test('a row that cannot decrypt renders exactly cannot be read, never blank', async () => {
  const bad = rowOf({ clientId: CLIENT_A, text: 'secret' });
  bad.encrypted = 'not base64';
  const { root } = mount({
    net: netOf({ list: { status: 200, body: { items: [bad], next: null, more: false } } }),
  });
  await flush();
  const row = listOf(root).children[0];
  assert.equal(row.children[0].textContent, 'cannot be read');
  assert.notEqual(row.children[0].textContent, '');
});

test('the copy button copies the row text through the flow and shows the copied message', async () => {
  const { root, copied } = mount({
    net: netOf({ list: { status: 200, body: { items: [
      rowOf({ clientId: CLIENT_A, text: 'copy me' }),
    ], next: null, more: false } } }),
  });
  await flush();
  const row = listOf(root).children[0];
  copyButton(row).click();
  await flush();
  assert.deepEqual(copied, ['copy me']);
  assert.equal(messageOf(root).textContent, MESSAGES.copied);
});

test('the copy button sends nothing to the server when it copies', async () => {
  const { root, net } = mount({
    net: netOf({ list: { status: 200, body: { items: [
      rowOf({ clientId: CLIENT_A, text: 'copy me' }),
    ], next: null, more: false } } }),
  });
  await flush();
  const callsAfterLoad = net.calls.length;
  const row = listOf(root).children[0];
  copyButton(row).click();
  await flush();
  assert.equal(net.calls.length, callsAfterLoad, 'copy must not call the server');
});

test('the copy button is disabled for a row that cannot decrypt and sends nothing', async () => {
  const bad = rowOf({ clientId: CLIENT_A, text: 'secret' });
  bad.encrypted = 'not base64';
  const { root, copied } = mount({
    net: netOf({ list: { status: 200, body: { items: [bad], next: null, more: false } } }),
  });
  await flush();
  const row = listOf(root).children[0];
  assert.equal(copyButton(row).disabled, true);
  copyButton(row).click();
  await flush();
  assert.deepEqual(copied, [], 'an unreadable row is never copied');
});

test('the delete button arms the confirm first and nothing is sent before it is used', async () => {
  const { root, net } = mount({
    net: netOf({
      list: { status: 200, body: { items: [rowOf({ clientId: CLIENT_A, text: 'doomed' })], next: null, more: false } },
      delete: { status: 204, body: null },
    }),
  });
  await flush();
  const row = listOf(root).children[0];
  assert.equal(confirmOf(row).hidden, true);
  deleteButton(row).click();
  await flush();
  assert.equal(confirmOf(row).hidden, false);
  assert.equal(net.calls.filter((call) => call.op === 'delete').length, 0, 'arming sends nothing');
  confirmDeleteButton(row).click();
  await flush();
  const deletes = net.calls.filter((call) => call.op === 'delete');
  assert.equal(deletes.length, 1);
  assert.equal(deletes[0].clientId, CLIENT_A);
  assert.equal(confirmOf(row).hidden, true, 'the confirm control is hidden after Delete');
});

test('clicking cancel hides the confirm control and sends nothing', async () => {
  const { root, net } = mount({
    net: netOf({ list: { status: 200, body: { items: [rowOf({ clientId: CLIENT_A, text: 'keep me' })], next: null, more: false } } }),
  });
  await flush();
  const row = listOf(root).children[0];
  deleteButton(row).click();
  await flush();
  assert.equal(confirmOf(row).hidden, false);
  cancelButton(row).click();
  await flush();
  assert.equal(confirmOf(row).hidden, true);
  assert.equal(net.calls.filter((call) => call.op === 'delete').length, 0);
});

test('a double click on the confirm Delete sends one delete', async () => {
  const { root, net } = mount({
    net: netOf({
      list: { status: 200, body: { items: [rowOf({ clientId: CLIENT_A, text: 'doomed' })], next: null, more: false } },
      delete: { status: 204, body: null },
    }),
  });
  await flush();
  const row = listOf(root).children[0];
  deleteButton(row).click();
  await flush();
  confirmDeleteButton(row).click();
  confirmDeleteButton(row).click();
  await flush();
  assert.equal(net.calls.filter((call) => call.op === 'delete').length, 1);
});

test('a delete click with no token signs the view out and sends nothing', async () => {
  let token = 'tok-abc';
  let reads = 0;
  const { root, net, signedOut } = mount({
    net: netOf({
      list: { status: 200, body: { items: [rowOf({ clientId: CLIENT_A, text: 'doomed' })], next: null, more: false } },
    }),
    getToken: () => { reads += 1; return token; },
  });
  await flush();
  const row = listOf(root).children[0];
  token = '';
  deleteButton(row).click();
  await flush();
  confirmDeleteButton(row).click();
  await flush();
  assert.equal(reads, 2, 'the view refuses before the flow reads the token again');
  assert.equal(net.calls.filter((call) => call.op === 'delete').length, 0);
  assert.equal(signedOut.length, 1);
});

test('401 on load calls onSignedOut and clears the list', async () => {
  const { root, signedOut } = mount({ net: netOf({ list: { status: 401, body: null } }) });
  await flush();
  assert.equal(signedOut.length, 1);
  assert.equal(listOf(root).children.length, 0);
});

test('an empty list shows the empty sentence', async () => {
  const { root } = mount();
  await flush();
  assert.equal(messageOf(root).textContent, MESSAGES.empty);
  assert.equal(listOf(root).children.length, 0);
});

test('typing in the search field filters the rows and sends nothing', async () => {
  const { root, net } = mount({
    net: netOf({ list: { status: 200, body: { items: [
      rowOf({ clientId: CLIENT_A, text: 'Truck engine oil' }),
      rowOf({ clientId: CLIENT_B, text: 'Grocery list' }),
    ], next: null, more: false } } }),
  });
  await flush();
  assert.equal(listOf(root).children.length, 2);
  const callsAfterLoad = net.calls.length;
  searchOf(root).value = 'ENGINE';
  searchOf(root).input();
  await flush();
  const texts = listOf(root).children.map((row) => row.children[0].textContent);
  assert.deepEqual(texts, ['Truck engine oil']);
  assert.equal(net.calls.length, callsAfterLoad, 'search must not call the server');
});

test('after a delete the view re-applies the typed term, and an empty term shows every survivor', async () => {
  const doomed = rowOf({ clientId: CLIENT_A, text: 'Truck engine oil' });
  const survivor = rowOf({ clientId: CLIENT_B, text: 'Grocery list' });
  let served = [doomed, survivor];
  const net = netOf({ delete: { status: 204, body: null } });
  net.listTranscriptions = async (token, since) => {
    net.calls.push({ op: 'list', token, since });
    return { status: 200, body: { items: served, next: null, more: false } };
  };
  const { root } = mount({ net });
  await flush();
  assert.deepEqual(
    listOf(root).children.map((cell) => cell.children[0].textContent),
    ['Truck engine oil', 'Grocery list'],
  );

  searchOf(root).value = 'engine';
  searchOf(root).input();
  await flush();
  assert.deepEqual(
    listOf(root).children.map((cell) => cell.children[0].textContent),
    ['Truck engine oil'],
  );

  // The delete removes CLIENT_A; the reload then holds only CLIENT_B.
  served = [survivor];
  const callsAfterSearch = net.calls.length;
  const doomedRow = listOf(root).children[0];
  deleteButton(doomedRow).click();
  await flush();
  confirmDeleteButton(doomedRow).click();
  await flush();

  // The term is still typed, so the surviving row must be filtered out.
  assert.deepEqual(listOf(root).children.map((cell) => cell.children[0].textContent), []);
  assert.equal(messageOf(root).textContent, MESSAGES.empty);
  const deletes = net.calls.filter((call) => call.op === 'delete');
  assert.equal(deletes.length, 1, 'exactly one delete call');
  assert.equal(deletes[0].clientId, CLIENT_A);
  assert.equal(net.calls.length, callsAfterSearch + 2, 'one delete and one reload, no other call');

  // An empty term shows every surviving row.
  searchOf(root).value = '';
  searchOf(root).input();
  await flush();
  assert.deepEqual(
    listOf(root).children.map((cell) => cell.children[0].textContent),
    ['Grocery list'],
  );
  assert.equal(messageOf(root).textContent, '');
});

test('a delete that signs out does not re-apply the term', async () => {
  const net = netOf({
    list: { status: 200, body: { items: [
      rowOf({ clientId: CLIENT_A, text: 'Truck engine oil' }),
    ], next: null, more: false } },
    delete: { status: 401, body: null },
  });
  const { root, signedOut } = mount({ net });
  await flush();
  searchOf(root).value = 'engine';
  searchOf(root).input();
  await flush();
  const doomedRow = listOf(root).children[0];
  deleteButton(doomedRow).click();
  await flush();
  confirmDeleteButton(doomedRow).click();
  await flush();
  assert.equal(signedOut.length, 1);
  assert.equal(listOf(root).children.length, 0, 'no row is drawn after sign-out');
});

test('the stored_at cell carries the formatted text and the raw stamp as the title', async () => {
  const { root } = mount({
    net: netOf({ list: { status: 200, body: { items: [
      rowOf({ clientId: CLIENT_A, text: 'words', storedAt: '2026-10-11T12:00:05.000Z' }),
    ], next: null, more: false } } }),
    format: (iso) => 'ZONE:' + iso,
  });
  await flush();
  const cell = storedCell(listOf(root).children[0]);
  assert.equal(cell.textContent, 'ZONE:2026-10-11T12:00:05.000Z');
  assert.equal(cell.title, '2026-10-11T12:00:05.000Z');
});

test('a delete record never reaches the list', async () => {
  const { root } = mount({
    net: netOf({ list: { status: 200, body: { items: [
      rowOf({ clientId: CLIENT_A, text: 'live' }),
      deletedRowOf({ clientId: CLIENT_B }),
    ], next: null, more: false } } }),
  });
  await flush();
  assert.deepEqual(listOf(root).children.map((row) => row.children[0].textContent), ['live']);
});

test('transcriptions-view.js writes no markup anywhere', () => {
  const source = readFileSync(new URL('../../main/resources/web/js/transcriptions-view.js', import.meta.url), 'utf8');
  for (const token of ['innerHTML', 'outerHTML', 'insertAdjacentHTML']) {
    assert.equal(source.includes(token), false, 'transcriptions-view.js contains ' + token);
  }
});
