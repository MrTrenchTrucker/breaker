import { stretch } from './signin-kdf.js';
export const available = false;
// derive never throws. A refusal or any failure is null, which signin.js turns into its derive_refused result.
export async function derive(password, salt, kdf) { try { return await stretch(password, salt, kdf); } catch { return null; } }
export function boxPublicKey() { throw new Error('not available'); }
export function seal() { throw new Error('not available'); }
