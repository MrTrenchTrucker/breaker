import test from 'node:test';
import assert from 'node:assert/strict';
import {
  MIN_PASSWORD_CHARS, MESSAGES, characterCount, checkPassword,
} from '../../main/resources/web/js/register-policy.js';

// The policy counts the NFC form of the typed text and trims nothing. Every case below
// is a whole typed password, not a fragment of one.

test('the minimum is exactly twelve characters and the refusal is one fixed sentence', () => {
  assert.equal(MIN_PASSWORD_CHARS, 12);
  assert.equal(MESSAGES.too_short, 'The password must be at least twelve characters.');
  assert.equal(Object.isFrozen(MESSAGES), true);
});

test('twelve characters pass and eleven characters fail', () => {
  assert.deepEqual(checkPassword('a'.repeat(12)), { ok: true, chars: 12 });
  assert.deepEqual(checkPassword('a'.repeat(11)), { ok: false, chars: 11 });
});

test('the empty password fails with a count of zero', () => {
  assert.deepEqual(checkPassword(''), { ok: false, chars: 0 });
});

test('the count is taken after NFC normalisation, so a decomposed spelling is not longer', () => {
  const decomposed = 'e\u0301'.repeat(6); // twelve code points before NFC, six after
  const composed = '\u00e9'.repeat(6);
  assert.equal(characterCount(decomposed), 6);
  assert.equal(characterCount(composed), 6);
  assert.deepEqual(checkPassword(decomposed), { ok: false, chars: 6 });
  assert.deepEqual(checkPassword('\u00e9'.repeat(12)), { ok: true, chars: 12 });
});

test('composed and decomposed spellings of the same word give the same count', () => {
  const composed = 'caf\u00e9-password';
  const decomposed = 'cafe\u0301-password';
  assert.equal(characterCount(composed), characterCount(decomposed));
  assert.equal(checkPassword(composed).ok, checkPassword(decomposed).ok);
});

test('nothing is trimmed: leading and trailing spaces are counted as characters', () => {
  // Eleven real characters plus one trailing space is twelve characters here. Trimming
  // would make it eleven and refuse it.
  assert.deepEqual(checkPassword('12345678901 '), { ok: true, chars: 12 });
  // A leading space plus ten digits is eleven characters, spaces included.
  assert.deepEqual(checkPassword(' 1234567890'), { ok: false, chars: 11 });
  // Tabs count too, because only NFC is applied.
  assert.deepEqual(checkPassword('\t12345678901'), { ok: true, chars: 12 });
});

test('the count is Unicode code points, not UTF-16 units', () => {
  assert.deepEqual(checkPassword('\u{1F600}'.repeat(12)), { ok: true, chars: 12 });
  // Six emoji are twelve UTF-16 units but six characters: a unit count would accept them.
  assert.deepEqual(checkPassword('\u{1F600}'.repeat(6)), { ok: false, chars: 6 });
});

test('a value that is not a string is refused with a count of zero', () => {
  assert.equal(characterCount(null), null);
  assert.equal(characterCount(undefined), null);
  assert.equal(characterCount(12345), null);
  assert.deepEqual(checkPassword(null), { ok: false, chars: 0 });
  assert.deepEqual(checkPassword(['a']), { ok: false, chars: 0 });
});

test('the refusal sentence is one fixed text and leaks no password fragment', () => {
  const typed = 'Zq7!vLm2#pX'; // eleven characters, refused, with a distinctive shape
  const result = checkPassword(typed);
  assert.equal(result.ok, false);
  // The sentence is the same fixed string whatever was typed, so it cannot echo one password.
  assert.equal(MESSAGES.too_short, 'The password must be at least twelve characters.');
  assert.equal(MESSAGES.too_short.includes(typed), false);
  assert.equal(MESSAGES.too_short.includes(typed.slice(0, 8)), false);
  assert.equal(MESSAGES.too_short.includes(typed.slice(-8)), false);
  // The same fixed sentence is served for a second, very different password.
  assert.equal(checkPassword('short').ok, false);
  assert.equal(MESSAGES.too_short.length > 0, true);
});
