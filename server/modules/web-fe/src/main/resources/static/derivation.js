// Browser-safe key derivation module.
// Uses ONLY globalThis.crypto.subtle, String.prototype.normalize('NFC'), TextEncoder.
// NO require, NO process, NO node builtins.

const KEK_LABEL = 'breaker-kek-v1';
const VERIFIER_LABEL = 'breaker-auth-verifier-v1';

const encoder = new TextEncoder();

function passwordToBytes(password) {
  const normalized = password.normalize('NFC');
  return encoder.encode(normalized);
}

function checkKdfParams(params) {
  const limits = {
    memoryKib: { min: 65536, max: 262144 },
    iterations: { min: 3, max: 10 },
    parallelism: { min: 1, max: 4 },
    outputBytes: 32,
    saltBytes: 16,
    kdfVersion: 1,
  };

  if (typeof params !== 'object' || params === null) {
    throw new Error('params must be an object');
  }

  const { memoryKib, iterations, parallelism, outputBytes, saltBytes, kdfVersion } = params;

  if (typeof memoryKib !== 'number' || !Number.isInteger(memoryKib) || memoryKib < limits.memoryKib.min || memoryKib > limits.memoryKib.max) {
    throw new Error(`memoryKib must be between ${limits.memoryKib.min} and ${limits.memoryKib.max}`);
  }
  if (typeof iterations !== 'number' || !Number.isInteger(iterations) || iterations < limits.iterations.min || iterations > limits.iterations.max) {
    throw new Error(`iterations must be between ${limits.iterations.min} and ${limits.iterations.max}`);
  }
  if (typeof parallelism !== 'number' || !Number.isInteger(parallelism) || parallelism < limits.parallelism.min || parallelism > limits.parallelism.max) {
    throw new Error(`parallelism must be between ${limits.parallelism.min} and ${limits.parallelism.max}`);
  }
  if (outputBytes !== limits.outputBytes) {
    throw new Error(`outputBytes must be ${limits.outputBytes}`);
  }
  if (saltBytes !== limits.saltBytes) {
    throw new Error(`saltBytes must be ${limits.saltBytes}`);
  }
  if (kdfVersion !== limits.kdfVersion) {
    throw new Error(`kdfVersion must be ${limits.kdfVersion}`);
  }
}

async function hkdfSha256(ikm, salt, info, length) {
  const subtle = globalThis.crypto.subtle;
  const key = await subtle.importKey('raw', ikm, { name: 'HKDF', hash: 'SHA-256' }, false, ['deriveBits']);
  const bits = await subtle.deriveBits(
    { name: 'HKDF', hash: 'SHA-256', salt, info },
    key,
    length * 8
  );
  return new Uint8Array(bits);
}

async function deriveKeys(ikm) {
  const kek = await hkdfSha256(ikm, new Uint8Array(0), encoder.encode(KEK_LABEL), 32);
  const verifier = await hkdfSha256(ikm, new Uint8Array(0), encoder.encode(VERIFIER_LABEL), 32);
  return { kek, verifier };
}

export { passwordToBytes, checkKdfParams, hkdfSha256, deriveKeys, KEK_LABEL, VERIFIER_LABEL };
