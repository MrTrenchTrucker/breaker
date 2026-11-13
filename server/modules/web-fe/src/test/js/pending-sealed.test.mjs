import test from 'node:test';
import assert from 'node:assert/strict';
import { createPendingSealed } from '../../main/resources/web/js/pending-sealed.js';

function renderOf() {
  const messages = [];
  const signedOut = [];
  const render = {
    showMessage: (text) => { messages.push(text); },
    showSignedOut: () => { signedOut.push(undefined); },
  };
  return { render, messages, signedOut };
}

function cryptoOf({ unseal = async () => null, encrypt = async () => null } = {}) {
  return { unseal, encrypt };
}

test('200 list converts one row and uploads with client id = record id', async () => {
  const unseal = async (sealedBytes, boxPrivateKey) => new Uint8Array([1, 2, 3]);
  const uploadCalls = [];
  const upload = async (clientId, keyVersion, sealed) => {
    uploadCalls.push({ clientId, keyVersion });
    return 'CONFIRMED';
  };
  const fetchFn = async () => ({ status: 200, body: [{ id: 'rec-1', sealed_box: 'AA==', created_at: '2026-10-11T12:00:00Z' }] });
  const deps = cryptoOf({ unseal, encrypt: async (plaintext, clientId, keyVersion) => ({ nonce: new Uint8Array(12), ciphertext: new Uint8Array(5), tag: new Uint8Array(16) }) });
  deps.upload = upload;
  deps.fetch = fetchFn;
  const { render } = renderOf();
  const flow = createPendingSealed(deps);
  const result = await flow.run('tok', new Uint8Array(32), 2, render);
  assert.equal(result.status, 'ok');
  assert.equal(result.converted, 1);
  assert.equal(result.skipped, 0);
  assert.equal(result.failed, 0);
  assert.equal(uploadCalls.length, 1);
  assert.equal(uploadCalls[0].clientId, 'rec-1');
  assert.equal(uploadCalls[0].keyVersion, 2);
});

test('unseal refusal skips the row and converts nothing', async () => {
  const unseal = async () => null;
  const upload = async () => { throw new Error('must not upload'); };
  const fetchFn = async () => ({ status: 200, body: [{ id: 'rec-1', sealed_box: 'AA==', created_at: '2026-10-11T12:00:00Z' }] });
  const deps = cryptoOf({ unseal, encrypt: async () => null });
  deps.upload = upload;
  deps.fetch = fetchFn;
  const { render, messages } = renderOf();
  const flow = createPendingSealed(deps);
  const result = await flow.run('tok', new Uint8Array(32), 2, render);
  assert.equal(result.status, 'ok');
  assert.equal(result.converted, 0);
  assert.equal(result.skipped, 1);
  assert.equal(result.failed, 0);
  assert.equal(messages.length, 0);
});

test('encrypt throws skip the row and keep going', async () => {
  const unseal = async () => new Uint8Array([1]);
  const encrypt = async () => { throw new Error('refused'); };
  const upload = async () => { throw new Error('must not upload'); };
  const fetchFn = async () => ({ status: 200, body: [{ id: 'rec-1', sealed_box: 'AA==', created_at: '2026-10-11T12:00:00Z' }] });
  const deps = cryptoOf({ unseal, encrypt });
  deps.upload = upload;
  deps.fetch = fetchFn;
  const { render } = renderOf();
  const flow = createPendingSealed(deps);
  const result = await flow.run('tok', new Uint8Array(32), 2, render);
  assert.equal(result.status, 'ok');
  assert.equal(result.converted, 0);
  assert.equal(result.skipped, 1);
  assert.equal(result.failed, 0);
});

test('401 signs out and converts nothing', async () => {
  const fetchFn = async () => ({ status: 401, body: null });
  const deps = cryptoOf({});
  deps.fetch = fetchFn;
  const { render, signedOut } = renderOf();
  const flow = createPendingSealed(deps);
  const result = await flow.run('tok', new Uint8Array(32), 2, render);
  assert.equal(result.status, 'signed_out');
  assert.equal(signedOut.length, 1);
});

