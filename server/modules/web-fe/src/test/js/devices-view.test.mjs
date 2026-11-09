import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mountDevices } from '../../main/resources/web/js/devices-view.js';
import { MESSAGES } from '../../main/resources/web/js/devices.js';

const HOSTILE = '<img src=x onerror=alert(1)>';
const LABEL = 'Unknown device';
const DEVICE_ID = 'device-99-c068-distinctive';

// The page this view mounts into installs a minimal document. The view builds
// its own elements with document.createElement and never looks anything up by
// id and never touches window.
globalThis.document = {
  createElement(tag) {
    return fakeElement(tag);
  },
};

// A plain element: textContent records every write, the markup setters throw,
// so a view that writes markup fails the test that drives it.
function fakeElement(tag) {
  const element = {
    tag,
    children: [],
    listeners: [],
    writes: [],
    text: '',
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
  return element;
}

// A fake device-calls object that records every call it is given.
function netOf(answers = {}) {
  const calls = [];
  const net = {
    calls,
    listDevices: async (token) => {
      calls.push({ op: 'list', token });
      return answers.list ?? { status: 200, body: { devices: [] } };
    },
    signOutDevice: async (token, id) => {
      calls.push({ op: 'one', token, id });
      return answers.one ?? { status: 204, body: null };
    },
    signOutAllDevices: async (token) => {
      calls.push({ op: 'all', token });
      return answers.all ?? { status: 204, body: null };
    },
  };
  return net;
}

async function flush() {
  for (let i = 0; i < 30; i += 1) await Promise.resolve();
}

// root.children[0] list, [1] message, [2] 'Sign out all my devices', [3] confirm control
function listOf(root) { return root.children[0]; }
function messageOf(root) { return root.children[1]; }
function allButtonOf(root) { return root.children[2]; }
function confirmOf(root) { return root.children[3]; }
function confirmButtonOf(root) {
  return confirmOf(root).children[1];
}
function cancelButtonOf(root) {
  return confirmOf(root).children[2];
}
function signOutButton(row) { return row.children[row.children.length - 1]; }
function lastSeenCell(row) {
  for (const child of row.children) {
    if (Object.prototype.hasOwnProperty.call(child, 'title')) return child;
  }
  return null;
}

function mount(options = {}) {
  const net = options.net ?? netOf();
  const signedOut = [];
  const root = fakeElement('root');
  mountDevices(root, {
    getToken: options.getToken ?? (() => 'tok-abc'),
    onSignedOut: () => { signedOut.push(undefined); },
    net,
    format: options.format ?? ((iso) => iso),
  });
  return { root, net, signedOut };
}

test('a hostile name renders as literal text and the markup setters are never called', async () => {
  const { root, net } = mount({
    net: netOf({ list: { status: 200, body: { devices: [{ id: 'd-1', name: HOSTILE, last_seen: null, current: false }] } } }),
  });
  await flush();
  assert.equal(net.calls.length, 1);
  const rows = listOf(root).children;
  assert.equal(rows.length, 1);
  assert.equal(rows[0].children[0].textContent, HOSTILE);
});

test('a null name and an empty name both get the fixed label', async () => {
  const { root, net } = mount({
    net: netOf({ list: { status: 200, body: { devices: [
      { id: 'd-1', name: null, last_seen: null, current: false },
      { id: 'd-2', name: '', last_seen: null, current: false },
    ] } } }),
  });
  await flush();
  const names = listOf(root).children.map((row) => row.children[0].textContent);
  assert.deepEqual(names, [LABEL, LABEL]);
});

test('the current marker appears only on the row with current === true', async () => {
  const { root, net } = mount({
    net: netOf({ list: { status: 200, body: { devices: [
      { id: 'd-1', name: 'Phone', last_seen: null, current: true },
      { id: 'd-2', name: 'Tablet', last_seen: null, current: false },
      { id: 'd-3', name: 'Laptop', last_seen: null, current: false },
    ] } } }),
  });
  await flush();
  const rows = listOf(root).children;
  assert.equal(rows.length, 3);
  for (const [index, row] of rows.entries()) {
    const markers = row.children.filter((child) => child.className === 'current');
    assert.equal(markers.length, index === 0 ? 1 : 0, 'row ' + index + ' must ' + (index === 0 ? 'have' : 'lack') + ' the current marker');
  }
});

test('401 on one-device sign-out signs out the view and calls onSignedOut once', async () => {
  const { root, net, signedOut } = mount({
    net: netOf({
      list: { status: 200, body: { devices: [{ id: 'd-1', name: 'Phone', last_seen: null, current: true }] } },
      one: { status: 401, body: null },
    }),
  });
  await flush();
  signOutButton(listOf(root).children[0]).click();
  await flush();
  assert.equal(signedOut.length, 1);
  assert.equal(listOf(root).children.length, 0);
  const ones = net.calls.filter((call) => call.op === 'one');
  assert.equal(ones.length, 1);
  assert.equal(ones[0].id, 'd-1');
});

test('403 on one-device sign-out signs out the view', async () => {
  const { root, net, signedOut } = mount({
    net: netOf({
      list: { status: 200, body: { devices: [{ id: 'd-1', name: 'Phone', last_seen: null, current: false }] } },
      one: { status: 403, body: null },
    }),
  });
  await flush();
  signOutButton(listOf(root).children[0]).click();
  await flush();
  assert.equal(signedOut.length, 1);
  assert.equal(listOf(root).children.length, 0);
  assert.equal(net.calls.filter((call) => call.op === 'one').length, 1);
});

test('404 on one-device sign-out shows the gone message and reloads the list', async () => {
  const { root, net } = mount({
    net: netOf({
      list: { status: 200, body: { devices: [{ id: 'd-1', name: 'Phone', last_seen: null, current: false }] } },
      one: { status: 404, body: null },
    }),
  });
  await flush();
  signOutButton(listOf(root).children[0]).click();
  await flush();
  // The user reads the FINAL text, not an earlier write: a reload that renders a
  // non-empty list clears the message, so asserting a write that includes the sentence
  // is true even when the sentence was erased. This asserts what the user sees.
  assert.equal(messageOf(root).textContent, MESSAGES.gone, 'the final message is the gone sentence: ' + messageOf(root).writes);
  const lists = net.calls.filter((call) => call.op === 'list');
  assert.equal(lists.length, 2, 'the flow must reload the list after a 404');
});

test('503 on list shows the retry message', async () => {
  const { root, net } = mount({ net: netOf({ list: { status: 503, body: null } }) });
  await flush();
  assert.equal(messageOf(root).textContent, MESSAGES.retry);
  assert.equal(net.calls.filter((call) => call.op === 'list').length, 1);
});

test('503 on one-device sign-out shows the retry message', async () => {
  const { root, net } = mount({
    net: netOf({
      list: { status: 200, body: { devices: [{ id: 'd-1', name: 'Phone', last_seen: null, current: false }] } },
      one: { status: 503, body: null },
    }),
  });
  await flush();
  signOutButton(listOf(root).children[0]).click();
  await flush();
  assert.equal(messageOf(root).textContent, MESSAGES.retry);
});

test('204 on sign-out-all shows signed out only after the confirm button is clicked', async () => {
  const { root, net, signedOut } = mount({ net: netOf({ all: { status: 204, body: null } }) });
  await flush();
  allButtonOf(root).click();
  await flush();
  assert.equal(net.calls.filter((call) => call.op === 'all').length, 0, 'nothing is sent before the confirm button is clicked');
  assert.equal(confirmOf(root).hidden, false);
  confirmButtonOf(root).click();
  await flush();
  assert.equal(net.calls.filter((call) => call.op === 'all').length, 1, 'exactly one sign-out-all call after the confirm button is clicked');
  assert.equal(signedOut.length, 1);
  assert.equal(listOf(root).children.length, 0);
});

test('clicking cancel hides the confirm control and sends nothing', async () => {
  const { root, net } = mount({ net: netOf({ all: { status: 204, body: null } }) });
  await flush();
  allButtonOf(root).click();
  await flush();
  assert.equal(confirmOf(root).hidden, false);
  cancelButtonOf(root).click();
  await flush();
  assert.equal(confirmOf(root).hidden, true);
  assert.equal(net.calls.filter((call) => call.op === 'all').length, 0, 'cancel sends no sign-out-all call');
});

test('a double click sends one call', async () => {
  const { root, net } = mount({
    net: netOf({
      list: { status: 200, body: { devices: [{ id: 'd-1', name: 'Phone', last_seen: null, current: false }] } },
      one: { status: 204, body: null },
    }),
  });
  await flush();
  const button = signOutButton(listOf(root).children[0]);
  button.click();
  button.click();
  await flush();
  assert.equal(net.calls.filter((call) => call.op === 'one').length, 1);
  assert.equal(button.disabled, false, 'the button is enabled again once the flow answers');
});

test('204 signing out the current device clears the list and calls onSignedOut once', async () => {
  const { root, net, signedOut } = mount({
    net: netOf({
      list: { status: 200, body: { devices: [{ id: 'd-1', name: 'Phone', last_seen: null, current: true }] } },
      one: { status: 204, body: null },
    }),
  });
  await flush();
  signOutButton(listOf(root).children[0]).click();
  await flush();
  assert.equal(signedOut.length, 1);
  assert.equal(listOf(root).children.length, 0);
});

test('no message contains the device id, for every status', async () => {
  for (const status of [401, 403, 404, 503]) {
    const net = netOf({
      list: { status: 200, body: { devices: [{ id: DEVICE_ID, name: 'Phone', last_seen: null, current: false }] } },
      one: { status, body: null },
    });
    const { root } = mount({ net });
    await flush();
    signOutButton(listOf(root).children[0]).click();
    await flush();
    for (const write of messageOf(root).writes) {
      assert.equal(write.includes(DEVICE_ID), false, 'status ' + status + ' wrote the id: ' + write);
      assert.equal(write.includes('99-c068'), false, 'status ' + status + ' wrote part of the id: ' + write);
    }
  }
});

test('the three devices source files contain no DOM or storage tokens', () => {
  const files = [
    new URL('../../main/resources/web/js/devices.js', import.meta.url),
    new URL('../../main/resources/web/js/devices-net.js', import.meta.url),
    new URL('../../main/resources/web/js/devices-view.js', import.meta.url),
  ];
  const tokens = ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'localStorage', 'sessionStorage', 'document.cookie', 'window.confirm'];
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    for (const token of tokens) {
      assert.equal(source.includes(token), false, file.pathname + ' contains forbidden token: ' + token);
    }
  }
});

