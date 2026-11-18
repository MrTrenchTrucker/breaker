"""Unit tests for scripts/write-manifest.sh.

Real openssl through the real sibling signer (sign-manifest.sh), THROWAWAY
Ed25519 keys made at run time (openssl genpkey, mode 0600, temp dirs). No key
text is ever written to the repo or printed. A stub signer is used only for the
call-shape and failure tests.

Rules this file reproduces from the app and the server (the decoder below is a
test-local re-implementation, checked against bad documents so it is not
vacuous):
  * ManifestJson.kt: decode() 33-41 (five text members are strings, version_code
    a number), readMembers 49-58 (UTF-8 strict, one document), wholeNumber
    67-72 (1-18 digits, no leading zero), Reader.readDocument 89-94 (text
    after the object refused), skipSpace 97 (four white-space characters),
    readObject 111-132 (duplicate key refused at 124), readValue depth checks
    156 and 161 (MAX_DEPTH = 4, line 10), readNumber 180-199 (number grammar),
    readString 213-236 (control char below 0x20 refused at 215, bad escape at
    232, \\uXXXX at 238-247), unknown members ignored (header, lines 19-20).
  * UpdatesRoute.kt: validate 99-108 (four text fields non-blank, notes a
    string), nonBlankText 110-113, readVersionCode 117-124, MANIFEST_CAP 22
    (65 536 bytes), manifestBody 127-133 (six members, this order).

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_write_manifest.py'
"""
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.normpath(os.path.join(
    HERE, '..', '..', '..', '..',
    'server', 'modules', 'deploy', 'scripts', 'write-manifest.sh',
))
SIGNER = os.path.join(os.path.dirname(SCRIPT), 'sign-manifest.sh')

NAME = '1.2.3.4'
CODE = '5'
MARK = 'ZZMARKZZ'  # appears in inputs; must never come back in a refusal
APK_BYTES = b'PK\x03\x04 fake apk bytes for the manifest writer test \x00\x01\x02'
PREFIX = b'write-manifest.sh: refused: '


def rel_url(name=NAME, code=CODE, scheme='http'):
    return '%s://updates.example.test:8080/%s/releases/%s-%s/breaker.apk' % (scheme, MARK, name, code)


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, timeout=60, **kw)


# ---------------------------------------------------------------------------
# Test-local strict decoder: a hand-written mirror of ManifestJson.kt (and the
# route's non-blank rule). Returns the six-member dict or None.
# ---------------------------------------------------------------------------
class _Bad(Exception):
    pass


class _Reader:
    MAX_DEPTH = 4  # ManifestJson.kt:10

    def __init__(self, s):
        self.s = s
        self.pos = 0

    def document(self):  # ManifestJson.kt:89-94
        self.space()
        members = self.obj(1)
        self.space()
        if self.pos != len(self.s):  # :92 text after the object refused
            raise _Bad()
        return members

    def space(self):  # :97 JSON white space is exactly these four characters
        while self.pos < len(self.s) and self.s[self.pos] in ' \t\n\r':
            self.pos += 1

    def peek(self):
        if self.pos >= len(self.s):
            raise _Bad()
        return self.s[self.pos]

    def expect(self, ch):
        if self.peek() != ch:
            raise _Bad()
        self.pos += 1

    def obj(self, depth):  # :111-132
        self.expect('{')
        members = {}
        self.space()
        if self.peek() == '}':
            self.pos += 1
            return members
        while True:
            self.space()
            key = self.string()
            self.space()
            self.expect(':')
            if key in members:  # :124 duplicate key refused
                raise _Bad()
            members[key] = self.value(depth)
            self.space()
            nxt = self.peek()
            self.pos += 1
            if nxt == '}':
                break
            if nxt != ',':
                raise _Bad()
        return members

    def arr(self, depth):
        self.expect('[')
        self.space()
        if self.peek() == ']':
            self.pos += 1
            return
        while True:
            self.value(depth)
            self.space()
            nxt = self.peek()
            self.pos += 1
            if nxt == ']':
                break
            if nxt != ',':
                raise _Bad()

    def value(self, depth):
        self.space()
        c = self.peek()
        if c == '{':
            if depth + 1 > self.MAX_DEPTH:  # :156
                raise _Bad()
            self.obj(depth + 1)
            return ('other',)
        if c == '[':
            if depth + 1 > self.MAX_DEPTH:  # :161
                raise _Bad()
            self.arr(depth + 1)
            return ('other',)
        if c == '"':
            return ('text', self.string())
        for word in ('true', 'false', 'null'):
            if c == word[0]:
                for ch in word:
                    self.expect(ch)
                return ('other',)
        if c == '-' or '0' <= c <= '9':
            return ('num', self.number())
        raise _Bad()

    def digits(self):
        begin = self.pos
        while self.pos < len(self.s) and '0' <= self.s[self.pos] <= '9':
            self.pos += 1
        return self.pos - begin

    def number(self):  # :180-199 the JSON number grammar, raw text kept
        start = self.pos
        if self.peek() == '-':
            self.pos += 1
        if self.peek() == '0':
            self.pos += 1
        elif '1' <= self.peek() <= '9':
            self.digits()
        else:
            raise _Bad()
        if self.pos < len(self.s) and self.s[self.pos] == '.':
            self.pos += 1
            if self.digits() == 0:
                raise _Bad()
        if self.pos < len(self.s) and self.s[self.pos] in 'eE':
            self.pos += 1
            if self.pos < len(self.s) and self.s[self.pos] in '+-':
                self.pos += 1
            if self.digits() == 0:
                raise _Bad()
        return self.s[start:self.pos]

    def string(self):  # :213-236
        self.expect('"')
        out = []
        while True:
            c = self.peek()
            self.pos += 1
            if c == '"':
                break
            if ord(c) < 0x20:  # :215 unescaped control character refused
                raise _Bad()
            if c != '\\':
                out.append(c)
                continue
            e = self.peek()
            self.pos += 1
            simple = {'"': '"', '\\': '\\', '/': '/', 'b': '\b', 'f': '\f',
                      'n': '\n', 'r': '\r', 't': '\t'}
            if e in simple:
                out.append(simple[e])
            elif e == 'u':  # :238-247
                hexs = self.s[self.pos:self.pos + 4]
                if len(hexs) != 4 or re.fullmatch(r'[0-9a-fA-F]{4}', hexs) is None:
                    raise _Bad()
                self.pos += 4
                out.append(chr(int(hexs, 16)))
            else:  # :232
                raise _Bad()
        # Kotlin strings are UTF-16: a \ud83d\ude00 pair is one code point
        return ''.join(out).encode('utf-16', 'surrogatepass').decode('utf-16')