test('403 signs out and converts nothing', async () => {
  const fetchFn = async () => ({ status: 403, body: null });
  const deps = cryptoOf({});
  deps.fetch = fetchFn;
  const { render, signedOut } = renderOf();
  const flow = createPendingSealed(deps);
  const result = await flow.run('tok', new Uint8Array(32), 2, render);
  assert.equal(result.status, 'signed_out');
  assert.equal(signedOut.length, 1);
});

test('503 shows list_failed and counts one failure', async () => {
  const fetchFn = async () => ({ status: 503, body: null });
  const deps = cryptoOf({});
  deps.fetch = fetchFn;
  const { render, messages } = renderOf();
  const flow = createPendingSealed(deps);
  const result = await flow.run('tok', new Uint8Array(32), 2, render);
  assert.equal(result.status, 'list_failed');
  assert.equal(result.failed, 1);
  assert.equal(result.converted, 0);
  assert.equal(messages[0], 'The pending-sealed list could not be read. Try again in a moment.');
});

test('non-array body shows list_failed and counts one failure', async () => {
  const fetchFn = async () => ({ status: 200, body: 'not an array' });
  const deps = cryptoOf({});
  deps.fetch = fetchFn;
  const { render, messages } = renderOf();
  const flow = createPendingSealed(deps);
  const result = await flow.run('tok', new Uint8Array(32), 2, render);
  assert.equal(result.status, 'list_failed');
  assert.equal(result.failed, 1);
});

test('bad rows are skipped but good rows still convert', async () => {
  const unseal = async () => new Uint8Array([1]);
  let uploadCalls = 0;
  const upload = async (clientId, keyVersion, sealed) => {
    uploadCalls += 1;
    return 'CONFIRMED';
  };
  const fetchFn = async () => ({ status: 200, body: [
    { id: 'rec-1', sealed_box: 'bad-base64!!', created_at: '2026-10-11T12:00:00Z' },
    { id: 'rec-2', sealed_box: 'cmVwbGFjZS13aXRoLXNhbXBsZS1zZWFsZWQtYm94', created_at: '2026-10-11T12:00:00Z' },
    { id: 123, sealed_box: 'cmVwbGFjZS13aXRoLXNhbXBsZS1zZWFsZWQtYm94', created_at: '2026-10-11T12:00:00Z' },
  ] });
  const deps = cryptoOf({ unseal, encrypt: async () => ({ nonce: new Uint8Array(12), ciphertext: new Uint8Array(5), tag: new Uint8Array(16) }) });
  deps.upload = upload;
  deps.fetch = fetchFn;
  const { render } = renderOf();
  const flow = createPendingSealed(deps);
  const result = await flow.run('tok', new Uint8Array(32), 2, render);
  assert.equal(result.status, 'ok');
  assert.equal(result.skipped, 2);
  assert.equal(result.converted, 1);
  assert.equal(uploadCalls, 1);
});

test('upload refused counts as failed and the run goes on', async () => {
  const unseal = async () => new Uint8Array([1]);
  const upload = async () => 'FAILED';
  const fetchFn = async () => ({ status: 200, body: [{ id: 'rec-1', sealed_box: 'cmVwbGFjZS13aXRoLXNhbXBsZS1zZWFsZWQtYm94', created_at: '2026-10-11T12:00:00Z' }] });
  const deps = cryptoOf({ unseal, encrypt: async () => ({ nonce: new Uint8Array(12), ciphertext: new Uint8Array(5), tag: new Uint8Array(16) }) });
  deps.upload = upload;
  deps.fetch = fetchFn;
  const { render } = renderOf();
  const flow = createPendingSealed(deps);
  const result = await flow.run('tok', new Uint8Array(32), 2, render);
  assert.equal(result.status, 'ok');
  assert.equal(result.converted, 0);
  assert.equal(result.skipped, 0);
  assert.equal(result.failed, 1);
});

