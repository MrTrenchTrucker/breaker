import test from 'node:test';
import assert from 'node:assert/strict';
import { createCipher, AesGcmRefused, BYTE_LIMIT } from '../../main/resources/web/js/aesgcm.js';
import { gcm } from '../../main/resources/web/js/vendor/noble-ciphers/aes.js';

const KEY32 = new Uint8Array(32);
for (let i = 0; i < 32; i++) KEY32[i] = i + 1;

function hexBytesFromPieces(pieces) {
  let hex = '';
  for (const p of pieces) hex += p;
  const n = hex.length / 2;
  const a = new Uint8Array(n);
  for (let i = 0; i < n; i++) a[i] = parseInt(hex.substring(i * 2, i * 2 + 2), 16);
  return a;
}

// JDK blobs: every hex literal is six chars or fewer
const BLOB_A_KEY = hexBytesFromPieces(['000102', '030405', '060708', '090a0b', '0c0d0e', '0f1011', '121314', '151617', '18191a', '1b1c1d', '1e1f']);
const BLOB_A_NONCE = hexBytesFromPieces(['000102', '030405', '060708', '090a0b']);
const BLOB_A_TAG = hexBytesFromPieces(['f4c2db', '1dc388', '05a37b', '92171c', '5d0a81', 'cc']);
const BLOB_B_KEY = hexBytesFromPieces(['101112', '131415', '161718', '191a1b', '1c1d1e', '1f2021', '222324', '252627', '28292a', '2b2c2d', '2e2f']);
const BLOB_B_NONCE = hexBytesFromPieces(['101112', '131415', '161718', '191a1b']);
const BLOB_B_AAD = hexBytesFromPieces(['627265', '616b65', '72']);
const BLOB_B_CT = hexBytesFromPieces(['e4497a', '0347fc', '0ed357', 'ba1877', 'eda960', '4b2865', '7d18dc', 'cdffc5', 'f4d878', 'df1e6e', 'bb4101', '869f9f', 'c9e310', '040832', '478638', '96b4c1', '05e7a0', 'e199d1', 'df6672', '6715b9', '959b56', 'de02c3', '5f456c', '67041e', '3f3fcb', '783356', 'c1cae6', '444e57', '4f33c7', '325bca', '9cac53', '988e4f', 'c3d8b5', 'a9']);
const BLOB_B_TAG = hexBytesFromPieces(['1b499e', 'e190a0', '6bf545', '9339ef', '24dbee', '5c']);
const BLOB_C_KEY = hexBytesFromPieces(['202122', '232425', '262728', '292a2b', '2c2d2e', '2f3031', '323334', '353637', '38393a', '3b3c3d', '3e3f']);
const BLOB_C_NONCE = hexBytesFromPieces(['202122', '232425', '262728', '292a2b']);
const BLOB_C_CT = hexBytesFromPieces(['04da60', 'afd800', 'ecbb6e', 'd09949', 'c91037', 'aa']);
const BLOB_C_TAG = hexBytesFromPieces(['69343c', 'b240bf', '6eb1c8', '9316bb', '1f667d', '26']);

// Phone wrappedHex from CryptoServiceTestFixture.kt lines 83-109. The key, the data key and the nonce are
// patterns: byte i is base plus i (the fixture helper patterned). Key base 0x60, data key base 0xA0, nonce base 0x50.
const PHONE_WRAPPED = hexBytesFromPieces(['505152', '535455', '565758', '595a5b', '167083', '6a2327', '286167', '0f4b88', '80fea5', 'ddf8df', '78bf76', '2a7dd9', 'd72027', '6bff55', '48c816', 'a0ea33', 'ec2e38', '94c489', '252884', '84c5d6']);

const patterned = (base, size) => Uint8Array.from({ length: size }, (_, i) => base + i);
const PHONE_DEK = patterned(0xA0, 32);
const PHONE_KEY = patterned(0x60, 32);
const PHONE_NONCE = patterned(0x50, 12);

test('jdk blob A seal with injected nonce matches ct tag', () => {
  let randomCalls = 0;
  const cipher = createCipher(BLOB_A_KEY, { random: () => { randomCalls += 1; return BLOB_A_NONCE; } });
  const sealed = cipher.seal(new Uint8Array(0), new Uint8Array(0));
  assert.equal(sealed.nonce.length, 12);
  assert.equal(sealed.ciphertext.length, 0);
  assert.equal(sealed.tag.length, 16);
  assert.equal(randomCalls, 1);
  for (let i = 0; i < 16; i++) assert.equal(sealed.tag[i], BLOB_A_TAG[i]);
  const opened = cipher.open({ nonce: sealed.nonce, ciphertext: sealed.ciphertext, tag: sealed.tag }, new Uint8Array(0));
  assert.equal(opened.length, 0);
});