def whole_number(raw):  # ManifestJson.kt:67-72
    if raw is None or raw == '' or len(raw) > 18:
        return None
    if any(c < '0' or c > '9' for c in raw):
        return None
    if raw[0] == '0':
        return None
    return int(raw)


def decode_like_app_and_route(data):
    """ManifestJson.decode (33-41, 49-58) plus the route's non-blank rule (101-105)."""
    try:
        text = data.decode('utf-8', 'strict')  # :51-52
    except UnicodeDecodeError:
        return None
    try:
        members = _Reader(text).document()
    except _Bad:
        return None

    def text_of(key):
        v = members.get(key)
        return v[1] if v and v[0] == 'text' else None

    code_v = members.get('version_code')
    code = whole_number(code_v[1]) if code_v and code_v[0] == 'num' else None
    fields = {k: text_of(k) for k in ('version', 'apk_url', 'sha256', 'signature', 'notes')}
    if code is None or any(v is None for v in fields.values()):
        return None
    # UpdatesRoute.kt:101-105 / 110-113: four text fields non-blank; notes may be empty (:106)
    for key in ('version', 'apk_url', 'sha256', 'signature'):
        if fields[key].strip() == '':
            return None
    fields['version_code'] = code
    return fields


class WriteManifestBase(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix='writemf-test-')
        self.addCleanup(shutil.rmtree, self.root, True)
        self.outdir = os.path.join(self.root, 'out')
        os.mkdir(self.outdir)
        self.out = os.path.join(self.outdir, 'latest.json')
        self.apk = self.write('app.apk', APK_BYTES, 0o644)
        self.notes = self.write('notes.txt', 'Fixes and ' + MARK + ' notes.\n', 0o644)
        self.key = self.make_key('key.pem')
        self.pub = self.path('pub.pem')
        self.assertEqual(run(['openssl', 'pkey', '-in', self.key, '-pubout', '-out', self.pub]).returncode, 0)
        self.before = None

    # ---- helpers ----
    def path(self, name):
        return os.path.join(self.root, name)

    def write(self, name, data, mode=0o600):
        p = self.path(name)
        with open(p, 'wb') as fh:
            fh.write(data if isinstance(data, bytes) else data.encode())
        os.chmod(p, mode)
        return p

    def make_key(self, name, mode=0o600):
        p = self.path(name)
        self.assertEqual(run(['openssl', 'genpkey', '-algorithm', 'ED25519', '-out', p]).returncode, 0)
        os.chmod(p, mode)
        return p

    def env(self, **extra):
        e = {'PATH': os.environ['PATH'], 'LC_ALL': 'C'}
        e.update(extra)
        return e

    def args(self, **ov):
        d = dict(apk=self.apk, version_name=NAME, version_code=CODE, apk_url=rel_url(),
                 notes_file=self.notes, out=self.out, key_file=self.key)
        d.update(ov)
        argv = []
        for k, v in d.items():
            if v is None:
                continue
            if v is True:
                argv.append('--' + k.replace('_', '-'))
            else:
                argv += ['--' + k.replace('_', '-'), v]
        return argv

    def listing(self):
        return sorted(os.listdir(self.outdir))

    def wm(self, argv=None, env=None, script=None, cwd=None):
        self.before = self.listing()
        r = run(['sh', script or SCRIPT] + (self.args() if argv is None else argv),
                env=env if env is not None else self.env(), cwd=cwd)
        out = r.stdout + r.stderr
        self.assertNotIn(b'PRIVATE KEY', out)
        with open(self.key, 'rb') as fh:
            for line in fh.read().splitlines():
                if len(line) >= 16 and not line.startswith(b'-----'):
                    self.assertNotIn(line, out)
        return r

    def assert_refused(self, r, part):
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(r.stdout, b'')
        self.assertTrue(r.stderr.startswith(PREFIX), r.stderr)
        self.assertTrue(r.stderr.endswith(b'\n'))
        self.assertEqual(r.stderr.count(b'\n'), 1, r.stderr)
        self.assertIn(part.encode(), r.stderr)
        self.assertNotIn(MARK.encode(), r.stderr)
        # no output file and no temp leftovers
        self.assertEqual(self.listing(), self.before)

    def assert_ok(self, r):
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, (self.out + '\n').encode())
        self.assertEqual(r.stderr, b'')

    def out_bytes(self):
        with open(self.out, 'rb') as fh:
            return fh.read()

    def current(self, version='1.2.3.4', code=5, raw=None):
        doc = {'version': version, 'version_code': code, 'apk_url': rel_url(version, code),
               'sha256': 'ab' * 32, 'signature': 'A' * 86 + '==', 'notes': 'old'}
        data = raw if raw is not None else json.dumps(doc, separators=(',', ':')) + '\n'
        return self.write('current.json', data, 0o644)


