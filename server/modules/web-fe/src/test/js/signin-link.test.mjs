import test from 'node:test';
import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import {
  CHALLENGE_LENGTH,
  isUrlSafe43,
  decodeBase64Url43,
  decodeBase64Padded,
  encodeBase64Padded,
  parseFragment,
  passwordToBytes,
  wipeBytes,
  isCode,
} from '../../main/resources/web/js/signin-link.js';

const UPPER = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
const LOWER = 'abcdefghijklmnopqrstuvwxyz';
const DIGITS = '01234' + '56789';
const URL_CHARS = UPPER + LOWER + DIGITS + '-_';
const STD_CHARS = UPPER + LOWER + DIGITS + '+/';

const patterned = (base, size) => Uint8Array.from({ length: size }, (_, i) => base + i);
const withCharAt = (text, index, ch) => text.substring(0, index) + ch + text.substring(index + 1);
const nextChar = (alphabet, ch) => alphabet.charAt(alphabet.indexOf(ch) + 1);

const APP_KEY_BYTES = patterned(0x20, 32);
const APP_KEY_TEXT = Buffer.from(APP_KEY_BYTES).toString('base64url');
const CHALLENGE = Buffer.from(patterned(0x40, 32)).toString('base64url');
const CHALLENGE_PAIR = 'challenge=' + CHALLENGE;
const APP_KEY_PAIR = 'app_key=' + APP_KEY_TEXT;
const BYTES_16 = patterned(0x30, 16);
const TEXT_16 = Buffer.from(BYTES_16).toString('base64');
const BYTES_32 = APP_KEY_BYTES;
const TEXT_32 = Buffer.from(BYTES_32).toString('base64');

test('valid fragment with challenge first is accepted', () => {
  const result = parseFragment('#' + CHALLENGE_PAIR + '&' + APP_KEY_PAIR);
  assert.equal(result.ok, true);
  assert.equal(result.challenge, CHALLENGE);
  assert.deepEqual(result.appKey, APP_KEY_BYTES);
});

test('valid fragment with app key first is accepted', () => {
  const result = parseFragment('#' + APP_KEY_PAIR + '&' + CHALLENGE_PAIR);
  assert.equal(result.ok, true);
  assert.equal(result.challenge, CHALLENGE);
  assert.deepEqual(result.appKey, APP_KEY_BYTES);
});

test('missing challenge is refused', () => {
  assert.equal(parseFragment('#' + APP_KEY_PAIR).ok, false);
  assert.equal(parseFragment('#' + APP_KEY_PAIR + '&other=1').ok, false);
});

test('missing app key is refused', () => {
  assert.equal(parseFragment('#' + CHALLENGE_PAIR).ok, false);
  assert.equal(parseFragment('#' + CHALLENGE_PAIR + '&other=1').ok, false);
});

test('a third key is refused', () => {
  assert.equal(parseFragment('#' + CHALLENGE_PAIR + '&' + APP_KEY_PAIR + '&extra=1').ok, false);
});

test('a duplicate challenge is refused', () => {
  assert.equal(parseFragment('#' + CHALLENGE_PAIR + '&' + CHALLENGE_PAIR).ok, false);
});

test('a duplicate app key is refused', () => {
  assert.equal(parseFragment('#' + APP_KEY_PAIR + '&' + APP_KEY_PAIR).ok, false);
});

test('a fragment without a leading hash is refused', () => {
  assert.equal(parseFragment(CHALLENGE_PAIR + '&' + APP_KEY_PAIR).ok, false);
  assert.equal(parseFragment('?' + CHALLENGE_PAIR + '&' + APP_KEY_PAIR).ok, false);
  assert.equal(parseFragment(undefined).ok, false);
});

test('an empty piece is refused', () => {
  assert.equal(parseFragment('#').ok, false);
  assert.equal(parseFragment('#&' + CHALLENGE_PAIR + '&' + APP_KEY_PAIR).ok, false);
  assert.equal(parseFragment('#' + CHALLENGE_PAIR + '&').ok, false);
});

test('an extra ampersand is refused', () => {
  assert.equal(parseFragment('#' + CHALLENGE_PAIR + '&&' + APP_KEY_PAIR).ok, false);
});

test('challenge of 42 characters is refused', () => {
  assert.equal(parseFragment('#challenge=' + CHALLENGE.substring(0, 42) + '&' + APP_KEY_PAIR).ok, false);
});

test('challenge of 44 characters is refused', () => {
  assert.equal(parseFragment('#challenge=' + CHALLENGE + 'A' + '&' + APP_KEY_PAIR).ok, false);
});

