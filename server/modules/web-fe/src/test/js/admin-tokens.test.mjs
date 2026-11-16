import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createAdminTokens, ADMIN_TOKENS_TEXTS } from '../../main/resources/web/js/admin-tokens.js';

// A fake token-calls object that records every call it is given.
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

function renderOf() {
  const rows = [];
  const messages = [];
  const secrets = [];
  const signedOut = [];
  const render = {
    showTokens: (next) => { rows.push(next); },
    showMessage: (text) => { messages.push(text); },
    showSecret: (token) => { secrets.push(token); },
    showSignedOut: () => { signedOut.push(undefined); },
  };
  return { render, rows, messages, secrets, signedOut };
}

// The server's GET answer is a bare array of AgentTokenEntry objects.
const LIST_BODY = [
  { id: 1, name: 'Syn transcription token', owner_username: 'alice@example.com', scope: 'transcribe', expires_at: null },
  { id: 2, name: 'Admin token', owner_username: 'bob@example.com', scope: 'admin', expires_at: '2027-01-02T03:04:05Z' },
];

const CREATE_BODY = {
  token: 'atk_secret_value_99',
  name: 'New token',
  owner_username: 'alice@example.com',
  scope: 'transcribe',
  expires_at: null,
};

function flowOf(net, render, getToken = () => 'tok-abc') {
  return createAdminTokens({ getToken, net, format: (iso) => (iso === null ? 'No expiry' : iso), render });
}

test('200 list maps each entry to a row and shows the rows', async () => {
  const net = netOf({ list: { status: 200, body: LIST_BODY } });
  const { render, rows } = renderOf();
  const outcome = await flowOf(net, render).load();
  assert.equal(outcome, 'ok');
  assert.equal(net.calls.length, 1);
  assert.equal(net.calls[0].op, 'list');
  assert.equal(net.calls[0].token, 'tok-abc');
  assert.equal(rows.length, 1);
  assert.deepEqual(rows[0], [
    { id: 1, name: 'Syn transcription token', owner_username: 'alice@example.com', scope: 'transcribe', expires_at: null, expires_text: 'No expiry' },
    { id: 2, name: 'Admin token', owner_username: 'bob@example.com', scope: 'admin', expires_at: '2027-01-02T03:04:05Z', expires_text: '2027-01-02T03:04:05Z' },
  ]);
});

test('a 200 whose body is not an array of entries shows the unknown sentence', async () => {
  for (const body of [{ tokens: [] }, [1, 2], [{ id: 1, name: 'Syn', owner_username: 'a@example.com', scope: 'transcribe', expires_at: null }, 7]]) {
    const net = netOf({ list: { status: 200, body } });
    const { render, rows, messages } = renderOf();
    const outcome = await flowOf(net, render).load();
    assert.equal(outcome, 'unknown');
    assert.equal(rows.length, 0);
    assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.unknown]);
  }
});

test('401 on list signs out', async () => {
  const net = netOf({ list: { status: 401, body: null } });
  const { render, rows, messages, signedOut } = renderOf();
  const outcome = await flowOf(net, render).load();
  assert.equal(outcome, 'signed_out');
  assert.equal(signedOut.length, 1);
  assert.equal(messages.length, 0);
  assert.equal(rows.length, 0);
});

test('403 on list shows the forbidden sentence and does not sign out', async () => {
  const net = netOf({ list: { status: 403, body: null } });
  const { render, messages, signedOut } = renderOf();
  const outcome = await flowOf(net, render).load();
  assert.equal(outcome, 'forbidden');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.list_403]);
  assert.equal(signedOut.length, 0);
});

test('503 on list shows the unavailable sentence', async () => {
  const net = netOf({ list: { status: 503, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).load();
  assert.equal(outcome, 'unavailable');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.list_503]);
});

test('an unknown status on list shows the unknown sentence', async () => {
  const net = netOf({ list: { status: 418, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).load();
  assert.equal(outcome, 'unknown');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.unknown]);
});