test('empty list converts nothing and reports ok', async () => {
  const fetchFn = async () => ({ status: 200, body: [] });
  const deps = cryptoOf({});
  deps.fetch = fetchFn;
  const { render } = renderOf();
  const flow = createPendingSealed(deps);
  const result = await flow.run('tok', new Uint8Array(32), 2, render);
  assert.equal(result.status, 'ok');
  assert.equal(result.converted, 0);
  assert.equal(result.skipped, 0);
  assert.equal(result.failed, 0);
});

test('unseal returns null skip does not stop subsequent rows', async () => {
  const unseal = async (sealedBytes, boxPrivateKey) => {
    if (sealedBytes[0] === 0) return null;
    return new Uint8Array([1]);
  };
  const uploadCalls = [];
  const upload = async (clientId, keyVersion, sealed) => {
    uploadCalls.push(clientId);
    return 'CONFIRMED';
  };
  const fetchFn = async () => ({ status: 200, body: [
    { id: 'rec-1', sealed_box: 'AA==', created_at: '2026-10-11T12:00:00Z' },
    { id: 'rec-2', sealed_box: 'cmVwbGFjZS13aXRoLXNhbXBsZS1zZWFsZWQtYm94', created_at: '2026-10-11T12:00:00Z' },
  ] });
  const deps = cryptoOf({ unseal, encrypt: async () => ({ nonce: new Uint8Array(12), ciphertext: new Uint8Array(5), tag: new Uint8Array(16) }) });
  deps.upload = upload;
  deps.fetch = fetchFn;
  const { render } = renderOf();
  const flow = createPendingSealed(deps);
  const result = await flow.run('tok', new Uint8Array(32), 2, render);
  assert.equal(result.status, 'ok');
  assert.equal(result.skipped, 1);
  assert.equal(result.converted, 1);
  assert.deepEqual(uploadCalls, ['rec-2']);
});

// --- Added by the gate: what the conversion hands to encrypt and upload, and what it does with its plaintext. ---

const GOOD_BOX = 'cmVwbGFjZS13aXRoLXNhbXBsZS1zZWFsZWQtYm94';
const SEALED = { nonce: new Uint8Array(12), ciphertext: new Uint8Array(5), tag: new Uint8Array(16) };

function rowOf(id) {
  return { id, sealed_box: GOOD_BOX, created_at: '2026-10-11T12:00:00Z' };
}

function flowOf({ rows, unseal, encrypt, upload }) {
  return createPendingSealed({ unseal, encrypt, upload, fetch: async () => ({ status: 200, body: rows }) });
}

test('encrypt gets the unsealed bytes intact, the record id and the key version', async () => {
  const seen = [];
  const flow = flowOf({
    rows: [rowOf('rec-9')],
    unseal: async () => new Uint8Array([7, 8, 9]),
    encrypt: async (plaintext, clientId, keyVersion) => {
      seen.push({ bytes: Array.from(plaintext), clientId, keyVersion });
      return SEALED;
    },
    upload: async () => 'CONFIRMED',
  });
  const result = await flow.run('tok', new Uint8Array(32), 4, renderOf().render);
  assert.equal(result.converted, 1);
  assert.deepEqual(seen, [{ bytes: [7, 8, 9], clientId: 'rec-9', keyVersion: 4 }]);
});

test('upload gets the record id, the key version and exactly what encrypt returned', async () => {
  const sealed = { nonce: new Uint8Array(12).fill(1), ciphertext: new Uint8Array(3).fill(2), tag: new Uint8Array(16).fill(3) };
  const seen = [];
  const flow = flowOf({
    rows: [rowOf('rec-5')],
    unseal: async () => new Uint8Array([1]),
    encrypt: async () => sealed,
    upload: async (clientId, keyVersion, body) => {
      seen.push({ clientId, keyVersion, same: body === sealed });
      return 'CONFIRMED';
    },
  });
  await flow.run('tok', new Uint8Array(32), 3, renderOf().render);
  assert.deepEqual(seen, [{ clientId: 'rec-5', keyVersion: 3, same: true }]);
});