test('challenge with a character outside the set is refused', () => {
  for (const ch of ['+', '/', '=', ' ', '%']) {
    const bad = withCharAt(CHALLENGE, 5, ch);
    assert.equal(isUrlSafe43(bad), false, 'accepted: ' + ch);
    assert.equal(parseFragment('#challenge=' + bad + '&' + APP_KEY_PAIR).ok, false, 'accepted in fragment: ' + ch);
  }
});

test('app key of 42 characters is refused', () => {
  assert.equal(parseFragment('#' + CHALLENGE_PAIR + '&app_key=' + APP_KEY_TEXT.substring(0, 42)).ok, false);
});

test('app key of 44 characters is refused', () => {
  assert.equal(parseFragment('#' + CHALLENGE_PAIR + '&app_key=' + APP_KEY_TEXT + 'A').ok, false);
});

test('app key with a spare bit set in the last character is refused', () => {
  const last = APP_KEY_TEXT.charAt(42);
  assert.equal(URL_CHARS.indexOf(last) % 4, 0, 'precondition: canonical last character');
  const nonCanonical = withCharAt(APP_KEY_TEXT, 42, nextChar(URL_CHARS, last));
  assert.equal(decodeBase64Url43(nonCanonical), null);
  assert.equal(parseFragment('#' + CHALLENGE_PAIR + '&app_key=' + nonCanonical).ok, false);
  assert.equal(parseFragment('#' + CHALLENGE_PAIR + '&' + APP_KEY_PAIR).ok, true);
});

test('exact decoded bytes of a patterned app key', () => {
  const decoded = decodeBase64Url43(APP_KEY_TEXT);
  assert.equal(decoded.length, 32);
  assert.deepEqual(decoded, APP_KEY_BYTES);
});

test('zero app key decodes and a spare bit in B is refused', () => {
  assert.deepEqual(decodeBase64Url43('A'.repeat(43)), new Uint8Array(32));
  assert.equal(decodeBase64Url43('A'.repeat(42) + 'B'), null);
});

test('decodeBase64Url43 refuses a non string and a short string', () => {
  assert.equal(decodeBase64Url43(null), null);
  assert.equal(decodeBase64Url43(CHALLENGE.substring(0, 42)), null);
});

test('isUrlSafe43 accepts 43 url characters including hyphen and underscore', () => {
  assert.equal(isUrlSafe43(CHALLENGE), true);
  assert.equal(isUrlSafe43('-'.repeat(43)), true);
  assert.equal(isUrlSafe43('_'.repeat(43)), true);
});

test('isUrlSafe43 refuses other types and lengths', () => {
  assert.equal(isUrlSafe43(null), false);
  assert.equal(isUrlSafe43(undefined), false);
  assert.equal(isUrlSafe43(new Uint8Array(43)), false);
  assert.equal(isUrlSafe43(CHALLENGE.substring(0, 42)), false);
  assert.equal(isUrlSafe43(CHALLENGE + 'A'), false);
  assert.equal(isUrlSafe43(withCharAt(CHALLENGE, 0, '.')), false);
  assert.equal(isUrlSafe43(CHALLENGE.substring(0, 42) + '='), false);
});

test('isCode follows the same shape as the challenge', () => {
  assert.equal(isCode(CHALLENGE), true);
  assert.equal(isCode(CHALLENGE.substring(0, 42)), false);
  assert.equal(isCode(CHALLENGE + 'A'), false);
  assert.equal(isCode(withCharAt(CHALLENGE, 0, '+')), false);
  assert.equal(CHALLENGE_LENGTH, 43);
});

test('decodeBase64Padded accepts the exact length', () => {
  assert.deepEqual(decodeBase64Padded(TEXT_16, 16), BYTES_16);
});

test('decodeBase64Padded refuses one byte short and one byte long', () => {
  const short = Buffer.from(patterned(0x30, 15)).toString('base64');
  const long = Buffer.from(patterned(0x30, 17)).toString('base64');
  assert.equal(decodeBase64Padded(short, 16), null);
  assert.equal(decodeBase64Padded(long, 16), null);
});

test('decodeBase64Padded refuses whole group short and long texts for 60 bytes', () => {
  assert.equal(decodeBase64Padded(encodeBase64Padded(patterned(0x10, 57)), 60), null);
  assert.equal(decodeBase64Padded(encodeBase64Padded(patterned(0x10, 63)), 60), null);
});

test('decodeBase64Padded refuses whole group short and long texts for 32 bytes', () => {
  assert.equal(decodeBase64Padded(encodeBase64Padded(patterned(0x10, 29)), 32), null);
  assert.equal(decodeBase64Padded(encodeBase64Padded(patterned(0x10, 35)), 32), null);
});