class WriteManifestTest(WriteManifestBase):
    # ---- (1) happy path ----
    def test_the_happy_path_writes_one_line_with_six_members_in_order(self):
        r = self.wm()
        self.assert_ok(r)
        data = self.out_bytes()
        self.assertEqual(data.count(b'\n'), 1)
        self.assertTrue(data.endswith(b'\n'))
        pairs = json.loads(data, object_pairs_hook=list)
        self.assertEqual([k for k, _ in pairs],
                         ['version', 'version_code', 'apk_url', 'sha256', 'signature', 'notes'])
        doc = dict(pairs)
        self.assertEqual(doc['version'], NAME)
        self.assertIs(type(doc['version_code']), int)
        self.assertEqual(doc['version_code'], 5)
        self.assertIn(b'"version_code":5,', data)
        self.assertEqual(doc['apk_url'], rel_url())
        self.assertEqual(self.listing(), ['latest.json'])

    def test_the_output_mode_is_0644(self):
        self.assert_ok(self.wm())
        self.assertEqual(os.stat(self.out).st_mode & 0o777, 0o644)

    def test_the_sha256_is_the_lowercase_hash_of_the_apk_bytes(self):
        self.assert_ok(self.wm())
        doc = json.loads(self.out_bytes())
        self.assertEqual(doc['sha256'], hashlib.sha256(APK_BYTES).hexdigest())
        self.assertRegex(doc['sha256'], r'^[0-9a-f]{64}$')

    def test_the_signature_verifies_over_the_exact_signed_message(self):
        self.assert_ok(self.wm())
        doc = json.loads(self.out_bytes())
        self.assertEqual(len(doc['signature']), 88)
        sig = base64.b64decode(doc['signature'], validate=True)
        self.assertEqual(len(sig), 64)
        msg = ('breaker-update-v1\n%s\n%s\n' % (doc['version'], doc['sha256'])).encode()
        m = self.write('vmsg', msg)
        s = self.write('vsig', sig)
        ok = run(['openssl', 'pkeyutl', '-verify', '-rawin', '-pubin', '-inkey', self.pub,
                  '-in', m, '-sigfile', s])
        self.assertEqual(ok.returncode, 0, ok.stderr)
        bad = self.write('vmsg2', msg + b'x')
        no = run(['openssl', 'pkeyutl', '-verify', '-rawin', '-pubin', '-inkey', self.pub,
                  '-in', bad, '-sigfile', s])
        self.assertNotEqual(no.returncode, 0)

    def test_a_relative_out_path_is_written_and_printed_as_given(self):
        r = self.wm(self.args(out='rel.json'), cwd=self.outdir)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, b'rel.json\n')
        self.assertEqual(self.listing(), ['rel.json'])

    def test_the_key_may_come_from_the_signers_environment_variable(self):
        r = self.wm(self.args(key_file=None), env=self.env(BREAKER_MANIFEST_KEY_FILE=self.key))
        self.assert_ok(r)

    def test_an_https_url_is_accepted(self):
        self.assert_ok(self.wm(self.args(apk_url=rel_url(scheme='https'))))

    def test_a_nine_digit_four_part_name_and_an_18_digit_code_are_accepted(self):
        name, code = '999999999.999999999.999999999.999999999', '999999999999999999'
        self.assert_ok(self.wm(self.args(version_name=name, version_code=code,
                                         apk_url=rel_url(name, code))))
        self.assertEqual(json.loads(self.out_bytes())['version_code'], 999999999999999999)

    def test_a_part_with_a_leading_zero_is_allowed(self):
        name = '01.02.03.04'
        self.assert_ok(self.wm(self.args(version_name=name, apk_url=rel_url(name))))

    def test_notes_of_exactly_16384_bytes_are_accepted(self):
        n = self.write('big.txt', 'a' * 16384, 0o644)
        self.assert_ok(self.wm(self.args(notes_file=n)))

    # ---- (2) round trip through the strict decoder ----
    def test_the_file_round_trips_through_the_strict_decoder(self):
        n = self.write('n2.txt', 'line one\n"quoted" back\\slash\ttab\n', 0o644)
        self.assert_ok(self.wm(self.args(notes_file=n)))
        got = decode_like_app_and_route(self.out_bytes())
        self.assertIsNotNone(got)
        self.assertEqual(got['version'], NAME)
        self.assertEqual(got['version_code'], 5)
        self.assertEqual(got['apk_url'], rel_url())
        self.assertEqual(got['sha256'], hashlib.sha256(APK_BYTES).hexdigest())
        self.assertEqual(got['notes'], 'line one\n"quoted" back\\slash\ttab\n')

    def test_the_decoder_copy_refuses_each_bad_document(self):
        good = ('{"version":"1.2.3.4","version_code":5,"apk_url":"http://h/x","sha256":"ab",'
                '"signature":"c","notes":"n"}')
        self.assertIsNotNone(decode_like_app_and_route(good.encode()))
        bad = {
            'duplicate key': good.replace('"notes":"n"', '"notes":"n","version":"9"'),
            '19-digit code': good.replace('"version_code":5', '"version_code":1234567890123456789'),
            'leading-zero code': good.replace('"version_code":5', '"version_code":05'),
            'trailing text': good + 'x',
            'control char': good.replace('"n"', '"a\x01b"'),
            'string code': good.replace('"version_code":5', '"version_code":"5"'),
            'fraction code': good.replace('"version_code":5', '"version_code":5.0'),
            'exponent code': good.replace('"version_code":5', '"version_code":5e0'),
            'negative code': good.replace('"version_code":5', '"version_code":-5'),
            'zero code': good.replace('"version_code":5', '"version_code":0'),
            'blank version': good.replace('"version":"1.2.3.4"', '"version":"  "'),
            'missing member': good.replace(',"notes":"n"', ''),
            'number text member': good.replace('"notes":"n"', '"notes":5'),
            'two objects': good + good,
            'bad escape': good.replace('"n"', '"\\q"'),
            'too deep': good.replace('"notes":"n"', '"notes":"n","x":{"a":{"b":{"c":{"d":1}}}}'),
        }
        for label, doc in bad.items():
            self.assertIsNone(decode_like_app_and_route(doc.encode()), label)
        self.assertIsNone(decode_like_app_and_route(good.encode()[:-1] + b'\xff}'))  # not UTF-8
        # unknown members are ignored, depth 4 is fine, an empty notes text is fine
        ok = good.replace('"notes":"n"', '"notes":"","x":{"a":{"b":{"c":1}}}')
        self.assertIsNotNone(decode_like_app_and_route(ok.encode()))

    # ---- (3) non-ASCII notes ----
    def test_non_ascii_notes_become_escapes_and_decode_back(self):
        text = 'caf\u00e9 \u4e2d emoji \U0001F600 end\n'
        n = self.write('n3.txt', text.encode('utf-8'), 0o644)
        self.assert_ok(self.wm(self.args(notes_file=n)))
        data = self.out_bytes()
        data.decode('ascii')  # pure ASCII or this raises
        self.assertIn(b'\\u00e9', data)
        self.assertIn(b'\\u4e2d', data)
        self.assertIn(b'\\ud83d\\ude00', data)
        self.assertEqual(decode_like_app_and_route(data)['notes'], text)
        self.assertEqual(json.loads(data)['notes'], text)

    # ---- (4) refusals ----
    def test_a_missing_apk_url_option_is_a_usage_error_not_a_refusal(self):
        r = self.wm(self.args(apk_url=None))
        self.assertEqual(r.returncode, 2)

    def test_a_signer_that_fails_leaves_no_file(self):
        script, _ = self.stub_dir('echo junk text >&2\nexit 3\n')
        r = self.wm(script=script)
        self.assert_refused(r, 'the signer failed')
        self.assertNotIn(b'junk', r.stderr)

    def test_a_signer_refusal_sentence_is_forwarded(self):
        script, _ = self.stub_dir('echo "sign-manifest.sh: refused: the key file mode is not 600" >&2\nexit 1\n')
        r = self.wm(script=script)
        self.assert_refused(r, 'the signer refused: the key file mode is not 600')

    def test_a_signer_that_prints_a_short_signature_is_refused(self):
        script, _ = self.stub_dir('echo AAAA\nexit 0\n')
        r = self.wm(script=script)
        self.assert_refused(r, '88 character signature')

    def test_a_key_with_mode_0644_is_refused_by_the_signer(self):
        k = self.make_key('loose.pem', mode=0o644)
        r = self.wm(self.args(key_file=k))
        self.assert_refused(r, 'the signer refused: the key file mode is not 600')

    def test_no_key_option_and_no_environment_key_refuses_through_the_signer(self):
        r = self.wm(self.args(key_file=None))
        self.assert_refused(r, 'the signer failed')

    def test_a_missing_key_file_is_refused_by_the_signer(self):
        r = self.wm(self.args(key_file=self.path('nokey.pem')))
        self.assert_refused(r, 'the signer refused: the key file is missing')

    def test_the_size_cap_is_exact_at_65536_bytes(self):
        # A control character such as U+0001 is ONE byte of UTF-8 notes but a SIX byte
        # \u0001 escape, so the 16384-byte notes cap (3x at most for text beyond U+0800,
        # which cannot reach 65536) is no bar to the 65536-byte manifest cap here.
        self.assert_ok(self.wm(self.args(notes_file=self.write('nb.txt', 'a', 0o644))))
        base0 = len(self.out_bytes()) - 1
        os.rename(self.out, self.path('first.json'))

        def notes_for(total):
            rest = total - base0
            return '\x01' * (rest // 6) + 'a' * (rest % 6)

        n_ok = notes_for(65536)
        self.assertLessEqual(len(n_ok), 16384)
        self.assert_ok(self.wm(self.args(notes_file=self.write('n_ok.txt', n_ok, 0o644))))
        self.assertEqual(len(self.out_bytes()), 65536)
        os.rename(self.out, self.path('second.json'))
        n_big = notes_for(65537)
        r = self.wm(self.args(notes_file=self.write('n_big.txt', n_big, 0o644)))
        self.assert_refused(r, 'larger than 65536 bytes')

    def test_a_16384_byte_notes_file_of_control_characters_is_over_the_cap(self):
        n = self.write('ctl.txt', '\x01' * 16384, 0o644)
        self.assert_refused(self.wm(self.args(notes_file=n)), 'larger than 65536 bytes')

    def test_an_existing_out_file_is_not_overwritten(self):
        with open(self.out, 'wb') as fh:
            fh.write(b'OLD')
        r = self.wm()
        self.assert_refused(r, 'already exists')
        self.assertEqual(self.out_bytes(), b'OLD')

    def test_an_out_symlink_to_a_missing_target_is_refused(self):
        os.symlink(self.path('nowhere'), self.out)
        r = self.wm()
        self.assert_refused(r, 'symlink')
        self.assertFalse(os.path.exists(self.path('nowhere')))

    def test_an_out_symlink_to_an_existing_file_is_refused_and_untouched(self):
        t = self.write('target.json', b'TARGET', 0o644)
        os.symlink(t, self.out)
        r = self.wm()
        self.assert_refused(r, 'symlink')
        with open(t, 'rb') as fh:
            self.assertEqual(fh.read(), b'TARGET')

    def test_an_out_directory_that_does_not_exist_is_refused(self):
        r = self.wm(self.args(out=os.path.join(self.root, 'nodir', 'latest.json')))
        self.assert_refused(r, 'output directory does not exist')

    def test_the_apk_symlink_to_a_real_file_is_refused(self):
        os.symlink(self.apk, self.path('link.apk'))
        self.assert_refused(self.wm(self.args(apk=self.path('link.apk'))), 'apk is a symlink')

    def test_the_file_is_moved_into_place_from_a_temp_in_the_same_directory(self):
        # a spy mv records its argv, then runs the real mv
        real_mv = shutil.which('mv')
        spy = os.path.join(self.root, 'spybin')
        os.mkdir(spy)
        rec = self.path('mv_argv.txt')
        self.write('spybin/mv', '#!/bin/sh\nfor a in "$@"; do printf "%s\\n" "$a" >> ' + rec
                   + '; done\nexec ' + real_mv + ' "$@"\n', 0o755)
        r = self.wm(env=self.env(PATH=spy + os.pathsep + os.environ['PATH']))
        self.assert_ok(r)
        with open(rec) as fh:
            argv = fh.read().split('\n')[:-1]
        self.assertEqual(len(argv), 2, argv)
        self.assertEqual(argv[1], self.out)
        self.assertNotEqual(argv[0], self.out)
        self.assertEqual(os.path.dirname(argv[0]), self.outdir)
        self.assertTrue(os.path.basename(argv[0]).startswith('.write-manifest.'))

    # ---- (5) signer call shape ----
    def stub_dir(self, body):
        d = tempfile.mkdtemp(prefix='writemf-stub-')
        self.addCleanup(shutil.rmtree, d, True)
        shutil.copy(SCRIPT, os.path.join(d, 'write-manifest.sh'))
        stub = ('#!/bin/sh\n'
                'd=$(dirname "$0")\n'
                'for a in "$@"; do printf "%s\\n" "$a" >> "$d/argv.txt"; done\n'
                'printf "call\\n" >> "$d/calls.txt"\n' + body)
        with open(os.path.join(d, 'sign-manifest.sh'), 'w') as fh:
            fh.write(stub)
        return os.path.join(d, 'write-manifest.sh'), d

    STUB_OK = 'printf "%s\\n" "' + 'A' * 86 + '=="\nexit 0\n'

    def test_the_signer_is_called_with_exactly_key_file_version_and_sha(self):
        script, d = self.stub_dir(self.STUB_OK)
        self.assert_ok(self.wm(script=script))
        with open(os.path.join(d, 'argv.txt')) as fh:
            argv = fh.read().split('\n')[:-1]
        sha = hashlib.sha256(APK_BYTES).hexdigest()
        self.assertEqual(argv, ['--key-file', self.key, '--version', NAME, '--sha256', sha])
        with open(os.path.join(d, 'calls.txt')) as fh:
            self.assertEqual(fh.read(), 'call\n')
        self.assertEqual(json.loads(self.out_bytes())['signature'], 'A' * 86 + '==')

    def test_without_a_key_file_option_the_signer_gets_no_key_file_argument(self):
        script, d = self.stub_dir(self.STUB_OK)
        self.assert_ok(self.wm(self.args(key_file=None), script=script))
        with open(os.path.join(d, 'argv.txt')) as fh:
            argv = fh.read().split('\n')[:-1]
        sha = hashlib.sha256(APK_BYTES).hexdigest()
        self.assertEqual(argv, ['--version', NAME, '--sha256', sha])

    def test_the_signer_is_not_called_when_an_input_check_fails(self):
        script, d = self.stub_dir(self.STUB_OK)
        r = self.wm(self.args(apk_url='ftp://h/releases/1.2.3.4-5/breaker.apk'), script=script)
        self.assert_refused(r, 'apk url')
        self.assertFalse(os.path.exists(os.path.join(d, 'calls.txt')))

    def test_the_signed_version_is_the_version_name_not_anything_else(self):
        script, d = self.stub_dir(self.STUB_OK)
        name = '7.8.9.10'
        self.assert_ok(self.wm(self.args(version_name=name, apk_url=rel_url(name)), script=script))
        with open(os.path.join(d, 'argv.txt')) as fh:
            argv = fh.read().split('\n')[:-1]
        self.assertEqual(argv[argv.index('--version') + 1], name)

    # ---- (6) monotonic and rollback table ----
    def test_a_higher_code_than_the_current_manifest_is_accepted(self):
        self.assert_ok(self.wm(self.args(current_manifest=self.current(code=4))))

    def test_an_equal_code_is_refused(self):
        r = self.wm(self.args(current_manifest=self.current(code=5)))
        self.assert_refused(r, 'version code is not higher')

    def test_a_lower_code_is_refused(self):
        r = self.wm(self.args(current_manifest=self.current(code=6)))
        self.assert_refused(r, 'version code is not higher')

    def test_a_lower_name_without_the_flag_is_refused(self):
        r = self.wm(self.args(current_manifest=self.current(version='1.2.3.5', code=4)))
        self.assert_refused(r, 'lower name needs --rollback-offer')

    def test_an_equal_name_with_a_higher_code_is_accepted(self):
        self.assert_ok(self.wm(self.args(current_manifest=self.current(version=NAME, code=4))))

    def test_a_higher_name_with_a_higher_code_is_accepted(self):
        self.assert_ok(self.wm(self.args(current_manifest=self.current(version='1.2.3.3', code=4))))

    def test_names_compare_numerically_per_part_not_as_text(self):
        # 1.10.0.0 is HIGHER than 1.9.9.9 (as text it would be lower)
        n1 = '1.10.0.0'
        self.assert_ok(self.wm(self.args(version_name=n1, apk_url=rel_url(n1),
                                         current_manifest=self.current(version='1.9.9.9', code=4))))
        os.rename(self.out, self.path('moved.json'))
        # 1.9.0.0 is LOWER than 1.10.0.0 (as text it would be higher)
        n2 = '1.9.0.0'
        r = self.wm(self.args(version_name=n2, apk_url=rel_url(n2),
                              current_manifest=self.current(version='1.10.0.0', code=4)))
        self.assert_refused(r, 'lower name needs --rollback-offer')

    def test_a_lower_name_with_the_flag_and_a_higher_code_is_accepted(self):
        r = self.wm(self.args(rollback_offer=True,
                              current_manifest=self.current(version='1.2.3.5', code=4)))
        self.assert_ok(r)

    def test_the_flag_changes_nothing_in_the_file(self):
        self.assert_ok(self.wm(self.args(rollback_offer=True,
                                         current_manifest=self.current(version='1.2.3.5', code=4))))
        with_flag = self.out_bytes()
        os.rename(self.out, self.path('with_flag.json'))
        self.assert_ok(self.wm())  # same inputs, no flag, no current manifest
        self.assertEqual(with_flag, self.out_bytes())
        pairs = json.loads(with_flag, object_pairs_hook=list)
        self.assertEqual(len(pairs), 6)
        self.assertNotIn(b'rollback', with_flag)

    def test_a_lower_name_with_the_flag_and_a_lower_code_is_refused(self):
        r = self.wm(self.args(rollback_offer=True,
                              current_manifest=self.current(version='1.2.3.5', code=9)))
        self.assert_refused(r, 'version code is not higher')

    def test_a_lower_name_with_the_flag_and_an_equal_code_is_refused(self):
        r = self.wm(self.args(rollback_offer=True,
                              current_manifest=self.current(version='1.2.3.5', code=5)))
        self.assert_refused(r, 'version code is not higher')

    def test_the_flag_without_a_current_manifest_is_refused(self):
        r = self.wm(self.args(rollback_offer=True))
        self.assert_refused(r, 'needs --current-manifest')

    def test_the_flag_with_an_equal_name_is_refused(self):
        r = self.wm(self.args(rollback_offer=True,
                              current_manifest=self.current(version=NAME, code=4)))
        self.assert_refused(r, 'needs a lower version name')

    def test_the_flag_with_a_higher_name_is_refused(self):
        r = self.wm(self.args(rollback_offer=True,
                              current_manifest=self.current(version='1.2.3.3', code=4)))
        self.assert_refused(r, 'needs a lower version name')

    def test_the_flag_with_an_incomparable_current_name_is_refused(self):
        r = self.wm(self.args(rollback_offer=True,
                              current_manifest=self.current(version='0.1.0-debug', code=4)))
        self.assert_refused(r, 'not comparable')

    def test_no_flag_with_an_incomparable_current_name_is_accepted(self):
        self.assert_ok(self.wm(self.args(current_manifest=self.current(version='0.1.0-debug', code=4))))

    def test_a_first_release_without_a_current_manifest_is_accepted(self):
        self.assert_ok(self.wm())

    def test_unknown_members_and_depth_four_in_the_current_manifest_are_ignored(self):
        raw = ('{"version":"1.2.3.4","version_code":4,"x":{"a":{"b":{"c":1}}},"y":[1,2,{"z":null}]}')
        self.assert_ok(self.wm(self.args(current_manifest=self.current(raw=raw))))

    def test_the_current_manifest_path_empty_is_refused(self):
        r = self.wm(self.args(current_manifest=''))
        self.assert_refused(r, 'current manifest path is empty')

    def test_a_missing_current_manifest_file_is_refused(self):
        r = self.wm(self.args(current_manifest=self.path('gone.json')))
        self.assert_refused(r, 'current manifest cannot be read')

    # ---- (7) usage errors ----
    def test_no_arguments_is_a_usage_error(self):
        r = self.wm([])
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout, b'')
        self.assertTrue(r.stderr.startswith(b'write-manifest.sh: '))
        self.assertEqual(self.listing(), self.before)

    def test_an_unknown_option_is_a_usage_error(self):
        r = self.wm(self.args() + ['--bogus', 'x'])
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout, b'')

    def test_a_stray_positional_argument_is_a_usage_error(self):
        r = self.wm(self.args() + ['stray'])
        self.assertEqual(r.returncode, 2)

    def test_an_option_without_its_value_is_a_usage_error(self):
        r = self.wm(self.args(out=None) + ['--out'])
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout, b'')

    # ---- (8) the script itself ----
    def header(self):
        with open(SCRIPT) as fh:
            lines = []
            for line in fh:
                if not line.startswith('#'):
                    break
                lines.append(line)
        return ''.join(lines)

    def test_the_header_says_the_flag_writes_no_marker_member(self):
        h = ' '.join(self.header().replace('#', ' ').split())
        self.assertIn('writes no marker member', h)
        self.assertIn('covers ONLY the version name and the sha256', h)
        self.assertIn('first release', h)
        self.assertIn('bump commit', h)

    def test_the_script_is_executable_syntax_clean_and_ascii(self):
        self.assertTrue(os.access(SCRIPT, os.X_OK))
        self.assertEqual(os.stat(SCRIPT).st_mode & 0o777, 0o755)
        self.assertEqual(run(['sh', '-n', SCRIPT]).returncode, 0)
        with open(SCRIPT, 'rb') as fh:
            fh.read().decode('ascii')
        self.assertTrue(os.path.exists(SIGNER))


