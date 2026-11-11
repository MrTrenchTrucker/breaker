import test from 'node:test';
import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { KdfRefused, stretch } from '../../main/resources/web/js/signin-kdf.js';
import { passwordToBytes } from '../../main/resources/web/js/signin-link.js';
import { argon2id } from '../../main/resources/web/js/vendor/noble-hashes/argon2.js';
import { hkdf } from '../../main/resources/web/js/vendor/noble-hashes/hkdf.js';
import { sha256 } from '../../main/resources/web/js/vendor/noble-hashes/sha2.js';
import { KDF_ANDROID, RFC5869_HKDF, RFC9106_ARGON2ID } from './signin-vectors.mjs';

// Vector order follows the Android known-answer file: main, composed and decomposed pair,
// alternative cost, parallelism two.
const [MAIN, PAIR, ALT, PARALLEL_TWO] = KDF_ANDROID;
const REFUSAL = 'key derivation refused';

const fromHex = (text) => new Uint8Array(Buffer.from(text, 'hex'));
const hex = (bytes) => Buffer.from(bytes).toString('hex');
const paramsOf = (v) => ({
  memoryKib: v.memoryKib, iterations: v.iterations, parallelism: v.parallelism, outputLength: 32,
});

function assertVector(result, v) {
  assert.equal(hex(result.kek), v.kek);
  assert.equal(hex(result.verifier), v.verifier);
}

// Each derivation runs once per file: the accept controls below reuse these promises.
const runs = new Map();
function once(key, start) {
  if (!runs.has(key)) runs.set(key, start());
  return runs.get(key);
}
const mainRun = () => once('main', () => stretch(
  passwordToBytes(MAIN.password), fromHex(MAIN.saltHex), paramsOf(MAIN),
));
const ceilingRun = () => once('ceiling', () => stretch(
  passwordToBytes(MAIN.password), fromHex(MAIN.saltHex), { memoryKib: 262144, iterations: 3, parallelism: 1, outputLength: 32 },
));
const strongestRun = () => once('strongest', () => stretch(
  passwordToBytes(MAIN.password), fromHex(MAIN.saltHex), { memoryKib: 65536, iterations: 10, parallelism: 4, outputLength: 32 },
));

const acceptsMain = async () => assertVector(await mainRun(), MAIN);
async function acceptsSized(run) {
  const r = await run();
  assert.equal(r.kek.length, 32);
  assert.equal(r.verifier.length, 32);
}

async function assertRefused(call) {
  await assert.rejects(call, (err) => err instanceof KdfRefused && err.name === 'KdfRefused' && err.message === REFUSAL);
}

const floor = paramsOf(MAIN);
const pw = passwordToBytes(MAIN.password);
const salt16 = fromHex(MAIN.saltHex);

test('the main Android vector gives its kek and verifier', acceptsMain);

test('the alternative cost vector gives its kek and verifier at 128 MiB', async () => {
  assertVector(await stretch(passwordToBytes(ALT.password), fromHex(ALT.saltHex), paramsOf(ALT)), ALT);
});

test('the parallelism two vector gives its kek and verifier', async () => {
  assertVector(
    await stretch(passwordToBytes(PARALLEL_TWO.password), fromHex(PARALLEL_TWO.saltHex), paramsOf(PARALLEL_TWO)),
    PARALLEL_TWO,
  );
});

test('composed and decomposed spellings give the same bytes and both give the vector', async () => {
  const composed = passwordToBytes(PAIR.password);
  const decomposed = passwordToBytes(PAIR.passwordDecomposed);
  assert.deepEqual(Array.from(composed), [0x63, 0x61, 0x66, 0xc3, 0xa9]);
  assert.deepEqual(decomposed, composed);
  assertVector(await stretch(composed, fromHex(PAIR.saltHex), paramsOf(PAIR)), PAIR);
  assertVector(await stretch(decomposed, fromHex(PAIR.saltHex), paramsOf(PAIR)), PAIR);
});

test('the RFC 9106 Argon2id library vector gives its tag', () => {
  const v = RFC9106_ARGON2ID;
  const tag = argon2id(fromHex(v.password), fromHex(v.salt), {
    t: v.passes, m: v.memoryKib, p: v.lanes, dkLen: v.tagLength,
    key: fromHex(v.secret), personalization: fromHex(v.associatedData),
  });
  assert.equal(hex(tag), v.tag);
});

for (const c of RFC5869_HKDF) {
  test('RFC 5869 library vector ' + c.name + ' gives its OKM', () => {
    const okm = hkdf(sha256, fromHex(c.ikm), fromHex(c.salt), fromHex(c.info), c.length);
    assert.equal(hex(okm), c.okm);
  });
}

test('kek and verifier are two distinct 32 byte arrays and neither is the Argon2id tag', async () => {
  const r = await mainRun();
  assert.ok(r.kek instanceof Uint8Array && r.kek.length === 32);
  assert.ok(r.verifier instanceof Uint8Array && r.verifier.length === 32);
  assert.notStrictEqual(r.kek, r.verifier);
  assert.notEqual(hex(r.kek), hex(r.verifier));
  assert.notEqual(hex(r.kek), MAIN.argon2id);
  assert.notEqual(hex(r.verifier), MAIN.argon2id);
});

