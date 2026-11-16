import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mountAdminTokens } from '../../main/resources/web/js/admin-tokens-view.js';
import { ADMIN_TOKENS_TEXTS } from '../../main/resources/web/js/admin-tokens.js';

// A plain element: textContent is a plain property, the markup setters throw, and a
// click runs the click listeners. No id lookups, no window, no storage.
function fakeElement(tag) {
  const element = {
    tag,
    children: [],
    listeners: [],
    text: '',
    value: '',
    disabled: false,
    hidden: false,
    type: '',
  };
  Object.defineProperty(element, 'textContent', {
    enumerable: true,
    get() { return element.text; },
    set(next) { element.text = next; },
  });
  Object.defineProperty(element, 'innerHTML', {
    enumerable: true,
    get: () => '',
    set: () => { throw new Error('innerHTML was set'); },
  });
  Object.defineProperty(element, 'outerHTML', {
    enumerable: true,
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
  return element;
}

globalThis.document = { createElement(tag) { return fakeElement(tag); } };

function netOf(answers = {}) {
  const calls = [];
  const net = {
    calls,
    listTokens: async (token) => {
      calls.push({ op: 'list', token });
      return answers.list ?? { status: 200, body: [] };
    },
    createToken: async (token, request) => {
      calls.push({ op: 'create', token, request });
      return answers.create ?? { status: 400, body: null };
    },
    revokeToken: async (token, id) => {
      calls.push({ op: 'revoke', token, id });
      return answers.revoke ?? { status: 204, body: null };
    },
  };
  return net;
}

function mount(options = {}) {
  const net = options.net ?? netOf();
  const signedOut = [];
  const root = fakeElement('root');
  const mounted = mountAdminTokens(root, {
    getToken: options.getToken ?? (() => 'tok-abc'),
    onSignedOut: () => { signedOut.push(undefined); },
    net,
    format: options.format ?? ((iso) => (iso === null ? 'No expiry' : iso)),
  });
  return { root, net, signedOut, ...mounted };
}

// The build order in the view is fixed, so the fields are read by position.
const listOf = (root) => root.children[0];
const messageOf = (root) => root.children[1];
const secretPanelOf = (root) => root.children[2];
const nameFieldOf = (root) => root.children[3];
const ownerFieldOf = (root) => root.children[4];
const scopeFieldOf = (root) => root.children[5];
const expiresFieldOf = (root) => root.children[6];
const createButtonOf = (root) => root.children[7];
const secretLabelOf = (root) => secretPanelOf(root).children[0];
const secretValueOf = (root) => secretPanelOf(root).children[1];
const dismissButtonOf = (root) => secretPanelOf(root).children[2];
const revokeButtonOf = (row) => row.children[row.children.length - 1];
const cellOf = (row, index) => row.children[index];

function allNodes(root) {
  const out = [];
  const walk = (node) => {
    out.push(node);
    for (const child of node.children) walk(child);
  };
  walk(root);
  return out;
}

// Every string the element currently holds: its text and its own string properties.
function heldStrings(node) {
  const held = [];
  for (const key of Object.keys(node)) {
    const value = node[key];
    if (typeof value === 'string') held.push(value);
  }
  if (typeof node.textContent === 'string') held.push(node.textContent);
  return held;
}

function heldUnder(root) {
  return allNodes(root).flatMap(heldStrings);
}

async function flush() {
  for (let index = 0; index < 30; index += 1) await Promise.resolve();
}

function rowOf(entry) {
  return {
    id: entry.id,
    name: entry.name,
    owner_username: entry.owner_username,
    scope: entry.scope,
    expires_at: entry.expires_at,
    expires_text: entry.expires_at === null ? 'No expiry' : entry.expires_at,
  };
}

const HOSTILE = '<img src=x onerror=alert(1)>';

test('a hostile token name renders as literal text and the markup setters are never called', async () => {
  const entry = { id: 1, name: HOSTILE, owner_username: 'alice@example.com', scope: 'transcribe', expires_at: null };
  const { root } = mount({ net: netOf({ list: { status: 200, body: [entry] } }) });
  await flush();
  const row = listOf(root).children[0];
  assert.equal(cellOf(row, 0).textContent, HOSTILE);
  assert.equal(cellOf(row, 1).textContent, 'alice@example.com');
});

test('a null name and an empty name both get the fixed label', async () => {
  const entries = [
    { id: 1, name: null, owner_username: 'alice@example.com', scope: 'transcribe', expires_at: null },
    { id: 2, name: '', owner_username: 'bob@example.com', scope: 'admin', expires_at: null },
  ];
  const { root } = mount({ net: netOf({ list: { status: 200, body: entries } }) });
  await flush();
  const names = listOf(root).children.map((row) => cellOf(row, 0).textContent);
  assert.deepEqual(names, ['Unnamed token', 'Unnamed token']);
});

test('the owner name, the scope and the expiry text render as literal text', async () => {
  const entry = { id: 3, name: 'Syn', owner_username: 'carol@example.com', scope: 'admin', expires_at: '2027-05-06T07:08:09Z' };
  const { root } = mount({ net: netOf({ list: { status: 200, body: [entry] } }) });
  await flush();
  const row = listOf(root).children[0];
  assert.equal(cellOf(row, 1).textContent, 'carol@example.com');
  assert.equal(cellOf(row, 2).textContent, 'admin');
  assert.equal(cellOf(row, 3).textContent, '2027-05-06T07:08:09Z');
});

test('R1: a created token is shown once in one element and is gone after dismiss', async () => {
  const SECRET = 'atk_one_time_secret_99';
  const { root } = mount({
    net: netOf({
      list: { status: 200, body: [{ id: 1, name: 'Old', owner_username: 'alice@example.com', scope: 'transcribe', expires_at: null }] },
      create: { status: 201, body: { token: SECRET, name: 'New', owner_username: 'alice@example.com', scope: 'transcribe', expires_at: null } },
    }),
  });
  await flush();
  nameFieldOf(root).value = 'New';
  ownerFieldOf(root).value = 'alice@example.com';
  scopeFieldOf(root).value = 'transcribe';
  expiresFieldOf(root).value = '';
  createButtonOf(root).click();
  await flush();
  const holders = allNodes(root).filter((node) => node.textContent === SECRET);
  assert.equal(holders.length, 1, 'the secret is written into exactly one element');
  assert.equal(secretValueOf(root).textContent, SECRET);
  assert.equal(secretLabelOf(root).textContent, ADMIN_TOKENS_TEXTS.create_201);
  assert.equal(secretPanelOf(root).hidden, false);
  dismissButtonOf(root).click();
  await flush();
  const held = heldUnder(root).filter((text) => text.includes(SECRET));
  assert.equal(held.length, 0, 'after dismiss no node under the root holds the secret: ' + JSON.stringify(held));
  assert.equal(secretValueOf(root).textContent, '');
  assert.equal(secretPanelOf(root).hidden, true);
});

test('R1: after dismiss the flow object has no way to read the secret back', async () => {
  const SECRET = 'atk_one_time_secret_99';
  const { root, flow } = mount({
    net: netOf({
      list: { status: 200, body: [] },
      create: { status: 201, body: { token: SECRET, name: 'New', owner_username: 'alice@example.com', scope: 'transcribe', expires_at: null } },
    }),
  });
  await flush();
  nameFieldOf(root).value = 'New';
  ownerFieldOf(root).value = 'alice@example.com';
  const outcome = await flow.createToken({ name: 'New', owner_username: 'alice@example.com' });
  dismissButtonOf(root).click();
  await flush();
  assert.deepEqual(Object.keys(flow).sort(), ['createToken', 'load', 'revoke']);
  assert.equal(JSON.stringify(flow), '{}', 'the flow object holds no data property');
  assert.equal(typeof outcome, 'string');
  assert.equal(outcome.includes(SECRET), false, 'the outcome does not carry the secret');
  await flow.load();
  await flush();
  const held = heldUnder(root).filter((text) => text.includes(SECRET));
  assert.equal(held.length, 0, 'a reload after dismiss does not bring the secret back: ' + JSON.stringify(held));
});

test('R3: after create the list is re-read from the server, not the request', async () => {
  let listCalls = 0;
  const net = {
    calls: [],
    listTokens: async (token) => {
      listCalls += 1;
      net.calls.push({ op: 'list', token });
      return listCalls === 1
        ? { status: 200, body: [] }
        : { status: 200, body: [{ id: 5, name: 'Server row', owner_username: 'server@example.com', scope: 'admin', expires_at: null }] };
    },
    createToken: async (token, request) => {
      net.calls.push({ op: 'create', token, request });
      return { status: 201, body: { token: 'atk_x', name: 'Request row', owner_username: 'request@example.com', scope: 'transcribe', expires_at: null } };
    },
    revokeToken: async () => ({ status: 204, body: null }),
  };
  const { root } = mount({ net });
  await flush();
  assert.equal(listCalls, 1);
  nameFieldOf(root).value = 'Request row';
  ownerFieldOf(root).value = 'request@example.com';
  createButtonOf(root).click();
  await flush();
  assert.equal(listCalls, 2, 'the list must be re-read after a create');
  const names = listOf(root).children.map((row) => cellOf(row, 0).textContent);
  assert.deepEqual(names, ['Server row']);
  assert.equal(names.includes('Request row'), false, 'the requested row is not shown ahead of the server answer');
});

test('R3: after revoke the list is re-read from the server', async () => {
  let listCalls = 0;
  const net = {
    calls: [],
    listTokens: async (token) => {
      listCalls += 1;
      net.calls.push({ op: 'list', token });
      return {
        status: 200,
        body: listCalls === 1
          ? [
              { id: 5, name: 'Keep', owner_username: 'a@example.com', scope: 'transcribe', expires_at: null },
              { id: 6, name: 'Drop', owner_username: 'b@example.com', scope: 'admin', expires_at: null },
            ]
          : [{ id: 5, name: 'Keep', owner_username: 'a@example.com', scope: 'transcribe', expires_at: null }],
      };
    },
    createToken: async () => ({ status: 400, body: null }),
    revokeToken: async (token, id) => {
      net.calls.push({ op: 'revoke', token, id });
      return { status: 204, body: null };
    },
  };
  const { root } = mount({ net });
  await flush();
  assert.equal(listOf(root).children.length, 2);
  revokeButtonOf(listOf(root).children[1]).click();
  await flush();
  assert.equal(listCalls, 2, 'the list must be re-read after a revoke');
  const names = listOf(root).children.map((row) => cellOf(row, 0).textContent);
  assert.deepEqual(names, ['Keep'], 'the list is the server answer, with no optimistic removal');
  assert.equal(messageOf(root).textContent, ADMIN_TOKENS_TEXTS.revoke_204);
});

test('the create click sends the name and owner entered in the fields', async () => {
  const { root, net } = mount({ net: netOf({ create: { status: 201, body: { token: 'atk_x' } } }) });
  await flush();
  nameFieldOf(root).value = 'Field name';
  ownerFieldOf(root).value = 'field-owner@example.com';
  scopeFieldOf(root).value = 'admin';
  expiresFieldOf(root).value = '2027-03-04T05:06:07Z';
  createButtonOf(root).click();
  await flush();
  const creates = net.calls.filter((call) => call.op === 'create');
  assert.equal(creates.length, 1);
  assert.deepEqual(creates[0].request, {
    name: 'Field name',
    owner_username: 'field-owner@example.com',
    scope: 'admin',
    expires_at: '2027-03-04T05:06:07Z',
  });
});

test('the token shown is the value the server answered, not a value the page made up', async () => {
  const SERVER_TOKEN = 'atk_from_the_server_42';
  const { root } = mount({
    net: netOf({
      list: { status: 200, body: [] },
      create: { status: 201, body: { token: SERVER_TOKEN, name: 'Server name', owner_username: 'server@example.com', scope: 'transcribe', expires_at: null } },
    }),
  });
  await flush();
  nameFieldOf(root).value = 'Field name';
  ownerFieldOf(root).value = 'field-owner@example.com';
  createButtonOf(root).click();
  await flush();
  assert.equal(secretValueOf(root).textContent, SERVER_TOKEN);
  assert.equal(secretLabelOf(root).textContent, ADMIN_TOKENS_TEXTS.create_201);
});

test('an empty name click sends nothing and shows the bad-request sentence', async () => {
  for (const name of ['', '   ']) {
    const { root, net } = mount({ net: netOf({ create: { status: 201, body: { token: 'atk_x' } } }) });
    await flush();
    nameFieldOf(root).value = name;
    ownerFieldOf(root).value = 'owner@example.com';
    createButtonOf(root).click();
    await flush();
    assert.equal(net.calls.filter((call) => call.op === 'create').length, 0, 'nothing is sent');
    assert.equal(secretPanelOf(root).hidden, true, 'no token is shown');
    assert.equal(messageOf(root).textContent, ADMIN_TOKENS_TEXTS.create_400);
  }
});

test('revoke sends with no confirm step', async () => {
  const { root, net } = mount({
    net: netOf({ list: { status: 200, body: [{ id: 6, name: 'Drop', owner_username: 'b@example.com', scope: 'admin', expires_at: null }] } }),
  });
  await flush();
  revokeButtonOf(listOf(root).children[0]).click();
  await flush();
  const revokes = net.calls.filter((call) => call.op === 'revoke');
  assert.equal(revokes.length, 1, 'one revoke call, with no confirm control');
  assert.equal(revokes[0].id, 6);
  assert.equal(net.calls.filter((call) => call.op === 'confirm').length, 0);
});

test('the create click with no token signs out and sends nothing', async () => {
  let token = 'tok-abc';
  let reads = 0;
  const net = netOf();
  const { root, signedOut } = mount({ net, getToken: () => { reads += 1; return token; } });
  await flush();
  assert.equal(reads, 1, 'the mount load reads the token once');
  token = '';
  nameFieldOf(root).value = 'New';
  createButtonOf(root).click();
  await flush();
  assert.equal(reads, 2, 'the view refuses before the flow reads the token again');
  assert.equal(net.calls.filter((call) => call.op === 'create').length, 0);
  assert.equal(signedOut.length, 1);
});

test('the revoke click with no token signs out and sends nothing', async () => {
  let token = 'tok-abc';
  let reads = 0;
  const net = netOf({ list: { status: 200, body: [{ id: 6, name: 'Drop', owner_username: 'b@example.com', scope: 'admin', expires_at: null }] } });
  const { root, signedOut } = mount({ net, getToken: () => { reads += 1; return token; } });
  await flush();
  assert.equal(reads, 1);
  token = '';
  revokeButtonOf(listOf(root).children[0]).click();
  await flush();
  assert.equal(reads, 2, 'the view refuses before the flow reads the token again');
  assert.equal(net.calls.filter((call) => call.op === 'revoke').length, 0);
  assert.equal(signedOut.length, 1);
});

test('401 on list signs the view out', async () => {
  const { root, signedOut } = mount({ net: netOf({ list: { status: 401, body: null } }) });
  await flush();
  assert.equal(signedOut.length, 1);
  assert.deepEqual(listOf(root).children, []);
  assert.equal(messageOf(root).textContent, '');
});

test('403 on list shows the forbidden sentence and keeps the view mounted', async () => {
  const { root, signedOut } = mount({ net: netOf({ list: { status: 403, body: null } }) });
  await flush();
  assert.equal(signedOut.length, 0);
  assert.equal(messageOf(root).textContent, ADMIN_TOKENS_TEXTS.list_403);
  assert.equal(root.children.length, 8, 'the view is still mounted');
});

test('a double click on revoke sends one call', async () => {
  const { root, net } = mount({
    net: netOf({ list: { status: 200, body: [{ id: 6, name: 'Drop', owner_username: 'b@example.com', scope: 'admin', expires_at: null }] } }),
  });
  await flush();
  const button = revokeButtonOf(listOf(root).children[0]);
  button.click();
  button.click();
  await flush();
  assert.equal(net.calls.filter((call) => call.op === 'revoke').length, 1);
});

test('the three source files contain no markup or storage tokens', () => {
  for (const path of ['admin-tokens-net.js', 'admin-tokens.js', 'admin-tokens-view.js']) {
    const source = readFileSync(new URL('../../main/resources/web/js/' + path, import.meta.url), 'utf8');
    for (const token of ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'localStorage', 'sessionStorage', 'document.cookie', 'window.confirm']) {
      assert.equal(source.includes(token), false, path + ' carries the forbidden token ' + token);
    }
  }
});

test('the three source files contain no console call', () => {
  for (const path of ['admin-tokens-net.js', 'admin-tokens.js', 'admin-tokens-view.js']) {
    const source = readFileSync(new URL('../../main/resources/web/js/' + path, import.meta.url), 'utf8');
    assert.equal(source.includes('console'), false, path + ' calls the console');
  }
});