# ---- generated refusal tests: each case is its own test method ----------------
def _add(name, fn):
    fn.__name__ = 'test_' + name
    setattr(WriteManifestTest, 'test_' + name, fn)


def _name_case(label, name, part='version name'):
    def t(self):
        self.assert_refused(self.wm(self.args(version_name=name, apk_url=rel_url(name))), part)
    _add('a_version_name_of_%s_is_refused' % label, t)


for _label, _name in [
    ('three_parts', '1.2.3'), ('five_parts', '1.2.3.4.5'), ('a_letter', '1.2.3.x'),
    ('a_rc_suffix', '1.2.3.4-rc'), ('an_empty_name', ''), ('an_empty_part', '1..3.4'),
    ('a_ten_digit_last_part', '1.2.3.1234567890'), ('a_ten_digit_first_part', '1234567890.2.3.4'),
    ('a_ten_digit_middle_part', '1.1234567890.3.4'), ('a_leading_space', ' 1.2.3.4'),
    ('a_trailing_newline', '1.2.3.4\n'), ('a_debug_name', '0.1.0-debug'),
    ('a_unicode_digit', '1.2.3.\u0664'), ('a_trailing_dot', '1.2.3.4.'),
]:
    _name_case(_label, _name)


def _code_case(label, code):
    def t(self):
        self.assert_refused(self.wm(self.args(version_code=code, apk_url=rel_url(NAME, code))),
                            'version code')
    _add('a_version_code_of_%s_is_refused' % label, t)


