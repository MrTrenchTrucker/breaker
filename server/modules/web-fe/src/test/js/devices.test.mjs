import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createDevices, MESSAGES } from '../../main/resources/web/js/devices.js';

// A fake device-calls object that records every call it is given.
// net methods are plain functions: they are called as (token, ...) with no `this` dependence.
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

function renderOf() {
  const devices = [];
  const messages = [];
  const signedOut = [];
  const render = {
    showDevices: (rows) => { devices.push(rows); },
    showMessage: (text) => { messages.push(text); },
    showSignedOut: () => { signedOut.push(undefined); },
  };
  return { render, devices, messages, signedOut };
}

// The server's GET /v1/auth/devices answer is a DeviceList object: { devices: [ ... ] }.
// Every list answer in this file uses that shape, so the flow is proved against the real contract.
const DEVICES_BODY = {
  devices: [
    { id: 'd-1', name: 'Phone', last_seen: '2026-01-02T03:04:05Z', current: true },
    { id: 'd-2', name: null, last_seen: null, current: false },
  ],
};

test('200 list maps each device to a row and shows the rows', async () => {
  const net = netOf({ list: { status: 200, body: DEVICES_BODY } });
  const { render, devices } = renderOf();
  const format = (iso) => 'fmt:' + iso;
  const flow = createDevices({ getToken: () => 'tok-abc', net, format, render });
  await flow.load();
  assert.equal(net.calls.length, 1);
  assert.equal(net.calls[0].op, 'list');
  assert.equal(net.calls[0].token, 'tok-abc');
  assert.equal(devices.length, 1);
  assert.deepEqual(devices[0], [
    { id: 'd-1', name: 'Phone', last_seen: '2026-01-02T03:04:05Z', current: true, last_seen_text: 'fmt:2026-01-02T03:04:05Z' },
    { id: 'd-2', name: null, last_seen: null, current: false, last_seen_text: 'fmt:null' },
  ]);
});

test('401 on list signs out', async () => {
  const net = netOf({ list: { status: 401, body: null } });
  const { render, devices, messages, signedOut } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.load();
  assert.equal(signedOut.length, 1);
  assert.equal(messages.length, 0);
  assert.equal(devices.length, 0);
});

test('403 on list signs out', async () => {
  const net = netOf({ list: { status: 403, body: null } });
  const { render, devices, messages, signedOut } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.load();
  assert.equal(signedOut.length, 1);
  assert.equal(messages.length, 0);
  assert.equal(devices.length, 0);
});

test('503 on list shows retry and the list stays as it was', async () => {
  const net = netOf({ list: { status: 503, body: null } });
  const { render, devices, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.load();
  await flow.load();
  assert.equal(messages.length, 2);
  assert.equal(messages[0], MESSAGES.retry);
  assert.equal(messages[1], MESSAGES.retry);
  assert.equal(devices.length, 0);
  assert.equal(net.calls.length, 2);
});

test('a thrown fetch error on list shows retry', async () => {
  const net = netOf({});
  net.listDevices = async () => { throw new Error('down'); };
  const { render, messages, devices } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.load();
  assert.equal(messages.length, 1);
  assert.equal(messages[0], MESSAGES.retry);
  assert.equal(devices.length, 0);
});

test('204 signing out the current device shows signed out and does not reload', async () => {
  const net = netOf({
    list: { status: 200, body: { devices: [{ id: 'd-1', name: 'Phone', last_seen: '2026-01-02T00:00:00Z', current: true }] } },
    one: { status: 204, body: null },
  });
  const { render, devices, signedOut, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => 'fmt:' + iso, render });
  await flow.load();
  assert.equal(devices.length, 1);
  await flow.signOutOne('d-1');
  assert.equal(signedOut.length, 1);
  assert.equal(messages.length, 0);
  const lists = net.calls.filter((c) => c.op === 'list');
  assert.equal(lists.length, 1);
});

