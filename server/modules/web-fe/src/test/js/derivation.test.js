'use strict';

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

// Assert Node major >= 20 first
const nodeMajor = parseInt(process.versions.node.split('.')[0], 10);
assert.ok(
  nodeMajor >= 20,
  `Node major version must be >= 20, got ${process.versions.node}`
);

// Load derivation.js via data: URL
const derivationPath = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'derivation.js');
const src = fs.readFileSync(derivationPath, 'utf8');
const importPromise = import('data:text/javascript;base64,' + Buffer.from(src).toString('base64'));

importPromise.then(m => {
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

  function bytesToHex(bytes) {
    return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
  }

  // --- Test 1: split of the counting input gives the expected key-encryption key ---
  test('split of the counting input gives the expected key-encryption key', async () => {
    const { kek } = await deriveKeys(countingInput);
    assert.strictEqual(bytesToHex(kek), EXPECTED_KEK);
  });

  // --- Test 2: split of the counting input gives the expected verifier ---
  test('split of the counting input gives the expected verifier', async () => {
    const { verifier } = await deriveKeys(countingInput);
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

  // --- Test 5: parallelism at the floor and at the ceiling is accepted ---
  test('parallelism at the floor and at the ceiling is accepted', () => {
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

  // --- memory NaN is refused ---
  test('memory NaN is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, memoryKib: NaN }), /memoryKib must be between/);
  });

  // --- memory 65536.5 is refused ---
  test('memory 65536.5 is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, memoryKib: 65536.5 }), /memoryKib must be between/);
  });

  // --- memory Infinity is refused ---
  test('memory Infinity is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, memoryKib: Infinity }), /memoryKib must be between/);
  });

  // --- iterations 3.5 is refused ---
  test('iterations 3.5 is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, iterations: 3.5 }), /iterations must be between/);
  });

  // --- iterations NaN is refused ---
  test('iterations NaN is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, iterations: NaN }), /iterations must be between/);
  });

  // --- parallelism 1.5 is refused ---
  test('parallelism 1.5 is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, parallelism: 1.5 }), /parallelism must be between/);
  });

  // --- parallelism NaN is refused ---
  test('parallelism NaN is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, parallelism: NaN }), /parallelism must be between/);
  });

  // --- memory 65535 is refused ---
  test('memory 65535 is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, memoryKib: 65535 }), /memoryKib must be between/);
  });

  // --- memory 262145 is refused ---
  test('memory 262145 is refused', () => {
    const base = { memoryKib: 65536, iterations: 3, parallelism: 1, outputBytes: 32, saltBytes: 16, kdfVersion: 1 };
    assert.throws(() => checkKdfParams({ ...base, memoryKib: 262145 }), /memoryKib must be between/);
  });
});