for _label, _code in [
    ('zero', '0'), ('leading_zeros', '007'), ('19_digits', '1' * 19), ('minus_one', '-1'),
    ('a_fraction', '1.5'), ('an_exponent', '1e3'), ('empty', ''), ('a_leading_space', ' 5'),
    ('a_plus_sign', '+5'), ('hex', '0x5'), ('a_trailing_newline', '5\n'),
]:
    _code_case(_label, _code)


def _apk_case(label, make, part):
    def t(self):
        p = make(self)
        self.assert_refused(self.wm(self.args(apk=p)), part)
    _add('an_apk_that_is_%s_is_refused' % label, t)


def _mk_dir(self):
    p = self.path('adir')
    os.mkdir(p)
    return p


_apk_case('missing', lambda s: s.path('none.apk'), 'apk is missing')
_apk_case('a_directory', _mk_dir, 'apk is missing')
_apk_case('empty', lambda s: s.write('empty.apk', b'', 0o644), 'apk is empty')


def _url_case(label, url, part='apk url'):
    def t(self):
        self.assert_refused(self.wm(self.args(apk_url=url)), part)
    _add('an_apk_url_with_%s_is_refused' % label, t)


_OKTAIL = '/%s/releases/%s-%s/breaker.apk' % (MARK, NAME, CODE)
for _label, _url in [
    ('the_ftp_scheme', 'ftp://h.example.test' + _OKTAIL),
    ('no_scheme', 'h.example.test' + _OKTAIL),
    ('a_relative_path', _OKTAIL),
    ('a_space', 'http://h.example.test/a b' + _OKTAIL),
    ('a_tab', 'http://h.example.test/a\tb' + _OKTAIL),
    ('a_newline', 'http://h.example.test/a\nb' + _OKTAIL),
    ('a_control_character', 'http://h.example.test/a\x01b' + _OKTAIL),
    ('user_info', 'http://user@h.example.test' + _OKTAIL),
    ('an_at_sign_in_the_path', 'http://h.example.test/a@b' + _OKTAIL),
    ('a_double_quote', 'http://h.example.test/a"b' + _OKTAIL),
    ('a_backslash', 'http://h.example.test/a\\b' + _OKTAIL),
    ('a_non_ascii_character', 'http://h.example.test/caf\u00e9' + _OKTAIL),
    ('another_name_in_the_suffix', 'http://h.example.test/%s/releases/1.2.3.9-5/breaker.apk' % MARK),
    ('another_code_in_the_suffix', 'http://h.example.test/%s/releases/1.2.3.4-6/breaker.apk' % MARK),
    ('another_file_name', 'http://h.example.test/%s/releases/1.2.3.4-5/other.apk' % MARK),
    ('a_trailing_slash', 'http://h.example.test' + _OKTAIL + '/'),
    ('a_query_after_the_suffix', 'http://h.example.test' + _OKTAIL + '?x=1'),
    ('an_empty_value', ''),
    ('a_scheme_only', 'http://'),
]:
    _url_case(_label, _url)


