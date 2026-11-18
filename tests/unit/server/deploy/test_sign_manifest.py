"""Unit tests for scripts/sign-manifest.sh.

Real openssl, throwaway Ed25519 keys made at run time (openssl genpkey), key
files mode 0600 in temp dirs. The one fixed key is the PUBLIC RFC 8032
section 7.1 TEST 2 vector (seed, public key, message 0x72, signature); its
PKCS#8 PEM is built at run time from the seed bytes (fixed DER prefix + seed),
so no private-key block is ever written to the repo.

Rule citations:
  * message layout: "breaker-update-v1\\n" + V + "\\n" + lowercase(sha) + "\\n",
    the bytes the app's SignatureCheck.verify builds (buildMessage).
  * version: not blank, no control character (SignatureCheck.verify step 1).
  * sha256: exactly 64 hex digits, any case (SignatureCheck step 2).
  * signature: canonical base64 of 64 bytes = 88 chars (step 3).
  * mode: exactly 600, read with `stat -c %a` (deploy-common.sh mode check).
  * openssl 3.0 cannot sign an EMPTY message, so the RFC TEST 1 vector is not
    usable; TEST 2 (message 0x72) is.

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_sign_manifest.py'
"""
import base64
import os
import re
import shlex
import shutil
import stat
import subprocess
import tempfile
import unittest

SCRIPT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    '..', '..', '..', '..',
    'server', 'modules', 'deploy', 'scripts', 'sign-manifest.sh',
))

SHA = 'ab' * 32
VERSION = '0.1.0.1'

# Public RFC 8032 section 7.1 TEST 2 vector.
RFC_SEED = bytes.fromhex('4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb')
RFC_PUB = bytes.fromhex('3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c')
RFC_MSG = bytes.fromhex('72')
RFC_SIG = bytes.fromhex(
    '92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da'
    '085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00')
PKCS8_PREFIX = bytes.fromhex('302e020100300506032b657004220420')
SPKI_PREFIX = bytes.fromhex('302a300506032b6570032100')


def pem_of(label, der):
    b64 = base64.b64encode(der).decode()
    lines = [b64[i:i + 64] for i in range(0, len(b64), 64)]
    return '-----BEGIN %s-----\n%s\n-----END %s-----\n' % (label, '\n'.join(lines), label)


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, timeout=60, **kw)


def message(version, sha):
    return b'breaker-update-v1\n' + version.encode() + b'\n' + sha.lower().encode() + b'\n'


class SignManifestTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix='signmf-test-')
        self.addCleanup(shutil.rmtree, self.root, True)
        self.tmpdir = os.path.join(self.root, 'tmp')
        os.mkdir(self.tmpdir)
        self.key = self.make_key('key.pem')

    # ---- helpers ----
    def path(self, name):
        return os.path.join(self.root, name)

    def write(self, name, data, mode=0o600):
        p = self.path(name)
        with open(p, 'wb') as fh:
            fh.write(data if isinstance(data, bytes) else data.encode())
        os.chmod(p, mode)
        return p

    def make_key(self, name, algo='ED25519', mode=0o600):
        p = self.path(name)
        args = ['openssl', 'genpkey', '-algorithm', algo, '-out', p]
        if algo == 'RSA':
            args += ['-pkeyopt', 'rsa_keygen_bits:2048']
        self.assertEqual(run(args).returncode, 0)
        os.chmod(p, mode)
        return p

    def pub_of(self, key, name='pub.pem'):
        p = self.path(name)
        self.assertEqual(run(['openssl', 'pkey', '-in', key, '-pubout', '-out', p]).returncode, 0)
        return p

    def env(self, **extra):
        e = {'PATH': os.environ['PATH'], 'TMPDIR': self.tmpdir, 'LC_ALL': 'C'}
        e.update(extra)
        return e

    def sign(self, args=None, env=None, key=None, version=VERSION, sha=SHA):
        if args is None:
            args = ['--key-file', key or self.key, '--version', version, '--sha256', sha]
        r = run(['sh', SCRIPT] + args, env=env if env is not None else self.env())
        self.check_no_key_text(r)
        return r

    def check_no_key_text(self, r):
        """(6) no PEM body line of any key file here and no key header in any output."""
        out = r.stdout + r.stderr
        self.assertNotIn(b'PRIVATE KEY', out)
        for name in os.listdir(self.root):
            p = self.path(name)
            if os.path.isfile(p) and os.access(p, os.R_OK):
                with open(p, 'rb') as fh:
                    for line in fh.read().splitlines():
                        if len(line) >= 16 and not line.startswith(b'-----'):
                            self.assertNotIn(line, out)

    def verifies(self, pubfile, msg, sig_bytes):
        m = self.write('vmsg', msg)
        s = self.write('vsig', sig_bytes)
        return run(['openssl', 'pkeyutl', '-verify', '-rawin', '-pubin', '-inkey', pubfile,
                    '-in', m, '-sigfile', s]).returncode == 0

    def expected_sig(self, key, msg):
        m = self.write('emsg', msg)
        o = self.path('esig')
        self.assertEqual(run(['openssl', 'pkeyutl', '-sign', '-rawin', '-inkey', key,
                              '-in', m, '-out', o]).returncode, 0)
        with open(o, 'rb') as fh:
            return fh.read()

    def assert_refused(self, r, reason_part=None):
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(r.stdout, b'')
        self.assertTrue(r.stderr.startswith(b'sign-manifest.sh: refused: '), r.stderr)
        self.assertTrue(r.stderr.endswith(b'\n'))
        self.assertEqual(r.stderr.count(b'\n'), 1)
        if reason_part:
            self.assertIn(reason_part.encode(), r.stderr)

    def assert_tmp_empty(self):
        self.assertEqual(os.listdir(self.tmpdir), [])

    def rfc_key(self):
        return self.write('rfc.pem', pem_of('PRIVATE KEY', PKCS8_PREFIX + RFC_SEED))

    def rfc_pub(self):
        return self.write('rfcpub.pem', pem_of('PUBLIC KEY', SPKI_PREFIX + RFC_PUB))

    # ---- (1) known answer ----
    def test_the_openssl_argv_the_script_uses_gives_the_rfc_8032_test_2_signature(self):
        with open(SCRIPT) as fh:
            text = fh.read()
        m = re.search(r'^if ! (openssl pkeyutl -sign -rawin -inkey \S+ -in \S+ -out \S+)', text, re.M)
        self.assertIsNotNone(m, 'sign line not found in the script')
        argv = shlex.split(m.group(1))
        sub = {'$tmpd/key.pem': self.rfc_key(), '$tmpd/msg': self.write('rmsg', RFC_MSG),
               '$tmpd/sig': self.path('rsig')}
        argv = [sub.get(a, a) for a in argv]
        for a in argv:
            self.assertNotIn('$', a)
        self.assertEqual(run(argv).returncode, 0)
        with open(self.path('rsig'), 'rb') as fh:
            self.assertEqual(fh.read(), RFC_SIG)
        self.assertTrue(self.verifies(self.rfc_pub(), RFC_MSG, RFC_SIG))
        self.assertFalse(self.verifies(self.rfc_pub(), b'\x73', RFC_SIG))

    def test_the_script_signs_the_card_message_with_the_rfc_key_matching_openssl(self):
        key = self.rfc_key()
        r = self.sign(key=key)
        self.assertEqual(r.returncode, 0, r.stderr)
        got = base64.b64decode(r.stdout.strip())
        self.assertEqual(got, self.expected_sig(key, message(VERSION, SHA)))
        self.assertTrue(self.verifies(self.rfc_pub(), message(VERSION, SHA), got))

    # ---- (2) round trip, output shape ----
    def test_a_random_key_round_trip_verifies_with_openssl(self):
        r = self.sign()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stderr, b'')
        sig = base64.b64decode(r.stdout.strip(), validate=True)
        self.assertEqual(len(sig), 64)
        self.assertTrue(self.verifies(self.pub_of(self.key), message(VERSION, SHA), sig))

    def test_stdout_is_exactly_one_88_char_canonical_base64_line(self):
        r = self.sign()
        self.assertEqual(r.returncode, 0)
        self.assertRegex(r.stdout.decode(), r'\A[A-Za-z0-9+/]{86}==\n\Z')
        self.assertEqual(base64.b64encode(base64.b64decode(r.stdout.strip())), r.stdout.strip())

    # ---- (3) message pinned ----
    def test_the_signature_covers_exactly_the_card_message(self):
        r = self.sign()
        sig = base64.b64decode(r.stdout.strip())
        pub = self.pub_of(self.key)
        good = b'breaker-update-v1\n' + VERSION.encode() + b'\n' + SHA.encode() + b'\n'
        self.assertTrue(self.verifies(pub, good, sig))
        for wrong in (good[:-1], good.replace(SHA.encode(), SHA.upper().encode()),
                      good.replace(b'v1', b'v2'), b'breaker-update-v1\n' + SHA.encode() + b'\n',
                      good + b'\n', VERSION.encode() + b'\n' + SHA.encode() + b'\n'):
            self.assertFalse(self.verifies(pub, wrong, sig), wrong)

    def test_an_uppercase_sha_is_accepted_and_signed_as_lowercase(self):
        up = ('AB' * 31) + 'Cd'
        r = self.sign(sha=up)
        self.assertEqual(r.returncode, 0, r.stderr)
        sig = base64.b64decode(r.stdout.strip())
        pub = self.pub_of(self.key)
        self.assertTrue(self.verifies(pub, message(VERSION, up), sig))
        self.assertFalse(self.verifies(pub, b'breaker-update-v1\n' + VERSION.encode() + b'\n'
                                       + up.encode() + b'\n', sig))
        self.assertEqual(r.stdout, self.sign(sha=up.lower()).stdout)

    # ---- (4) refusals ----
    def test_a_key_with_mode_0644_is_refused(self):
        for mode in (0o644, 0o400, 0o640, 0o660, 0o666, 0o700, 0o200, 0o000):
            k = self.make_key('m%o.pem' % mode, mode=mode)
            self.assert_refused(self.sign(key=k), 'mode')
            os.chmod(k, 0o600)

    def test_a_missing_key_file_is_refused(self):
        self.assert_refused(self.sign(key=self.path('nope.pem')), 'regular file')

    def test_a_directory_as_key_file_is_refused(self):
        d = self.path('adir')
        os.mkdir(d)
        os.chmod(d, 0o600)
        self.assert_refused(self.sign(key=d), 'regular file')

    def test_a_non_ed25519_key_is_refused(self):
        self.assert_refused(self.sign(key=self.make_key('rsa.pem', algo='RSA')), 'Ed25519')

    def test_a_garbage_key_file_is_refused(self):
        self.assert_refused(self.sign(key=self.write('g.pem', b'hello not a key\n')), 'Ed25519')

    def test_a_pem_header_over_garbage_body_is_refused(self):
        body = pem_of('PRIVATE KEY', b'\x00' * 40)
        self.assert_refused(self.sign(key=self.write('g2.pem', body)), 'Ed25519')

    def test_a_key_file_with_text_before_the_pkcs8_header_is_refused(self):
        with open(self.key, 'rb') as fh:
            pem = fh.read()
        k = self.write('lead.pem', b'# a note\n' + pem)
        self.assert_refused(self.sign(key=k), 'PKCS#8')

    def test_a_blank_version_is_refused(self):
        for v in ('', ' ', '   '):
            self.assert_refused(self.sign(version=v), 'version')

    def test_a_version_with_a_control_character_is_refused(self):
        for v in ('1.0\n', '1\n.0', '1.0\t', 'a\x01b', 'a\x1fb', 'a\x7fb', '1.0\r',
                  'a\u0085b', 'a\u009fb'):
            args = ['--key-file', self.key, '--version', v, '--sha256', SHA]
            self.assert_refused(self.sign(args=args), 'control')

    def test_a_version_with_ordinary_non_ascii_text_is_accepted(self):
        r = self.sign(version='1.0.\u00e9.0')
        self.assertEqual(r.returncode, 0, r.stderr)
        sig = base64.b64decode(r.stdout.strip())
        self.assertTrue(self.verifies(self.pub_of(self.key), message('1.0.\u00e9.0', SHA), sig))

    def test_a_sha_of_the_wrong_length_is_refused(self):
        for s in ('', 'a', 'ab' * 31 + 'a', 'ab' * 32 + 'a', 'ab' * 33, 'a' * 128):
            self.assert_refused(self.sign(sha=s), 'sha256')

    def test_a_non_hex_sha_is_refused(self):
        for bad in ('g', 'G', 'z', ' ', '\n', '-', 'x'):
            s = bad + 'a' * 63
            self.assert_refused(self.sign(sha=s), 'sha256')
            s = 'a' * 63 + bad
            self.assert_refused(self.sign(sha=s), 'sha256')

    def test_a_valid_sha_with_every_hex_digit_is_accepted(self):
        for s in ('0123456789abcdef' * 4, 'ABCDEF0123456789' * 4):
            self.assertEqual(self.sign(sha=s).returncode, 0)

    def test_a_signature_that_does_not_verify_is_refused(self):
        # An openssl whose verify step fails (everything else passes through).
        shim = self.shim('''
case "$*" in *"-verify"*) exit 1 ;; esac
exec %s "$@"
''' % shlex.quote(shutil.which('openssl')))
        r = self.sign(env=self.env(PATH=shim + os.pathsep + os.environ['PATH']))
        self.assert_refused(r, 'verify')
        self.assert_tmp_empty()

    def shim(self, body):
        d = self.path('shim')
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, 'openssl')
        with open(p, 'w') as fh:
            fh.write('#!/bin/sh\n' + body)
        os.chmod(p, 0o755)
        return d

    # ---- (5) temp wipe ----
    def test_the_temp_dir_is_empty_after_success(self):
        self.assertEqual(self.sign().returncode, 0)
        self.assert_tmp_empty()

    def test_the_temp_dir_is_empty_after_every_refusal_that_follows_the_copy(self):
        cases = [self.make_key('rsa.pem', algo='RSA'), self.write('g.pem', b'junk\n')]
        for k in cases:
            self.assert_refused(self.sign(key=k))
            self.assert_tmp_empty()

    def test_the_temp_dir_is_empty_after_refusals_before_the_copy(self):
        self.assert_refused(self.sign(version=''))
        self.assert_refused(self.sign(sha='x'))
        self.assert_refused(self.sign(key=self.path('nope')))
        self.assert_tmp_empty()

    def test_a_failing_openssl_sign_step_still_leaves_the_temp_dir_empty(self):
        shim = self.shim('''
case "$*" in *"pkeyutl"*) exit 1 ;; esac
exec %s "$@"
''' % shlex.quote(shutil.which('openssl')))
        r = self.sign(env=self.env(PATH=shim + os.pathsep + os.environ['PATH']))
        self.assert_refused(r, 'signing failed')
        self.assert_tmp_empty()

    def test_the_key_copy_exists_in_the_temp_dir_while_openssl_runs_with_mode_0600(self):
        # Proves the wipe test is not vacuous: the copy is really there mid-run.
        log = self.path('seen.txt')
        shim = self.shim('''
case "$*" in *"pkeyutl -sign"*)
  d=$(dirname "$(printf '%%s\\n' "$@" | grep key.pem | head -n 1)")
  ls "$d" > %s; stat -c '%%a %%n' "$d" "$d/key.pem" >> %s ;;
esac
exec %s "$@"
''' % (shlex.quote(log), shlex.quote(log), shlex.quote(shutil.which('openssl'))))
        r = self.sign(env=self.env(PATH=shim + os.pathsep + os.environ['PATH']))
        self.assertEqual(r.returncode, 0, r.stderr)
        with open(log) as fh:
            seen = fh.read().split('\n')
        self.assertIn('key.pem', seen)
        self.assertTrue(any(l.startswith('700 ') for l in seen), seen)
        self.assertTrue(any(l.startswith('600 ') and l.endswith('key.pem') for l in seen), seen)
        self.assert_tmp_empty()

    def test_the_temp_dir_is_empty_after_the_script_is_terminated_mid_run(self):
        import signal
        import time
        marker = self.path('started')
        shim = self.shim('''
case "$*" in *"pkeyutl -sign"*) : > %s; sleep 20 ;; esac
exec %s "$@"
''' % (shlex.quote(marker), shlex.quote(shutil.which('openssl'))))
        p = subprocess.Popen(['sh', SCRIPT, '--key-file', self.key, '--version', VERSION,
                              '--sha256', SHA],
                             env=self.env(PATH=shim + os.pathsep + os.environ['PATH']),
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             start_new_session=True)
        try:
            for _ in range(100):
                if os.path.exists(marker):
                    break
                time.sleep(0.1)
            self.assertTrue(os.path.exists(marker))
            self.assertEqual(len(os.listdir(self.tmpdir)), 1)
            os.killpg(p.pid, signal.SIGTERM)
            p.communicate(timeout=30)
        finally:
            if p.poll() is None:
                os.killpg(p.pid, signal.SIGKILL)
                p.communicate()
        self.assert_tmp_empty()

    # ---- (6) no key text ----
    def test_no_key_text_reaches_output_on_success_or_failure(self):
        # check_no_key_text runs inside sign() for every call; make it bite here too.
        self.assertEqual(self.sign().returncode, 0)
        self.assert_refused(self.sign(key=self.make_key('rsa.pem', algo='RSA')))
        with open(self.key, 'rb') as fh:
            body = [l for l in fh.read().splitlines() if not l.startswith(b'-----')]
        self.assertTrue(body and all(len(l) >= 16 for l in body))

    def test_the_key_value_in_the_environment_is_not_read(self):
        r = self.sign(args=['--version', VERSION, '--sha256', SHA],
                      env=self.env(BREAKER_MANIFEST_KEY='x' * 64))
        self.assertEqual(r.returncode, 2)

    # ---- (7) key path option / env ----
    def test_the_key_file_option_beats_the_environment_variable(self):
        other = self.make_key('other.pem')
        args = ['--key-file', self.key, '--version', VERSION, '--sha256', SHA]
        r = self.sign(args=args, env=self.env(BREAKER_MANIFEST_KEY_FILE=other))
        self.assertEqual(r.returncode, 0, r.stderr)
        sig = base64.b64decode(r.stdout.strip())
        self.assertTrue(self.verifies(self.pub_of(self.key), message(VERSION, SHA), sig))
        self.assertFalse(self.verifies(self.pub_of(other, 'p2.pem'), message(VERSION, SHA), sig))

    def test_the_environment_variable_works_alone(self):
        r = self.sign(args=['--version', VERSION, '--sha256', SHA],
                      env=self.env(BREAKER_MANIFEST_KEY_FILE=self.key))
        self.assertEqual(r.returncode, 0, r.stderr)
        sig = base64.b64decode(r.stdout.strip())
        self.assertTrue(self.verifies(self.pub_of(self.key), message(VERSION, SHA), sig))

    def test_neither_option_nor_environment_variable_is_a_usage_error(self):
        r = self.sign(args=['--version', VERSION, '--sha256', SHA])
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout, b'')

    # ---- (8) usage ----
    def test_usage_errors_exit_2_with_no_stdout(self):
        k = ['--key-file', self.key]
        v = ['--version', VERSION]
        s = ['--sha256', SHA]
        cases = [
            k + v + s + ['--bogus'],
            k + v + s + ['--bogus', 'x'],
            k + v + s + ['stray'],
            k + v,
            k + s,
            v + s,
            [],
            ['--key-file'],
            k + ['--version'],
            k + v + ['--sha256'],
        ]
        for args in cases:
            r = self.sign(args=args)
            self.assertEqual(r.returncode, 2, args)
            self.assertEqual(r.stdout, b'', args)
            self.assertTrue(r.stderr.startswith(b'sign-manifest.sh: '), args)
            self.assertNotIn(b'refused', r.stderr)
        self.assert_tmp_empty()

    def test_the_script_is_executable_and_syntax_clean(self):
        self.assertEqual(stat.S_IMODE(os.stat(SCRIPT).st_mode), 0o755)
        self.assertEqual(run(['sh', '-n', SCRIPT]).returncode, 0)


if __name__ == '__main__':
    unittest.main()