test('a thrown fetch error on list shows the network sentence', async () => {
  const net = netOf();
  net.listTokens = async () => { throw new Error('down'); };
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).load();
  assert.equal(outcome, 'network');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.network]);
});

test('201 create hands the token to the view once and reloads the list', async () => {
  let listCalls = 0;
  const net = {
    calls: [],
    listTokens: async (token) => {
      listCalls += 1;
      net.calls.push({ op: 'list', token });
      return listCalls === 1
        ? { status: 200, body: [] }
        : { status: 200, body: [LIST_BODY[0]] };
    },
    createToken: async (token, request) => {
      net.calls.push({ op: 'create', token, request });
      return { status: 201, body: CREATE_BODY };
    },
    revokeToken: async () => ({ status: 204, body: null }),
  };
  const { render, secrets, rows } = renderOf();
  const flow = flowOf(net, render);
  assert.equal(await flow.load(), 'ok');
  assert.equal(listCalls, 1, 'the mount load reads the list once');
  const outcome = await flow.createToken({ name: 'New token', owner_username: 'alice@example.com' });
  assert.equal(outcome, 'created');
  assert.deepEqual(secrets, [CREATE_BODY.token]);
  assert.equal(listCalls, 2, 'the list is re-read after a create');
  assert.equal(rows.length, 2, 'the mount load and the reload each rendered the list');
  assert.equal(rows[1][0].name, 'Syn transcription token');
  const creates = net.calls.filter((call) => call.op === 'create');
  assert.equal(creates.length, 1);
  assert.equal(creates[0].token, 'tok-abc');
  assert.deepEqual(creates[0].request, { name: 'New token', owner_username: 'alice@example.com' });
});

test('201 create answers an outcome that carries no token value', async () => {
  const net = netOf({ create: { status: 201, body: CREATE_BODY }, list: { status: 200, body: LIST_BODY } });
  const { render, secrets } = renderOf();
  const flow = flowOf(net, render);
  const outcome = await flow.createToken({ name: 'New token', owner_username: 'alice@example.com' });
  assert.equal(outcome, 'created');
  assert.equal(outcome.includes(CREATE_BODY.token), false);
  assert.deepEqual(secrets, [CREATE_BODY.token]);
  assert.deepEqual(Object.keys(flow).sort(), ['createToken', 'load', 'revoke']);
  assert.equal(JSON.stringify(flow), '{}', 'the flow object holds no data property');
});

test('a 201 whose body carries no token string shows the unknown sentence', async () => {
  for (const body of [{ name: 'New token' }, { token: 7 }, null]) {
    const net = netOf({ create: { status: 201, body } });
    const { render, secrets, messages } = renderOf();
    const outcome = await flowOf(net, render).createToken({ name: 'New token', owner_username: 'alice@example.com' });
    assert.equal(outcome, 'failed');
    assert.deepEqual(secrets, [], 'a token value that is not a string is never shown');
    assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.unknown], 'never a create sentence that promises a token');
  }
});

test('an empty or all-blank name shows the bad-request sentence and sends nothing', async () => {
  for (const name of ['', '   ', '\t\n', null, 7]) {
    const net = netOf({ create: { status: 201, body: CREATE_BODY } });
    const { render, secrets, messages, rows } = renderOf();
    const outcome = await flowOf(net, render).createToken({ name, owner_username: 'alice@example.com' });
    assert.equal(outcome, 'failed');
    assert.equal(net.calls.length, 0, 'nothing is sent');
    assert.deepEqual(secrets, []);
    assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.create_400]);
    assert.equal(rows.length, 0, 'nothing is created');
  }
});

test('400 create shows the bad-request sentence', async () => {
  const net = netOf({ create: { status: 400, body: null } });
  const { render, messages, signedOut } = renderOf();
  const outcome = await flowOf(net, render).createToken({ name: 'x', owner_username: 'y' });
  assert.equal(outcome, 'failed');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.create_400]);
  assert.equal(signedOut.length, 0);
});

