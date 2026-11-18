"""Unit tests for scripts/serve-release.sh and the compose/nginx pins it needs.

The script runs for real in temp directories with the real python3 and
sha256sum. Only docker is a stub (it records its argv and answers
`volume inspect` with a temp directory); mv and cp get a thin recording
wrapper in two tests that must make one step fail. No container, no network.

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_serve_release.py'
"""
import base64
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest

ROOT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', '..'))
DEPLOY = os.path.join(ROOT, 'server', 'modules', 'deploy')
SCRIPT = os.path.join(DEPLOY, 'scripts', 'serve-release.sh')
COMMON = os.path.join(DEPLOY, 'scripts', 'deploy-common.sh')
COMPOSE = os.path.join(DEPLOY, 'docker-compose.yml')
TEMPLATE = os.path.join(DEPLOY, 'images', 'web-fe', 'nginx.conf.template')
TOOLS = os.path.join(ROOT, 'tools')
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import yaml_subset  # noqa: E402

PREFIX = 'serve-release.sh: refused: '
ZIP = b'PK\x03\x04' + b'apk-bytes-for-a-test-release' * 8
SIG = base64.b64encode(bytes(range(64))).decode('ascii')

STUB_DOCKER = r'''#!/bin/sh
for a in "$@"; do printf '%s\n' "$a" >> "$STUB_LOG"; done
printf -- '--\n' >> "$STUB_LOG"
if [ "$1 $2" = "volume inspect" ]; then
    [ "${STUB_RC:-0}" = 0 ] || exit "$STUB_RC"
    printf '%s\n' "$STUB_MOUNT"
fi
exit 0
'''

STUB_WRAP = r'''#!/bin/sh
tool=$(basename "$0")
printf '%s %s\n' "$tool" "$*" >> "$STUB_WRAP_LOG"
n=$(grep -c "^$tool " "$STUB_WRAP_LOG")
if [ "$tool" = mv ] && [ "${STUB_FAIL_MV_NTH:-0}" = "$n" ]; then exit 1; fi
if [ "$tool" = cp ] && [ -n "${STUB_FAIL_CP_SUFFIX:-}" ]; then
    for a in "$@"; do
        case "$a" in *"$STUB_FAIL_CP_SUFFIX") exit 1 ;; esac
    done
fi
if [ "$tool" = mv ]; then exec "$REAL_MV" "$@"; fi
if [ -n "${STUB_CORRUPT_CP:-}" ]; then
    for last in "$@"; do :; done
    case "$last" in
        */breaker.apk) "$REAL_CP" "$@" || exit 1; printf x >> "$last"; exit 0 ;;
    esac
fi
exec "$REAL_CP" "$@"
'''


def sha_of(data):
    return hashlib.sha256(data).hexdigest()


def write(path, data, mode=0o644):
    if isinstance(data, str):
        data = data.encode('utf-8')
    with open(path, 'wb') as handle:
        handle.write(data)
    os.chmod(path, mode)


def tree(root):
    """Every path under root: (relative path, kind, mode, link target or sha)."""
    out = []
    for base, dirs, files in os.walk(root):
        for name in sorted(dirs + files):
            full = os.path.join(base, name)
            rel = os.path.relpath(full, root)
            info = os.lstat(full)
            mode = stat.S_IMODE(info.st_mode)
            if stat.S_ISLNK(info.st_mode):
                out.append((rel, 'link', 0, os.readlink(full)))
            elif stat.S_ISDIR(info.st_mode):
                out.append((rel, 'dir', mode, ''))
            else:
                with open(full, 'rb') as handle:
                    out.append((rel, 'file', mode, sha_of(handle.read())))
    return sorted(out)


class ServeBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='breaker-serve-test-')
        self.root = os.path.join(self.tmp, 'volume')
        self.inp = os.path.join(self.tmp, 'in')
        self.bin = os.path.join(self.tmp, 'bin')
        self.wrap = os.path.join(self.tmp, 'wrap')
        for path in (self.root, self.inp, self.bin, self.wrap):
            os.mkdir(path)
        self.log = os.path.join(self.tmp, 'docker.log')
        self.wlog = os.path.join(self.tmp, 'wrap.log')
        write(os.path.join(self.bin, 'docker'), STUB_DOCKER, 0o755)
        write(os.path.join(self.wrap, 'mv'), STUB_WRAP, 0o755)
        write(os.path.join(self.wrap, 'cp'), STUB_WRAP, 0o755)
        self.addCleanup(shutil.rmtree, self.tmp, True)

    # -- inputs ---------------------------------------------------------
    def make_apk(self, version='0.0.0.2', data=ZIP, name=None):
        path = os.path.join(self.inp, name or 'breaker-%s.apk' % version)
        write(path, data)
        return path

    def fields(self, version='0.0.0.2', code=3, data=ZIP):
        return [
            ('version', version),
            ('version_code', code),
            ('apk_url', 'http://updates.example/releases/%s-%s/breaker.apk' % (version, code)),
            ('sha256', sha_of(data)),
            ('signature', SIG),
            ('notes', 'a note'),
        ]

    def make_manifest(self, pairs=None, raw=None, name='manifest.json'):
        path = os.path.join(self.inp, name)
        if raw is None:
            raw = json.dumps(dict(pairs if pairs is not None else self.fields())) + '\n'
        write(path, raw)
        return path

    def release(self, version='0.0.0.2', code=3, data=ZIP):
        apk = self.make_apk(version, data)
        man = self.make_manifest(self.fields(version, code, data),
                                 name='m-%s-%s.json' % (version, code))
        return apk, man

    # -- running --------------------------------------------------------
    def run_script(self, args, extra_env=None, wrapped=False):
        env = {'PATH': ('%s:' % self.wrap if wrapped else '') + os.environ['PATH'],
               'LC_ALL': 'C', 'HOME': self.tmp, 'STUB_LOG': self.log,
               'STUB_WRAP_LOG': self.wlog, 'STUB_MOUNT': self.root,
               'REAL_MV': shutil.which('mv'), 'REAL_CP': shutil.which('cp')}
        env.update(extra_env or {})
        return subprocess.run(['sh', SCRIPT] + list(args), capture_output=True,
                              text=True, env=env, timeout=60)

    def serve(self, apk, man, extra=(), **kw):
        return self.run_script(['--apk', apk, '--manifest', man, '--root', self.root] + list(extra), **kw)

    def assert_refused(self, result, reason=None):
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(result.stdout, '')
        self.assertTrue(result.stderr.startswith(PREFIX), result.stderr)
        self.assertEqual(result.stderr.count('\n'), 1, result.stderr)
        if reason:
            self.assertEqual(result.stderr, PREFIX + reason + '\n')

    def release_dir(self, name):
        return os.path.join(self.root, 'releases', name)


