// Registration password policy: the one place the website decides whether a typed password is long
// enough. The count is taken on the NFC form of the typed text, with nothing trimmed, no case folding
// and no other change, so a password of fewer than twelve characters never registers however it is
// spelled or padded. The count is a floor, not a strength test: twelve spaces, twelve zero-width
// joiners or twelve combining marks all reach it, exactly as the work order specified.
// This file imports nothing, touches no page and keeps no state.
export const MIN_PASSWORD_CHARS = 12;

// One refusal sentence. It never holds a password character.
export const MESSAGES = Object.freeze({
  too_short: 'The password must be at least twelve characters.',
});

// Counts Unicode code points of the NFC form, or null for a value that is not text.
// Code points, not UTF-16 units: one BMP character counts once, while an emoji sequence built
// from zero-width joiners counts as several.
export function characterCount(text) {
  if (typeof text !== 'string') return null;
  return Array.from(text.normalize('NFC')).length;
}

// Answers { ok, chars }: ok is true only at or above MIN_PASSWORD_CHARS. A non-string answer has
// zero characters, so it can never pass.
export function checkPassword(text) {
  const chars = characterCount(text);
  if (chars === null) return { ok: false, chars: 0 };
  return { ok: chars >= MIN_PASSWORD_CHARS, chars };
}