// Every refusal runs before any work. Each accept control is a call the file already makes.
const REFUSALS = [
  ['memory one below the floor is refused and the floor is accepted',
    () => stretch(pw, salt16, { ...floor, memoryKib: 65535 }), acceptsMain],
  ['memory one above the ceiling is refused and the ceiling is accepted',
    () => stretch(pw, salt16, { ...floor, memoryKib: 262145 }), () => acceptsSized(ceilingRun)],
  ['iterations one below the floor is refused and the floor is accepted',
    () => stretch(pw, salt16, { ...floor, iterations: 2 }), acceptsMain],
  ['iterations one above the ceiling is refused and the ceiling is accepted',
    () => stretch(pw, salt16, { ...floor, iterations: 11 }), () => acceptsSized(strongestRun)],
  ['parallelism below the floor is refused and the floor is accepted',
    () => stretch(pw, salt16, { ...floor, parallelism: 0 }), acceptsMain],
  ['parallelism above the ceiling is refused and the ceiling is accepted',
    () => stretch(pw, salt16, { ...floor, parallelism: 5 }), () => acceptsSized(strongestRun)],
  ['output length 31 is refused and the 32 byte length is accepted',
    () => stretch(pw, salt16, { ...floor, outputLength: 31 }), acceptsMain],
  ['output length 33 is refused and the 32 byte length is accepted',
    () => stretch(pw, salt16, { ...floor, outputLength: 33 }), acceptsMain],
  ['a salt of 15 bytes is refused and a 16 byte salt is accepted',
    () => stretch(pw, salt16.subarray(0, 15), floor), acceptsMain],
  ['a salt of 17 bytes is refused and a 16 byte salt is accepted',
    () => stretch(pw, Uint8Array.from([...salt16, 0x00]), floor), acceptsMain],
  ['a salt that is an ordinary array is refused and a typed array is accepted',
    () => stretch(pw, Array.from(salt16), floor), acceptsMain],
  ['a password that is a string is refused and a byte array is accepted',
    () => stretch(MAIN.password, salt16, floor), acceptsMain],
  ['params with a missing field are refused and the full params are accepted',
    () => stretch(pw, salt16, { iterations: 3, parallelism: 1, outputLength: 32 }), acceptsMain],
  ['a memory value that is not an integer is refused and the integer is accepted',
    () => stretch(pw, salt16, { ...floor, memoryKib: 65536.5 }), acceptsMain],
  ['params that is null is refused and the floor is accepted',
    () => stretch(pw, salt16, null), acceptsMain],
  ['params that is a number is refused and the floor is accepted',
    () => stretch(pw, salt16, 65536), acceptsMain],
  ['an iterations value of 3.5 is refused and the floor is accepted',
    () => stretch(pw, salt16, { ...floor, iterations: 3.5 }), acceptsMain],
  ['a parallelism value of 1.5 is refused and the floor is accepted',
    () => stretch(pw, salt16, { ...floor, parallelism: 1.5 }), acceptsMain],
  ['a NaN memory value is refused and the floor is accepted',
    () => stretch(pw, salt16, { ...floor, memoryKib: NaN }), acceptsMain],
  ['an Infinity memory value is refused and the floor is accepted',
    () => stretch(pw, salt16, { ...floor, memoryKib: Infinity }), acceptsMain],
  ['a NaN iterations value is refused and the floor is accepted',
    () => stretch(pw, salt16, { ...floor, iterations: NaN }), acceptsMain],
  ['an Infinity iterations value is refused and the floor is accepted',
    () => stretch(pw, salt16, { ...floor, iterations: Infinity }), acceptsMain],
  ['a NaN parallelism value is refused and the floor is accepted',
    () => stretch(pw, salt16, { ...floor, parallelism: NaN }), acceptsMain],
  ['an Infinity parallelism value is refused and the floor is accepted',
    () => stretch(pw, salt16, { ...floor, parallelism: Infinity }), acceptsMain],
];

for (const [name, call, accept] of REFUSALS) {
  test(name, async () => {
    await assertRefused(call());
    await accept();
  });
}

test('a refusal text is exactly the fixed message and holds no password or salt character', async () => {
  const leakPassword = passwordToBytes('QZXJKW');
  const leakSalt = new Uint8Array(15).fill(0x5a);
  await assert.rejects(stretch(leakPassword, leakSalt, floor), (err) => {
    assert.equal(err.message, REFUSAL);
    for (const ch of 'QZXJKW') assert.equal(err.message.includes(ch), false);
    for (const b of leakSalt) assert.equal(err.message.includes(String.fromCharCode(b)), false);
    assert.equal(err.name, 'KdfRefused');
    return err instanceof KdfRefused;
  });
});