class LayoutTests(ServeBase):
    def test_a_good_release_gets_the_frozen_layout(self):
        apk, man = self.release()
        result = self.serve(apk, man)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, '')
        rel = self.release_dir('0.0.0.2-3')
        self.assertEqual(result.stdout, rel + '\n')
        self.assertEqual(sorted(os.listdir(rel)),
                         ['breaker.apk', 'breaker.apk.sha256', 'latest.json'])
        self.assertEqual(sorted(os.listdir(os.path.join(self.root, 'releases'))), ['0.0.0.2-3'])
        self.assertEqual(sorted(os.listdir(self.root)), ['current', 'releases'])

    def test_the_bytes_and_the_sha_text_are_exact(self):
        apk, man = self.release()
        self.assertEqual(self.serve(apk, man).returncode, 0)
        rel = self.release_dir('0.0.0.2-3')
        with open(os.path.join(rel, 'breaker.apk'), 'rb') as h:
            self.assertEqual(h.read(), ZIP)
        with open(man, 'rb') as a, open(os.path.join(rel, 'latest.json'), 'rb') as b:
            self.assertEqual(a.read(), b.read())
        with open(os.path.join(rel, 'breaker.apk.sha256'), 'rb') as h:
            self.assertEqual(h.read(), ('%s  breaker.apk\n' % sha_of(ZIP)).encode('ascii'))

    def test_files_are_0644_and_directories_0755_even_under_a_hostile_umask(self):
        apk, man = self.release()
        os.chmod(apk, 0o600)
        old = os.umask(0o077)
        try:
            result = self.serve(apk, man)
        finally:
            os.umask(old)
        self.assertEqual(result.returncode, 0, result.stderr)
        rel = self.release_dir('0.0.0.2-3')
        for name in ('breaker.apk', 'latest.json', 'breaker.apk.sha256'):
            with self.subTest(name=name):
                self.assertEqual(stat.S_IMODE(os.stat(os.path.join(rel, name)).st_mode), 0o644)
        self.assertEqual(stat.S_IMODE(os.stat(rel).st_mode), 0o755)
        self.assertEqual(stat.S_IMODE(os.stat(os.path.join(self.root, 'releases')).st_mode), 0o755)

    def test_current_is_a_relative_symlink_to_the_release(self):
        apk, man = self.release()
        self.assertEqual(self.serve(apk, man).returncode, 0)
        link = os.path.join(self.root, 'current')
        self.assertTrue(os.path.islink(link))
        self.assertEqual(os.readlink(link), 'releases/0.0.0.2-3')
        self.assertTrue(os.path.exists(os.path.join(link, 'latest.json')))

    def test_a_trailing_slash_on_root_does_not_double_the_path(self):
        apk, man = self.release()
        result = self.run_script(['--apk', apk, '--manifest', man, '--root', self.root + '/'])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, self.release_dir('0.0.0.2-3') + '\n')

    def test_a_second_release_keeps_the_first_untouched_and_flips_current(self):
        apk, man = self.release('0.0.0.2', 3)
        self.assertEqual(self.serve(apk, man).returncode, 0)
        first = tree(self.release_dir('0.0.0.2-3'))
        data2 = ZIP + b'second'
        apk2, man2 = self.release('0.0.0.3', 4, data2)
        result = self.serve(apk2, man2)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(tree(self.release_dir('0.0.0.2-3')), first)
        self.assertEqual(os.readlink(os.path.join(self.root, 'current')), 'releases/0.0.0.3-4')
        self.assertEqual(sorted(os.listdir(os.path.join(self.root, 'releases'))),
                         ['0.0.0.2-3', '0.0.0.3-4'])

    def test_a_failed_second_release_leaves_current_and_the_first_untouched(self):
        apk, man = self.release('0.0.0.2', 3)
        self.assertEqual(self.serve(apk, man).returncode, 0)
        before = tree(self.root)
        data2 = ZIP + b'second'
        apk2 = self.make_apk('0.0.0.3', data2)
        bad = self.fields('0.0.0.3', 4, ZIP)  # sha of other bytes
        man2 = self.make_manifest(bad, name='bad.json')
        result = self.serve(apk2, man2)
        self.assert_refused(result)
        self.assertEqual(tree(self.root), before)

    def test_a_failing_apk_copy_leaves_no_trace_and_current_stays(self):
        apk, man = self.release('0.0.0.2', 3)
        self.assertEqual(self.serve(apk, man).returncode, 0)
        before = tree(self.root)
        apk2, man2 = self.release('0.0.0.3', 4, ZIP + b'x')
        result = self.serve(apk2, man2, wrapped=True,
                            extra_env={'STUB_FAIL_CP_SUFFIX': '/breaker.apk'})
        self.assert_refused(result)
        self.assertEqual(tree(self.root), before)
        self.assertEqual(os.readlink(os.path.join(self.root, 'current')), 'releases/0.0.0.2-3')

    def test_a_copy_that_differs_from_the_checked_apk_is_refused_and_wiped(self):
        apk, man = self.release('0.0.0.2', 3)
        self.assertEqual(self.serve(apk, man).returncode, 0)
        before = tree(self.root)
        apk2, man2 = self.release('0.0.0.3', 4, ZIP + b'x')
        result = self.serve(apk2, man2, wrapped=True, extra_env={'STUB_CORRUPT_CP': '1'})
        self.assert_refused(result, 'the copied APK does not match the manifest sha256')
        self.assertEqual(tree(self.root), before)

    def test_a_failing_swap_wipes_the_new_release_and_current_stays(self):
        apk, man = self.release('0.0.0.2', 3)
        self.assertEqual(self.serve(apk, man).returncode, 0)
        before = tree(self.root)
        apk2, man2 = self.release('0.0.0.3', 4, ZIP + b'x')
        result = self.serve(apk2, man2, wrapped=True, extra_env={'STUB_FAIL_MV_NTH': '2'})
        self.assert_refused(result)
        self.assertEqual(tree(self.root), before)
        with open(self.wlog) as h:
            mvs = [line for line in h if line.startswith('mv ')]
        self.assertEqual(len(mvs), 2, mvs)

    def test_the_link_is_swapped_with_mv_dash_t_onto_the_final_name(self):
        apk, man = self.release()
        self.assertEqual(self.serve(apk, man, wrapped=True).returncode, 0)
        with open(self.wlog) as h:
            mvs = [line.split() for line in h if line.startswith('mv ')]
        self.assertEqual(len(mvs), 2, mvs)
        self.assertEqual(mvs[0][:2], ['mv', '-T'])
        self.assertEqual(mvs[1][:2], ['mv', '-T'])
        self.assertEqual(mvs[1][3], os.path.join(self.root, 'current'))
        self.assertTrue(os.path.basename(mvs[1][2]).startswith('.current.new.'))
        with open(SCRIPT) as h:
            text = h.read()
        self.assertIn('mv -T "$tmplink" "$root/current"', text)
        self.assertNotIn('ln -sf', text)

    def test_the_apk_is_in_place_before_the_link_moves(self):
        apk, man = self.release()
        self.assertEqual(self.serve(apk, man, wrapped=True).returncode, 0)
        with open(self.wlog) as h:
            lines = h.read().splitlines()
        cp_apk = [i for i, line in enumerate(lines) if line.startswith('cp ') and line.endswith('/breaker.apk')]
        cp_man = [i for i, line in enumerate(lines) if line.startswith('cp ') and line.endswith('/latest.json')]
        mv_link = [i for i, line in enumerate(lines) if line.startswith('mv ') and line.endswith('/current')]
        self.assertEqual((len(cp_apk), len(cp_man), len(mv_link)), (1, 1, 1))
        self.assertLess(cp_apk[0], mv_link[0])
        self.assertLess(cp_man[0], mv_link[0])

    def test_current_is_never_missing_or_dangling_during_a_swap(self):
        apk, man = self.release('0.0.0.2', 3)
        self.assertEqual(self.serve(apk, man).returncode, 0)
        apk2, man2 = self.release('0.0.0.3', 4, ZIP + b'y')
        link = os.path.join(self.root, 'current')
        seen = []
        bad = []
        env = {'PATH': os.environ['PATH'], 'LC_ALL': 'C'}
        proc = subprocess.Popen(['sh', SCRIPT, '--apk', apk2, '--manifest', man2, '--root', self.root],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)

        def poll():
            for _ in range(200):
                try:
                    target = os.readlink(link)
                    if not os.path.exists(os.path.join(link, 'latest.json')):
                        bad.append('dangling ' + target)
                    seen.append(target)
                except OSError as exc:
                    bad.append('missing %s' % exc)
                time.sleep(0.001)

        thread = threading.Thread(target=poll)
        thread.start()
        thread.join(30)
        proc.wait(timeout=30)
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(bad, [])
        self.assertEqual(len(seen), 200)
        self.assertEqual(os.readlink(link), 'releases/0.0.0.3-4')
        self.assertTrue(set(seen) <= {'releases/0.0.0.2-3', 'releases/0.0.0.3-4'})