test('the plaintext is wiped after encrypt has answered', async () => {
  const held = new Uint8Array([5, 6, 7]);
  const flow = flowOf({
    rows: [rowOf('rec-1')],
    unseal: async () => held,
    encrypt: async () => SEALED,
    upload: async () => 'CONFIRMED',
  });
  await flow.run('tok', new Uint8Array(32), 2, renderOf().render);
  assert.deepEqual(Array.from(held), [0, 0, 0]);
});

test('the plaintext is wiped when encrypt throws', async () => {
  const held = new Uint8Array([5, 6, 7]);
  const flow = flowOf({
    rows: [rowOf('rec-1')],
    unseal: async () => held,
    encrypt: async () => { throw new Error('refused'); },
    upload: async () => { throw new Error('must not upload'); },
  });
  const result = await flow.run('tok', new Uint8Array(32), 2, renderOf().render);
  assert.equal(result.skipped, 1);
  assert.deepEqual(Array.from(held), [0, 0, 0]);
});

test('an unseal that throws skips that row and the next row still converts', async () => {
  const uploaded = [];
  let calls = 0;
  const flow = flowOf({
    rows: [rowOf('rec-1'), rowOf('rec-2')],
    unseal: async () => {
      calls += 1;
      if (calls === 1) throw new Error('boom');
      return new Uint8Array([1]);
    },
    encrypt: async () => SEALED,
    upload: async (clientId) => { uploaded.push(clientId); return 'CONFIRMED'; },
  });
  const result = await flow.run('tok', new Uint8Array(32), 2, renderOf().render);
  assert.deepEqual({ converted: result.converted, skipped: result.skipped, failed: result.failed }, { converted: 1, skipped: 1, failed: 0 });
  assert.deepEqual(uploaded, ['rec-2']);
});

test('an upload that throws counts as failed and the next row still converts', async () => {
  const tried = [];
  const flow = flowOf({
    rows: [rowOf('rec-1'), rowOf('rec-2')],
    unseal: async () => new Uint8Array([1]),
    encrypt: async () => SEALED,
    upload: async (clientId) => {
      tried.push(clientId);
      if (clientId === 'rec-1') throw new Error('network');
      return 'CONFIRMED';
    },
  });
  const result = await flow.run('tok', new Uint8Array(32), 2, renderOf().render);
  assert.deepEqual({ converted: result.converted, skipped: result.skipped, failed: result.failed }, { converted: 1, skipped: 0, failed: 1 });
  assert.deepEqual(tried, ['rec-1', 'rec-2']);
});

test('an upload that is unreachable is not counted as converted', async () => {
  const flow = flowOf({
    rows: [rowOf('rec-1')],
    unseal: async () => new Uint8Array([1]),
    encrypt: async () => SEALED,
    upload: async () => 'UNREACHABLE',
  });
  const result = await flow.run('tok', new Uint8Array(32), 2, renderOf().render);
  assert.deepEqual({ converted: result.converted, skipped: result.skipped, failed: result.failed }, { converted: 0, skipped: 0, failed: 1 });
});

test('an unseal answer that is not bytes is a refusal: nothing is encrypted or uploaded', async () => {
  let touched = 0;
  const flow = flowOf({
    rows: [rowOf('rec-1')],
    unseal: async () => 'plain text',
    encrypt: async () => { touched += 1; return SEALED; },
    upload: async () => { touched += 1; return 'CONFIRMED'; },
  });
  const result = await flow.run('tok', new Uint8Array(32), 2, renderOf().render);
  assert.equal(result.skipped, 1);
  assert.equal(touched, 0);
});

test('an encrypt answer of null skips the row and nothing is uploaded', async () => {
  let uploads = 0;
  const flow = flowOf({
    rows: [rowOf('rec-1')],
    unseal: async () => new Uint8Array([1]),
    encrypt: async () => null,
    upload: async () => { uploads += 1; return 'CONFIRMED'; },
  });
  const result = await flow.run('tok', new Uint8Array(32), 2, renderOf().render);
  assert.equal(result.skipped, 1);
  assert.equal(uploads, 0);
});