test('decodeBase64Padded refuses whole group short texts for 16 bytes', () => {
  assert.equal(decodeBase64Padded(encodeBase64Padded(patterned(0x10, 13)), 16), null);
  assert.equal(decodeBase64Padded(encodeBase64Padded(patterned(0x10, 19)), 16), null);
});

test('decodeBase64Padded refuses missing and extra padding', () => {
  assert.equal(decodeBase64Padded(TEXT_16.substring(0, 23), 16), null);
  assert.equal(decodeBase64Padded(TEXT_16.substring(0, 22) + 'AA', 16), null);
  assert.equal(decodeBase64Padded(TEXT_16 + '==', 16), null);
});

test('decodeBase64Padded refuses whitespace', () => {
  assert.equal(decodeBase64Padded(withCharAt(TEXT_16, 4, ' '), 16), null);
  assert.equal(decodeBase64Padded(withCharAt(TEXT_16, 4, '\n'), 16), null);
});

test('decodeBase64Padded refuses a non alphabet character', () => {
  assert.equal(decodeBase64Padded(withCharAt(TEXT_16, 0, '-'), 16), null);
  assert.equal(decodeBase64Padded(withCharAt(TEXT_16, 1, '_'), 16), null);
});

test('decodeBase64Padded refuses a non zero spare bit and accepts the canonical text', () => {
  const v16 = STD_CHARS.indexOf(TEXT_16.charAt(21));
  assert.equal(v16 % 16, 0, 'precondition: canonical spare bits for 16 bytes');
  assert.equal(decodeBase64Padded(withCharAt(TEXT_16, 21, STD_CHARS.charAt(v16 + 1)), 16), null);
  const v32 = STD_CHARS.indexOf(TEXT_32.charAt(42));
  assert.equal(v32 % 4, 0, 'precondition: canonical spare bits for 32 bytes');
  assert.equal(decodeBase64Padded(withCharAt(TEXT_32, 42, STD_CHARS.charAt(v32 + 1)), 32), null);
  assert.deepEqual(decodeBase64Padded(TEXT_32, 32), BYTES_32);
});

test('decodeBase64Padded refuses the empty string and a non string', () => {
  assert.equal(decodeBase64Padded('', 16), null);
  assert.equal(decodeBase64Padded(null, 16), null);
  assert.equal(decodeBase64Padded(TEXT_16, 0), null);
});

test('encodeBase64Padded gives 152 characters for 113 bytes', () => {
  const bytes = patterned(0x10, 113);
  const text = encodeBase64Padded(bytes);
  assert.equal(text.length, 152);
  assert.equal(text, Buffer.from(bytes).toString('base64'));
});

test('encodeBase64Padded gives 44 characters for 32 bytes', () => {
  const text = encodeBase64Padded(BYTES_32);
  assert.equal(text.length, 44);
  assert.equal(text, TEXT_32);
});

test('encodeBase64Padded then decodeBase64Padded gives back 113 bytes', () => {
  const bytes = patterned(0x10, 113);
  assert.deepEqual(decodeBase64Padded(encodeBase64Padded(bytes), 113), bytes);
});

test('encodeBase64Padded refuses a non byte input', () => {
  assert.throws(() => encodeBase64Padded([1, 2, 3]), TypeError);
});

test('passwordToBytes gives the same bytes for composed and decomposed text', () => {
  const composed = String.fromCharCode(0xe9);
  const decomposed = 'e' + String.fromCharCode(0x301);
  assert.deepEqual(passwordToBytes(composed), passwordToBytes(decomposed));
  assert.deepEqual(passwordToBytes(composed), Uint8Array.from([0xc3, 0xa9]));
});

test('passwordToBytes keeps leading and trailing spaces', () => {
  assert.deepEqual(passwordToBytes(' ab '), Uint8Array.from([0x20, 0x61, 0x62, 0x20]));
});

test('passwordToBytes refuses a non string', () => {
  assert.throws(() => passwordToBytes(12), TypeError);
  assert.throws(() => passwordToBytes(null), TypeError);
});

test('wipeBytes zero fills the array it is given and only that array', () => {
  const target = patterned(0x11, 8);
  const other = patterned(0x21, 8);
  wipeBytes(target);
  assert.deepEqual(target, new Uint8Array(8));
  assert.deepEqual(other, patterned(0x21, 8));
});

test('wipeBytes ignores a value that is not an array', () => {
  const plain = [1, 2, 3];
  wipeBytes(plain);
  wipeBytes(null);
  wipeBytes(undefined);
  assert.deepEqual(plain, [1, 2, 3]);
});