test('204 signing out a non-current device reloads the list', async () => {
  const net = netOf({
    list: { status: 200, body: { devices: [{ id: 'd-1', name: 'Phone', last_seen: '2026-01-02T00:00:00Z', current: false }] } },
    one: { status: 204, body: null },
  });
  const { render, signedOut, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.load();
  await flow.signOutOne('d-1');
  assert.equal(signedOut.length, 0);
  assert.equal(messages.length, 0);
  const lists = net.calls.filter((c) => c.op === 'list');
  assert.equal(lists.length, 2);
});

test('404 on one-device sign-out shows gone then reloads', async () => {
  const net = netOf({
    list: { status: 200, body: { devices: [{ id: 'd-1', name: 'Phone', last_seen: '2026-01-02T00:00:00Z', current: false }] } },
    one: { status: 404, body: null },
  });
  const { render, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.load();
  await flow.signOutOne('d-gone');
  assert.equal(messages.length, 1);
  assert.equal(messages[0], MESSAGES.gone);
  const lists = net.calls.filter((c) => c.op === 'list');
  assert.equal(lists.length, 2);
});

test('401 on one-device sign-out signs out', async () => {
  const net = netOf({ one: { status: 401, body: null } });
  const { render, signedOut, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.signOutOne('d-1');
  assert.equal(signedOut.length, 1);
  assert.equal(messages.length, 0);
});

test('403 on one-device sign-out signs out', async () => {
  const net = netOf({ one: { status: 403, body: null } });
  const { render, signedOut, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.signOutOne('d-1');
  assert.equal(signedOut.length, 1);
  assert.equal(messages.length, 0);
});

test('503 on one-device sign-out shows retry', async () => {
  const net = netOf({ one: { status: 503, body: null } });
  const { render, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.signOutOne('d-1');
  assert.equal(messages.length, 1);
  assert.equal(messages[0], MESSAGES.retry);
});

test('a thrown fetch error on one-device sign-out shows retry', async () => {
  const net = netOf({});
  net.signOutDevice = async () => { throw new Error('down'); };
  const { render, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.signOutOne('d-1');
  assert.equal(messages.length, 1);
  assert.equal(messages[0], MESSAGES.retry);
});

test('204 on sign-out-all shows signed out', async () => {
  const net = netOf({ all: { status: 204, body: null } });
  const { render, signedOut, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.signOutAll();
  assert.equal(signedOut.length, 1);
  assert.equal(messages.length, 0);
  assert.equal(net.calls.length, 1);
  assert.equal(net.calls[0].op, 'all');
});

test('503 on sign-out-all shows retry', async () => {
  const net = netOf({ all: { status: 503, body: null } });
  const { render, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.signOutAll();
  assert.equal(messages.length, 1);
  assert.equal(messages[0], MESSAGES.retry);
});

test('401 and 403 on sign-out-all sign out', async () => {
  for (const status of [401, 403]) {
    const net = netOf({ all: { status, body: null } });
    const { render, signedOut, messages } = renderOf();
    const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
    await flow.signOutAll();
    assert.equal(signedOut.length, 1, 'status ' + status + ' did not sign out');
    assert.equal(messages.length, 0, 'status ' + status + ' showed a message');
  }
});

test('a thrown fetch error on sign-out-all shows retry', async () => {
  const net = netOf({});
  net.signOutAllDevices = async () => { throw new Error('down'); };
  const { render, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.signOutAll();
  assert.equal(messages.length, 1);
  assert.equal(messages[0], MESSAGES.retry);
});

test('a null token signs out without calling net', async () => {
  const net = netOf({});
  const { render, signedOut, messages, devices } = renderOf();
  const flow = createDevices({ getToken: () => null, net, format: (iso) => iso, render });
  await flow.load();
  await flow.signOutOne('d-1');
  await flow.signOutAll();
  assert.equal(signedOut.length, 3);
  assert.equal(messages.length, 0);
  assert.equal(devices.length, 0);
  assert.equal(net.calls.length, 0);
});

test('no id is echoed in any message, for every status with a distinctive id', async () => {
  const id = 'device-99-c068-secret-id';
  const statuses = [401, 403, 404, 500, 503];
  for (const status of statuses) {
    const net = netOf({ one: { status, body: null } });
    const { render, messages, signedOut } = renderOf();
    const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
    await flow.signOutOne(id);
    if (status === 401 || status === 403) {
      assert.equal(signedOut.length, 1);
      assert.equal(messages.length, 0);
      continue;
    }
    assert.equal(messages.length, 1);
    const text = messages[0];
    assert.equal(text.includes(id), false, 'status ' + status + ' echoed the id in: ' + text);
    assert.equal(text.includes('99-c068'), false, 'status ' + status + ' echoed part of the id in: ' + text);
  }
});

test('the formatter is injected: last_seen_text comes from format', async () => {
  const net = netOf({ list: { status: 200, body: { devices: [{ id: 'd-1', name: 'Phone', last_seen: '2026-05-05T05:05:05Z', current: false }] } } });
  const { render, devices } = renderOf();
  const format = (iso) => 'ZONE:' + iso;
  const flow = createDevices({ getToken: () => 'tok', net, format, render });
  await flow.load();
  assert.equal(devices[0][0].last_seen_text, 'ZONE:2026-05-05T05:05:05Z');
});

test('a 200 with the DeviceList shape shows the rows the server sends', async () => {
  const net = netOf({
    list: { status: 200, body: { devices: [{ id: 'd-7', name: 'Pixel', last_seen: '2026-02-02T02:02:02Z', current: true }] } },
  });
  const { render, devices, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => 'fmt:' + iso, render });
  await flow.load();
  assert.equal(devices.length, 1);
  assert.deepEqual(devices[0], [
    { id: 'd-7', name: 'Pixel', last_seen: '2026-02-02T02:02:02Z', current: true, last_seen_text: 'fmt:2026-02-02T02:02:02Z' },
  ]);
  assert.equal(messages.length, 0);
});

test('a 200 with an empty DeviceList shows an empty list, not a message', async () => {
  const net = netOf({ list: { status: 200, body: { devices: [] } } });
  const { render, devices, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.load();
  assert.equal(devices.length, 1);
  assert.deepEqual(devices[0], []);
  assert.equal(messages.length, 0);
});

test('a 200 with a bare array is not a DeviceList and shows the list_failed message', async () => {
  const net = netOf({
    list: { status: 200, body: [{ id: 'd-1', name: 'Phone', last_seen: '2026-01-02T00:00:00Z', current: true }] },
  });
  const { render, devices, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.load();
  assert.equal(devices.length, 0);
  assert.equal(messages.length, 1);
  assert.equal(messages[0], MESSAGES.list_failed);
});

test('MESSAGES holds the fixed sentences', () => {
  assert.equal(MESSAGES.signed_out, 'You are signed out. Sign in again to see your devices.');
  assert.equal(MESSAGES.gone, 'That device is already signed out. The list is up to date.');
  assert.equal(MESSAGES.retry, 'The server is unavailable. Try again in a moment.');
  assert.equal(MESSAGES.empty, 'No devices to show.');
  assert.equal(MESSAGES.list_failed, 'The list could not be loaded. Try again in a moment.');
});

test('devices.js contains no DOM or storage tokens', () => {
  const source = readFileSync(new URL('../../main/resources/web/js/devices.js', import.meta.url), 'utf8');
  for (const token of ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'localStorage', 'sessionStorage', 'document.cookie', 'window.confirm']) {
    assert.equal(source.includes(token), false, 'forbidden token present: ' + token);
  }
});

// --- The current-row match, the empty token, the strict current flag and the reload order ---

test('S1: a non-current row reloads the list; only the current row signs the user out', async () => {
  const body = { devices: [
    { id: 'd-1', name: 'Phone', last_seen: null, current: true },
    { id: 'd-2', name: 'Tablet', last_seen: null, current: false },
  ] };
  // Signing out the non-current row d-2 must reload, not sign the user out.
  const netB = netOf({ list: { status: 200, body }, one: { status: 204, body: null } });
  const rB = renderOf();
  const flowB = createDevices({ getToken: () => 'tok', net: netB, format: (iso) => iso, render: rB.render });
  await flowB.load();
  await flowB.signOutOne('d-2');
  assert.equal(rB.signedOut.length, 0, 'a non-current row must not sign the user out');
  assert.equal(netB.calls.filter((call) => call.op === 'list').length, 2, 'the list must reload');
  // Signing out the current row d-1 must sign the user out with no reload.
  const netA = netOf({ list: { status: 200, body }, one: { status: 204, body: null } });
  const rA = renderOf();
  const flowA = createDevices({ getToken: () => 'tok', net: netA, format: (iso) => iso, render: rA.render });
  await flowA.load();
  await flowA.signOutOne('d-1');
  assert.equal(rA.signedOut.length, 1, 'the current row must sign the user out');
  assert.equal(netA.calls.filter((call) => call.op === 'list').length, 1, 'no reload when the current row goes');
});

test('S2: an empty token is treated as no token and sends nothing', async () => {
  const net = netOf();
  const { render, signedOut, messages } = renderOf();
  const flow = createDevices({ getToken: () => '', net, format: (iso) => iso, render });
  await flow.load();
  await flow.signOutOne('d-1');
  await flow.signOutAll();
  assert.equal(net.calls.length, 0, 'no call may be made without a token');
  assert.equal(signedOut.length, 3, 'each action signs the view out');
  assert.equal(messages.length, 0);
});

test('S3: only a boolean true marks a row current; a string or a number does not', async () => {
  const net = netOf({ list: { status: 200, body: { devices: [
    { id: 'd-1', name: 'A', last_seen: null, current: 'true' },
    { id: 'd-2', name: 'B', last_seen: null, current: 1 },
    { id: 'd-3', name: 'C', last_seen: null, current: true },
  ] } } });
  const { render, devices } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.load();
  assert.deepEqual(devices[0].map((row) => row.current), [false, false, true]);
});

test('D1: the 404 reload happens before the gone message is shown', async () => {
  const events = [];
  const net = {
    calls: [],
    listDevices: async () => { events.push('list'); return { status: 200, body: { devices: [] } }; },
    signOutDevice: async () => { events.push('one'); return { status: 404, body: null }; },
    signOutAllDevices: async () => ({ status: 204, body: null }),
  };
  const render = {
    showDevices: () => { events.push('devices'); },
    showMessage: (text) => { events.push('message:' + text); },
    showSignedOut: () => { events.push('signedOut'); },
  };
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.load();
  events.length = 0; // ignore the first load
  await flow.signOutOne('d-gone');
  const listAt = events.indexOf('list');
  const goneAt = events.indexOf('message:' + MESSAGES.gone);
  assert.ok(listAt >= 0, 'the list must be reloaded: ' + events.join(','));
  assert.ok(goneAt > listAt, 'the gone message must come after the reload: ' + events.join(','));
});

test('D1: a 404 whose reload fails keeps the reload message, not the gone sentence', async () => {
  const net = netOf({
    list: { status: 503, body: null },
    one: { status: 404, body: null },
  });
  const { render, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.signOutOne('d-gone');
  assert.deepEqual(messages, [MESSAGES.retry], 'the failed reload owns the message');
});

// --- The 404 path when the reload does not end 'ok' ---

test('R1: a 404 whose reload answers 401 never writes the gone sentence', async () => {
  const net = netOf({
    list: { status: 401, body: null },
    one: { status: 404, body: null },
  });
  const { render, messages, signedOut } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.signOutOne('d-gone');
  assert.equal(messages.includes(MESSAGES.gone), false, 'the gone sentence must not be written: ' + messages.join(' | '));
  assert.equal(signedOut.length, 1, 'the reload signs the user out');
});

test('R1: a 404 whose reload answers 403 never writes the gone sentence', async () => {
  const net = netOf({
    list: { status: 403, body: null },
    one: { status: 404, body: null },
  });
  const { render, messages, signedOut } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.signOutOne('d-gone');
  assert.equal(messages.includes(MESSAGES.gone), false, 'the gone sentence must not be written: ' + messages.join(' | '));
  assert.equal(signedOut.length, 1, 'the reload signs the user out');
});

test('R1: a 404 whose reload answers a bare array keeps the list_failed sentence', async () => {
  const net = netOf({
    list: { status: 200, body: [{ id: 'd-1', name: 'Phone', last_seen: null, current: false }] },
    one: { status: 404, body: null },
  });
  const { render, messages } = renderOf();
  const flow = createDevices({ getToken: () => 'tok', net, format: (iso) => iso, render });
  await flow.signOutOne('d-gone');
  assert.deepEqual(messages, [MESSAGES.list_failed], 'the final message is the list_failed sentence');
});

test('R1: a token that vanishes between the two reads never writes the gone sentence', async () => {
  let reads = 0;
  const net = netOf({
    list: { status: 200, body: { devices: [] } },
    one: { status: 404, body: null },
  });
  const { render, messages, signedOut } = renderOf();
  const flow = createDevices({
    getToken: () => { reads += 1; return reads === 1 ? 'tok' : null; },
    net, format: (iso) => iso, render,
  });
  await flow.signOutOne('d-gone');
  assert.equal(reads, 2, 'the token is read once for the call and once for the reload');
  assert.equal(messages.includes(MESSAGES.gone), false, 'the gone sentence must not be written: ' + messages.join(' | '));
  assert.equal(signedOut.length, 1, 'the reload signs the user out');
});
