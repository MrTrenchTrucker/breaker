import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createAdminStore, ADMIN_STORE_TEXTS } from '../../main/resources/web/js/admin-store.js';

// A fake store-calls object that records every call it is given. The net method is
// a plain function: it is called as (token, options) with no this dependence.
function netOf(answer = { status: 200, body: { cleared: 0 } }) {
  const calls = [];
  const net = {
    calls,
    clearStore: async (token, options) => {
      calls.push({ token, options });
      return answer;
    },
  };
  return net;
}

function renderOf() {
  const messages = [];
  const signedOut = [];
  const render = {
    showMessage: (text) => { messages.push(text); },
    showSignedOut: () => { signedOut.push(undefined); },
  };
  return { render, messages, signedOut };
}

test('clearOne sends the username with confirm true and shows the cleared count the server answered', async () => {
  const net = netOf({ status: 200, body: { cleared: 7 } });
  const { render, messages } = renderOf();
  const flow = createAdminStore({ getToken: () => 'tok-abc', net, render });
  assert.equal(await flow.clearOne('alice@example.com'), 'ok');
  assert.equal(net.calls.length, 1);
  assert.equal(net.calls[0].token, 'tok-abc');
  assert.deepEqual(net.calls[0].options, { username: 'alice@example.com', confirm: true });
  assert.equal(messages.length, 1);
  assert.equal(messages[0], 'The store was cleared: 7 rows.');
});

test('clearAll sends a null username with confirm true', async () => {
  const net = netOf({ status: 200, body: { cleared: 12 } });
  const { render, messages } = renderOf();
  const flow = createAdminStore({ getToken: () => 'tok-abc', net, render });
  assert.equal(await flow.clearAll(), 'ok');
  assert.equal(net.calls.length, 1);
  assert.deepEqual(net.calls[0].options, { username: null, confirm: true });
  assert.equal(messages[0], 'The store was cleared: 12 rows.');
});

test('400 shows the bad request sentence', async () => {
  const net = netOf({ status: 400, body: null });
  const { render, messages } = renderOf();
  const flow = createAdminStore({ getToken: () => 'tok', net, render });
  assert.equal(await flow.clearOne('alice@example.com'), 'bad_request');
  assert.deepEqual(messages, [ADMIN_STORE_TEXTS.bad_request]);
});

test('401 shows the unauthorized sentence', async () => {
  const net = netOf({ status: 401, body: null });
  const { render, messages } = renderOf();
  const flow = createAdminStore({ getToken: () => 'tok', net, render });
  assert.equal(await flow.clearAll(), 'unauthorized');
  assert.deepEqual(messages, [ADMIN_STORE_TEXTS.unauthorized]);
});

test('403 shows the forbidden sentence', async () => {
  const net = netOf({ status: 403, body: null });
  const { render, messages } = renderOf();
  const flow = createAdminStore({ getToken: () => 'tok', net, render });
  assert.equal(await flow.clearOne('alice@example.com'), 'forbidden');
  assert.deepEqual(messages, [ADMIN_STORE_TEXTS.forbidden]);
});

test('404 shows the not found sentence', async () => {
  const net = netOf({ status: 404, body: null });
  const { render, messages } = renderOf();
  const flow = createAdminStore({ getToken: () => 'tok', net, render });
  assert.equal(await flow.clearAll(), 'not_found');
  assert.deepEqual(messages, [ADMIN_STORE_TEXTS.not_found]);
});

test('a status this module does not read shows the unknown sentence', async () => {
  const net = netOf({ status: 413, body: null });
  const { render, messages } = renderOf();
  const flow = createAdminStore({ getToken: () => 'tok', net, render });
  assert.equal(await flow.clearOne('alice@example.com'), 'unknown');
  assert.deepEqual(messages, [ADMIN_STORE_TEXTS.unknown]);
});

test('a rejected fetch shows the network sentence', async () => {
  const net = netOf();
  net.clearStore = async () => { throw new Error('down'); };
  const { render, messages } = renderOf();
  const flow = createAdminStore({ getToken: () => 'tok', net, render });
  assert.equal(await flow.clearAll(), 'network');
  assert.deepEqual(messages, [ADMIN_STORE_TEXTS.network]);
});

test('a 200 whose body has no integer cleared shows the unknown sentence', async () => {
  const net = netOf({ status: 200, body: { cleared: 'many' } });
  const { render, messages } = renderOf();
  const flow = createAdminStore({ getToken: () => 'tok', net, render });
  assert.equal(await flow.clearOne('alice@example.com'), 'unknown');
  assert.deepEqual(messages, [ADMIN_STORE_TEXTS.unknown]);
});

test('a null token signs out and sends nothing', async () => {
  const net = netOf();
  const { render, messages, signedOut } = renderOf();
  const flow = createAdminStore({ getToken: () => null, net, render });
  assert.equal(await flow.clearOne('alice@example.com'), 'signed_out');
  assert.equal(await flow.clearAll(), 'signed_out');
  assert.equal(net.calls.length, 0);
  assert.equal(messages.length, 0);
  assert.equal(signedOut.length, 2);
});

test('no message contains the token, for every status', async () => {
  const token = 'tok-99-secret';
  for (const status of [200, 400, 401, 403, 404, 413]) {
    const body = status === 200 ? { cleared: 2 } : null;
    const net = netOf({ status, body });
    const { render, messages } = renderOf();
    const flow = createAdminStore({ getToken: () => token, net, render });
    await flow.clearOne('alice@example.com');
    for (const text of messages) {
      assert.equal(text.includes(token), false, 'status ' + status + ' wrote the token: ' + text);
    }
  }
});

test('ADMIN_STORE_TEXTS is frozen and holds the seven status sentences', () => {
  assert.equal(Object.isFrozen(ADMIN_STORE_TEXTS), true);
  assert.equal(ADMIN_STORE_TEXTS.cleared, 'The store was cleared: {count} rows.');
  assert.equal(ADMIN_STORE_TEXTS.bad_request, 'The server refused the request. Check the confirmation and try again.');
  assert.equal(ADMIN_STORE_TEXTS.unauthorized, 'You are not signed in. Sign in again to clear the store.');
  assert.equal(ADMIN_STORE_TEXTS.forbidden, 'Your account is not an admin. An admin role is required.');
  assert.equal(ADMIN_STORE_TEXTS.not_found, 'No such account. The name was not found.');
  assert.equal(ADMIN_STORE_TEXTS.unknown, 'The server answered with a status this panel does not understand.');
  assert.equal(ADMIN_STORE_TEXTS.network, 'The server is unavailable. Try again in a moment.');
});

test('admin-store.js contains no DOM or storage tokens', () => {
  const source = readFileSync(new URL('../../main/resources/web/js/admin-store.js', import.meta.url), 'utf8');
  for (const token of ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'localStorage', 'sessionStorage', 'document.cookie', 'window.confirm']) {
    assert.equal(source.includes(token), false, 'forbidden token present: ' + token);
  }
});