def _notes_case(label, data, part):
    def t(self):
        n = self.write('bad_notes.txt', data, 0o644)
        self.assert_refused(self.wm(self.args(notes_file=n)), part)
    _add('notes_that_are_%s_are_refused' % label, t)


_notes_case('empty', b'', 'notes are blank')
_notes_case('only_white_space', b'  \n\t \r\n ', 'notes are blank')
_notes_case('only_unicode_white_space', '\u2003\u3000 \n'.encode('utf-8'), 'notes are blank')
_notes_case('invalid_utf8', MARK.encode() + b'\xff\xfe', 'not valid UTF-8')
_notes_case('an_encoded_surrogate', b'a\xed\xa0\x80b', 'not valid UTF-8')
_notes_case('an_overlong_encoding', b'a\xc0\xafb', 'not valid UTF-8')
_notes_case('nul_holding', b'a\x00b ' + MARK.encode(), 'NUL byte')
_notes_case('16385_bytes', b'a' * 16385, 'too large')


def _notes_path_case(label, make):
    def t(self):
        self.assert_refused(self.wm(self.args(notes_file=make(self))), 'notes file cannot be read')
    _add('a_notes_file_that_is_%s_is_refused' % label, t)


_notes_path_case('missing', lambda s: s.path('nonotes.txt'))
_notes_path_case('a_directory', _mk_dir)


