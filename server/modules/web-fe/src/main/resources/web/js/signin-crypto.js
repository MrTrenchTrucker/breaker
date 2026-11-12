import { stretch } from './signin-kdf.js';
import { x25519 } from './vendor/noble-curves/ed25519.js';
import { seal as sealBox } from './signin-seal.js';
export const available = false;
// derive never throws. A refusal or any failure is null, which signin.js turns into its derive_refused result.
export async function derive(password, salt, kdf) { try { return await stretch(password, salt, kdf); } catch { return null; } }
// boxPublicKey never throws. A key that is not a 32-byte Uint8Array, or any failure, is null, which signin.js turns into its keys_refused result.
export function boxPublicKey(boxKey) {
  try {
    if (!(boxKey instanceof Uint8Array) || boxKey.length !== 32) return null;
    return x25519.getPublicKey(boxKey);
  } catch { return null; }
}
export function seal(plaintext, recipientPublic, ephemeralPrivate) { return sealBox(plaintext, recipientPublic, ephemeralPrivate); }