test('401 create signs out', async () => {
  const net = netOf({ create: { status: 401, body: null } });
  const { render, messages, signedOut } = renderOf();
  const outcome = await flowOf(net, render).createToken({ name: 'x', owner_username: 'y' });
  assert.equal(outcome, 'signed_out');
  assert.equal(signedOut.length, 1);
  assert.equal(messages.length, 0);
});

test('403 create shows the forbidden sentence', async () => {
  const net = netOf({ create: { status: 403, body: null } });
  const { render, messages, signedOut } = renderOf();
  const outcome = await flowOf(net, render).createToken({ name: 'x', owner_username: 'y' });
  assert.equal(outcome, 'forbidden');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.create_403]);
  assert.equal(signedOut.length, 0);
});

test('404 create shows the no-such-account sentence', async () => {
  const net = netOf({ create: { status: 404, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).createToken({ name: 'x', owner_username: 'y' });
  assert.equal(outcome, 'failed');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.create_404]);
});

test('409 create shows the conflict sentence', async () => {
  const net = netOf({ create: { status: 409, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).createToken({ name: 'x', owner_username: 'y' });
  assert.equal(outcome, 'failed');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.create_409]);
});

test('413 create shows the too-large sentence', async () => {
  const net = netOf({ create: { status: 413, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).createToken({ name: 'x', owner_username: 'y' });
  assert.equal(outcome, 'failed');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.create_413]);
});

test('503 create shows the unavailable sentence', async () => {
  const net = netOf({ create: { status: 503, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).createToken({ name: 'x', owner_username: 'y' });
  assert.equal(outcome, 'unavailable');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.create_503]);
});

test('an unknown status on create shows the unknown sentence', async () => {
  const net = netOf({ create: { status: 418, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).createToken({ name: 'x', owner_username: 'y' });
  assert.equal(outcome, 'unknown');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.unknown]);
});

test('a thrown fetch error on create shows the network sentence', async () => {
  const net = netOf();
  net.createToken = async () => { throw new Error('down'); };
  const { render, messages, secrets } = renderOf();
  const outcome = await flowOf(net, render).createToken({ name: 'x', owner_username: 'y' });
  assert.equal(outcome, 'network');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.network]);
  assert.deepEqual(secrets, []);
});

test('204 revoke reloads the list and then shows the revoked sentence', async () => {
  let listCalls = 0;
  const net = {
    calls: [],
    listTokens: async (token) => {
      listCalls += 1;
      net.calls.push({ op: 'list', token });
      return { status: 200, body: listCalls === 1 ? LIST_BODY : [LIST_BODY[0]] };
    },
    createToken: async () => ({ status: 400, body: null }),
    revokeToken: async (token, id) => {
      net.calls.push({ op: 'revoke', token, id });
      return { status: 204, body: null };
    },
  };
  const { render, messages, rows } = renderOf();
  const flow = flowOf(net, render);
  assert.equal(await flow.load(), 'ok');
  assert.equal(listCalls, 1, 'the mount load reads the list once');
  const outcome = await flow.revoke(2);
  assert.equal(outcome, 'revoked');
  assert.equal(listCalls, 2, 'the list is re-read after a revoke');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.revoke_204]);
  assert.equal(rows.length, 2, 'the mount load and the reload each rendered the list');
  assert.equal(rows[1][0].id, 1);
  const revokes = net.calls.filter((call) => call.op === 'revoke');
  assert.equal(revokes.length, 1);
  assert.equal(revokes[0].id, 2);
  assert.equal(revokes[0].token, 'tok-abc');
});

test('401 revoke signs out', async () => {
  const net = netOf({ revoke: { status: 401, body: null } });
  const { render, messages, signedOut } = renderOf();
  const outcome = await flowOf(net, render).revoke(2);
  assert.equal(outcome, 'signed_out');
  assert.equal(signedOut.length, 1);
  assert.equal(messages.length, 0);
});

test('403 revoke shows the forbidden sentence', async () => {
  const net = netOf({ revoke: { status: 403, body: null } });
  const { render, messages, signedOut } = renderOf();
  const outcome = await flowOf(net, render).revoke(2);
  assert.equal(outcome, 'forbidden');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.revoke_403]);
  assert.equal(signedOut.length, 0);
});