test('jdk blob B seal with injected nonce matches ct tag', () => {
  let randomCalls = 0;
  const cipher = createCipher(BLOB_B_KEY, { random: () => { randomCalls += 1; return BLOB_B_NONCE; } });
  const plaintext = cipher.open({ nonce: BLOB_B_NONCE, ciphertext: BLOB_B_CT, tag: BLOB_B_TAG }, BLOB_B_AAD);
  const sealed = cipher.seal(plaintext, BLOB_B_AAD);
  assert.equal(sealed.nonce.length, 12);
  assert.equal(sealed.ciphertext.length, BLOB_B_CT.length);
  assert.equal(sealed.tag.length, 16);
  assert.equal(randomCalls, 1);
  for (let i = 0; i < sealed.ciphertext.length; i++) assert.equal(sealed.ciphertext[i], BLOB_B_CT[i]);
  for (let i = 0; i < 16; i++) assert.equal(sealed.tag[i], BLOB_B_TAG[i]);
});

test('jdk blob C seal with injected nonce matches ct tag', () => {
  let randomCalls = 0;
  const cipher = createCipher(BLOB_C_KEY, { random: () => { randomCalls += 1; return BLOB_C_NONCE; } });
  const plaintext = cipher.open({ nonce: BLOB_C_NONCE, ciphertext: BLOB_C_CT, tag: BLOB_C_TAG }, new Uint8Array(0));
  const sealed = cipher.seal(plaintext, new Uint8Array(0));
  assert.equal(sealed.nonce.length, 12);
  assert.equal(sealed.ciphertext.length, BLOB_C_CT.length);
  assert.equal(sealed.tag.length, 16);
  assert.equal(randomCalls, 1);
  for (let i = 0; i < sealed.ciphertext.length; i++) assert.equal(sealed.ciphertext[i], BLOB_C_CT[i]);
  for (let i = 0; i < 16; i++) assert.equal(sealed.tag[i], BLOB_C_TAG[i]);
});

test('phone wrappedHex unwraps to the patterned data key under the patterned key', () => {
  const cipher = createCipher(PHONE_KEY);
  const recovered = cipher.unwrapDek(PHONE_WRAPPED);
  assert.equal(recovered.length, 32);
  for (let i = 0; i < 32; i++) assert.equal(recovered[i], PHONE_DEK[i]);
});

test('phone wrap with the patterned nonce gives exactly wrappedHex', () => {
  const cipher = createCipher(PHONE_KEY, { random: () => PHONE_NONCE });
  const wrapped = cipher.wrapDek(PHONE_DEK);
  assert.equal(wrapped.length, 60);
  for (let i = 0; i < 60; i++) assert.equal(wrapped[i], PHONE_WRAPPED[i]);
});