class RefusalTests(ServeBase):
    def refuse_case(self, label, apk, man, reason=None):
        before = tree(self.root)
        with self.subTest(case=label):
            result = self.serve(apk, man)
            self.assert_refused(result, reason)
            self.assertEqual(tree(self.root), before)

    def mutated(self, key, value, version='0.0.0.2', code=3):
        pairs = self.fields(version, code)
        out = [(k, value if k == key else v) for k, v in pairs]
        return self.make_manifest(out, name='m.json')

    def test_a_changed_apk_byte_is_refused(self):
        apk, man = self.release()
        flipped = bytearray(ZIP)
        flipped[10] ^= 1
        write(apk, bytes(flipped))
        self.refuse_case('byte', apk, man,
                         'the manifest sha256 does not match the APK bytes')

    def test_a_version_that_differs_from_the_apk_name_is_refused(self):
        apk = self.make_apk('0.0.0.2')
        self.refuse_case('version', apk, self.mutated('version', '0.0.0.9'),
                         'the manifest version does not match the APK name')

    def test_an_apk_url_with_another_code_or_suffix_is_refused(self):
        apk = self.make_apk('0.0.0.2')
        reason = 'the manifest apk_url does not end with /releases/<name>-<code>/breaker.apk'
        for label, url in (('code', 'http://updates.example/releases/0.0.0.2-4/breaker.apk'),
                           ('suffix', 'http://updates.example/releases/0.0.0.2-3/other.apk'),
                           ('name', 'http://updates.example/releases/0.0.0.9-3/breaker.apk'),
                           ('slash', 'http://updates.example/releases/0.0.0.2-3/breaker.apk/'),
                           ('space', 'http://updates.example/x y/releases/0.0.0.2-3/breaker.apk'),
                           ('type', 5)):
            self.refuse_case(label, apk, self.mutated('apk_url', url), reason)

    def test_a_member_beyond_the_six_is_refused(self):
        apk = self.make_apk()
        man = self.make_manifest(self.fields() + [('rollback', True)])
        self.refuse_case('extra', apk, man, 'the manifest has a member beyond the six')

    def test_a_missing_member_is_refused(self):
        apk = self.make_apk()
        for gone in ('version', 'version_code', 'apk_url', 'sha256', 'signature', 'notes'):
            man = self.make_manifest([(k, v) for k, v in self.fields() if k != gone])
            self.refuse_case('missing ' + gone, apk, man, 'the manifest lacks a required member')

    def test_a_duplicate_key_or_a_non_object_is_refused(self):
        apk = self.make_apk()
        good = json.dumps(dict(self.fields()))
        dup = good[:-1] + ', "notes": "again"}'
        reason = 'the manifest is not one JSON object with unique keys'
        for label, raw in (('dup', dup), ('list', '[%s]' % good), ('string', '"x"'),
                           ('number', '7'), ('empty', ''), ('two', good + good),
                           ('garbage', '{not json')):
            self.refuse_case(label, apk, self.make_manifest(raw=raw), reason)

    def test_a_bad_version_code_is_refused(self):
        apk = self.make_apk()
        reason_range = 'the manifest version_code is outside 1 to 18 digits'
        reason_type = 'the manifest version_code is not a whole number'
        for label, value, reason in (('zero', 0, reason_range), ('negative', -3, reason_range),
                                     ('19 digits', 10 ** 18, reason_range),
                                     ('string', '3', reason_type), ('float', 3.0, reason_type),
                                     ('bool', True, reason_type), ('null', None, reason_type)):
            code_url = value if isinstance(value, int) and not isinstance(value, bool) else 3
            pairs = self.fields()
            pairs = [(k, value if k == 'version_code' else v) for k, v in pairs]
            pairs = [(k, 'http://updates.example/releases/0.0.0.2-%s/breaker.apk' % code_url
                      if k == 'apk_url' else v) for k, v in pairs]
            self.refuse_case(label, apk, self.make_manifest(pairs), reason)
        raw = json.dumps(dict(self.fields())).replace('"version_code": 3', '"version_code": 03')
        self.refuse_case('leading zero', apk, self.make_manifest(raw=raw),
                         'the manifest is not one JSON object with unique keys')

    def test_the_largest_version_code_of_18_digits_is_accepted(self):
        code = 10 ** 18 - 1
        apk = self.make_apk()
        man = self.make_manifest(self.fields(code=code))
        result = self.serve(apk, man)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, self.release_dir('0.0.0.2-%d' % code) + '\n')

    def test_an_uppercase_or_short_sha_is_refused(self):
        apk = self.make_apk()
        reason = 'the manifest sha256 is not 64 lowercase hex characters'
        for label, value in (('upper', sha_of(ZIP).upper()), ('short', sha_of(ZIP)[:63]),
                             ('long', sha_of(ZIP) + '0'), ('type', 7),
                             ('newline', sha_of(ZIP) + '\n')):
            self.refuse_case(label, apk, self.mutated('sha256', value), reason)

    def test_a_signature_that_is_not_88_canonical_base64_characters_is_refused(self):
        apk = self.make_apk()
        reason = 'the manifest signature is not canonical base64 of 88 characters'
        noncanon = SIG[:-3] + 'B=='  # non-zero trailing bits
        for label, value in (('87', SIG[:-1]), ('89', SIG + 'A'), ('noncanon', noncanon),
                             ('urlsafe', SIG.replace('+', '-') if '+' in SIG else SIG[:-4] + '-_==')
                             , ('type', 5), ('empty', '')):
            self.refuse_case(label, apk, self.mutated('signature', value), reason)

    def test_a_manifest_over_65536_bytes_is_refused_and_one_at_the_cap_is_not(self):
        apk = self.make_apk()
        pairs = self.fields()
        base = json.dumps(dict(pairs))
        notes = 'n' * (len('a note') + 65536 - len(base))
        at_cap = json.dumps(dict([(k, notes if k == 'notes' else v) for k, v in pairs]))
        self.assertEqual(len(at_cap), 65536)
        over = self.make_manifest(raw=at_cap + ' ')
        self.refuse_case('65537', apk, over, 'the manifest is larger than 65536 bytes')
        ok = self.make_manifest(raw=at_cap, name='cap.json')
        self.assertEqual(self.serve(apk, ok).returncode, 0)

    def test_a_non_ascii_manifest_is_refused(self):
        apk = self.make_apk()
        raw = json.dumps(dict(self.fields()), ensure_ascii=False).replace('a note', 'caf\u00e9')
        self.refuse_case('utf8', apk, self.make_manifest(raw=raw), 'the manifest is not ASCII')

    def test_a_misnamed_apk_is_refused(self):
        man = self.make_manifest()
        for name in ('breaker-0.0.0.2-unsigned.apk', 'breaker-0.0.2.apk', 'breaker-0.0.0.2.0.apk',
                     'breaker-a.b.c.d.apk', 'app.apk', 'breaker-0.0.0.2.apk.bak',
                     'Breaker-0.0.0.2.apk', 'breaker-0.0.0.2.APK'):
            apk = self.make_apk(name=name)
            self.refuse_case(name, apk, man, 'the APK is not named breaker-A.B.C.D.apk')

    def test_symlink_inputs_are_refused(self):
        apk = self.make_apk()
        man = self.make_manifest()
        link_apk = os.path.join(self.inp, 'breaker-0.0.0.5.apk')
        os.symlink(apk, link_apk)
        self.refuse_case('apk link', link_apk, man, 'the APK is not a regular file')
        link_man = os.path.join(self.inp, 'link.json')
        os.symlink(man, link_man)
        self.refuse_case('manifest link', apk, link_man, 'the manifest is not a regular file')

    def test_a_missing_or_non_regular_input_is_refused(self):
        apk = self.make_apk()
        man = self.make_manifest()
        self.refuse_case('no apk', os.path.join(self.inp, 'breaker-0.0.0.7.apk'), man,
                         'the APK is not a regular file')
        self.refuse_case('no manifest', apk, os.path.join(self.inp, 'nope.json'),
                         'the manifest is not a regular file')
        self.refuse_case('dir manifest', apk, self.inp, 'the manifest is not a regular file')

    def test_an_empty_or_non_zip_apk_is_refused(self):
        man = self.make_manifest()
        apk = self.make_apk(data=b'')
        self.refuse_case('empty', apk, man, 'the APK is empty')
        for label, data in (('text', b'hello world, not a zip'), ('short', b'PK\x03'),
                            ('empty zip magic', b'PK\x05\x06' + b'\0' * 18)):
            apk = self.make_apk(data=data)
            self.refuse_case(label, apk, man, 'the APK is not a zip file')

    def test_an_existing_release_directory_is_refused_and_not_touched(self):
        apk, man = self.release()
        self.assertEqual(self.serve(apk, man).returncode, 0)
        before = tree(self.root)
        self.refuse_case('again', apk, man, 'the release directory already exists')
        self.assertEqual(tree(self.root), before)

    def test_a_missing_root_or_a_symlinked_root_or_a_file_root_is_refused(self):
        apk, man = self.release()
        reason = 'the release root is missing, not a directory, or a symlink'
        missing = os.path.join(self.tmp, 'absent')
        result = self.run_script(['--apk', apk, '--manifest', man, '--root', missing])
        self.assert_refused(result, reason)
        self.assertFalse(os.path.exists(missing))
        link = os.path.join(self.tmp, 'rootlink')
        os.symlink(self.root, link)
        self.assert_refused(self.run_script(['--apk', apk, '--manifest', man, '--root', link]), reason)
        self.assertEqual(os.listdir(self.root), [])
        afile = os.path.join(self.tmp, 'afile')
        write(afile, 'x')
        self.assert_refused(self.run_script(['--apk', apk, '--manifest', man, '--root', afile]), reason)
        self.assert_refused(self.run_script(['--apk', apk, '--manifest', man, '--root', 'relative']),
                            'the release root is not an absolute path')

    def test_a_current_that_is_not_a_symlink_is_refused(self):
        apk, man = self.release()
        os.mkdir(os.path.join(self.root, 'current'))
        before = tree(self.root)
        self.refuse_case('dir', apk, man, 'current exists and is not a symlink')
        self.assertEqual(tree(self.root), before)