test('404 revoke reloads first and then shows the already-revoked sentence', async () => {
  const events = [];
  const net = {
    calls: [],
    listTokens: async (token) => {
      events.push('net:list');
      net.calls.push({ op: 'list', token });
      return { status: 200, body: [LIST_BODY[0]] };
    },
    createToken: async () => ({ status: 400, body: null }),
    revokeToken: async (token, id) => {
      events.push('net:revoke');
      net.calls.push({ op: 'revoke', token, id });
      return { status: 404, body: null };
    },
  };
  const rows = [];
  const messages = [];
  const render = {
    showTokens: (next) => { events.push('render:rows'); rows.push(next); },
    showMessage: (text) => { events.push('render:message'); messages.push(text); },
    showSecret: () => { events.push('render:secret'); },
    showSignedOut: () => { events.push('render:signed-out'); },
  };
  const outcome = await flowOf(net, render).revoke(9);
  assert.equal(outcome, 'revoked');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.revoke_404]);
  assert.equal(rows.length, 1, 'the reload rendered the server list');
  assert.deepEqual(events, ['net:revoke', 'net:list', 'render:rows', 'render:message']);
});

test('an unknown status on revoke shows the unknown sentence', async () => {
  const net = netOf({ revoke: { status: 418, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).revoke(2);
  assert.equal(outcome, 'unknown');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.unknown]);
});

test('a thrown fetch error on revoke shows the network sentence', async () => {
  const net = netOf();
  net.revokeToken = async () => { throw new Error('down'); };
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).revoke(2);
  assert.equal(outcome, 'network');
  assert.deepEqual(messages, [ADMIN_TOKENS_TEXTS.network]);
});

test('a null or empty token sends nothing and signs out', async () => {
  for (const token of [null, '']) {
    const net = netOf();
    const { render, signedOut, messages } = renderOf();
    const flow = flowOf(net, render, () => token);
    await flow.load();
    await flow.createToken({ name: 'x', owner_username: 'y' });
    await flow.revoke(2);
    assert.equal(net.calls.length, 0, 'no call without a token');
    assert.equal(signedOut.length, 3, 'each action signs the view out');
    assert.equal(messages.length, 0);
  }
});

test('ADMIN_TOKENS_TEXTS holds the eighteen fixed sentences', () => {
  assert.deepEqual(Object.keys(ADMIN_TOKENS_TEXTS), [
    'list_200', 'list_401', 'list_403', 'list_503',
    'create_201', 'create_400', 'create_401', 'create_403', 'create_404', 'create_409', 'create_413', 'create_503',
    'revoke_204', 'revoke_401', 'revoke_403', 'revoke_404',
    'unknown', 'network',
  ]);
  assert.equal(ADMIN_TOKENS_TEXTS.list_200, 'No agent tokens to show.');
  assert.equal(ADMIN_TOKENS_TEXTS.create_201, 'Agent token created. Copy it now; it is shown only once.');
  assert.equal(ADMIN_TOKENS_TEXTS.revoke_204, 'The agent token was revoked.');
  assert.equal(ADMIN_TOKENS_TEXTS.unknown, 'The server answered in a way this page does not understand.');
  assert.equal(ADMIN_TOKENS_TEXTS.network, 'The server could not be reached. Try again in a moment.');
});

test('no sentence carries the bearer token or a token value', () => {
  for (const [key, text] of Object.entries(ADMIN_TOKENS_TEXTS)) {
    assert.equal(text.includes('session-token-7'), false, key + ' carries the bearer token');
    assert.equal(text.includes('atk_secret_value_99'), false, key + ' carries a token value');
  }
});

test('admin-tokens.js contains no DOM or storage tokens', () => {
  const source = readFileSync(new URL('../../main/resources/web/js/admin-tokens.js', import.meta.url), 'utf8');
  for (const token of ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'localStorage', 'sessionStorage', 'document.cookie', 'window.confirm']) {
    assert.equal(source.includes(token), false, 'forbidden token present: ' + token);
  }
});
