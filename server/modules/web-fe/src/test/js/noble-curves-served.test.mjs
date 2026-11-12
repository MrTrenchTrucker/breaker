import test from 'node:test';
import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { x25519 } from '../../main/resources/web/js/vendor/noble-curves/ed25519.js';

const fromHex = (text) => new Uint8Array(Buffer.from(text, 'hex'));
const hex = (bytes) => Buffer.from(bytes).toString('hex');
const joinHex = (pieces) => pieces.join('');

// RFC 7748 section 6.1 X25519 vectors. Each value is data on one line, written in six-character pieces.
const ALICE_PRIVATE = fromHex(joinHex(['77076d', '0a7318', 'a57d3c', '16c172', '51b266', '45df4c', '2f87eb', 'c0992a', 'b177fb', 'a51db9', '2c2a']));
const ALICE_PUBLIC_HEX = joinHex(['8520f0', '098930', 'a75474', '8b7ddc', 'b43ef7', '5a0dbf', '3a0d26', '381af4', 'eba4a9', '8eaa9b', '4e6a']);
const BOB_PRIVATE = fromHex(joinHex(['5dab08', '7e624a', '8a4b79', 'e17f8b', '83800e', 'e66f3b', 'b12926', '18b6fd', '1c2f8b', '27ff88', 'e0eb']));
const BOB_PUBLIC_HEX = joinHex(['de9edb', '7d7b7d', 'c1b4d3', '5b61c2', 'ece435', '373f83', '43c85b', '78674d', 'adfc7e', '146f88', '2b4f']);
const SHARED_HEX = joinHex(['4a5d9d', '5ba4ce', '2de172', '8e3bf4', '80350f', '25e07e', '21c947', 'd19e33', '76f09b', '3c1e16', '1742']);

test('Alice private key gives the RFC 7748 public key', () => {
  assert.equal(hex(x25519.getPublicKey(ALICE_PRIVATE)), ALICE_PUBLIC_HEX);
});

test('Bob private key gives the RFC 7748 public key', () => {
  assert.equal(hex(x25519.getPublicKey(BOB_PRIVATE)), BOB_PUBLIC_HEX);
});

test('Alice side gives the RFC 7748 shared secret', () => {
  assert.equal(hex(x25519.getSharedSecret(ALICE_PRIVATE, fromHex(BOB_PUBLIC_HEX))), SHARED_HEX);
});

test('Bob side gives the same RFC 7748 shared secret', () => {
  assert.equal(hex(x25519.getSharedSecret(BOB_PRIVATE, fromHex(ALICE_PUBLIC_HEX))), SHARED_HEX);
});

test('the same private key always gives the same public key', () => {
  const first = hex(x25519.getPublicKey(ALICE_PRIVATE));
  const second = hex(x25519.getPublicKey(ALICE_PRIVATE));
  assert.equal(first, second);
});

test('a different private key gives a different public key', () => {
  assert.notEqual(hex(x25519.getPublicKey(BOB_PRIVATE)), hex(x25519.getPublicKey(ALICE_PRIVATE)));
});

test('a 31-byte private key is refused by getPublicKey', () => {
  assert.throws(() => x25519.getPublicKey(ALICE_PRIVATE.subarray(0, 31)), RangeError);
});

test('a 31-byte private key is refused by getSharedSecret', () => {
  assert.throws(
    () => x25519.getSharedSecret(ALICE_PRIVATE.subarray(0, 31), fromHex(BOB_PUBLIC_HEX)),
    RangeError,
  );
});
