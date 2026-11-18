"""Unit tests for scripts/build-apk.sh.

No container, no Gradle: the script runs for real against a real local git
work tree built in a temp dir; the only stub is a fake `docker` on PATH that
records its argv (one argument per line, calls separated by a line of three
dashes) and, for the `run` call, creates the unsigned APK file(s) the real
build would leave. Every git call is hermetic (no global or system config).

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_build_apk.py'
"""
import hashlib
import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.normpath(os.path.join(
    HERE, '..', '..', '..', '..', 'server', 'modules', 'deploy', 'scripts'))
SCRIPT = os.path.join(SCRIPTS, 'build-apk.sh')
GIT_PULL = os.path.join(SCRIPTS, 'git-pull.sh')

IMAGE = 'breaker-android-builder:jdk25-api36-1'

GRADLE = ('android {\n    defaultConfig {\n'
          '        versionCode = 2\n'
          '        versionName = "0.0.0.1"\n'
          '    }\n}\n')

STUB = r'''#!/bin/sh
{ for a in "$@"; do printf '%s\n' "$a"; done; echo '---'; } >> "$STUB_ARGV_FILE"
if [ "$1" = run ]; then
    if [ -n "$STUB_FAIL" ]; then exit "$STUB_FAIL"; fi
    prev=""
    src=""
    for a in "$@"; do
        if [ "$prev" = "-v" ]; then
            case "$a" in *:/work) src="${a%:/work}" ;; esac
        fi
        prev="$a"
    done
    d="$src/android/app/build/outputs/apk/release"
    mkdir -p "$d"
    n="${STUB_APKS:-1}"
    i=1
    while [ "$i" -le "$n" ]; do
        printf 'APKBYTES%s' "$i" > "$d/app-release-$i-unsigned.apk"
        i=$((i + 1))
    done
fi
exit 0
'''


def hermetic_env(root):
    env = dict(os.environ)
    env.update({
        'GIT_CONFIG_GLOBAL': os.path.join(root, 'no-such-global.cfg'),
        'GIT_CONFIG_SYSTEM': os.path.join(root, 'no-such-system.cfg'),
        'GIT_AUTHOR_NAME': 'Test',
        'GIT_AUTHOR_EMAIL': 'test@example.invalid',
        'GIT_COMMITTER_NAME': 'Test',
        'GIT_COMMITTER_EMAIL': 'test@example.invalid',
        'GIT_TERMINAL_PROMPT': '0',
    })
    env.pop('DEPLOY_DOCKER', None)
    return env


def git(args, env, cwd=None):
    r = subprocess.run(['git'] + list(args), env=env, cwd=cwd,
                       capture_output=True, text=True, timeout=60)
    assert r.returncode == 0, r.stderr
    return r


def sha(path):
    with open(path, 'rb') as fh:
        return hashlib.sha256(fh.read()).hexdigest()


class BuildApkTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix='buildapk-')
        self.addCleanup(shutil.rmtree, self.root, True)
        self.env = hermetic_env(self.root)
        self.bin = os.path.join(self.root, 'bin')
        os.mkdir(self.bin)
        stub = os.path.join(self.bin, 'docker')
        with open(stub, 'w') as fh:
            fh.write(STUB)
        os.chmod(stub, 0o755)
        self.argv_file = os.path.join(self.root, 'argv.txt')
        self.env['PATH'] = self.bin + os.pathsep + self.env['PATH']
        self.env['STUB_ARGV_FILE'] = self.argv_file
        self.out = os.path.join(self.root, 'out')
        # a throwaway key list; the marker line must never show up anywhere
        self.marker = os.urandom(16).hex()
        self.keys = os.path.join(self.root, 'update-keys.txt')
        with open(self.keys, 'w') as fh:
            fh.write('MARKER' + self.marker + '\n')
        os.chmod(self.keys, 0o644)

    # helpers
    def make_src(self, gradle=GRADLE, tag='app-v0.0.0.1', name='src',
                 commit_gradle=True):
        src = os.path.join(self.root, name)
        os.makedirs(os.path.join(src, 'android', 'app'))
        git(['init', '-q', '--initial-branch=main', src], self.env)
        with open(os.path.join(src, '.gitignore'), 'w') as fh:
            fh.write('build/\n')
        with open(os.path.join(src, 'android', 'app', 'build.gradle.kts'), 'w') as fh:
            fh.write(gradle)
        git(['add', '-A'], self.env, cwd=src)
        git(['commit', '-qm', 'release'], self.env, cwd=src)
        if tag:
            git(['tag', tag], self.env, cwd=src)
        return src

    def run_script(self, args, env=None):
        return subprocess.run(['sh', SCRIPT] + list(args), env=env or self.env,
                              capture_output=True, text=True, timeout=60)

    def build(self, src, extra=(), env=None, keys=True):
        args = ['--src', src, '--out-dir', self.out]
        if keys:
            args += ['--update-keys', self.keys]
        return self.run_script(args + list(extra), env)

    def calls(self):
        if not os.path.exists(self.argv_file):
            return []
        with open(self.argv_file) as fh:
            text = fh.read()
        res = []
        cur = []
        for line in text.split('\n')[:-1]:
            if line == '---':
                res.append(cur)
                cur = []
            else:
                cur.append(line)
        return res

    def assert_refused(self, r, text=None):
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertTrue(r.stderr.startswith('build-apk.sh: refused: '), r.stderr)
        if text:
            self.assertIn(text, r.stderr)
        self.assertEqual(self.calls(), [], 'docker must not be called on a refusal')
        self.assertFalse(os.path.exists(self.out) and os.listdir(self.out))

    def expected_run(self, real, keys, memory='8g', cpus='4', pids='2048', image=IMAGE):
        return ['run', '--rm', '--user', '%d:%d' % (os.getuid(), os.getgid()),
                '--memory', memory, '--memory-swap', memory, '--cpus', cpus,
                '--cpu-shares', '2', '--pids-limit', pids,
                '-v', real + ':/work', '-v', keys + ':/keys/update-keys:ro',
                '-w', '/work', image,
                'nice', '-n', '19', './gradlew', '--no-daemon', '--max-workers=2',
                '-Dorg.gradle.jvmargs=-Xmx3g', '-Pkotlin.daemon.jvmargs=-Xmx2g',
                '-PbreakerUpdateKeysFile=/keys/update-keys',
                ':android:app:assembleRelease']

    # happy path
    def test_a_tagged_clean_tree_builds_and_prints_the_copied_path(self):
        src = self.make_src()
        r = self.build(src)
        self.assertEqual(r.returncode, 0, r.stderr)
        target = os.path.join(self.out, 'breaker-0.0.0.1-unsigned.apk')
        self.assertEqual(r.stdout, target + '\n')
        with open(target, 'rb') as fh:
            self.assertEqual(fh.read(), b'APKBYTES1')
        self.assertEqual(stat.S_IMODE(os.stat(target).st_mode), 0o644)

    def test_the_run_argv_is_pinned_exactly(self):
        src = self.make_src()
        real = os.path.realpath(src)
        r = self.build(src)
        self.assertEqual(r.returncode, 0, r.stderr)
        expected = self.expected_run(real, os.path.realpath(self.keys))
        self.assertEqual(self.calls(), [expected])

    def test_the_run_argv_has_two_mounts_only_and_no_keystore_or_no_cache(self):
        src = self.make_src()
        self.build(src)
        argv = self.calls()[0]
        self.assertEqual(argv.count('-v'), 2)
        mounts = [argv[i + 1] for i, a in enumerate(argv) if a == '-v']
        self.assertEqual(mounts, [os.path.realpath(src) + ':/work',
                                  self.keys + ':/keys/update-keys:ro'])
        self.assertNotIn('--no-cache', argv)
        self.assertNotIn('--user', argv[argv.index('--user') + 2:])
        self.assertIn('--user', argv)
        for a in argv:
            self.assertNotIn('keystore', a.lower())
            self.assertNotIn('password', a.lower())

    def test_the_builder_image_argument_overrides_the_default(self):
        src = self.make_src()
        self.build(src, ['--builder-image', 'other:1'])
        argv = self.calls()[0]
        self.assertIn('other:1', argv)
        self.assertNotIn(IMAGE, argv)

    def test_the_docker_bin_argument_and_the_env_name_are_used(self):
        src = self.make_src()
        alt = os.path.join(self.bin, 'mydock')
        shutil.copy(os.path.join(self.bin, 'docker'), alt)
        os.chmod(alt, 0o755)
        env = dict(self.env)
        env['PATH'] = self.bin  # no `docker` on this path name check below
        os.unlink(os.path.join(self.bin, 'docker'))
        env['PATH'] = self.bin + os.pathsep + '/usr/bin' + os.pathsep + '/bin'
        r = self.build(src, ['--docker-bin', 'mydock'], env)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(len(self.calls()), 1)
        out2 = os.path.join(self.root, 'out2')
        env2 = dict(env)
        env2['DEPLOY_DOCKER'] = 'mydock'
        r = self.run_script(['--src', src, '--out-dir', out2, '--update-keys', self.keys], env2)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(len(self.calls()), 2)

    def test_rebuild_image_runs_a_pinned_docker_build_before_the_run(self):
        src = self.make_src()
        recipe = os.path.join(self.root, 'recipe')
        os.mkdir(recipe)
        r = self.build(src, ['--rebuild-image', recipe])
        self.assertEqual(r.returncode, 0, r.stderr)
        calls = self.calls()
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0], ['build', '--no-cache', '-t', IMAGE, recipe])
        self.assertEqual(calls[1][0], 'run')
        self.assertNotIn('--no-cache', calls[1])

    def test_without_rebuild_image_no_docker_build_is_made(self):
        src = self.make_src()
        self.build(src)
        self.assertEqual([c[0] for c in self.calls()], ['run'])

    # tag refusals
    def test_an_untagged_head_is_refused(self):
        src = self.make_src(tag=None)
        self.assert_refused(self.build(src), 'no release tag')

    def test_a_tag_not_in_app_form_is_refused(self):
        for i, tag in enumerate(['v1.2.3', 'app-v1.2.3', 'app-v1.2.3.4-rc',
                                 'app-v1.2.3.4.5', 'app-v1.2.3.x', 'x-app-v1.2.3.4',
                                 'app-v1.2.3.4a', '1.2.3']):
            with self.subTest(tag=tag):
                src = self.make_src(tag=tag, name='src%d' % i)
                r = self.build(src)
                self.assert_refused(r, 'not of the form app-vA.B.C.D')
                self.assertNotIn(tag, r.stderr)

    def test_a_four_part_tag_other_than_the_first_is_accepted(self):
        src = self.make_src(gradle=GRADLE.replace('0.0.0.1', '12.0.3.40'),
                            tag='app-v12.0.3.40')
        r = self.build(src)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, os.path.join(self.out, 'breaker-12.0.3.40-unsigned.apk') + '\n')

    # tree state
    def test_a_dirty_tracked_change_is_refused(self):
        src = self.make_src()
        with open(os.path.join(src, '.gitignore'), 'a') as fh:
            fh.write('x\n')
        self.assert_refused(self.build(src), 'not clean')

    def test_an_untracked_file_is_refused(self):
        src = self.make_src()
        with open(os.path.join(src, 'stray.txt'), 'w') as fh:
            fh.write('x')
        self.assert_refused(self.build(src), 'not clean')

    def test_a_directory_that_is_not_a_work_tree_is_refused(self):
        d = os.path.join(self.root, 'plain')
        os.mkdir(d)
        self.assert_refused(self.build(d))

    def test_a_missing_src_is_refused(self):
        self.assert_refused(self.build(os.path.join(self.root, 'nope')))

    # literals
    def test_a_version_name_that_differs_from_the_tag_is_refused(self):
        src = self.make_src(gradle=GRADLE.replace('"0.0.0.1"', '"0.0.0.2"'))
        self.assert_refused(self.build(src), 'does not match the tag')

    def test_a_missing_version_code_is_refused(self):
        src = self.make_src(gradle=GRADLE.replace('        versionCode = 2\n', ''))
        self.assert_refused(self.build(src), 'versionCode')

    def test_a_version_code_assigned_twice_is_refused(self):
        src = self.make_src(gradle=GRADLE.replace(
            '        versionCode = 2\n', '        versionCode = 2\n        versionCode = 3\n'))
        self.assert_refused(self.build(src), 'versionCode')

    def test_a_bad_version_code_value_is_refused(self):
        for i, bad in enumerate(['0', '007', '"5"', '-3', '2.5', '1L', '']):
            with self.subTest(value=bad):
                src = self.make_src(gradle=GRADLE.replace('versionCode = 2', 'versionCode = ' + bad),
                                    name='bc%d' % i)
                self.assert_refused(self.build(src), 'versionCode')

    def test_a_good_version_code_of_one_is_accepted(self):
        src = self.make_src(gradle=GRADLE.replace('versionCode = 2', 'versionCode = 1'))
        r = self.build(src)
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_a_missing_or_doubled_version_name_is_refused(self):
        src = self.make_src(gradle=GRADLE.replace('        versionName = "0.0.0.1"\n', ''), name='a')
        self.assert_refused(self.build(src), 'versionName')
        src = self.make_src(gradle=GRADLE.replace(
            '        versionName = "0.0.0.1"\n',
            '        versionName = "0.0.0.1"\n        versionName = "0.0.0.1"\n'), name='b')
        self.assert_refused(self.build(src), 'versionName')

    def test_a_computed_version_name_is_refused(self):
        src = self.make_src(gradle=GRADLE.replace('"0.0.0.1"', 'computeName()'))
        self.assert_refused(self.build(src), 'versionName')

    def test_a_missing_build_file_is_refused(self):
        src = self.make_src()
        git(['rm', '-q', 'android/app/build.gradle.kts'], self.env, cwd=src)
        git(['commit', '-qm', 'drop'], self.env, cwd=src)
        git(['tag', '-f', 'app-v0.0.0.1'], self.env, cwd=src)
        self.assert_refused(self.build(src), 'missing')

    # build outcome
    def test_zero_apks_produced_is_refused(self):
        src = self.make_src()
        env = dict(self.env)
        env['STUB_APKS'] = '0'
        r = self.build(src, env=env)
        self.assertEqual(r.returncode, 1)
        self.assertIn('refused: no unsigned APK was produced', r.stderr)
        self.assertFalse(os.path.exists(self.out) and os.listdir(self.out))

    def test_two_apks_produced_is_refused(self):
        src = self.make_src()
        env = dict(self.env)
        env['STUB_APKS'] = '2'
        r = self.build(src, env=env)
        self.assertEqual(r.returncode, 1)
        self.assertIn('refused: more than one unsigned APK was produced', r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertFalse(os.path.exists(self.out) and os.listdir(self.out))

    def test_a_failing_docker_gives_exit_1_and_no_output_file(self):
        src = self.make_src()
        env = dict(self.env)
        env['STUB_FAIL'] = '7'
        r = self.build(src, env=env)
        self.assertEqual(r.returncode, 1)
        self.assertIn('refused: the build failed', r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertFalse(os.path.exists(self.out) and os.listdir(self.out))

    def test_an_existing_output_target_is_refused_and_left_alone(self):
        src = self.make_src()
        os.makedirs(self.out)
        target = os.path.join(self.out, 'breaker-0.0.0.1-unsigned.apk')
        with open(target, 'wb') as fh:
            fh.write(b'OLD')
        r = self.build(src)
        self.assertEqual(r.returncode, 1)
        self.assertIn('already exists', r.stderr)
        with open(target, 'rb') as fh:
            self.assertEqual(fh.read(), b'OLD')
        self.assertEqual(self.calls(), [])

    def test_the_source_tree_is_unchanged_after_a_run(self):
        src = self.make_src()
        gradle = os.path.join(src, 'android', 'app', 'build.gradle.kts')
        before = sha(gradle)
        r = self.build(src)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(sha(gradle), before)
        self.assertEqual(git(['status', '--porcelain'], self.env, cwd=src).stdout, '')

    def test_the_copy_is_readable_under_a_restrictive_caller_umask(self):
        src = self.make_src()
        old = os.umask(0o077)
        try:
            r = self.build(src)
        finally:
            os.umask(old)
        self.assertEqual(r.returncode, 0, r.stderr)
        mode = stat.S_IMODE(os.stat(os.path.join(self.out, 'breaker-0.0.0.1-unsigned.apk')).st_mode)
        self.assertEqual(mode, 0o644)
        # the build tree and the output directory are made under umask 022
        apkdir = os.path.join(src, 'android', 'app', 'build', 'outputs', 'apk', 'release')
        self.assertEqual(stat.S_IMODE(os.stat(apkdir).st_mode), 0o755)
        self.assertEqual(stat.S_IMODE(os.stat(self.out).st_mode), 0o755)

    # usage
    def test_usage_errors_exit_2(self):
        src = self.make_src()
        cases = [[], ['--src', src], ['--out-dir', self.out],
                 ['--src'], ['--src', src, '--out-dir'],
                 ['--src', src, '--out-dir', self.out, '--bogus'],
                 ['--src', src, '--out-dir', self.out, '--builder-image', ''],
                 ['--src', src, '--out-dir', self.out, '--docker-bin', ''],
                 ['--src', src, '--out-dir', self.out, '--rebuild-image'],
                 ['--src', src, '--out-dir', self.out, '--rebuild-image', ''],
                 ['--src', '', '--out-dir', self.out],
                 ['--src', src, '--out-dir', self.out, '--update-keys'],
                 ['--src', src, '--out-dir', self.out, '--update-keys', ''],
                 ['--src', src, '--out-dir', self.out, '--update-keys', self.keys, '--memory'],
                 ['--src', src, '--out-dir', self.out, '--update-keys', self.keys, '--cpus'],
                 ['--src', src, '--out-dir', self.out, '--update-keys', self.keys, '--pids-limit']]
        for args in cases:
            with self.subTest(args=args):
                r = self.run_script(args)
                self.assertEqual(r.returncode, 2, r.stderr)
                self.assertTrue(r.stderr.startswith('build-apk.sh: '))
                self.assertNotIn('refused', r.stderr)
                self.assertEqual(r.stdout, '')
        self.assertEqual(self.calls(), [])

    # header and file facts
    def test_the_header_states_the_box_go_and_the_quiet_window(self):
        with open(SCRIPT) as fh:
            text = fh.read()
        self.assertIn('HEAVY JOB', text)
        self.assertIn('quiet-box go (BOX GO)', text)
        self.assertIn('never run in the\n# 09:55-10:20Z window', text)
        self.assertIn('NEVER mounted', text)
        self.assertIn('NOT VERIFIED on a real build', text)

    def test_the_header_states_the_caps_the_flags_and_the_key_list_rules(self):
        with open(SCRIPT) as fh:
            text = fh.read()
        head = text.split('\numask 022')[0]
        flat = re.sub(r'\n# ?', ' ', head)
        for needle in ('--memory 8g --memory-swap 8g --cpus 4 --cpu-shares 2 --pids-limit 2048',
                       'nice -n 19 ./gradlew --no-daemon --max-workers=2',
                       '-Dorg.gradle.jvmargs=-Xmx3g', '-Pkotlin.daemon.jvmargs=-Xmx2g',
                       "needs the gate owner's quiet-box go (BOX GO)",
                       'never run in the 09:55-10:20Z window',
                       '--update-keys FILE is REQUIRED', 'READ-ONLY', '/keys/update-keys',
                       '-PbreakerUpdateKeysFile=/keys/update-keys',
                       'usage error (exit 2)', 'never copied'):
            self.assertIn(needle, flat)
        self.assertIn('NEVER mounted', text)

    # the owner's resource caps and Gradle flags
    def test_the_caps_are_in_the_exact_order_with_the_defaults(self):
        src = self.make_src()
        self.build(src)
        argv = self.calls()[0]
        i = argv.index('--memory')
        self.assertEqual(argv[i:i + 10], ['--memory', '8g', '--memory-swap', '8g', '--cpus', '4',
                                          '--cpu-shares', '2', '--pids-limit', '2048'])
        self.assertLess(argv.index('--user'), i)
        self.assertLess(i + 9, argv.index('-v'))

    def test_nice_is_the_first_word_of_the_container_command(self):
        src = self.make_src()
        self.build(src)
        argv = self.calls()[0]
        cmd = argv[argv.index(IMAGE) + 1:]
        self.assertEqual(cmd[:3], ['nice', '-n', '19'])
        self.assertEqual(cmd[3], './gradlew')

    def test_the_gradle_flags_and_the_key_property_are_present_exactly(self):
        src = self.make_src()
        self.build(src)
        argv = self.calls()[0]
        cmd = argv[argv.index(IMAGE) + 1:]
        self.assertEqual(cmd[3:], ['./gradlew', '--no-daemon', '--max-workers=2',
                                   '-Dorg.gradle.jvmargs=-Xmx3g', '-Pkotlin.daemon.jvmargs=-Xmx2g',
                                   '-PbreakerUpdateKeysFile=/keys/update-keys',
                                   ':android:app:assembleRelease'])
        self.assertEqual(argv.count('-PbreakerUpdateKeysFile=/keys/update-keys'), 1)

    def test_each_override_changes_only_its_own_flag(self):
        src = self.make_src()
        real = os.path.realpath(src)
        keys = os.path.realpath(self.keys)
        cases = [(['--memory', '6g'], dict(memory='6g')),
                 (['--memory', '512m'], dict(memory='512m')),
                 (['--cpus', '2'], dict(cpus='2')),
                 (['--cpus', '16'], dict(cpus='16')),
                 (['--pids-limit', '99999'], dict(pids='99999')),
                 (['--pids-limit', '1'], dict(pids='1')),
                 (['--memory', '3g', '--cpus', '3', '--pids-limit', '500'],
                  dict(memory='3g', cpus='3', pids='500'))]
        for i, (extra, kw) in enumerate(cases):
            with self.subTest(extra=extra):
                self.out = os.path.join(self.root, 'out%d' % i)
                r = self.build(src, extra)
                self.assertEqual(r.returncode, 0, r.stderr)
                self.assertEqual(self.calls()[-1], self.expected_run(real, keys, **kw))
        self.assertEqual(len(self.calls()), len(cases))

    def test_bad_cap_values_are_refused_with_exit_2_and_no_docker_call(self):
        src = self.make_src()
        bad = {'--memory': ['0g', '08g', '8', '8G', '8gb', 'g', '-8g', '8 g', '8g8', '1.5g', ''],
               '--cpus': ['0', '00', '100', '04', '-1', '2.5', 'x', '1 ', ''],
               '--pids-limit': ['0', '100000', '0100', '-5', '1e3', 'x', '']}
        for opt, values in bad.items():
            for v in values:
                with self.subTest(opt=opt, value=v):
                    r = self.build(src, [opt, v])
                    self.assertEqual(r.returncode, 2, r.stderr)
                    self.assertTrue(r.stderr.startswith('build-apk.sh: '), r.stderr)
                    self.assertNotIn('refused', r.stderr)
                    self.assertEqual(r.stdout, '')
        self.assertEqual(self.calls(), [])

    def test_the_rebuild_image_build_argv_has_no_caps_or_key_mount(self):
        src = self.make_src()
        recipe = os.path.join(self.root, 'recipe')
        os.mkdir(recipe)
        self.build(src, ['--memory', '6g', '--rebuild-image', recipe])
        self.assertEqual(self.calls()[0], ['build', '--no-cache', '-t', IMAGE, recipe])

    # the update-key list
    def test_a_missing_update_keys_option_is_a_usage_error_and_no_docker_call(self):
        src = self.make_src()
        r = self.build(src, keys=False)
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertEqual(r.stderr, 'build-apk.sh: --update-keys is required\n')
        self.assertEqual(r.stdout, '')
        self.assertFalse(os.path.exists(self.argv_file))
        self.assertFalse(os.path.exists(self.out))

    def test_the_key_list_is_mounted_read_only_and_is_the_only_extra_mount(self):
        src = self.make_src()
        self.build(src)
        argv = self.calls()[0]
        self.assertIn(self.keys + ':/keys/update-keys:ro', argv)
        self.assertEqual(argv.count('-v'), 2)
        self.assertEqual([a for a in argv if ':/keys' in a], [self.keys + ':/keys/update-keys:ro'])

    def test_a_bad_key_list_is_refused_before_any_docker_call(self):
        src = self.make_src()
        link = os.path.join(self.root, 'link-keys')
        os.symlink(self.keys, link)
        empty = os.path.join(self.root, 'empty-keys')
        open(empty, 'w').close()
        adir = os.path.join(self.root, 'keys-dir')
        os.mkdir(adir)
        colon = os.path.join(self.root, 'a:b-keys')
        comma = os.path.join(self.root, 'a,b-keys')
        nlf = os.path.join(self.root, 'a\nb-keys')
        for f in (colon, comma, nlf):
            with open(f, 'w') as fh:
                fh.write('MARKER' + self.marker + '\n')
        cases = [('missing', os.path.join(self.root, 'nope'), 'not a regular file'),
                 ('directory', adir, 'not a regular file'),
                 ('symlink', link, 'symlink'),
                 ('empty', empty, 'empty'),
                 ('colon', colon, 'colon'),
                 ('comma', comma, 'comma'),
                 ('newline', nlf, 'newline')]
        for name, path, text in cases:
            with self.subTest(case=name):
                r = self.run_script(['--src', src, '--out-dir', self.out, '--update-keys', path])
                self.assert_refused(r, text)
                self.assertNotIn(path, r.stderr)
                self.assertNotIn(self.marker, r.stderr)
        self.assertFalse(os.path.exists(self.argv_file))

    def test_a_relative_key_path_is_made_absolute_in_the_mount(self):
        src = self.make_src()
        r = subprocess.run(['sh', SCRIPT, '--src', src, '--out-dir', self.out,
                            '--update-keys', 'update-keys.txt'],
                           env=self.env, cwd=self.root, capture_output=True, text=True, timeout=60)
        self.assertEqual(r.returncode, 0, r.stderr)
        mounts = [a for a in self.calls()[0] if a.endswith(':/keys/update-keys:ro')]
        self.assertEqual(mounts, [self.root + '/update-keys.txt:/keys/update-keys:ro'])
        self.assertTrue(mounts[0].startswith('/'))

    def test_no_key_text_reaches_any_argv_stream_or_the_output_dir(self):
        src = self.make_src()
        r = self.build(src)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn(self.marker, r.stdout)
        self.assertNotIn(self.marker, r.stderr)
        with open(self.argv_file) as fh:
            self.assertNotIn(self.marker, fh.read())
        n = 0
        for d, _dirs, files in os.walk(self.out):
            for f in files:
                n += 1
                with open(os.path.join(d, f), 'rb') as fh:
                    self.assertNotIn(self.marker.encode(), fh.read())
        self.assertEqual(n, 1)
        self.assertEqual(os.listdir(self.out), ['breaker-0.0.0.1-unsigned.apk'])

    def test_the_key_file_is_unchanged_after_a_run(self):
        src = self.make_src()
        before = sha(self.keys)
        st = os.stat(self.keys)
        r = self.build(src)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(sha(self.keys), before)
        after = os.stat(self.keys)
        self.assertEqual((after.st_mode, after.st_mtime_ns), (st.st_mode, st.st_mtime_ns))
        self.assertEqual(git(['status', '--porcelain'], self.env, cwd=src).stdout, '')

    def test_the_script_is_executable_posix_sh_and_ascii(self):
        self.assertTrue(os.access(SCRIPT, os.X_OK))
        with open(SCRIPT, 'rb') as fh:
            data = fh.read()
        data.decode('ascii')
        self.assertTrue(data.startswith(b'#!/usr/bin/env sh\n'))
        r = subprocess.run(['sh', '-n', SCRIPT], capture_output=True, text=True, timeout=30)
        self.assertEqual(r.returncode, 0, r.stderr)

    # the owner's way of making a directory, and its documented limit
    def test_a_git_pull_checkout_with_a_plain_tag_is_refused_by_the_build(self):
        work = self.make_src(tag='1.2.3', name='seedwork')
        bare = os.path.join(self.root, 'bare.git')
        git(['init', '-q', '--bare', '--initial-branch=main', bare], self.env)
        git(['push', '-q', bare, 'HEAD:refs/heads/main'], self.env, cwd=work)
        git(['push', '-q', bare, 'refs/tags/1.2.3'], self.env, cwd=work)
        dest = os.path.join(self.root, 'checkout')
        r = subprocess.run(['sh', GIT_PULL, '--remote', bare, '--dest', dest, '--tag', '1.2.3'],
                           env=self.env, capture_output=True, text=True, timeout=60)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(os.path.isfile(os.path.join(dest, 'android', 'app', 'build.gradle.kts')))
        self.assert_refused(self.build(dest), 'not of the form app-vA.B.C.D')


if __name__ == '__main__':
    unittest.main()