test('the injected formatter is used and the raw ISO is the title attribute', async () => {
  const { root, net } = mount({
    net: netOf({ list: { status: 200, body: { devices: [{ id: 'd-1', name: 'Phone', last_seen: '2026-05-05T05:05:05Z', current: false }] } } }),
    format: (iso) => 'ZONE:' + iso,
  });
  await flush();
  const row = listOf(root).children[0];
  const seen = lastSeenCell(row);
  assert.equal(seen.textContent, 'ZONE:2026-05-05T05:05:05Z');
  assert.equal(seen.title, '2026-05-05T05:05:05Z');
});

test('an empty account shows the empty sentence and keeps sign-out-all usable', async () => {
  const { root, net } = mount(); // the list answers with an empty device list
  await flush();
  assert.equal(messageOf(root).textContent, MESSAGES.empty);
  assert.equal(listOf(root).children.length, 0);
  allButtonOf(root).click();
  await flush();
  assert.equal(confirmOf(root).hidden, false);
  assert.equal(net.calls.filter((call) => call.op === 'all').length, 0, 'nothing is sent before the confirm button is clicked');
  confirmButtonOf(root).click();
  await flush();
  assert.equal(net.calls.filter((call) => call.op === 'all').length, 1, 'exactly one sign-out-all call after the confirm button is clicked');
});

