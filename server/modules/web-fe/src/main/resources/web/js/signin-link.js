// Pure helpers for the sign-in link: the URL fragment, the base64 forms and the password bytes.
// This module imports nothing and keeps no state, so each result depends only on its arguments.

// The challenge and the app key are both 43 characters, the unpadded base64url form of 32 bytes.
export const CHALLENGE_LENGTH = 43;

const UPPER = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
const LOWER = 'abcdefghijklmnopqrstuvwxyz';
const DIGITS = '01234' + '56789';
const STANDARD_CHARS = UPPER + LOWER + DIGITS + '+/';
const URL_CHARS = UPPER + LOWER + DIGITS + '-_';

// Reads base64 characters as 6-bit values and writes exactly byteCount bytes.
// A character outside the alphabet, a wrong byte count, or a non-zero spare bit at the end is refused with null.
function decodeChars(body, alphabet, byteCount) {
  const out = new Uint8Array(byteCount);
  let acc = 0;
  let accBits = 0;
  let at = 0;
  for (let i = 0; i < body.length; i++) {
    const v = alphabet.indexOf(body.charAt(i));
    if (v < 0) return null;
    acc = (acc << 6) | v;
    accBits += 6;
    if (accBits >= 8) {
      accBits -= 8;
      out[at] = (acc >> accBits) & 255;
      at++;
      acc &= (1 << accBits) - 1;
    }
  }
  // Only canonical text is accepted: the leftover bits must all be zero.
  if (at !== byteCount || acc !== 0) return null;
  return out;
}

// True only for a string of exactly 43 characters, each one from the URL-safe alphabet.
export function isUrlSafe43(text) {
  if (typeof text !== 'string' || text.length !== CHALLENGE_LENGTH) return false;
  for (let i = 0; i < text.length; i++) {
    if (URL_CHARS.indexOf(text.charAt(i)) < 0) return false;
  }
  return true;
}

// Decodes a 43-character URL-safe string to 32 bytes, or returns null.
// The last character carries two spare bits, and they must be zero, so only the canonical form is accepted.
export function decodeBase64Url43(text) {
  if (!isUrlSafe43(text)) return null;
  return decodeChars(text, URL_CHARS, 32);
}

// Decodes standard padded base64 to exactly expectedLength bytes, or returns null.
// The text must have the length and the padding that the byte count implies, and the spare bits must be zero.
export function decodeBase64Padded(text, expectedLength) {
  if (typeof text !== 'string' || !Number.isInteger(expectedLength) || expectedLength < 1) return null;
  const need = Math.ceil(expectedLength / 3) * 4;
  if (text.length !== need) return null;
  const pad = (3 - (expectedLength % 3)) % 3;
  // The padding must be exactly the '=' characters that the byte count needs, no more and no less.
  if (text.substring(need - pad) !== '='.repeat(pad)) return null;
  return decodeChars(text.substring(0, need - pad), STANDARD_CHARS, expectedLength);
}

// Encodes bytes as standard base64 with padding: 113 bytes give 152 characters and 32 bytes give 44.
export function encodeBase64Padded(bytes) {
  if (!(bytes instanceof Uint8Array)) throw new TypeError('bytes must be a Uint8Array');
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i];
    const b1 = i + 1 < bytes.length ? bytes[i + 1] : 0;
    const b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
    const triple = (b0 << 16) | (b1 << 8) | b2;
    out += STANDARD_CHARS.charAt((triple >> 18) & 63);
    out += STANDARD_CHARS.charAt((triple >> 12) & 63);
    out += i + 1 < bytes.length ? STANDARD_CHARS.charAt((triple >> 6) & 63) : '=';
    out += i + 2 < bytes.length ? STANDARD_CHARS.charAt(triple & 63) : '=';
  }
  return out;
}

// Splits one piece at its only equals sign, or returns null when there is no equals sign or the name is empty.
function splitPair(piece) {
  const at = piece.indexOf('=');
  if (at < 1) return null;
  return { name: piece.substring(0, at), value: piece.substring(at + 1) };
}

// Reads location.hash: a leading hash mark, then exactly two name=value pairs joined by one ampersand.
// The names must be challenge and app_key in either order, so a duplicate, a third key or an empty piece is refused.
export function parseFragment(hash) {
  if (typeof hash !== 'string' || hash.charAt(0) !== '#') return { ok: false };
  const pieces = hash.substring(1).split('&');
  if (pieces.length !== 2) return { ok: false };
  const first = splitPair(pieces[0]);
  const second = splitPair(pieces[1]);
  if (first === null || second === null) return { ok: false };
  const paired = (first.name === 'challenge' && second.name === 'app_key') || (first.name === 'app_key' && second.name === 'challenge');
  if (!paired) return { ok: false };
  const challengeText = first.name === 'challenge' ? first.value : second.value;
  const appKeyText = first.name === 'app_key' ? first.value : second.value;
  if (!isUrlSafe43(challengeText)) return { ok: false };
  const appKey = decodeBase64Url43(appKeyText);
  if (appKey === null) return { ok: false };
  return { ok: true, challenge: challengeText, appKey };
}

// Turns typed text into UTF-8 bytes after NFC normalisation. Nothing is trimmed and no case is folded.
export function passwordToBytes(text) {
  if (typeof text !== 'string') throw new TypeError('text must be a string');
  return new TextEncoder().encode(text.normalize('NFC'));
}

// Zero-fills a Uint8Array in place. Any other value is ignored, so a caller may pass a missing array safely.
export function wipeBytes(bytes) {
  if (bytes instanceof Uint8Array) bytes.fill(0);
}

// The one-time code has the same shape as the challenge, so it uses the same test.
export function isCode(text) {
  return isUrlSafe43(text);
}
