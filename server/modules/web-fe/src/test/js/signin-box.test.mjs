import test from 'node:test';
import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import * as facade from '../../main/resources/web/js/signin-crypto.js';
import { X25519_BOX } from './signin-vectors.mjs';

const [ALICE, BOB] = X25519_BOX;
const fromHex = (text) => new Uint8Array(Buffer.from(text, 'hex'));
const hex = (bytes) => Buffer.from(bytes).toString('hex');
const privateOf = (row) => fromHex(row.privateHex);

test('the alice private key gives the alice public key', () => {
  assert.equal(hex(facade.boxPublicKey(privateOf(ALICE))), ALICE.publicHex);
});

test('the bob private key gives the bob public key', () => {
  assert.equal(hex(facade.boxPublicKey(privateOf(BOB))), BOB.publicHex);
});

test('boxPublicKey returns a new 32 byte Uint8Array on each call', () => {
  const key = privateOf(ALICE);
  const first = facade.boxPublicKey(key);
  const second = facade.boxPublicKey(key);
  assert.ok(first instanceof Uint8Array);
  assert.equal(first.length, 32);
  assert.notStrictEqual(first, key);
  assert.notStrictEqual(first, second);
  assert.equal(hex(second), ALICE.publicHex);
});

test('boxPublicKey leaves its argument unchanged', () => {
  const key = privateOf(BOB);
  facade.boxPublicKey(key);
  assert.equal(hex(key), BOB.privateHex);
});

test('a 32 byte Buffer is accepted and gives the same key', () => {
  const key = Buffer.from(ALICE.privateHex, 'hex');
  assert.equal(key.length, 32);
  assert.equal(hex(facade.boxPublicKey(key)), ALICE.publicHex);
});

test('boxPublicKey refuses every malformed key with null and no throw', () => {
  const good = privateOf(ALICE);
  const cases = [
    ['31 bytes', good.subarray(0, 31)],
    ['33 bytes', new Uint8Array(33)],
    ['0 bytes', new Uint8Array(0)],
    ['a plain Array of 32 numbers', Array.from(good)],
    ['a hex string of 64 characters', ALICE.privateHex],
    ['null', null],
    ['undefined', undefined],
    ['an object with length 32', { length: 32 }],
    ['a Uint16Array of 16 elements', new Uint16Array(16)],
  ];
  for (const [name, value] of cases) {
    assert.strictEqual(facade.boxPublicKey(value), null, name);
  }
});

test('clamping holds: flipped low or high scalar bits give the same public key', () => {
  const low = privateOf(ALICE);
  low[0] ^= 0x07;
  const high = privateOf(ALICE);
  high[31] ^= 0x80;
  assert.equal(hex(facade.boxPublicKey(low)), ALICE.publicHex);
  assert.equal(hex(facade.boxPublicKey(high)), ALICE.publicHex);
});

test('alice and bob give different public keys, and a repeated key gives the same one', () => {
  assert.notEqual(hex(facade.boxPublicKey(privateOf(ALICE))), hex(facade.boxPublicKey(privateOf(BOB))));
  assert.equal(hex(facade.boxPublicKey(privateOf(BOB))), hex(facade.boxPublicKey(privateOf(BOB))));
});

test('seal of no arguments returns null and available is exactly false', () => {
  assert.strictEqual(facade.seal(), null);
  assert.strictEqual(facade.available, false);
});