test('an encrypt answer of undefined skips the row and nothing is uploaded', async () => {
  let uploads = 0;
  const flow = flowOf({
    rows: [rowOf('rec-1')],
    unseal: async () => new Uint8Array([1]),
    encrypt: async () => undefined,
    upload: async () => { uploads += 1; return 'CONFIRMED'; },
  });
  const result = await flow.run('tok', new Uint8Array(32), 2, renderOf().render);
  assert.equal(result.skipped, 1);
  assert.equal(result.converted, 0);
  assert.equal(uploads, 0);
});

test('a fetch that throws is a list failure, not a thrown run', async () => {
  const { render, messages } = renderOf();
  const flow = createPendingSealed({
    unseal: async () => null,
    encrypt: async () => null,
    upload: async () => 'CONFIRMED',
    fetch: async () => { throw new Error('offline'); },
  });
  const result = await flow.run('tok', new Uint8Array(32), 2, render);
  assert.equal(result.status, 'list_failed');
  assert.equal(messages.length, 1);
});

test('unseal gets the sealed bytes decoded from the row and the box private key it was given', async () => {
  const privateKey = new Uint8Array(32).fill(9);
  const seen = [];
  const flow = flowOf({
    rows: [{ id: 'rec-1', sealed_box: 'AQID', created_at: '2026-10-11T12:00:00Z' }],
    unseal: async (bytes, key) => { seen.push({ bytes: Array.from(bytes), same: key === privateKey }); return null; },
    encrypt: async () => SEALED,
    upload: async () => 'CONFIRMED',
  });
  await flow.run('tok', privateKey, 2, renderOf().render);
  assert.deepEqual(seen, [{ bytes: [1, 2, 3], same: true }]);
});

test('a non-200 answer is a list failure even when its body looks like a list', async () => {
  let unsealed = 0;
  const flow = createPendingSealed({
    unseal: async () => { unsealed += 1; return null; },
    encrypt: async () => SEALED,
    upload: async () => 'CONFIRMED',
    fetch: async () => ({ status: 500, body: [rowOf('rec-1')] }),
  });
  const { render, messages } = renderOf();
  const result = await flow.run('tok', new Uint8Array(32), 2, render);
  assert.equal(result.status, 'list_failed');
  assert.equal(unsealed, 0);
  assert.equal(messages.length, 1);
});

test('a sealed_box that is not strict padded standard base64 is skipped and never unsealed', async () => {
  const notStrict = [
    'AB==', // trailing bits set: it decodes to one byte but re-encodes as AA==
    'AA-_', // url-safe characters
    'AA A', // an embedded space
    'AA=A', // padding in the middle
    'AAA', // length not a multiple of four
    'AA==\n', // trailing white space
    '',
  ];
  let unsealed = 0;
  const flow = flowOf({
    rows: notStrict.map((text, at) => ({ id: 'rec-' + at, sealed_box: text, created_at: '2026-10-11T12:00:00Z' })),
    unseal: async () => { unsealed += 1; return new Uint8Array([1]); },
    encrypt: async () => SEALED,
    upload: async () => 'CONFIRMED',
  });
  const result = await flow.run('tok', new Uint8Array(32), 2, renderOf().render);
  assert.deepEqual({ converted: result.converted, skipped: result.skipped, failed: result.failed }, { converted: 0, skipped: notStrict.length, failed: 0 });
  assert.equal(unsealed, 0);
});

test('a row with an empty created_at is skipped and never unsealed', async () => {
  let unsealed = 0;
  const flow = flowOf({
    rows: [{ id: 'rec-1', sealed_box: GOOD_BOX, created_at: '' }, rowOf('rec-2')],
    unseal: async () => { unsealed += 1; return new Uint8Array([1]); },
    encrypt: async () => SEALED,
    upload: async () => 'CONFIRMED',
  });
  const result = await flow.run('tok', new Uint8Array(32), 2, renderOf().render);
  assert.deepEqual({ converted: result.converted, skipped: result.skipped, failed: result.failed }, { converted: 1, skipped: 1, failed: 0 });
  assert.equal(unsealed, 1);
});