class UsageAndDockerTests(ServeBase):
    def test_usage_errors_exit_2_with_nothing_on_stdout(self):
        apk, man = self.release()
        cases = [
            [],
            ['--apk', apk],
            ['--manifest', man],
            ['--apk', apk, '--manifest', man, '--bogus'],
            ['--apk', apk, '--manifest'],
            ['--apk', apk, '--manifest', man, '--root', self.root, '--volume', 'v'],
            ['--apk', apk, '--manifest', man, '--root', ''],
            ['--apk', apk, '--manifest', man, '--root', self.root, '--root', self.root],
            ['--apk', apk, '--manifest', man, '--volume', 'bad name'],
            ['--apk', apk, '--manifest', man, '--volume', '-x'],
            ['--apk', apk, '--manifest', man, '--root', self.root, '--docker-bin', ''],
        ]
        for args in cases:
            with self.subTest(args=args):
                result = self.run_script(args)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertEqual(result.stdout, '')
                self.assertTrue(result.stderr.startswith('serve-release.sh: '))
                self.assertNotIn('refused', result.stderr)
        self.assertEqual(os.listdir(self.root), [])

    def docker_calls(self):
        if not os.path.exists(self.log):
            return []
        with open(self.log) as h:
            return h.read().split('--\n')[:-1]

    def test_docker_is_not_run_when_root_is_given(self):
        apk, man = self.release()
        result = self.serve(apk, man, extra=['--docker-bin', os.path.join(self.bin, 'docker')])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.docker_calls(), [])

    def test_without_root_the_volume_mountpoint_comes_from_docker_inspect(self):
        apk, man = self.release()
        docker = os.path.join(self.bin, 'docker')
        result = self.run_script(['--apk', apk, '--manifest', man, '--docker-bin', docker])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, self.release_dir('0.0.0.2-3') + '\n')
        self.assertEqual(self.docker_calls(),
                         ['volume\ninspect\n-f\n{{.Mountpoint}}\nbreaker_breaker_releases\n'])

    def test_a_named_volume_is_inspected_and_the_env_docker_is_the_fallback(self):
        apk, man = self.release()
        docker = os.path.join(self.bin, 'docker')
        result = self.run_script(['--apk', apk, '--manifest', man, '--volume', 'other_vol'],
                                 extra_env={'DEPLOY_DOCKER': docker})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.docker_calls(),
                         ['volume\ninspect\n-f\n{{.Mountpoint}}\nother_vol\n'])

    def test_docker_bin_wins_over_the_env_and_a_failing_docker_is_a_refusal(self):
        apk, man = self.release()
        good = os.path.join(self.bin, 'docker')
        result = self.run_script(['--apk', apk, '--manifest', man, '--docker-bin', good],
                                 extra_env={'DEPLOY_DOCKER': '/nonexistent/docker'})
        self.assertEqual(result.returncode, 0, result.stderr)
        before = tree(self.root)
        result = self.run_script(['--apk', apk, '--manifest', man, '--docker-bin', good],
                                 extra_env={'STUB_RC': '1'})
        self.assert_refused(result)
        self.assertEqual(tree(self.root), before)
        result = self.run_script(['--apk', apk, '--manifest', man],
                                 extra_env={'DEPLOY_DOCKER': '/nonexistent/docker'})
        self.assert_refused(result, 'the release volume directory could not be resolved')

    def test_the_script_is_posix_sh_clean_and_executable(self):
        self.assertEqual(subprocess.run(['sh', '-n', SCRIPT]).returncode, 0)
        self.assertEqual(stat.S_IMODE(os.stat(SCRIPT).st_mode), 0o755)
        with open(SCRIPT, 'rb') as h:
            raw = h.read()
        raw.decode('ascii')
        self.assertTrue(raw.startswith(b'#!/usr/bin/env sh\n'))


