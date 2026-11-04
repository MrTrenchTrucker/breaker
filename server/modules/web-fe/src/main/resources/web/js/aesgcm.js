// AES-GCM layer for web page -- no key derivation, no fallback entropy
// Imports only from vendored noble-ciphers files under vendor/noble-ciphers
import { gcm } from './vendor/noble-ciphers/aes.js';
import { randomBytes } from './vendor/noble-ciphers/utils.js';

// Single refusal class for all AES-GCM refusals
class AesGcmRefused extends Error {
  constructor(message = 'AES-GCM refused') {
    super(message);
    this.name = 'AesGcmRefused';
  }
}

// Byte budget guard: 2**36 bytes strictly below
const BYTE_LIMIT = 2 ** 36;

// Nonce uniqueness guard: at most 3 draws on reuse, then refuse
const MAX_NONCE_RETRIES = 3;

function createCipher(key, options = {}) {
  // Key must be Uint8Array exactly 32 bytes
  if (!key || !(key instanceof Uint8Array) || key.length !== 32) {
    throw new AesGcmRefused('AES-GCM refused');
  }

  // startingBytes option: validated integer, 0 <= value < BYTE_LIMIT, else refusal
  let startingBytes = 0;
  if (options.startingBytes !== undefined) {
    startingBytes = options.startingBytes;
    if (
      !Number.isInteger(startingBytes) ||
      startingBytes < 0 ||
      startingBytes >= BYTE_LIMIT
    ) {
      throw new AesGcmRefused('AES-GCM refused');
    }
  }

  // Random source: injected for tests only, otherwise real CSPRNG
  const random = options.random || randomBytes;

  // Remember nonces per cipher object to enforce uniqueness (hex strings for memory)
  let drawnNonces = new Set();

  // Hex string of a nonce, used as the key of the nonce memory
  function hex(buf) {
    let result = '';
    for (let i = 0; i < buf.length; i++) {
      const b = buf[i];
      result += b < 16 ? '0' + b.toString(16) : b.toString(16);
    }
    return result;
  }

  let totalBytesSealed = startingBytes;

  function seal(plaintext, sealAad = new Uint8Array(0)) {
    // plaintext must be Uint8Array -- validate first before any budget check
    if (!plaintext || !(plaintext instanceof Uint8Array)) {
      throw new AesGcmRefused('AES-GCM refused');
    }

    // AAD must be Uint8Array
    if (!(sealAad instanceof Uint8Array)) {
      throw new AesGcmRefused('AES-GCM refused');
    }

    // Byte budget guard: strictly below 2**36
    if (totalBytesSealed + plaintext.length >= BYTE_LIMIT) {
      throw new AesGcmRefused('AES-GCM refused');
    }

    // Nonce uniqueness: at most 3 draws on reuse, then refuse
    let nonceGenerated = false;
    let candidate = null;
    for (let draw = 0; draw < MAX_NONCE_RETRIES; draw++) {
      let raw = null;
      try {
        raw = random(12);
      } catch (e) {
        throw new AesGcmRefused('AES-GCM refused');
      }
      if (!(raw instanceof Uint8Array) || raw.length !== 12) {
        throw new AesGcmRefused('AES-GCM refused');
      }
      candidate = new Uint8Array(raw);
      if (!drawnNonces.has(hex(candidate))) {
        nonceGenerated = true;
        drawnNonces.add(hex(candidate));
        break;
      }
    }
    if (!nonceGenerated || candidate.length !== 12) {
      throw new AesGcmRefused('AES-GCM refused');
    }

    // Call noble gcm with ready 32-byte key, 12-byte nonce, AAD; then encrypt
    let encrypted;
    try {
      encrypted = gcm(key, candidate, sealAad).encrypt(plaintext);
    } catch (e) {
      throw new AesGcmRefused('AES-GCM refused');
    }

    // Copy the two parts out of the encrypted view
    const tag = new Uint8Array(encrypted.subarray(encrypted.length - 16));
    const ciphertext = new Uint8Array(encrypted.subarray(0, encrypted.length - 16));

    totalBytesSealed += plaintext.length;
    return { nonce: candidate, ciphertext, tag };
  }

  function open(record, openAad = new Uint8Array(0)) {
    // open validates nonce===12 bytes, tag===16 bytes, all three Uint8Array, before anything else
    if (!record || !(record.nonce instanceof Uint8Array) || record.nonce.length !== 12) {
      throw new AesGcmRefused('AES-GCM refused');
    }
    if (!(record.tag instanceof Uint8Array) || record.tag.length !== 16) {
      throw new AesGcmRefused('AES-GCM refused');
    }
    if (!(record.ciphertext instanceof Uint8Array)) {
      throw new AesGcmRefused('AES-GCM refused');
    }

    // AAD must be Uint8Array (seal and open both)
    if (!(openAad instanceof Uint8Array)) {
      throw new AesGcmRefused('AES-GCM refused');
    }

    // Reconstruct ciphertext||tag (noble decrypt takes ct||tag concatenated)
    const ciphertextWithTag = new Uint8Array(record.ciphertext.length + record.tag.length);
    ciphertextWithTag.set(record.ciphertext, 0);
    ciphertextWithTag.set(record.tag, record.ciphertext.length);

    try {
      const cipher = gcm(key, record.nonce, openAad);
      const plaintext = cipher.decrypt(ciphertextWithTag);
      return plaintext;
    } catch (e) {
      throw new AesGcmRefused('AES-GCM refused');
    }
  }

  function wrapDek(dek) {
    // wrapDek(dek) takes NO aad argument. Wrapped key is under EMPTY AAD always.
    if (!dek || !(dek instanceof Uint8Array) || dek.length !== 32) {
      throw new AesGcmRefused('AES-GCM refused');
    }

    const sealed = seal(dek, new Uint8Array(0)); // empty AAD always
    const wrapped = new Uint8Array(60);
    wrapped.set(sealed.nonce, 0);
    wrapped.set(sealed.ciphertext, 12);
    wrapped.set(sealed.tag, 44);
    return wrapped;
  }

  function unwrapDek(wrapped) {
    // unwrapDek(wrapped) takes NO aad argument
    if (!wrapped || !(wrapped instanceof Uint8Array) || wrapped.length !== 60) {
      throw new AesGcmRefused('AES-GCM refused');
    }

    // Copy the three parts out of the wrapped key
    const nonce = new Uint8Array(wrapped.subarray(0, 12));
    const ciphertext = new Uint8Array(wrapped.subarray(12, 44));
    const tag = new Uint8Array(wrapped.subarray(44, 60));

    const record = { nonce, ciphertext, tag };
    const dek = open(record, new Uint8Array(0)); // empty AAD always
    return dek;
  }

  return { seal, open, wrapDek, unwrapDek };
}

// Export the interface
export { createCipher, AesGcmRefused, BYTE_LIMIT };