// --- The confirm control, its double-click guard and the empty-token click paths ---

test('S4: the confirm control is hidden at mount and shown only after the first click', async () => {
  const { root, net } = mount({ net: netOf({ all: { status: 204, body: null } }) });
  await flush();
  assert.equal(confirmOf(root).hidden, true, 'the confirm control starts hidden');
  assert.equal(net.calls.filter((call) => call.op === 'all').length, 0);
  allButtonOf(root).click();
  await flush();
  assert.equal(confirmOf(root).hidden, false, 'the first click shows the confirm control');
  assert.equal(net.calls.filter((call) => call.op === 'all').length, 0, 'showing the control sends nothing');
});

test('S5: clicking the confirm button hides the confirm control', async () => {
  const { root } = mount({ net: netOf({ all: { status: 204, body: null } }) });
  await flush();
  allButtonOf(root).click();
  await flush();
  assert.equal(confirmOf(root).hidden, false);
  confirmButtonOf(root).click();
  await flush();
  assert.equal(confirmOf(root).hidden, true, 'the confirm control is hidden after Confirm');
});

test('S6: two quick clicks on the confirm button send one sign-out-all', async () => {
  let resolveAll = null;
  const calls = [];
  const net = {
    listDevices: async () => ({ status: 200, body: { devices: [] } }),
    signOutDevice: async () => ({ status: 204, body: null }),
    signOutAllDevices: async () => {
      calls.push('all');
      return new Promise((resolve) => { resolveAll = resolve; });
    },
  };
  const { root, signedOut } = mount({ net });
  await flush();
  allButtonOf(root).click();
  await flush();
  confirmButtonOf(root).click();
  confirmButtonOf(root).click();
  await flush();
  assert.equal(calls.length, 1, 'the second click while the first is pending must not send');
  resolveAll({ status: 204, body: null });
  await flush();
  assert.equal(signedOut.length, 1);
});