def compose_doc():
    with open(COMPOSE, encoding='ascii') as handle:
        return yaml_subset.loads(handle.read(), COMPOSE)


class ComposePins(unittest.TestCase):
    def setUp(self):
        self.doc = compose_doc()

    def test_the_release_volume_exists_at_the_top_level(self):
        self.assertIn('breaker_releases', self.doc['volumes'])

    def test_sync_api_mounts_it_read_only_at_the_exact_path(self):
        svc = self.doc['services']['sync-api']
        self.assertIn('breaker_releases:/var/lib/breaker/releases:ro', svc['volumes'])
        self.assertEqual(svc['environment']['BREAKER_SYNC_API_MANIFEST_FILE'],
                         '/var/lib/breaker/releases/current/latest.json')

    def test_web_fe_mounts_it_read_only(self):
        self.assertEqual(self.doc['services']['web-fe']['volumes'],
                         ['breaker_releases:/srv/breaker/releases:ro'])

    def test_no_service_mounts_it_read_write_and_whisper_does_not_mount_it(self):
        for name, svc in self.doc['services'].items():
            for mount in svc.get('volumes', []):
                if mount.startswith('breaker_releases'):
                    with self.subTest(service=name):
                        self.assertTrue(mount.endswith(':ro'), mount)
        self.assertEqual([m for m in self.doc['services']['whisper-server']['volumes']
                          if 'releases' in m], [])
        self.assertNotIn('volumes', self.doc['services']['training'])

    def test_the_hardening_stays(self):
        for name in ('sync-api', 'web-fe'):
            svc = self.doc['services'][name]
            self.assertEqual(svc['cap_drop'], ['ALL'])
            self.assertEqual(svc['security_opt'], ['no-new-privileges:true'])

    def test_the_compose_file_is_ascii(self):
        with open(COMPOSE, 'rb') as handle:
            handle.read().decode('ascii')

    def test_deploy_db_paths_answers_the_same_with_the_release_mounts(self):
        tmp = tempfile.mkdtemp(prefix='breaker-serve-compose-')
        self.addCleanup(shutil.rmtree, tmp, True)
        docker = os.path.join(tmp, 'docker')
        write(docker, '#!/bin/sh\n'
                      'case "$*" in\n'
                      '  "volume ls --format {{.Name}}") printf "breaker_breaker_sync_api_data\\n'
                      'breaker_breaker_whisper_data\\nbreaker_breaker_releases\\n" ;;\n'
                      '  "volume inspect -f {{.Mountpoint}} "*) printf "/srv/v/%s\\n" "$5" ;;\n'
                      'esac\n', 0o755)
        driver = os.path.join(tmp, 'driver')
        write(driver, '#!/bin/sh\nSCRIPT_DIR=%s\n. %s\ndeploy_db_paths\n'
              % (os.path.dirname(COMMON), COMMON))
        env = {'PATH': tmp + ':' + os.environ['PATH'], 'COMPOSE_FILE': COMPOSE, 'LC_ALL': 'C'}
        result = subprocess.run(['sh', driver], capture_output=True, text=True, env=env, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout,
                         '/srv/v/breaker_breaker_sync_api_data/sync-api.db\n'
                         '/srv/v/breaker_breaker_whisper_data/whisper-server.db\n')