test('59 byte wrapped key refused', () => {
  const cipher = createCipher(PHONE_KEY);
  try {
    cipher.unwrapDek(PHONE_WRAPPED.subarray(0, 59));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('61 byte wrapped key refused even when it starts with the genuine 60 bytes', () => {
  const cipher = createCipher(PHONE_KEY);
  const longer = new Uint8Array(61);
  longer.set(PHONE_WRAPPED, 0);
  try {
    cipher.unwrapDek(longer);
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('0 byte wrapped key refused', () => {
  const cipher = createCipher(KEY32);
  try {
    cipher.unwrapDek(new Uint8Array(0));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('31 byte dek refused', () => {
  const cipher = createCipher(KEY32);
  try {
    cipher.wrapDek(new Uint8Array(31));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('33 byte dek refused', () => {
  const cipher = createCipher(KEY32);
  try {
    cipher.wrapDek(new Uint8Array(33));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('flipped tag bit refused', () => {
  const cipher = createCipher(KEY32);
  const sealed = cipher.seal(new Uint8Array(8).fill(0xFF));
  const bad = { nonce: sealed.nonce, ciphertext: sealed.ciphertext, tag: new Uint8Array(sealed.tag) };
  bad.tag[bad.tag.length - 1] ^= 0x01;
  try {
    cipher.open(bad, new Uint8Array(0));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('flipped ciphertext bit refused', () => {
  const cipher = createCipher(KEY32);
  const sealed = cipher.seal(new Uint8Array(8).fill(0xFF));
  const bad = { nonce: sealed.nonce, ciphertext: new Uint8Array(sealed.ciphertext), tag: sealed.tag };
  bad.ciphertext[0] ^= 0x01;
  try {
    cipher.open(bad, new Uint8Array(0));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('flipped nonce bit refused', () => {
  const cipher = createCipher(KEY32);
  const sealed = cipher.seal(new Uint8Array(8).fill(0xFF));
  const bad = { nonce: new Uint8Array(sealed.nonce), ciphertext: sealed.ciphertext, tag: sealed.tag };
  bad.nonce[0] ^= 0x01;
  try {
    cipher.open(bad, new Uint8Array(0));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('key not 32 bytes refused', () => {
  try {
    createCipher(new Uint8Array(16));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('open record sealed under a 13 byte nonce refused', () => {
  const nonce13 = patterned(0x70, 13);
  const sealed = gcm(KEY32, nonce13, new Uint8Array(0)).encrypt(new Uint8Array(8).fill(0x42));
  const record = { nonce: nonce13, ciphertext: sealed.subarray(0, 8), tag: sealed.subarray(8) };
  const cipher = createCipher(KEY32);
  try {
    cipher.open(record, new Uint8Array(0));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('open record with a 17 byte tag and a ciphertext one byte shorter refused', () => {
  const cipher = createCipher(KEY32);
  const sealed = cipher.seal(new Uint8Array(8).fill(0x42));
  const shorter = sealed.ciphertext.subarray(0, 7);
  const tag17 = new Uint8Array(17);
  tag17[0] = sealed.ciphertext[7];
  tag17.set(sealed.tag, 1);
  try {
    cipher.open({ nonce: sealed.nonce, ciphertext: shorter, tag: tag17 }, new Uint8Array(0));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('open record with a 15 byte tag and a ciphertext one byte longer refused', () => {
  const cipher = createCipher(KEY32);
  const sealed = cipher.seal(new Uint8Array(8).fill(0x42));
  const longer = new Uint8Array(9);
  longer.set(sealed.ciphertext, 0);
  longer[8] = sealed.tag[0];
  try {
    cipher.open({ nonce: sealed.nonce, ciphertext: longer, tag: sealed.tag.subarray(1) }, new Uint8Array(0));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('aad not Uint8Array on seal refused', () => {
  const cipher = createCipher(KEY32);
  try {
    cipher.seal(new Uint8Array(8), 'not a uint8array');
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('aad not Uint8Array on open refused', () => {
  const cipher = createCipher(KEY32);
  const sealed = cipher.seal(new Uint8Array(8));
  try {
    cipher.open({ nonce: sealed.nonce, ciphertext: sealed.ciphertext, tag: sealed.tag }, 'not a uint8array');
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('null aad on seal refused', () => {
  const cipher = createCipher(KEY32);
  try {
    cipher.seal(new Uint8Array(8), null);
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('null aad on open refused', () => {
  const cipher = createCipher(KEY32);
  const sealed = cipher.seal(new Uint8Array(8));
  try {
    cipher.open({ nonce: sealed.nonce, ciphertext: sealed.ciphertext, tag: sealed.tag }, null);
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('non Uint8Array plaintext refused', () => {
  const cipher = createCipher(KEY32);
  try {
    cipher.seal('not a uint8array', new Uint8Array(0));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('random source returning 11 bytes refused', () => {
  const cipher = createCipher(KEY32, { random: () => new Uint8Array(11) });
  try {
    cipher.seal(new Uint8Array(8));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('random source returning number 12 refused', () => {
  const cipher = createCipher(KEY32, { random: () => 12 });
  try {
    cipher.seal(new Uint8Array(8));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('random source throwing refused', () => {
  const cipher = createCipher(KEY32, { random: () => { throw new Error('no entropy'); } });
  try {
    cipher.seal(new Uint8Array(8));
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('byte limit pinned value', () => {
  assert.equal(BYTE_LIMIT, 68_719_476_736);
});

test('byte budget startingBytes option', () => {
  const limit = 68_719_476_736;
  const cipher = createCipher(KEY32, { startingBytes: limit - 2 });
  const first = cipher.seal(new Uint8Array(1));
  assert.equal(first.ciphertext.length, 1);
  try {
    cipher.seal(new Uint8Array(1));
    assert.fail('second seal should have been refused');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
  const zero = cipher.seal(new Uint8Array(0));
  assert.equal(zero.ciphertext.length, 0);
});

test('byte budget startingBytes at limit minus one refused', () => {
  const limit = 68_719_476_736;
  const cipher = createCipher(KEY32, { startingBytes: limit - 1 });
  try {
    cipher.seal(new Uint8Array(1));
    assert.fail('seal at limit-1 plus 1 byte should be refused');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('startingBytes fractional value refused', () => {
  try {
    createCipher(KEY32, { startingBytes: 1.5 });
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('startingBytes -1 refused', () => {
  try {
    createCipher(KEY32, { startingBytes: -1 });
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('startingBytes at limit refused', () => {
  try {
    createCipher(KEY32, { startingBytes: 68_719_476_736 });
    assert.fail('should have thrown');
  } catch (e) {
    assert.equal(e.name, 'AesGcmRefused');
  }
});

test('nonce guard repeating source A A B second seal B exactly 3 draws', () => {
  const key = new Uint8Array(32);
  for (let i = 0; i < 32; i++) key[i] = i + 1;
  const sourceA = new Uint8Array(12).fill(0xAA);
  const sourceB = new Uint8Array(12).fill(0xBB);
  let draws = 0;
  const cipher = createCipher(key, {
    random: () => {
      draws += 1;
      if (draws <= 2) return sourceA;
      return sourceB;
    },
  });
  const first = cipher.seal(new Uint8Array(8));
  assert.equal(draws, 1);
  const second = cipher.seal(new Uint8Array(8));
  assert.equal(draws, 3);
  let differ = false;
  for (let i = 0; i < 12; i++) if (second.nonce[i] !== first.nonce[i]) differ = true;
  assert.equal(differ, true);
});

test('nonce guard stuck source refused after exactly 3 draws', () => {
  const key = new Uint8Array(32);
  for (let i = 0; i < 32; i++) key[i] = i + 1;
  const stuck = new Uint8Array(12).fill(0xCC);
  let draws = 0;
  const cipher = createCipher(key, { random: () => { draws += 1; return stuck; } });
  const first = cipher.seal(new Uint8Array(8));
  assert.equal(draws, 1);
  let refused = 0;
  for (let i = 0; i < 3; i++) {
    const drawsBefore = draws;
    try { cipher.seal(new Uint8Array(8)); assert.fail('should have thrown'); }
    catch (e) {
      assert.equal(e.name, 'AesGcmRefused');
      refused += 1;
    }
    assert.equal(draws - drawsBefore, 3, 'each refused seal must draw exactly 3 times');
  }
  assert.equal(refused, 3);
});

test('no entropy remove globalThis crypto default source refuses', () => {
  const key = new Uint8Array(32);
  for (let i = 0; i < 32; i++) key[i] = i + 1;
  let original;
  try {
    original = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
    Object.defineProperty(globalThis, 'crypto', { value: undefined, configurable: true });
    const cipher = createCipher(key);
    try {
      cipher.seal(new Uint8Array(8));
      assert.fail('should have thrown');
    } catch (e) {
      assert.equal(e.name, 'AesGcmRefused');
    }
  } finally {
    if (original) Object.defineProperty(globalThis, 'crypto', original);
  }
});

test('no entropy only subtle removed from crypto seal still works', () => {
  const key = new Uint8Array(32);
  for (let i = 0; i < 32; i++) key[i] = i + 1;
  let original;
  try {
    original = Object.getOwnPropertyDescriptor(globalThis.crypto, 'subtle');
    Object.defineProperty(globalThis.crypto, 'subtle', { value: undefined, configurable: true });
    const cipher = createCipher(key);
    const sealed = cipher.seal(new Uint8Array(8));
    const opened = cipher.open({ nonce: sealed.nonce, ciphertext: sealed.ciphertext, tag: sealed.tag }, new Uint8Array(0));
    assert.equal(opened.length, 8);
  } finally {
    if (original) Object.defineProperty(globalThis.crypto, 'subtle', original);
  }
});

test('exports verified', async () => {
  const m = await import('../../main/resources/web/js/aesgcm.js');
  assert.equal(typeof m.createCipher, 'function');
  assert.equal(typeof m.AesGcmRefused, 'function');
  assert.equal(typeof m.BYTE_LIMIT, 'number');
});
