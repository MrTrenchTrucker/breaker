"""Integration test: the five release scripts run together.

build-apk.sh -> sign-apk.sh -> write-manifest.sh (with sign-manifest.sh) ->
serve-release.sh, real scripts, real openssl, python3, sha256sum and git, in
temp dirs, with THROWAWAY Ed25519 keys made at run time (openssl genpkey).
The ONLY stub is a fake `docker` on PATH for build-apk.sh and sign-apk.sh (it
makes the unsigned APK, and the signed APK, without a container). One extra
stub, a `cp` wrapper, is used for one fault-injection test only.

It proves the frozen seams between the steps: each step's stdout is the next
step's accepted input, as is; the file names chain; the served layout, the
served manifest and the signature agree with each other and with the app's
strict decoder (a test-local copy of the decoder in test_write_manifest.py,
which cites ManifestJson.kt and UpdatesRoute.kt line numbers).

The scripts directory is the real one unless the environment variable
RELEASE_PIPELINE_SCRIPTS names another directory that holds the same five
scripts (used to measure mutants on a copy, never on the production files).

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_release_pipeline.py'
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
from urllib.parse import urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.environ.get('RELEASE_PIPELINE_SCRIPTS') or os.path.normpath(os.path.join(
    HERE, '..', '..', '..', '..', 'server', 'modules', 'deploy', 'scripts'))
BUILD = os.path.join(SCRIPTS, 'build-apk.sh')
SIGN = os.path.join(SCRIPTS, 'sign-apk.sh')
WRITE = os.path.join(SCRIPTS, 'write-manifest.sh')
SERVE = os.path.join(SCRIPTS, 'serve-release.sh')
SIGNER = os.path.join(SCRIPTS, 'sign-manifest.sh')

# The seams, in ONE place: the file names and layout the pipeline relies on,
# and the text each script's header comment must still mention (the header is
# the script's published contract).
SEAMS = {
    'unsigned_name': 'breaker-%s-unsigned.apk',      # build-apk.sh output, sign-apk.sh input
    'signed_name': 'breaker-%s.apk',                 # sign-apk.sh output, write-manifest/serve input
    'releases_dir': 'releases',
    'release_dir': '%s-%s',                          # <name>-<code>
    'served_apk': 'breaker.apk',
    'served_manifest': 'latest.json',
    'served_sha': 'breaker.apk.sha256',
    'current_link': 'current',
    'sha_text': '%s  breaker.apk\n',
    'signed_message': 'breaker-update-v1\n%s\n%s\n',
    'members': ['version', 'version_code', 'apk_url', 'sha256', 'signature', 'notes'],
    'header_mentions': {
        'build-apk.sh': ['breaker-<versionName>-unsigned.apk', 'app-vA.B.C.D'],
        'sign-apk.sh': ['breaker-A.B.C.D-unsigned.apk', 'DIR/breaker-A.B.C.D.apk'],
        'sign-manifest.sh': ['breaker-update-v1'],
        'write-manifest.sh': ['/releases/<A.B.C.D>-<code>/breaker.apk', 'breaker-update-v1',
                              '--current-manifest', '--rollback-offer',
                              'version, version_code (a number), apk_url, sha256, signature, notes'],
        'serve-release.sh': ['releases/<name>-<code>/breaker.apk', 'releases/', 'current',
                             'latest.json', 'breaker.apk.sha256', 'RELATIVE', 'breaker-A.B.C.D.apk'],
    },
}

STUB_DOCKER = r'''#!/bin/sh
mode=""
for a in "$@"; do
    case "$a" in
        ./gradlew) mode=build ;;
        sign) mode=sign ;;
        verify) mode=verify ;;
    esac
done
iodir=""
prev=""
for a in "$@"; do
    if [ "$prev" = "-v" ]; then
        case "$a" in
            *:/work) iodir="${a%:/work}" ;;
            *:/work:ro) iodir="${a%:/work:ro}" ;;
        esac
    fi
    prev="$a"
done
case "$mode" in
    build)
        d="$iodir/android/app/build/outputs/apk/release"
        mkdir -p "$d"
        printf 'PK\003\004%s' "$STUB_APK_TEXT" > "$d/app-release-unsigned.apk"
        exit 0 ;;
    sign)
        cp "$iodir/in.apk" "$iodir/out.apk"
        printf SIGNED >> "$iodir/out.apk"
        exit 0 ;;
    verify)
        [ -f "$iodir/out.apk" ] || exit 5
        exit 0 ;;
esac
exit 9
'''

NOTES_TEXT = 'Pipeline test notes.\n'
BASE_URL = 'http://updates.example.test:8080'


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, timeout=120, **kw)


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




def snapshot(top):
    """Everything under top: path -> (kind, mode, sha256 or link target). Equal snapshots = unchanged."""
    res = {}
    for dirpath, dirs, files in os.walk(top):
        for n in dirs + files:
            p = os.path.join(dirpath, n)
            rel = os.path.relpath(p, top)
            if os.path.islink(p):
                res[rel] = ('link', None, os.readlink(p))
            elif os.path.isdir(p):
                res[rel] = ('dir', os.stat(p).st_mode & 0o777, None)
            else:
                with open(p, 'rb') as fh:
                    res[rel] = ('file', os.stat(p).st_mode & 0o777, hashlib.sha256(fh.read()).hexdigest())
    return res


class PipelineBase(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix='pipeline-test-')
        self.addCleanup(shutil.rmtree, self.root, True)
        self.bin = self.mkdir('bin')
        self.tmp = self.mkdir('tmp')
        self.site = self.mkdir('site')          # the release volume directory (ROOT)
        stub = os.path.join(self.bin, 'docker')
        with open(stub, 'w') as fh:
            fh.write(STUB_DOCKER)
        os.chmod(stub, 0o755)
        self.env = {
            'PATH': self.bin + os.pathsep + os.environ['PATH'],
            'LC_ALL': 'C',
            'TMPDIR': self.tmp,
            'GIT_CONFIG_GLOBAL': os.path.join(self.root, 'no-such-global.cfg'),
            'GIT_CONFIG_SYSTEM': os.path.join(self.root, 'no-such-system.cfg'),
            'GIT_AUTHOR_NAME': 'Test', 'GIT_AUTHOR_EMAIL': 'test@example.invalid',
            'GIT_COMMITTER_NAME': 'Test', 'GIT_COMMITTER_EMAIL': 'test@example.invalid',
            'GIT_TERMINAL_PROMPT': '0',
        }
        # throwaway keys, made now, never stored anywhere but this temp dir
        self.key = self.path('manifest-key.pem')
        self.assertEqual(run(['openssl', 'genpkey', '-algorithm', 'ED25519', '-out', self.key]).returncode, 0)
        os.chmod(self.key, 0o600)
        self.pub = self.path('manifest-pub.pem')
        self.assertEqual(run(['openssl', 'pkey', '-in', self.key, '-pubout', '-out', self.pub]).returncode, 0)
        self.ks = self.write('store.p12', b'stub keystore ' + os.urandom(8).hex().encode(), 0o600)
        self.passf = self.write('pass.txt', b'pw-' + os.urandom(8).hex().encode(), 0o600)
        self.notes = self.write('notes.txt', NOTES_TEXT, 0o644)
        self.update_keys = self.write('update-keys.txt', b'throwaway-key-list-' + os.urandom(8).hex().encode() + b'\n', 0o644)

    # ---- helpers ----
    def path(self, *parts):
        return os.path.join(self.root, *parts)

    def mkdir(self, name):
        p = self.path(name)
        os.mkdir(p)
        return p

    def write(self, name, data, mode):
        p = self.path(name)
        with open(p, 'wb') as fh:
            fh.write(data if isinstance(data, bytes) else data.encode())
        os.chmod(p, mode)
        return p

    def git(self, args, cwd):
        r = run(['git'] + args, env=self.env, cwd=cwd)
        self.assertEqual(r.returncode, 0, r.stderr)

    def make_src(self, name, code):
        src = self.path('src-%s-%s' % (name, code))
        os.makedirs(os.path.join(src, 'android', 'app'))
        self.git(['init', '-q', '--initial-branch=main', src], None)
        with open(os.path.join(src, '.gitignore'), 'w') as fh:
            fh.write('build/\n')
        with open(os.path.join(src, 'android', 'app', 'build.gradle.kts'), 'w') as fh:
            fh.write('android {\n    defaultConfig {\n        versionCode = %s\n'
                     '        versionName = "%s"\n    }\n}\n' % (code, name))
        self.git(['add', '-A'], src)
        self.git(['commit', '-qm', 'release'], src)
        self.git(['tag', 'app-v' + name], src)
        return src

    def ok(self, r, what):
        self.assertEqual(r.returncode, 0, '%s: %r' % (what, r.stderr))
        self.assertEqual(r.stderr, b'', what)
        lines = r.stdout.decode().split('\n')
        self.assertEqual(len(lines), 2, r.stdout)       # one line and its newline
        self.assertEqual(lines[1], '')
        return lines[0]

    def build_and_sign(self, name, code):
        """build-apk.sh then sign-apk.sh; each stdout path is passed on AS IS."""
        src = self.make_src(name, code)
        env = dict(self.env, STUB_APK_TEXT='built-%s-%s' % (name, code))
        bout = self.path('built-%s-%s' % (name, code))
        r = run(['sh', BUILD, '--src', src, '--out-dir', bout, '--update-keys', self.update_keys],
                env=env)
        unsigned = self.ok(r, 'build')
        sdir = self.path('signed-%s-%s' % (name, code))
        r = run(['sh', SIGN, '--in', unsigned, '--keystore', self.ks, '--alias', 'release',
                 '--pass-file', self.passf, '--out-dir', sdir, '--docker-bin', 'docker'], env=env)
        signed = self.ok(r, 'sign')
        return unsigned, signed

    def apk_url(self, name, code):
        return '%s/%s/%s/%s' % (BASE_URL, SEAMS['releases_dir'], SEAMS['release_dir'] % (name, code),
                                SEAMS['served_apk'])

    def manifest(self, signed, name, code, current=None, rollback=False, tag=None):
        out = self.path('manifest-%s-%s%s.json' % (name, code, tag or ''))
        argv = ['sh', WRITE, '--apk', signed, '--version-name', name, '--version-code', str(code),
                '--apk-url', self.apk_url(name, code), '--notes-file', self.notes, '--out', out,
                '--key-file', self.key]
        if current:
            argv += ['--current-manifest', current]
        if rollback:
            argv.append('--rollback-offer')
        r = run(argv, env=self.env)
        return out, r

    def serve(self, signed, manifest, env=None):
        return run(['sh', SERVE, '--apk', signed, '--manifest', manifest, '--root', self.site],
                   env=env or self.env)

    def full_release(self, name, code, current=None, rollback=False):
        unsigned, signed = self.build_and_sign(name, code)
        man, r = self.manifest(signed, name, code, current, rollback)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, (man + '\n').encode())
        s = self.serve(signed, man)
        self.assertEqual(s.returncode, 0, s.stderr)
        rel = SEAMS['release_dir'] % (name, code)
        self.assertEqual(s.stdout, ('%s/%s/%s\n' % (self.site, SEAMS['releases_dir'], rel)).encode())
        return signed, man

    def read(self, *parts):
        with open(os.path.join(*parts), 'rb') as fh:
            return fh.read()

    def current_manifest(self):
        return os.path.join(self.site, SEAMS['current_link'], SEAMS['served_manifest'])


class PipelineTest(PipelineBase):
    # ---- (1) the chain, each stdout path used as is ----
    def test_each_steps_stdout_path_is_the_next_steps_accepted_input(self):
        unsigned, signed = self.build_and_sign('0.0.0.2', 3)
        self.assertEqual(os.path.basename(unsigned), SEAMS['unsigned_name'] % '0.0.0.2')
        self.assertEqual(os.path.basename(unsigned), 'breaker-0.0.0.2-unsigned.apk')
        self.assertEqual(os.path.basename(signed), SEAMS['signed_name'] % '0.0.0.2')
        self.assertEqual(os.path.basename(signed), 'breaker-0.0.0.2.apk')
        self.assertTrue(os.path.isfile(unsigned))
        self.assertTrue(os.path.isfile(signed))
        self.assertEqual(self.read(unsigned), b'PK\x03\x04built-0.0.0.2-3')
        self.assertEqual(self.read(signed), self.read(unsigned) + b'SIGNED')
        man, r = self.manifest(signed, '0.0.0.2', 3)
        self.assertEqual(r.returncode, 0, r.stderr)
        s = self.serve(signed, man)
        self.assertEqual(s.returncode, 0, s.stderr)

    def test_the_unsigned_name_is_not_accepted_by_the_later_steps(self):
        unsigned, signed = self.build_and_sign('0.0.0.2', 3)
        man, r = self.manifest(signed, '0.0.0.2', 3)
        self.assertEqual(r.returncode, 0, r.stderr)
        before = snapshot(self.site)
        s = self.serve(unsigned, man)           # the build output is not a release APK
        self.assertEqual(s.returncode, 1)
        self.assertEqual(snapshot(self.site), before)
        again = run(['sh', SIGN, '--in', signed, '--keystore', self.ks, '--alias', 'release',
                     '--pass-file', self.passf, '--out-dir', self.path('again'),
                     '--docker-bin', 'docker'], env=self.env)
        self.assertEqual(again.returncode, 1)   # the signed name is not a sign-apk input

    # ---- (2) layout and agreement ----
    def test_the_served_layout_the_link_and_the_files_are_as_frozen(self):
        signed, man = self.full_release('0.0.0.2', 3)
        rel = SEAMS['release_dir'] % ('0.0.0.2', 3)
        self.assertEqual(sorted(os.listdir(self.site)), [SEAMS['current_link'], SEAMS['releases_dir']])
        reldir = os.path.join(self.site, SEAMS['releases_dir'], rel)
        self.assertEqual(sorted(os.listdir(os.path.join(self.site, SEAMS['releases_dir']))), [rel])
        self.assertEqual(sorted(os.listdir(reldir)),
                         sorted([SEAMS['served_apk'], SEAMS['served_manifest'], SEAMS['served_sha']]))
        link = os.path.join(self.site, SEAMS['current_link'])
        self.assertTrue(os.path.islink(link))
        self.assertEqual(os.readlink(link), SEAMS['releases_dir'] + '/' + rel)
        self.assertFalse(os.path.isabs(os.readlink(link)))
        self.assertEqual(os.stat(reldir).st_mode & 0o777, 0o755)
        for n in os.listdir(reldir):
            self.assertEqual(os.stat(os.path.join(reldir, n)).st_mode & 0o777, 0o644, n)
        self.assertEqual(self.read(reldir, SEAMS['served_apk']), self.read(signed))
        self.assertEqual(self.read(reldir, SEAMS['served_manifest']), self.read(man))

    def test_the_apk_url_path_maps_to_a_served_file_whose_hash_is_in_the_manifest_and_the_sha_file(self):
        signed, man = self.full_release('0.0.0.2', 3)
        doc = json.loads(self.read(self.current_manifest()))
        path = urlparse(doc['apk_url']).path
        self.assertEqual(path, '/releases/0.0.0.2-3/breaker.apk')
        # the nginx alias idea: /releases/<dir>/breaker.apk == ROOT/releases/<dir>/breaker.apk
        served = os.path.join(self.site, path.lstrip('/'))
        self.assertTrue(os.path.isfile(served))
        want = hashlib.sha256(self.read(served)).hexdigest()
        self.assertEqual(doc['sha256'], want)
        self.assertEqual(want, hashlib.sha256(self.read(signed)).hexdigest())
        sha_file = os.path.join(self.site, SEAMS['current_link'], SEAMS['served_sha'])
        self.assertEqual(self.read(sha_file).decode(), SEAMS['sha_text'] % want)
        self.assertEqual(self.read(self.site, SEAMS['current_link'], SEAMS['served_apk']), self.read(served))

    def test_the_signature_verifies_over_exactly_the_signed_message(self):
        self.full_release('0.0.0.2', 3)
        doc = json.loads(self.read(self.current_manifest()))
        sig = base64.b64decode(doc['signature'], validate=True)
        self.assertEqual(len(sig), 64)
        sigf = self.write('sig.bin', sig, 0o600)

        def verifies(msg):
            m = self.write('msg.bin', msg, 0o600)
            return run(['openssl', 'pkeyutl', '-verify', '-rawin', '-pubin', '-inkey', self.pub,
                        '-in', m, '-sigfile', sigf]).returncode == 0
        msg = (SEAMS['signed_message'] % (doc['version'], doc['sha256'])).encode()
        self.assertEqual(msg, b'breaker-update-v1\n0.0.0.2\n' + doc['sha256'].encode() + b'\n')
        self.assertTrue(verifies(msg))
        # the control: other messages do not verify, so the check can fail
        self.assertFalse(verifies(('breaker-update-v1\n%s\n' % doc['version']).encode()))
        self.assertFalse(verifies(msg + b'x'))
        self.assertFalse(verifies(('breaker-update-v1\n0.0.0.9\n%s\n' % doc['sha256']).encode()))

    def test_the_manifest_has_six_members_in_order_and_a_numeric_code(self):
        self.full_release('0.0.0.2', 3)
        data = self.read(self.current_manifest())
        self.assertEqual(data.count(b'\n'), 1)
        pairs = json.loads(data, object_pairs_hook=list)
        self.assertEqual([k for k, _ in pairs], SEAMS['members'])
        self.assertIn(b'"version_code":3,', data)

    # ---- (3) the app's strict decoder reads the served file ----
    def test_the_strict_decoder_reads_the_file_served_through_current(self):
        signed, man = self.full_release('0.0.0.2', 3)
        data = self.read(self.current_manifest())
        got = decode_like_app_and_route(data)
        self.assertIsNotNone(got)
        self.assertEqual(sorted(got), sorted(SEAMS['members']))
        self.assertEqual(got['version'], '0.0.0.2')
        self.assertEqual(got['version_code'], 3)
        self.assertEqual(got['apk_url'], self.apk_url('0.0.0.2', 3))
        self.assertEqual(got['sha256'], hashlib.sha256(self.read(signed)).hexdigest())
        self.assertEqual(len(base64.b64decode(got['signature'], validate=True)), 64)
        self.assertEqual(got['notes'], NOTES_TEXT)
        # the control: the decoder is not vacuous
        self.assertIsNone(decode_like_app_and_route(b'{}'))
        self.assertIsNone(decode_like_app_and_route(data[:-5]))
        self.assertIsNone(decode_like_app_and_route(data.replace(b'"version_code":3', b'"version_code":"3"')))

    # ---- (4) second release, rollback re-offer, refusals ----
    def test_a_second_release_flips_current_and_leaves_the_first_byte_identical(self):
        self.full_release('0.0.0.2', 3)
        first_dir = os.path.join(self.site, 'releases', '0.0.0.2-3')
        first = snapshot(first_dir)
        cur1 = self.write('cur1.json', self.read(self.current_manifest()), 0o644)
        self.full_release('0.0.0.3', 4, current=cur1)
        self.assertEqual(os.readlink(os.path.join(self.site, 'current')), 'releases/0.0.0.3-4')
        self.assertEqual(sorted(os.listdir(os.path.join(self.site, 'releases'))), ['0.0.0.2-3', '0.0.0.3-4'])
        self.assertEqual(snapshot(first_dir), first)
        self.assertEqual(json.loads(self.read(self.current_manifest()))['version'], '0.0.0.3')
        self.assertEqual(decode_like_app_and_route(self.read(self.current_manifest()))['version_code'], 4)

    def _two_releases(self):
        self.full_release('0.0.0.2', 3)
        cur1 = self.write('cur1.json', self.read(self.current_manifest()), 0o644)
        self.full_release('0.0.0.3', 4, current=cur1)
        return self.write('cur2.json', self.read(self.current_manifest()), 0o644)

    def test_a_rollback_re_offer_with_a_higher_code_and_lower_name_is_served(self):
        cur2 = self._two_releases()
        old = snapshot(os.path.join(self.site, 'releases', '0.0.0.2-3'))
        newer = snapshot(os.path.join(self.site, 'releases', '0.0.0.3-4'))
        self.full_release('0.0.0.2', 5, current=cur2, rollback=True)
        self.assertEqual(os.readlink(os.path.join(self.site, 'current')), 'releases/0.0.0.2-5')
        self.assertEqual(sorted(os.listdir(os.path.join(self.site, 'releases'))),
                         ['0.0.0.2-3', '0.0.0.2-5', '0.0.0.3-4'])
        self.assertEqual(snapshot(os.path.join(self.site, 'releases', '0.0.0.2-3')), old)
        self.assertEqual(snapshot(os.path.join(self.site, 'releases', '0.0.0.3-4')), newer)
        got = decode_like_app_and_route(self.read(self.current_manifest()))
        self.assertEqual((got['version'], got['version_code']), ('0.0.0.2', 5))
        self.assertEqual(got['apk_url'], self.apk_url('0.0.0.2', 5))

    def test_the_same_inputs_without_the_rollback_flag_are_refused_and_root_is_unchanged(self):
        cur2 = self._two_releases()
        _unsigned, signed = self.build_and_sign('0.0.0.2', 5)
        before = snapshot(self.site)
        outs_before = sorted(os.listdir(self.root))
        man, r = self.manifest(signed, '0.0.0.2', 5, current=cur2, rollback=False)
        self.assertEqual(r.returncode, 1)
        self.assertEqual(r.stdout, b'')
        self.assertTrue(r.stderr.startswith(b'write-manifest.sh: refused: '), r.stderr)
        self.assertIn(b'--rollback-offer', r.stderr)
        self.assertFalse(os.path.exists(man))
        self.assertEqual(snapshot(self.site), before)
        self.assertEqual(sorted(os.listdir(self.root)), outs_before)
        # control: the same call with the flag succeeds, so the refusal is the rule and nothing else
        man2, r2 = self.manifest(signed, '0.0.0.2', 5, current=cur2, rollback=True, tag='-flag')
        self.assertEqual(r2.returncode, 0, r2.stderr)

    def test_a_non_increasing_code_is_refused_by_write_manifest_and_root_is_unchanged(self):
        cur2 = self._two_releases()                       # current is 0.0.0.3 code 4
        before = snapshot(self.site)
        for name, code in (('0.0.0.4', 4), ('0.0.0.4', 3)):
            _unsigned, signed = self.build_and_sign(name, code)
            man, r = self.manifest(signed, name, code, current=cur2)
            self.assertEqual(r.returncode, 1, (name, code))
            self.assertIn(b'not higher than the current', r.stderr)
            self.assertFalse(os.path.exists(man))
        self.assertEqual(snapshot(self.site), before)
        # control: one more than the current code passes
        _unsigned, signed = self.build_and_sign('0.0.0.4', 5)
        man, r = self.manifest(signed, '0.0.0.4', 5, current=cur2)
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_a_rollback_offer_with_a_name_that_is_not_lower_is_refused(self):
        cur2 = self._two_releases()
        _unsigned, signed = self.build_and_sign('0.0.0.3', 6)
        man, r = self.manifest(signed, '0.0.0.3', 6, current=cur2, rollback=True)
        self.assertEqual(r.returncode, 1)
        self.assertIn(b'lower version name', r.stderr)

    def test_serve_release_refuses_a_manifest_whose_sha_differs_from_the_apk(self):
        self.full_release('0.0.0.2', 3)
        cur1 = self.write('cur1.json', self.read(self.current_manifest()), 0o644)
        unsigned, signed = self.build_and_sign('0.0.0.3', 4)
        man, r = self.manifest(signed, '0.0.0.3', 4, current=cur1)
        self.assertEqual(r.returncode, 0, r.stderr)
        data = bytearray(self.read(signed))
        data[-1] ^= 0x01                                    # one byte flipped
        tampered_dir = self.mkdir('tampered')
        tampered = os.path.join(tampered_dir, os.path.basename(signed))
        with open(tampered, 'wb') as fh:
            fh.write(bytes(data))
        before = snapshot(self.site)
        s = self.serve(tampered, man)
        self.assertEqual(s.returncode, 1)
        self.assertEqual(s.stdout, b'')
        self.assertIn(b'sha256 does not match the APK bytes', s.stderr)
        self.assertEqual(snapshot(self.site), before)
        self.assertEqual(os.readlink(os.path.join(self.site, 'current')), 'releases/0.0.0.2-3')
        # control: the untampered APK is served with the same manifest
        s2 = self.serve(signed, man)
        self.assertEqual(s2.returncode, 0, s2.stderr)

    def test_a_failed_copy_leaves_current_and_the_first_release_as_they_were(self):
        self.full_release('0.0.0.2', 3)
        cur1 = self.write('cur1.json', self.read(self.current_manifest()), 0o644)
        _unsigned, signed = self.build_and_sign('0.0.0.3', 4)
        man, r = self.manifest(signed, '0.0.0.3', 4, current=cur1)
        self.assertEqual(r.returncode, 0, r.stderr)
        # fault injection: a cp wrapper on PATH that fails only when it writes breaker.apk
        real_cp = shutil.which('cp')
        fbin = self.mkdir('faultbin')
        with open(os.path.join(fbin, 'cp'), 'w') as fh:
            fh.write('#!/bin/sh\ncase "$2" in\n    */breaker.apk) exit 1 ;;\nesac\nexec %s "$@"\n' % real_cp)
        os.chmod(os.path.join(fbin, 'cp'), 0o755)
        before = snapshot(self.site)
        s = self.serve(signed, man, env=dict(self.env, PATH=fbin + os.pathsep + self.env['PATH']))
        self.assertEqual(s.returncode, 1)
        self.assertIn(b'the APK could not be copied', s.stderr)
        self.assertEqual(snapshot(self.site), before)
        self.assertEqual(os.readlink(os.path.join(self.site, 'current')), 'releases/0.0.0.2-3')
        self.assertTrue(os.path.isfile(os.path.join(self.site, 'current', 'breaker.apk')))
        # control: without the fault the same inputs are served
        s2 = self.serve(signed, man)
        self.assertEqual(s2.returncode, 0, s2.stderr)

    def test_a_release_that_is_already_served_is_refused_and_root_is_unchanged(self):
        signed, man = self.full_release('0.0.0.2', 3)
        before = snapshot(self.site)
        s = self.serve(signed, man)
        self.assertEqual(s.returncode, 1)
        self.assertEqual(snapshot(self.site), before)

    # ---- (5) seam freeze pins ----
    def header(self, script):
        with open(os.path.join(SCRIPTS, script)) as fh:
            lines = fh.read().split('\n')
        out = []
        for line in lines[1:]:
            if not line.startswith('#'):
                break
            out.append(line)
        return '\n'.join(out)

    def test_each_scripts_header_still_names_the_seam_it_relies_on(self):
        for script, needles in SEAMS['header_mentions'].items():
            head = self.header(script)
            self.assertGreater(len(head), 200, script)
            for needle in needles:
                self.assertIn(needle, self.flat(head), '%s header lacks %r' % (script, needle))

    @staticmethod
    def flat(text):
        # header lines wrap: join "# " continuation prefixes so a phrase split over lines is found
        return re.sub(r'\n#\s*', ' ', text)

    def test_the_seam_constants_agree_with_the_real_outputs(self):
        unsigned, signed = self.build_and_sign('0.0.0.2', 3)
        self.assertEqual(os.path.basename(unsigned), SEAMS['unsigned_name'] % '0.0.0.2')
        self.assertEqual(os.path.basename(signed), SEAMS['signed_name'] % '0.0.0.2')
        for name in (BUILD, SIGN, WRITE, SERVE, SIGNER):
            self.assertTrue(os.path.isfile(name), name)


if __name__ == '__main__':
    unittest.main()
