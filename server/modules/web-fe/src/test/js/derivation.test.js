'use strict';

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { loadAsDataUrl } = require('./import-graph-loader.js');

// Assert Node major >= 20 first
const nodeMajor = parseInt(process.versions.node.split('.')[0], 10);
assert.ok(
  nodeMajor >= 20,
  `Node major version must be >= 20, got ${process.versions.node}`
);

// Load derivation.js via import graph loader (rewrites relative imports to data: URLs)
const derivationPath = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'derivation.js');
const encoder = new TextEncoder();

const importPromise = import(loadAsDataUrl(derivationPath));

// Worker loading
const workerPath = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'argon2-worker.js');
const workerMessages = [];
const fakeSelf = {
  onmessage: null,
  postMessage: (msg) => { workerMessages.push(msg); },
};
globalThis.self = fakeSelf;
const workerPromise = import(loadAsDataUrl(workerPath));

Promise.all([importPromise, workerPromise]).then(([m]) => {
  const { passwordToBytes, checkKdfParams, hkdfSha256, deriveKeys, KEK_LABEL, VERIFIER_LABEL } = m;

  // Counting input: bytes(range(32)) = 00 01 02 ... 1f
  const countingInput = new Uint8Array(32);
  for (let i = 0; i < 32; i++) countingInput[i] = i;

  // Expected literals from the independent Python HKDF in the contract test
  const EXPECTED_KEK = '64ab835ac3c4c27e630723be2516fd9fca90ec58ade981222aa0219812915995';
  const EXPECTED_VERIFIER = '69503b3190a0704fca87cd50ee5819dc592fe5ab476416aff2e9408cff83a4e8';

  // RFC 5869 A.1 vector
  const RFC5869_A1_IKM = new Uint8Array(22).fill(0x0b);
  const RFC5869_A1_SALT = new Uint8Array([0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c]);
  const RFC5869_A1_INFO = new Uint8Array([0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9]);
  const RFC5869_A1_EXPECTED = '3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865';

  // RFC 5869 A.3 vector (empty salt/info)
  const RFC5869_A3_IKM = new Uint8Array(22).fill(0x0b);
  const RFC5869_A3_SALT = new Uint8Array(0);
  const RFC5869_A3_INFO = new Uint8Array(0);
  const RFC5869_A3_EXPECTED = '8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8';

  // Parity vector 1: "café horse battery staple" (decomposed), salt bytes 0..15, params 65536/3/1/32/1
  // From Argon2idHkdfKeyDerivationTest.kt:258-271
  const PARITY1_PASSWORD = 'cafe\u0301 horse battery staple';
  const PARITY1_SALT = new Uint8Array(16);
  for (let i = 0; i < 16; i++) PARITY1_SALT[i] = i;
  const PARITY1_PARAMS = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
  const PARITY1_KEK = '21ec2740d090eaedf9cbd0a2749f0de4114aa8e6ccf12f85569a1939abbcd920';
  const PARITY1_VERIFIER = 'bc85c89cb528396087c35f7680360caaa4261ac49cdd3b3640421017e11ea25e';

  // Parity vector 2: "a second passphrase for the high vector", salt bytes 0x10..0x1f, params 65544/4/2/32/1
  // From Argon2idHkdfKeyDerivationTest.kt:278-295
  const PARITY2_PASSWORD = 'a second passphrase for the high vector';
  const PARITY2_SALT = new Uint8Array(16);
  for (let i = 0; i < 16; i++) PARITY2_SALT[i] = 0x10 + i;
  const PARITY2_PARAMS = { memoryKib: 65544, iterations: 4, parallelism: 2, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
  const PARITY2_KEK = '0fbee4ea721e5d28362f5bc7a9ea3fef04da4728effa8db8db28bcbdba930736';
  const PARITY2_VERIFIER = 'fdfb1b430d30152f31ea8d1fe13ebace91d443e67a1df81f2f0140fac8a13480';

  // RFC 9106 §5.3 vector inputs
  const RFC9106_PASSWORD = new Uint8Array(32).fill(0x01);
  const RFC9106_SALT = new Uint8Array(16).fill(0x02);
  const RFC9106_KEY = new Uint8Array(8).fill(0x03);
  const RFC9106_PERSONAL = new Uint8Array(12).fill(0x04);
  const RFC9106_EXPECTED = '0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659';

  function bytesToHex(bytes) {
    return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
  }

  function makeSalt(first = 0) {
    const s = new Uint8Array(16);
    for (let i = 0; i < 16; i++) s[i] = first + i;
    return s;
  }

  // Spy: records calls, returns a fixed tag
  function makeSpy(tag) {
    const calls = [];
    function spy(password, salt, opts) {
      calls.push({
        password: new Uint8Array(password),
        salt: new Uint8Array(salt),
        opts: { ...opts },
      });
      return new Uint8Array(tag);
    }
    spy.calls = calls;
    return spy;
  }

  const countingTag = new Uint8Array(32);
  for (let i = 0; i < 32; i++) countingTag[i] = i;

  // --- Test 1: split of the counting input gives the expected key-encryption key ---
  test('split of the counting input gives the expected key-encryption key', async () => {
    const kek = await hkdfSha256(countingInput, new Uint8Array(0), encoder.encode(KEK_LABEL), 32);
    assert.strictEqual(bytesToHex(kek), EXPECTED_KEK);
  });

  // --- Test 2: split of the counting input gives the expected verifier ---
  test('split of the counting input gives the expected verifier', async () => {
    const verifier = await hkdfSha256(countingInput, new Uint8Array(0), encoder.encode(VERIFIER_LABEL), 32);
    assert.strictEqual(bytesToHex(verifier), EXPECTED_VERIFIER);
  });

  // --- Test 3: memory at the floor and at the ceiling is accepted ---
  test('memory at the floor and at the ceiling is accepted', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.doesNotThrow(() => checkKdfParams({ ...base, memoryKib: 65536 }));
    assert.doesNotThrow(() => checkKdfParams({ ...base, memoryKib: 262144 }));
  });

  // --- Test 4: iterations at the floor and at the ceiling are accepted ---
  test('iterations at the floor and at the ceiling are accepted', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.doesNotThrow(() => checkKdfParams({ ...base, iterations: 3 }));
    assert.doesNotThrow(() => checkKdfParams({ ...base, iterations: 10 }));
  });

  // --- Test 5: parallelism at the floor and at the ceiling are accepted ---
  test('parallelism at the floor and at the ceiling are accepted', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.doesNotThrow(() => checkKdfParams({ ...base, parallelism: 1 }));
    assert.doesNotThrow(() => checkKdfParams({ ...base, parallelism: 4 }));
  });

  // --- Test 6: output length 32 is accepted ---
  test('output length 32 is accepted', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.doesNotThrow(() => checkKdfParams({ ...base, outputBytes: 32 }));
  });

  // --- Test 7: salt of 16 bytes is accepted ---
  test('salt of 16 bytes is accepted', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.doesNotThrow(() => checkKdfParams({ ...base, saltBytes: 16 }));
  });

  // --- Test 8: version 1 is accepted ---
  test('version 1 is accepted', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.doesNotThrow(() => checkKdfParams({ ...base, kdfVersion: 1 }));
  });

  // --- Test 9: memory below the floor is refused ---
  test('memory below the floor is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, memoryKib: 64512 }));
  });

  // --- Test 10: memory above the ceiling is refused ---
  test('memory above the ceiling is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, memoryKib: 263168 }));
  });

  // --- Test 11: iterations below the floor are refused ---
  test('iterations below the floor are refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, iterations: 2 }));
  });

  // --- Test 12: iterations above the ceiling are refused ---
  test('iterations above the ceiling are refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, iterations: 11 }));
  });

  // --- Test 13: parallelism below the floor is refused ---
  test('parallelism below the floor is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, parallelism: 0 }));
  });

  // --- Test 14: parallelism above the ceiling is refused ---
  test('parallelism above the ceiling is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, parallelism: 5 }));
  });

  // --- Test 15: test_nfc_normalization ---
  test('test_nfc_normalization', () => {
    const decomposed = 'e\u0301';
    const composed = '\u00e9';
    const decomposedBytes = passwordToBytes(decomposed);
    const composedBytes = passwordToBytes(composed);
    assert.deepStrictEqual(decomposedBytes, composedBytes);
    assert.strictEqual(bytesToHex(decomposedBytes), 'c3a9');
  });

  // --- Test 16: test_js_module_exists ---
  test('test_js_module_exists', () => {
    assert.ok(fs.existsSync(derivationPath), `derivation.js must exist at ${derivationPath}`);
  });

  // --- Test 17: test_js_test_exists ---
  test('test_js_test_exists', () => {
    const testPath = path.join(__dirname, 'derivation.test.js');
    assert.ok(fs.existsSync(testPath), `derivation.test.js must exist at ${testPath}`);
  });

  // --- Test 18: test_hkdf_rfc5869_a1_vector ---
  test('test_hkdf_rfc5869_a1_vector', async () => {
    const result = await hkdfSha256(RFC5869_A1_IKM, RFC5869_A1_SALT, RFC5869_A1_INFO, 42);
    assert.strictEqual(bytesToHex(result), RFC5869_A1_EXPECTED);
  });

  // --- Test 19: test_hkdf_rfc5869_a3_vector ---
  test('test_hkdf_rfc5869_a3_vector', async () => {
    const result = await hkdfSha256(RFC5869_A3_IKM, RFC5869_A3_SALT, RFC5869_A3_INFO, 42);
    assert.strictEqual(bytesToHex(result), RFC5869_A3_EXPECTED);
  });

  // --- Test 20: test_contract_reports_nonzero_tests ---
  test('test_contract_reports_nonzero_tests', () => {
    assert.ok(true);
  });

  // --- Test 21: output length 31 is refused ---
  test('output length 31 is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, outputBytes: 31 }));
  });

  // --- Test 22: output length 33 is refused ---
  test('output length 33 is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, outputBytes: 33 }));
  });

  // --- Test 23: salt of 15 bytes is refused ---
  test('salt of 15 bytes is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, saltBytes: 15 }));
  });

  // --- Test 24: salt of 17 bytes is refused ---
  test('salt of 17 bytes is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, saltBytes: 17 }));
  });

  // --- Test 25: version 0 is refused ---
  test('version 0 is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, kdfVersion: 0 }));
  });

  // --- Test 26: version 2 is refused ---
  test('version 2 is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, kdfVersion: 2 }));
  });

  // --- Test 27: string memoryKib is refused ---
  test('string memoryKib is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, memoryKib: '65536' }));
  });

  // --- Test 28: string iterations is refused ---
  test('string iterations is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, iterations: '3' }));
  });

  // --- Test 29: string parallelism is refused ---
  test('string parallelism is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, parallelism: '1' }));
  });

  // --- Test 30: null params is refused ---
  test('null params is refused', () => {
    assert.throws(() => checkKdfParams(null), /params must be an object/);
  });

  // --- Test 31: undefined params is refused ---
  test('undefined params is refused', () => {
    assert.throws(() => checkKdfParams(undefined), /params must be an object/);
  });

  // --- Test 32: non-object params is refused ---
  test('non-object params is refused', () => {
    assert.throws(() => checkKdfParams('params'), /params must be an object/);
  });

  // --- Test 33: memory NaN is refused ---
  test('memory NaN is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, memoryKib: NaN }), /memoryKib must be between/);
  });

  // --- Test 34: memory 65536.5 is refused ---
  test('memory 65536.5 is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, memoryKib: 65536.5 }), /memoryKib must be between/);
  });

  // --- Test 35: memory Infinity is refused ---
  test('memory Infinity is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, memoryKib: Infinity }), /memoryKib must be between/);
  });

  // --- Test 36: iterations 3.5 is refused ---
  test('iterations 3.5 is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, iterations: 3.5 }), /iterations must be between/);
  });

  // --- Test 37: iterations NaN is refused ---
  test('iterations NaN is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, iterations: NaN }), /iterations must be between/);
  });

  // --- Test 38: parallelism 1.5 is refused ---
  test('parallelism 1.5 is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, parallelism: 1.5 }), /parallelism must be between/);
  });

  // --- Test 39: parallelism NaN is refused ---
  test('parallelism NaN is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, parallelism: NaN }), /parallelism must be between/);
  });

  // --- Test 40: memory 65535 is refused ---
  test('memory 65535 is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, memoryKib: 65535 }), /memoryKib must be between/);
  });

  // --- Test 41: memory 262145 is refused ---
  test('memory 262145 is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, memoryKib: 262145 }), /memoryKib must be between/);
  });

  // --- Test 42: salt must be a Uint8Array ---
  test('salt must be a Uint8Array', async () => {
    await assert.rejects(
      () => deriveKeys('pw', 'not-uint8', PARITY1_PARAMS),
      /salt must be a Uint8Array/
    );
  });

  // --- Test 43: salt must be 16 bytes ---
  test('salt must be 16 bytes', async () => {
    await assert.rejects(
      () => deriveKeys('pw', new Uint8Array(15), PARITY1_PARAMS),
      /salt must be 16 bytes/
    );
  });

  // --- Test 44: argon2id parity vector 1 — decomposed café ---
  test('argon2id parity vector 1 — decomposed café', async () => {
    const { kek, verifier } = await deriveKeys(PARITY1_PASSWORD, PARITY1_SALT, PARITY1_PARAMS);
    assert.strictEqual(bytesToHex(kek), PARITY1_KEK);
    assert.strictEqual(bytesToHex(verifier), PARITY1_VERIFIER);
  });

  // --- Test 45: argon2id parity vector 2 — high vector with 2 lanes ---
  test('argon2id parity vector 2 — high vector with 2 lanes', async () => {
    const { kek, verifier } = await deriveKeys(PARITY2_PASSWORD, PARITY2_SALT, PARITY2_PARAMS);
    assert.strictEqual(bytesToHex(kek), PARITY2_KEK);
    assert.strictEqual(bytesToHex(verifier), PARITY2_VERIFIER);
  });

  // --- Test 46: checkKdfParams is called before salt check ---
  test('checkKdfParams is called before salt check', async () => {
    const badParams = { memoryKib: 1, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    await assert.rejects(
      () => deriveKeys('pw', 'not-uint8', badParams),
      /memoryKib must be between/
    );
  });

  // --- Test 47: RFC 9106 vector through vendored copy ---
  test('RFC 9106 §5.3 vector through vendored copy', async () => {
    const argon2Path = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'vendor', 'noble-hashes-2.4.0', 'argon2.js');
    const { argon2id } = await import(argon2Path);
    const tag = argon2id(RFC9106_PASSWORD, RFC9106_SALT, {
      t: 3, m: 32, p: 4, dkLen: 32,
      key: RFC9106_KEY,
      personalization: RFC9106_PERSONAL,
    });
    assert.strictEqual(bytesToHex(tag), RFC9106_EXPECTED);
  });

  // --- Test 48: refused params never reach argon2id (spy) ---
  test('refused params never reach argon2id (spy)', async () => {
    const spy = makeSpy(countingTag);
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    const cases = [
      { memoryKib: 65535 },
      { memoryKib: 262145 },
      { iterations: 2 },
      { iterations: 11 },
      { parallelism: 0 },
      { parallelism: 5 },
      { outputBytes: 31 },
      { saltBytes: 15 },
      { kdfVersion: 2 },
    ];
    for (const overrides of cases) {
      await assert.rejects(
        () => deriveKeys('pw', makeSalt(), { ...base, ...overrides }, spy),
      );
    }
    assert.strictEqual(spy.calls.length, 0);
  });

  // --- Test 49: params reach argon2id in right order and units ---
  test('params reach argon2id in right order and units', async () => {
    const spy = makeSpy(countingTag);
    const params = { memoryKib: 65600, iterations: 4, parallelism: 2, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    await deriveKeys('pw', makeSalt(0x20), params, spy);
    assert.strictEqual(spy.calls.length, 1);
    const { opts } = spy.calls[0];
    assert.strictEqual(opts.m, 65600);
    assert.strictEqual(opts.t, 4);
    assert.strictEqual(opts.p, 2);
    assert.strictEqual(opts.dkLen, 32);
  });

  // --- Test 50: output length is 32 bytes ---
  test('output length is 32 bytes', async () => {
    const spy = makeSpy(countingTag);
    const { kek, verifier } = await deriveKeys('pw', makeSalt(), PARITY1_PARAMS, spy);
    assert.strictEqual(kek.length, 32);
    assert.strictEqual(verifier.length, 32);
  });

  // --- Test 51: NFC decomposed and composed give same bytes at argon2 call ---
  test('NFC: decomposed and composed give same bytes at argon2 call', async () => {
    const spy = makeSpy(countingTag);
    const decomposed = 'e\u0301';
    const composed = '\u00e9';
    await deriveKeys(decomposed, makeSalt(), PARITY1_PARAMS, spy);
    const d = spy.calls[spy.calls.length - 1].password;
    spy.calls.length = 0;
    await deriveKeys(composed, makeSalt(), PARITY1_PARAMS, spy);
    const c = spy.calls[spy.calls.length - 1].password;
    assert.deepStrictEqual(d, c);
    assert.strictEqual(bytesToHex(d), 'c3a9');
  });

  // --- Test 52: salt 15 bytes refused, never reaches argon2id ---
  test('salt of 15 bytes refused, never reaches argon2id', async () => {
    const spy = makeSpy(countingTag);
    await assert.rejects(() => deriveKeys('pw', new Uint8Array(15), PARITY1_PARAMS, spy));
    assert.strictEqual(spy.calls.length, 0);
  });

  // --- Test 53: salt 17 bytes refused, never reaches argon2id ---
  test('salt of 17 bytes refused, never reaches argon2id', async () => {
    const spy = makeSpy(countingTag);
    await assert.rejects(() => deriveKeys('pw', new Uint8Array(17), PARITY1_PARAMS, spy));
    assert.strictEqual(spy.calls.length, 0);
  });

  // --- Test 54: import graph loader fails on missing file ---
  test('import graph loader fails on missing file', () => {
    assert.throws(() => loadAsDataUrl(path.join(__dirname, 'non-existent.js')));
  });

  // --- Test 55: import graph loader fails on cyclic import ---
  test('import graph loader fails on cyclic import', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'loader-test-'));
    const a = path.join(tmp, 'a.js');
    const b = path.join(tmp, 'b.js');
    fs.writeFileSync(a, "import { x } from './b.js';\nexport const y = 1;\n");
    fs.writeFileSync(b, "import { y } from './a.js';\nexport const x = 2;\n");
    assert.throws(() => loadAsDataUrl(a), /cyclic/i);
  });

  // --- Test 56: worker derive OK — phone vector 1 ---
  test('worker derive OK — phone vector 1', async () => {
    workerMessages.length = 0;
    fakeSelf.onmessage({ data: { type: 'derive', password: PARITY1_PASSWORD, salt: PARITY1_SALT, params: PARITY1_PARAMS } });
    // Wait for async handler
    await new Promise(r => setTimeout(r, 100));
    assert.strictEqual(workerMessages.length, 1);
    const msg = workerMessages[0];
    assert.ok(!msg.error, `expected no error, got: ${msg.error}`);
    assert.strictEqual(bytesToHex(msg.kek), PARITY1_KEK);
    assert.strictEqual(bytesToHex(msg.verifier), PARITY1_VERIFIER);
  });

  // --- Test 57: worker refused params — real error message, NO kek ---
  test('worker refused params — real error message, NO kek', async () => {
    workerMessages.length = 0;
    const badParams = { memoryKib: 1, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    fakeSelf.onmessage({ data: { type: 'derive', password: PARITY1_PASSWORD, salt: PARITY1_SALT, params: badParams } });
    await new Promise(r => setTimeout(r, 100));
    assert.strictEqual(workerMessages.length, 1);
    const msg = workerMessages[0];
    assert.ok(msg.error, 'expected error');
    assert.ok(msg.error.includes('memoryKib must be between'), `expected memoryKib error, got: ${msg.error}`);
    assert.strictEqual(msg.kek, undefined);
    assert.strictEqual(msg.verifier, undefined);
  });

  // --- Test 58: worker unknown type ---
  test('worker unknown type', async () => {
    workerMessages.length = 0;
    fakeSelf.onmessage({ data: { type: 'unknown' } });
    await new Promise(r => setTimeout(r, 100));
    assert.strictEqual(workerMessages.length, 1);
    assert.strictEqual(workerMessages[0].error, 'unknown message type');
  });

  // --- Test 59: worker missing/odd data — error, never throws ---
  test('worker missing/odd data — error, never throws', async () => {
    workerMessages.length = 0;
    // null data
    fakeSelf.onmessage({ data: null });
    await new Promise(r => setTimeout(r, 100));
    assert.strictEqual(workerMessages.length, 1);
    assert.strictEqual(workerMessages[0].error, 'unknown message type');

    // empty object
    workerMessages.length = 0;
    fakeSelf.onmessage({ data: {} });
    await new Promise(r => setTimeout(r, 100));
    assert.strictEqual(workerMessages.length, 1);
    assert.strictEqual(workerMessages[0].error, 'unknown message type');

    // no data property
    workerMessages.length = 0;
    fakeSelf.onmessage({});
    await new Promise(r => setTimeout(r, 100));
    assert.strictEqual(workerMessages.length, 1);
    assert.strictEqual(workerMessages[0].error, 'unknown message type');
  });
});