# ---- generated usage tests: each required option missing ---------------------
def _usage_case(opt):
    def t(self):
        r = self.wm(self.args(**{opt: None}))
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertEqual(r.stdout, b'')
        self.assertTrue(r.stderr.startswith(b'write-manifest.sh: '))
        self.assertNotIn(b'refused', r.stderr)
        self.assertEqual(self.listing(), self.before)
    _add('a_missing_%s_option_is_a_usage_error' % opt, t)


for _opt in ('apk', 'version_name', 'version_code', 'apk_url', 'notes_file', 'out'):
    _usage_case(_opt)


# ---- generated bad-current-manifest tests --------------------------------------
def _cur_case(label, raw, part='current manifest'):
    def t(self):
        r = self.wm(self.args(current_manifest=self.current(raw=raw)))
        self.assert_refused(r, part)
    _add('a_current_manifest_that_is_%s_is_refused' % label, t)


_GOODCUR = '{"version":"1.2.3.4","version_code":4}'
_cur_case('garbage', b'{nope')
_cur_case('empty', b'')
_cur_case('a_json_array', b'[1,2]')
_cur_case('a_duplicate_key', b'{"version":"1.2.3.4","version_code":4,"version":"1.2.3.3"}', 'duplicate key')
_cur_case('a_nested_duplicate_key', b'{"version":"1.2.3.4","version_code":4,"x":{"a":1,"a":2}}', 'duplicate key')
_cur_case('a_19_digit_code', b'{"version":"1.2.3.4","version_code":1234567890123456789}')
_cur_case('a_leading_zero_code', b'{"version":"1.2.3.4","version_code":04}')
_cur_case('a_string_code', b'{"version":"1.2.3.4","version_code":"4"}')
_cur_case('a_fraction_code', b'{"version":"1.2.3.4","version_code":4.0}')
_cur_case('an_exponent_code', b'{"version":"1.2.3.4","version_code":4e0}')
_cur_case('a_zero_code', b'{"version":"1.2.3.4","version_code":0}')
_cur_case('a_negative_code', b'{"version":"1.2.3.4","version_code":-4}')
_cur_case('missing_the_code', b'{"version":"1.2.3.4"}')
_cur_case('a_number_version', b'{"version":1,"version_code":4}')
_cur_case('missing_the_version', b'{"version_code":4}')
_cur_case('followed_by_text', (_GOODCUR + ' x').encode())
_cur_case('two_objects', (_GOODCUR + _GOODCUR).encode())
_cur_case('not_utf8', b'{"version":"1.2.3.4\xff","version_code":4}')
_cur_case('holding_a_raw_control_character', b'{"version":"1.2.3\x014","version_code":4}')
_cur_case('nested_five_deep', b'{"version":"1.2.3.4","version_code":4,"x":{"a":{"b":{"c":{"d":1}}}}}')
_cur_case('holding_nan', b'{"version":"1.2.3.4","version_code":4,"x":NaN}')


if __name__ == '__main__':
    unittest.main()
