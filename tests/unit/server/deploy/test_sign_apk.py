"""Unit tests for scripts/sign-apk.sh.

The script is driven via subprocess with a STUB in place of docker (a shell
script that records each run's argv, its own environment and a few
content-independent facts about the mounts, and that fakes apksigner's
output). No real container, JDK or network is involved. Keystore and
password contents are random markers generated at run time; the tests assert
the markers appear nowhere the script or the stub could leak them.

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_sign_apk.py'
"""
import os
import secrets
import shutil
import stat
import subprocess
import tempfile
import unittest

SCRIPT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    '..', '..', '..', '..',
    'server', 'modules', 'deploy', 'scripts', 'sign-apk.sh',
))

IMAGE = 'breaker-android-builder:jdk25-api36-1'
APKSIGNER = '/opt/android-sdk/build-tools/36.0.0/apksigner'
GOOD_NAME = 'breaker-1.2.3.4-unsigned.apk'
ZIP = b'PK\x03\x04' + b'filler-bytes-for-an-apk' * 4

# The stub is invoked as `<stub> run ARGS...`. Each call N (counted by the
# files already in $STUB_DIR) writes run<N>.argv (one arg per line),
# run<N>.env (its whole environment) and run<N>.facts. For the sign call it
# creates out.apk in the mounted io dir (input bytes + SIGNED); for the verify
# call it creates nothing. STUB_FAIL_SIGN / STUB_FAIL_VERIFY / STUB_NO_OUT
# select failures.
STUB = r'''#!/bin/sh
n=1
while [ -e "$STUB_DIR/run$n.argv" ]; do n=$((n+1)); done
: > "$STUB_DIR/run$n.argv"
for a in "$@"; do printf '%s\n' "$a" >> "$STUB_DIR/run$n.argv"; done
env > "$STUB_DIR/run$n.env"
facts="$STUB_DIR/run$n.facts"
: > "$facts"
mode=""
iodir=""
ksdir=""
for a in "$@"; do
    case "$a" in
        sign) mode=sign ;;
        verify) mode=verify ;;
    esac
done
echo "mode=$mode" >> "$facts"
# set -- "$@"
while [ $# -gt 0 ]; do
    if [ "$1" = "-v" ]; then
        v="$2"
        case "$v" in
            *:/ks:ro) ksdir="${v%:/ks:ro}" ;;
            *:/work) iodir="${v%:/work}" ;;
            *:/work:ro) iodir="${v%:/work:ro}" ;;
        esac
        shift
    fi
    shift
done
echo "iodir=$iodir" >> "$facts"
echo "ksdir=$ksdir" >> "$facts"
if [ -n "$ksdir" ]; then
    echo "ks_store_mode=$(stat -c %a "$ksdir/store.p12" 2>/dev/null)" >> "$facts"
    echo "ks_pass_mode=$(stat -c %a "$ksdir/pass" 2>/dev/null)" >> "$facts"
    echo "ks_dir_mode=$(stat -c %a "$ksdir" 2>/dev/null)" >> "$facts"
    echo "work_mode=$(stat -c %a "$(dirname "$ksdir")" 2>/dev/null)" >> "$facts"
fi
if [ -n "$iodir" ]; then
    echo "io_has_in=$([ -f "$iodir/in.apk" ] && echo yes || echo no)" >> "$facts"
    echo "io_has_out=$([ -f "$iodir/out.apk" ] && echo yes || echo no)" >> "$facts"
    echo "io_listing=$(ls "$iodir" | tr '\n' ' ')" >> "$facts"
fi
if [ "$mode" = sign ]; then
    [ -n "$STUB_FAIL_SIGN" ] && exit 3
    [ -n "$STUB_CREATE_TARGET" ] && printf old > "$STUB_CREATE_TARGET"
    if [ -z "$STUB_NO_OUT" ]; then
        cp "$iodir/in.apk" "$iodir/out.apk"
        printf SIGNED >> "$iodir/out.apk"
    fi
    exit 0
fi
if [ "$mode" = verify ]; then
    [ -n "$STUB_FAIL_VERIFY" ] && exit 4
    [ -f "$iodir/out.apk" ] || exit 5
    exit 0
fi
exit 9
'''


class SignApkTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix='signapk-test-')
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.stubdir = os.path.join(self.root, 'stubbin')
        self.rec = os.path.join(self.root, 'rec')
        self.tmp = os.path.join(self.root, 'tmp')
        self.inputs = os.path.join(self.root, 'inputs')
        self.out = os.path.join(self.root, 'out')
        for d in (self.stubdir, self.rec, self.tmp, self.inputs):
            os.mkdir(d)
        stub = os.path.join(self.stubdir, 'fakedocker')
        with open(stub, 'w') as fh:
            fh.write(STUB)
        os.chmod(stub, 0o755)
        self.pw = 'PW-MARKER-' + secrets.token_hex(8)
        self.ksmark = 'KS-MARKER-' + secrets.token_hex(8)
        self.apk = os.path.join(self.inputs, GOOD_NAME)
        with open(self.apk, 'wb') as fh:
            fh.write(ZIP)
        self.ks = self.write_secret('store.p12', self.ksmark.encode())
        self.passf = self.write_secret('pass.txt', self.pw.encode())

    def write_secret(self, name, data, mode=0o600):
        p = os.path.join(self.inputs, name)
        with open(p, 'wb') as fh:
            fh.write(data)
        os.chmod(p, mode)
        return p

    def env(self, **extra):
        e = {
            'PATH': self.stubdir + ':/usr/bin:/bin',
            'TMPDIR': self.tmp,
            'STUB_DIR': self.rec,
        }
        e.update(extra)
        return e

    def args(self, **over):
        a = {
            '--in': self.apk, '--keystore': self.ks, '--alias': 'release',
            '--pass-file': self.passf, '--out-dir': self.out,
            '--docker-bin': 'fakedocker',
        }
        a.update(over)
        out = []
        for k, v in a.items():
            if v is not None:
                out += [k, v]
        return out

    def run_script(self, args=None, env=None):
        return subprocess.run(
            ['sh', SCRIPT] + (self.args() if args is None else args),
            env=self.env() if env is None else env,
            capture_output=True, text=True, timeout=60)

    def runs(self):
        """Recorded argv lists, one per container run, in order."""
        res = []
        n = 1
        while os.path.exists(os.path.join(self.rec, 'run%d.argv' % n)):
            with open(os.path.join(self.rec, 'run%d.argv' % n)) as fh:
                res.append(fh.read().split('\n')[:-1])
            n += 1
        return res

    def facts(self, n):
        d = {}
        with open(os.path.join(self.rec, 'run%d.facts' % n)) as fh:
            for line in fh:
                k, _, v = line.rstrip('\n').partition('=')
                d[k] = v
        return d

    def assert_refused(self, r, reason=None):
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertTrue(r.stderr.startswith('sign-apk.sh: refused: '), r.stderr)
        if reason:
            self.assertEqual(r.stderr, 'sign-apk.sh: refused: %s\n' % reason)
        self.assertEqual(self.runs(), [], 'a container was started')
        self.assertFalse(os.path.exists(self.out), 'output dir was created')
        self.assertEqual(os.listdir(self.tmp), [])

    def assert_no_markers(self, *texts):
        for t in texts:
            self.assertNotIn(self.pw, t)
            self.assertNotIn(self.ksmark, t)

    # ---- happy path --------------------------------------------------
    def test_a_good_run_prints_the_signed_path_and_the_bytes_are_the_signers(self):
        r = self.run_script()
        self.assertEqual(r.returncode, 0, r.stderr)
        target = os.path.join(self.out, 'breaker-1.2.3.4.apk')
        self.assertEqual(r.stdout, target + '\n')
        with open(target, 'rb') as fh:
            self.assertEqual(fh.read(), ZIP + b'SIGNED')
        self.assertEqual(stat.S_IMODE(os.stat(target).st_mode), 0o644)
        self.assertEqual(stat.S_IMODE(os.stat(self.out).st_mode), 0o755)
        self.assertEqual(os.listdir(self.out), ['breaker-1.2.3.4.apk'])

    def test_the_docker_program_may_come_from_the_environment(self):
        r = self.run_script(args=self.args(**{'--docker-bin': None}),
                            env=self.env(DEPLOY_DOCKER='fakedocker'))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(len(self.runs()), 2)

    def test_an_existing_output_dir_is_kept_and_not_chmodded(self):
        os.mkdir(self.out, 0o750)
        os.chmod(self.out, 0o750)
        r = self.run_script()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(stat.S_IMODE(os.stat(self.out).st_mode), 0o750)

    # ---- the argv of both runs, pinned by full equality --------------
    def norm(self, argv):
        out = []
        for a in argv:
            if a.startswith(self.tmp + '/sign-apk.'):
                rest = a[len(self.tmp + '/sign-apk.'):]
                # drop the random suffix up to the first slash or colon
                i = 0
                while i < len(rest) and rest[i] not in '/:':
                    i += 1
                a = 'WORK' + rest[i:]
            out.append(a)
        return out

    def test_the_two_container_runs_have_exactly_the_pinned_argv(self):
        r = self.run_script()
        self.assertEqual(r.returncode, 0, r.stderr)
        uidgid = '%d:%d' % (os.getuid(), os.getgid())
        runs = [self.norm(a) for a in self.runs()]
        self.assertEqual(runs, [
            ['run', '--rm', '--network', 'none',
             '--memory', '512m', '--memory-swap', '512m', '--pids-limit', '256',
             '--user', uidgid,
             '-v', 'WORK/ks:/ks:ro', '-v', 'WORK/io:/work',
             IMAGE, APKSIGNER, 'sign',
             '--ks', '/ks/store.p12', '--ks-type', 'pkcs12',
             '--ks-key-alias', 'release', '--ks-pass', 'file:/ks/pass',
             '--out', '/work/out.apk', '/work/in.apk'],
            ['run', '--rm', '--network', 'none',
             '--memory', '512m', '--memory-swap', '512m', '--pids-limit', '256',
             '--user', uidgid,
             '-v', 'WORK/io:/work:ro',
             IMAGE, APKSIGNER, 'verify', '/work/out.apk'],
        ])

    def test_both_runs_carry_the_resource_caps_right_after_the_network_flag(self):
        self.run_script()
        runs = self.runs()
        self.assertEqual(len(runs), 2)
        for argv in runs:
            i = argv.index('--network')
            self.assertEqual(argv[i + 1], 'none')
            self.assertEqual(argv[i + 2:i + 8], [
                '--memory', '512m', '--memory-swap', '512m',
                '--pids-limit', '256'], argv)
            for flag in ('--memory', '--memory-swap', '--pids-limit'):
                self.assertEqual(argv.count(flag), 1, flag)
                self.assertLess(argv.index(flag), argv.index(IMAGE), flag)

    def test_the_header_says_what_was_run_for_real_and_what_is_still_not_verified(self):
        with open(SCRIPT) as fh:
            head = fh.read().split('\numask 077\n')[0]
        flat = ' '.join(head.replace('#', ' ').split())
        self.assertNotIn('written from the tool', flat)
        self.assertIn('run for real by the manager on a toy APK in the real builder '
                      'image on 2026-11-18', flat)
        self.assertIn('apksigner reported Verifies', flat)
        self.assertIn('--ks --ks-type --ks-key-alias --ks-pass file: --out; verify', flat)
        self.assertIn('Gradle build step and the exact unsigned APK path '
                      '(see build-apk.sh) are still NOT verified', flat)
        self.assertIn('the tests here use a docker stub only', flat)

    def test_the_image_and_apksigner_can_be_overridden(self):
        r = self.run_script(args=self.args(**{
            '--builder-image': 'other/image:9', '--apksigner': '/x/apksigner'}))
        self.assertEqual(r.returncode, 0, r.stderr)
        for argv in self.runs():
            self.assertIn('other/image:9', argv)
            self.assertIn('/x/apksigner', argv)
            self.assertNotIn(IMAGE, argv)
            self.assertNotIn(APKSIGNER, argv)

    def test_the_verify_run_has_no_keystore_mount(self):
        self.run_script()
        sign, verify = self.runs()
        self.assertTrue(any(a.endswith(':/ks:ro') for a in sign))
        self.assertFalse(any('/ks' in a for a in verify), verify)
        self.assertNotIn('--ks', verify)
        f = self.facts(2)
        self.assertEqual(f['ksdir'], '')
        self.assertEqual(f['io_has_out'], 'yes')

    def test_the_verify_run_mounts_the_apk_read_only(self):
        self.run_script()
        verify = self.runs()[1]
        mounts = [a for a in verify if a.endswith(':/work') or a.endswith(':/work:ro')]
        self.assertEqual(len(mounts), 1)
        self.assertTrue(mounts[0].endswith(':/work:ro'))

    def test_the_sign_run_mounts_the_key_directory_read_only_and_the_io_dir_writable(self):
        self.run_script()
        sign = self.runs()[0]
        ks_mounts = [a for a in sign if a.endswith(':/ks:ro') or a.endswith(':/ks')]
        self.assertEqual(len(ks_mounts), 1)
        self.assertTrue(ks_mounts[0].endswith(':/ks:ro'))
        io_mounts = [a for a in sign if a.endswith(':/work') or a.endswith(':/work:ro')]
        self.assertEqual(len(io_mounts), 1)
        self.assertTrue(io_mounts[0].endswith(':/work'))

    def test_the_io_dir_never_holds_key_material(self):
        self.run_script()
        f = self.facts(1)
        self.assertEqual(f['io_listing'], 'in.apk ')
        f2 = self.facts(2)
        self.assertEqual(f2['io_listing'], 'in.apk out.apk ')

    def test_both_runs_have_the_network_off_and_the_callers_uid(self):
        self.run_script()
        uidgid = '%d:%d' % (os.getuid(), os.getgid())
        for argv in self.runs():
            i = argv.index('--network')
            self.assertEqual(argv[i + 1], 'none')
            j = argv.index('--user')
            self.assertEqual(argv[j + 1], uidgid)

    def test_the_staged_key_copies_are_mode_0600_in_a_0700_work_dir(self):
        self.run_script()
        f = self.facts(1)
        self.assertEqual(f['ks_store_mode'], '600')
        self.assertEqual(f['ks_pass_mode'], '600')
        self.assertEqual(f['ks_dir_mode'], '700')
        self.assertEqual(f['work_mode'], '700')
        self.assertTrue(f['ksdir'].startswith(self.tmp + '/'))

    # ---- secrets appear nowhere --------------------------------------
    def test_the_password_and_keystore_text_appear_nowhere(self):
        r = self.run_script()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assert_no_markers(r.stdout, r.stderr)
        for n in (1, 2):
            for ext in ('argv', 'env', 'facts'):
                with open(os.path.join(self.rec, 'run%d.%s' % (n, ext))) as fh:
                    self.assert_no_markers(fh.read())
        with open(os.path.join(self.out, 'breaker-1.2.3.4.apk'), 'rb') as fh:
            data = fh.read()
        self.assertNotIn(self.pw.encode(), data)
        self.assertNotIn(self.ksmark.encode(), data)

    def test_the_ks_pass_argument_is_exactly_the_file_form(self):
        self.run_script()
        argv = self.runs()[0]
        i = argv.index('--ks-pass')
        self.assertEqual(argv[i + 1], 'file:/ks/pass')
        self.assertEqual(argv.count('--ks-pass'), 1)

    def test_the_password_does_not_leak_when_signing_fails(self):
        r = self.run_script(env=self.env(STUB_FAIL_SIGN='1'))
        self.assertEqual(r.returncode, 1)
        self.assert_no_markers(r.stdout, r.stderr)
        for n in (1,):
            for ext in ('argv', 'env', 'facts'):
                with open(os.path.join(self.rec, 'run%d.%s' % (n, ext))) as fh:
                    self.assert_no_markers(fh.read())

    def test_the_password_does_not_leak_when_verify_fails(self):
        r = self.run_script(env=self.env(STUB_FAIL_VERIFY='1'))
        self.assertEqual(r.returncode, 1)
        self.assert_no_markers(r.stdout, r.stderr)

    # ---- key and pass file modes -------------------------------------
    def test_a_keystore_with_mode_0644_is_refused_before_any_container(self):
        os.chmod(self.ks, 0o644)
        self.assert_refused(self.run_script(), 'keystore mode is not 600')

    def test_a_pass_file_with_mode_0644_is_refused_before_any_container(self):
        os.chmod(self.passf, 0o644)
        self.assert_refused(self.run_script(), 'pass file mode is not 600')

    def test_other_modes_are_refused_for_both_files(self):
        for mode in (0o400, 0o640, 0o660, 0o700, 0o666):
            for which in ('ks', 'pass'):
                path = self.ks if which == 'ks' else self.passf
                os.chmod(path, mode)
                r = self.run_script()
                self.assertEqual(r.returncode, 1, (oct(mode), which))
                self.assertEqual(self.runs(), [])
                os.chmod(path, 0o600)

    def test_a_missing_keystore_or_pass_file_is_refused(self):
        self.assert_refused(self.run_script(args=self.args(**{
            '--keystore': os.path.join(self.inputs, 'nope.p12')})),
            'keystore is not a regular file')
        self.assert_refused(self.run_script(args=self.args(**{
            '--pass-file': os.path.join(self.inputs, 'nope.txt')})),
            'pass file is not a regular file')

    def test_a_directory_as_keystore_or_pass_file_is_refused(self):
        d = os.path.join(self.inputs, 'adir')
        os.mkdir(d, 0o600)
        self.assert_refused(self.run_script(args=self.args(**{'--keystore': d})),
                            'keystore is not a regular file')
        self.assert_refused(self.run_script(args=self.args(**{'--pass-file': d})),
                            'pass file is not a regular file')

    # ---- the input -----------------------------------------------------
    def make_input(self, name, data=ZIP):
        p = os.path.join(self.inputs, name)
        with open(p, 'wb') as fh:
            fh.write(data)
        return p

    def test_misnamed_inputs_are_refused(self):
        names = [
            'breaker-1.2.3-unsigned.apk',
            'breaker-1.2.3.4.5-unsigned.apk',
            'foo.apk',
            'breaker-0.0.0.1.apk',
            'breaker-1.2.3.4-signed.apk',
            'breaker-1.2.3.4-unsigned.apk.bak',
            'breaker-1.2.3.a-unsigned.apk',
            'breaker-1..3.4-unsigned.apk',
            'breaker-.1.2.3-unsigned.apk',
            'breaker-1.2.3.4.-unsigned.apk',
            'breaker--unsigned.apk',
            'xbreaker-1.2.3.4-unsigned.apk',
            'breaker-1.2.3.4-unsigned.apk\nx',
        ]
        for name in names:
            p = self.make_input(name)
            r = self.run_script(args=self.args(**{'--in': p}))
            self.assertEqual(r.returncode, 1, name)
            self.assertEqual(r.stderr, 'sign-apk.sh: refused: unsigned or misnamed input\n', name)
            self.assertEqual(r.stdout, '', name)
            self.assertEqual(self.runs(), [], name)

    def test_a_four_part_name_with_large_numbers_is_accepted(self):
        p = self.make_input('breaker-10.20.300.4000-unsigned.apk')
        r = self.run_script(args=self.args(**{'--in': p}))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, os.path.join(self.out, 'breaker-10.20.300.4000.apk') + '\n')

    def test_the_name_rule_looks_at_the_basename_only(self):
        d = os.path.join(self.inputs, 'breaker-9.9.9.9-unsigned.apk.d')
        os.mkdir(d)
        p = os.path.join(d, GOOD_NAME)
        with open(p, 'wb') as fh:
            fh.write(ZIP)
        r = self.run_script(args=self.args(**{'--in': p}))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(r.stdout.endswith('breaker-1.2.3.4.apk\n'))

    def test_a_non_zip_input_is_refused(self):
        for data in (b'not a zip at all', b'PK\x03', b'PK\x05\x06' + b'x' * 20,
                     b'xPK\x03\x04' + b'x' * 20, b'#!/bin/sh\necho hi\n'):
            os.unlink(self.apk)
            with open(self.apk, 'wb') as fh:
                fh.write(data)
            self.assert_refused(self.run_script(), 'input is not a zip archive')

    def test_an_empty_input_is_refused(self):
        open(self.apk, 'wb').close()
        self.assert_refused(self.run_script(), 'input is empty')

    def test_a_symlink_input_is_refused(self):
        real = self.make_input('real.bin')
        os.unlink(self.apk)
        os.symlink(real, self.apk)
        self.assert_refused(self.run_script(), 'input is a symlink')

    def test_a_missing_input_and_a_directory_input_are_refused(self):
        os.unlink(self.apk)
        self.assert_refused(self.run_script(), 'input is not a regular file')
        os.mkdir(self.apk)
        self.assert_refused(self.run_script(), 'input is not a regular file')

    # ---- the alias -------------------------------------------------------
    def test_bad_aliases_are_refused_and_never_reach_a_container(self):
        for alias in ('has space', 'a/b', 'a;b', 'a$b', 'a`b`', 'a"b', "a'b",
                      'a\nb', 'a:b', 'a&b', '', 'x' * 65, '\xe9', 'a|b', 'a*b'):
            r = self.run_script(args=self.args(**{'--alias': alias}))
            self.assertEqual(r.returncode, 1, repr(alias))
            self.assertEqual(r.stderr, 'sign-apk.sh: refused: alias is not valid\n', repr(alias))
            self.assertEqual(self.runs(), [], repr(alias))

    def test_good_aliases_pass_through_as_one_argv_element(self):
        for alias in ('a', 'Release_1', 'my.key-2', 'x' * 64, 'in-side-dash', '9'):
            for n in range(1, 3):
                p = os.path.join(self.rec, 'run%d' % n)
                for ext in ('.argv', '.env', '.facts'):
                    if os.path.exists(p + ext):
                        os.unlink(p + ext)
            if os.path.isdir(self.out):
                shutil.rmtree(self.out)
            r = self.run_script(args=self.args(**{'--alias': alias}))
            self.assertEqual(r.returncode, 0, (alias, r.stderr))
            argv = self.runs()[0]
            self.assertEqual(argv[argv.index('--ks-key-alias') + 1], alias)

    # ---- image / apksigner values ---------------------------------------
    def test_an_image_or_apksigner_value_that_looks_like_an_option_is_refused(self):
        self.assert_refused(self.run_script(args=self.args(**{'--builder-image': '--privileged'})),
                            'builder image is not valid')
        self.assert_refused(self.run_script(args=self.args(**{'--apksigner': '-x'})),
                            'apksigner path is not valid')
        self.assert_refused(self.run_script(args=self.args(**{'--apksigner': '-'})),
                            'apksigner path is not valid')

    # ---- the output -----------------------------------------------------
    def test_an_existing_output_file_is_refused_and_left_alone(self):
        os.mkdir(self.out)
        target = os.path.join(self.out, 'breaker-1.2.3.4.apk')
        with open(target, 'wb') as fh:
            fh.write(b'old')
        r = self.run_script()
        self.assertEqual(r.returncode, 1)
        self.assertEqual(r.stderr, 'sign-apk.sh: refused: output already exists\n')
        self.assertEqual(r.stdout, '')
        self.assertEqual(self.runs(), [])
        with open(target, 'rb') as fh:
            self.assertEqual(fh.read(), b'old')
        self.assertEqual(os.listdir(self.tmp), [])

    def test_an_output_that_appears_during_signing_is_not_overwritten(self):
        os.mkdir(self.out)
        target = os.path.join(self.out, 'breaker-1.2.3.4.apk')
        r = self.run_script(env=self.env(STUB_CREATE_TARGET=target))
        self.assertEqual(r.returncode, 1)
        self.assertEqual(r.stderr, 'sign-apk.sh: refused: output already exists\n')
        self.assertEqual(r.stdout, '')
        with open(target, 'rb') as fh:
            self.assertEqual(fh.read(), b'old')
        self.assertEqual(os.listdir(self.tmp), [])

    def test_an_existing_output_symlink_is_refused(self):
        os.mkdir(self.out)
        victim = os.path.join(self.root, 'victim')
        os.symlink(victim, os.path.join(self.out, 'breaker-1.2.3.4.apk'))
        r = self.run_script()
        self.assertEqual(r.returncode, 1)
        self.assertFalse(os.path.exists(victim))

    def test_an_output_dir_that_is_a_file_is_refused(self):
        with open(self.out, 'w') as fh:
            fh.write('x')
        r = self.run_script()
        self.assertEqual(r.returncode, 1)
        self.assertEqual(r.stderr, 'sign-apk.sh: refused: output directory is not a directory\n')
        self.assertEqual(self.runs(), [])

    # ---- failing containers -----------------------------------------------
    def test_a_failing_sign_run_exits_1_with_no_output_and_no_verify(self):
        r = self.run_script(env=self.env(STUB_FAIL_SIGN='1'))
        self.assertEqual(r.returncode, 1)
        self.assertEqual(r.stdout, '')
        self.assertEqual(r.stderr, 'sign-apk.sh: refused: signing failed\n')
        self.assertEqual(len(self.runs()), 1)
        self.assertFalse(os.path.exists(os.path.join(self.out, 'breaker-1.2.3.4.apk')))

    def test_a_sign_run_that_exits_0_but_writes_nothing_is_a_failure(self):
        r = self.run_script(env=self.env(STUB_NO_OUT='1'))
        self.assertEqual(r.returncode, 1)
        self.assertEqual(r.stderr, 'sign-apk.sh: refused: signing failed\n')
        self.assertEqual(len(self.runs()), 1)
        self.assertFalse(os.path.exists(self.out))

    def test_a_failing_verify_run_exits_1_with_no_output(self):
        r = self.run_script(env=self.env(STUB_FAIL_VERIFY='1'))
        self.assertEqual(r.returncode, 1)
        self.assertEqual(r.stdout, '')
        self.assertEqual(r.stderr, 'sign-apk.sh: refused: verification failed\n')
        self.assertEqual(len(self.runs()), 2)
        self.assertFalse(os.path.exists(os.path.join(self.out, 'breaker-1.2.3.4.apk')))

    def test_a_missing_docker_program_is_refused(self):
        r = self.run_script(args=self.args(**{'--docker-bin': 'no-such-docker-xyz'}))
        self.assertEqual(r.returncode, 1)
        self.assertEqual(r.stderr, 'sign-apk.sh: refused: docker is not available\n')
        self.assertEqual(os.listdir(self.tmp), [])

    # ---- temp wipe ----------------------------------------------------------
    def test_the_temp_dir_is_empty_after_a_good_run(self):
        self.assertEqual(self.run_script().returncode, 0)
        self.assertEqual(os.listdir(self.tmp), [])

    def test_the_temp_dir_is_empty_after_a_failing_sign(self):
        self.run_script(env=self.env(STUB_FAIL_SIGN='1'))
        self.assertEqual(os.listdir(self.tmp), [])

    def test_the_temp_dir_is_empty_after_a_sign_that_wrote_nothing(self):
        self.run_script(env=self.env(STUB_NO_OUT='1'))
        self.assertEqual(os.listdir(self.tmp), [])

    def test_the_temp_dir_is_empty_after_a_failing_verify(self):
        self.run_script(env=self.env(STUB_FAIL_VERIFY='1'))
        self.assertEqual(os.listdir(self.tmp), [])

    def test_the_temp_dir_is_empty_after_a_refusal_at_the_publish_step(self):
        # an output file appearing between the pre-check and the copy is
        # simulated by an output path whose parent cannot be created
        blocker = os.path.join(self.root, 'blocker')
        with open(blocker, 'w') as fh:
            fh.write('x')
        r = self.run_script(args=self.args(**{'--out-dir': os.path.join(blocker, 'sub')}))
        self.assertEqual(r.returncode, 1)
        self.assertEqual(len(self.runs()), 2)
        self.assertEqual(os.listdir(self.tmp), [])

    def test_the_work_dir_exists_during_the_run_and_is_under_tmpdir(self):
        self.run_script()
        f = self.facts(1)
        self.assertTrue(f['iodir'].startswith(self.tmp + '/sign-apk.'))
        self.assertFalse(os.path.exists(f['iodir']))

    def test_a_tmpdir_path_with_a_colon_is_refused_and_leaves_nothing(self):
        bad = os.path.join(self.root, 'a:b')
        os.mkdir(bad)
        r = self.run_script(env=self.env(TMPDIR=bad))
        self.assertEqual(r.returncode, 1)
        self.assertEqual(r.stderr, 'sign-apk.sh: refused: temp directory path is not usable\n')
        self.assertEqual(self.runs(), [])
        self.assertEqual(os.listdir(bad), [])

    # ---- usage errors -------------------------------------------------------
    def test_each_missing_required_option_is_a_usage_error(self):
        for opt in ('--in', '--keystore', '--alias', '--pass-file', '--out-dir'):
            r = self.run_script(args=self.args(**{opt: None}))
            self.assertEqual(r.returncode, 2, opt)
            self.assertTrue(r.stderr.startswith('sign-apk.sh: '), opt)
            self.assertNotIn('refused', r.stderr)
            self.assertEqual(r.stdout, '')
        self.assertEqual(self.runs(), [])

    def test_unknown_options_and_stray_arguments_are_usage_errors(self):
        for extra in (['--bogus', 'x'], ['stray'], ['--password', 'abc']):
            r = self.run_script(args=self.args() + extra)
            self.assertEqual(r.returncode, 2, extra)
            self.assertEqual(self.runs(), [])

    def test_an_option_without_a_value_is_a_usage_error(self):
        for opt in ('--in', '--keystore', '--alias', '--pass-file', '--out-dir',
                    '--builder-image', '--apksigner', '--docker-bin'):
            r = self.run_script(args=self.args()[:0] + [opt])
            self.assertEqual(r.returncode, 2, opt)
        r = self.run_script(args=self.args() + ['--apksigner'])
        self.assertEqual(r.returncode, 2)

    def test_an_empty_option_value_is_a_usage_error(self):
        for opt in ('--in', '--keystore', '--pass-file', '--out-dir'):
            r = self.run_script(args=self.args(**{opt: ''}))
            self.assertEqual(r.returncode, 2, opt)

    def test_no_arguments_is_a_usage_error(self):
        r = self.run_script(args=[])
        self.assertEqual(r.returncode, 2)


if __name__ == '__main__':
    unittest.main()