def read_template():
    with open(TEMPLATE, encoding='ascii') as handle:
        return handle.read()


def render(text, port='8080'):
    """The entrypoint's envsubst replaces only ${BREAKER_WEB_FE_PORT}."""
    return text.replace('${BREAKER_WEB_FE_PORT}', port)


class NginxPins(unittest.TestCase):
    def setUp(self):
        self.text = read_template()
        match = re.search(r'(?ms)^        location \^?~? ?/releases/ \{\n.*?^        \}\n', self.text)
        self.assertIsNotNone(match, 'no /releases/ location block')
        self.block = match.group(0)

    def test_there_is_exactly_one_releases_location(self):
        self.assertEqual(len(re.findall(r'location[^{]*/releases/', self.text)), 1)

    def test_only_get_and_head_are_allowed_and_nothing_is_listed(self):
        self.assertIn('limit_except GET {\n                deny all;\n            }', self.block)
        self.assertIn('autoindex off;', self.block)
        self.assertNotIn('autoindex on', self.text)

    def test_the_alias_points_at_the_per_release_directories(self):
        self.assertIn('alias /srv/breaker/releases/releases/;', self.block)

    def test_nothing_is_proxied(self):
        self.assertNotIn('proxy_pass', self.text)

    def test_the_mime_types_are_the_apk_and_json_types(self):
        self.assertIn('default_type application/octet-stream;', self.block)
        self.assertIn('types {\n                application/vnd.android.package-archive apk;\n'
                      '                application/json json;\n            }', self.block)

    def test_the_health_location_and_the_root_stay(self):
        self.assertIn('location = /health {', self.text)
        self.assertIn('location / {\n            try_files $uri $uri/ =404;', self.text)

    def test_the_template_has_no_new_substitution_variable(self):
        self.assertEqual(sorted(set(re.findall(r'\$\{[A-Z_]+\}', self.text))),
                         ['${BREAKER_WEB_FE_PORT}'])
        self.assertNotIn('$', self.block)

    def test_the_rendered_config_passes_nginx_t_when_nginx_exists(self):
        nginx = shutil.which('nginx')
        if nginx is None:
            raise unittest.SkipTest('nginx is not on PATH here: `nginx -t` on the rendered '
                                    'config could not be run (checked by pins only)')
        tmp = tempfile.mkdtemp(prefix='breaker-nginx-test-')
        self.addCleanup(shutil.rmtree, tmp, True)
        conf = os.path.join(tmp, 'nginx.conf')
        text = render(self.text).replace('/var/lib/nginx/', tmp + '/')
        text = text.replace('listen 8080;', 'listen 127.0.0.1:18080;')
        write(conf, text)
        result = subprocess.run([nginx, '-t', '-c', conf, '-p', tmp], capture_output=True,
                                text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