test('S2: a row sign-out click with no token signs the view out and sends nothing', async () => {
  let token = 'tok-abc';
  let reads = 0;
  const net = netOf({ list: { status: 200, body: { devices: [{ id: 'd-1', name: 'Phone', last_seen: null, current: false }] } } });
  const { root, signedOut } = mount({ net, getToken: () => { reads += 1; return token; } });
  await flush();
  assert.equal(listOf(root).children.length, 1);
  assert.equal(reads, 1, 'the mount load reads the token once');
  token = '';
  signOutButton(listOf(root).children[0]).click();
  await flush();
  // The view reads the token itself and stops there. If its check is broken the flow
  // reads the token a third time before refusing, so the count proves the view's guard.
  assert.equal(reads, 2, 'the view refuses before the flow reads the token again');
  assert.equal(net.calls.filter((call) => call.op === 'one').length, 0, 'no sign-out call without a token');
  assert.equal(signedOut.length, 1);
});

test('S2: a confirm click with no token signs the view out and sends nothing', async () => {
  let token = 'tok-abc';
  let reads = 0;
  const net = netOf({ all: { status: 204, body: null } });
  const { root, signedOut } = mount({ net, getToken: () => { reads += 1; return token; } });
  await flush();
  allButtonOf(root).click();
  await flush();
  assert.equal(reads, 1, 'the mount load reads the token once');
  token = '';
  confirmButtonOf(root).click();
  await flush();
  assert.equal(reads, 2, 'the view refuses before the flow reads the token again');
  assert.equal(net.calls.filter((call) => call.op === 'all').length, 0, 'no sign-out-all call without a token');
  assert.equal(signedOut.length, 1);
});

test('R1: a 404 whose reload signs the user out leaves the gone sentence unshown', async () => {
  let listCalls = 0;
  const net = {
    calls: [],
    listDevices: async () => {
      listCalls += 1;
      if (listCalls === 1) {
        return { status: 200, body: { devices: [{ id: 'd-1', name: 'Phone', last_seen: null, current: false }] } };
      }
      return { status: 401, body: null };
    },
    signOutDevice: async () => ({ status: 404, body: null }),
    signOutAllDevices: async () => ({ status: 204, body: null }),
  };
  const { root, signedOut } = mount({ net });
  await flush();
  assert.equal(listOf(root).children.length, 1);
  signOutButton(listOf(root).children[0]).click();
  await flush();
  // The reload signs the user out: the gone sentence must not be the final message.
  assert.notEqual(messageOf(root).textContent, MESSAGES.gone, 'the gone sentence was written after a sign-out: ' + messageOf(root).writes);
  assert.equal(signedOut.length, 1);
});
