"""Unit tests for scripts/git-pull.sh.

Drives the script via subprocess against local bare git remotes built in
temp dirs (no network; the only remotes touched are the local ones and the
two deliberately unreachable ones). Every git call runs hermetic:
GIT_CONFIG_GLOBAL and GIT_CONFIG_SYSTEM point at missing paths and the
author env is fixed for the test's own commits. Every test is proven red
first (the mutant table is in the delivery audit).

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_git_pull.py'
"""
import os
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    '..', '..', '..', '..',
    'server', 'modules', 'deploy', 'scripts', 'git-pull.sh',
))


def hermetic_env(root):
    """Env for git and the script: no reachable global/system config, fixed
    author, no interactive prompts."""
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
    return env


def run_git(args, env, cwd=None):
    return subprocess.run(['git'] + list(args), env=env, cwd=cwd,
                          capture_output=True, text=True, timeout=60)


def run_script(args, env, cwd):
    return subprocess.run(['sh', SCRIPT] + list(args), env=env, cwd=cwd,
                          capture_output=True, text=True, timeout=60)


def make_repo(root, files, tags, env, annotated=(), name='bare.git'):
    """Build a bare remote in root: one commit per file content, the given
    tags on the last commit, pushed to the bare repo. Returns its path."""
    work = os.path.join(root, 'work')
    bare = os.path.join(root, name)
    assert run_git(['init', '-q', '--bare', '--initial-branch=main', bare], env).returncode == 0
    assert run_git(['init', '-q', '--initial-branch=main', work], env).returncode == 0
    for i, content in enumerate(files):
        with open(os.path.join(work, 'a.txt'), 'w') as fh:
            fh.write(content + '\n')
        assert run_git(['add', '-A'], env, cwd=work).returncode == 0
        assert run_git(['commit', '-qm', 'commit %d' % i], env, cwd=work).returncode == 0
    for t in tags:
        if t in annotated:
            assert run_git(['tag', '-a', t, '-m', 'release ' + t], env, cwd=work).returncode == 0
        else:
            assert run_git(['tag', t], env, cwd=work).returncode == 0
    assert run_git(['push', '-q', bare, 'HEAD:refs/heads/main'], env, cwd=work).returncode == 0
    for t in tags:
        assert run_git(['push', '-q', bare, 'refs/tags/' + t], env, cwd=work).returncode == 0
    return bare


def rev(repo, ref, env):
    r = run_git(['-C', repo, 'rev-parse', ref], env)
    assert r.returncode == 0, r.stderr
    return r.stdout.strip()


class GitPullTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = self._tmp.name
        self.env = hermetic_env(self.root)
        self.dest = os.path.join(self.root, 'dest')

    def tearDown(self):
        self._tmp.cleanup()

    def standard_bare(self):
        return make_repo(self.root, ['one', 'two', 'three'],
                         ['v1.0.0', 'v1.2.0', 'v1.10.0', '2.0.0-rc1', 'latest'],
                         self.env)

    def run_pull(self, extra=()):
        return run_script(['--remote', self.bare, '--dest', self.dest] + list(extra),
                          self.env, cwd=self.root)

    # --- the choice -------------------------------------------------------

    def test_default_checks_out_latest(self):
        self.bare = self.standard_bare()
        r = self.run_pull()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, 'v1.10.0\n')

    def test_previous_checks_out_second_latest(self):
        self.bare = self.standard_bare()
        r = self.run_pull(['--previous'])
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, 'v1.2.0\n')

    def test_named_tag_checks_out_named(self):
        self.bare = self.standard_bare()
        r = self.run_pull(['--tag', 'v1.2.0'])
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, 'v1.2.0\n')

    def test_named_tag_not_a_release_tag_refused(self):
        self.bare = self.standard_bare()
        r = self.run_pull(['--tag', '2.0.0-rc1'])
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertIn('not a release tag', r.stderr)
        self.assertFalse(os.path.exists(self.dest))

    def test_named_tag_absent_from_remote_exit_3(self):
        self.bare = self.standard_bare()
        r = self.run_pull(['--tag', 'v9.9.9'])
        self.assertEqual(r.returncode, 3, r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertFalse(os.path.exists(self.dest))

    def test_named_tag_must_match_the_whole_name(self):
        # the remote has v1.2.0 only: 1.2.0 is a release tag but not a tag of this remote
        self.bare = make_repo(self.root, ['one', 'two'], ['v1.2.0'], self.env)
        r = self.run_pull(['--tag', '1.2.0'])
        self.assertEqual(r.returncode, 3, r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertFalse(os.path.exists(self.dest))

    def test_named_tag_dots_are_not_wildcards(self):
        # 1.2.3 is a release tag; the remote has 1a2b3 only, which a regex dot would match
        self.bare = make_repo(self.root, ['one', 'two'], ['1a2b3', 'v1.0.0'], self.env)
        r = self.run_pull(['--tag', '1.2.3'])
        self.assertEqual(r.returncode, 3, r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertFalse(os.path.exists(self.dest))

    def test_no_release_tag_exit_3(self):
        self.bare = make_repo(self.root, ['one'], ['2.0.0-rc1', 'latest'], self.env)
        r = self.run_pull()
        self.assertEqual(r.returncode, 3, r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertFalse(os.path.exists(self.dest))

    def test_single_tag_previous_exit_3(self):
        self.bare = make_repo(self.root, ['one'], ['v1.0.0'], self.env)
        r = self.run_pull(['--previous'])
        self.assertEqual(r.returncode, 3, r.stderr)
        self.assertEqual(r.stdout, '')

    # --- the dest ---------------------------------------------------------

    def test_dest_nonempty_refused(self):
        self.bare = self.standard_bare()
        os.makedirs(self.dest)
        with open(os.path.join(self.dest, 'file.txt'), 'w') as fh:
            fh.write('x')
        r = self.run_pull()
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertTrue(os.path.isfile(os.path.join(self.dest, 'file.txt')))
        self.assertFalse(os.path.exists(os.path.join(self.dest, '.git')))
        hidden = os.path.join(self.root, 'dest2')
        os.makedirs(hidden)
        with open(os.path.join(hidden, '.hidden'), 'w') as fh:
            fh.write('x')
        r2 = run_script(['--remote', self.bare, '--dest', hidden],
                        self.env, cwd=self.root)
        self.assertEqual(r2.returncode, 2, r2.stderr)
        self.assertTrue(os.path.isfile(os.path.join(hidden, '.hidden')))
        self.assertFalse(os.path.exists(os.path.join(hidden, '.git')))

    def test_dest_is_a_file_refused(self):
        self.bare = self.standard_bare()
        with open(self.dest, 'w') as fh:
            fh.write('x')
        r = self.run_pull()
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertIn('not a directory', r.stderr)
        with open(self.dest) as fh:
            self.assertEqual(fh.read(), 'x')

    def test_dest_with_double_dot_name_refused(self):
        # a name that starts with two dots is matched by neither * nor .[!.]*
        self.bare = self.standard_bare()
        os.makedirs(self.dest)
        with open(os.path.join(self.dest, '..odd'), 'w') as fh:
            fh.write('x')
        r = self.run_pull()
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertTrue(os.path.isfile(os.path.join(self.dest, '..odd')))
        self.assertFalse(os.path.exists(os.path.join(self.dest, '.git')))

    def test_dest_existing_empty_used(self):
        self.bare = self.standard_bare()
        os.makedirs(self.dest)
        r = self.run_pull()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(os.path.isdir(os.path.join(self.dest, '.git')))

    def test_dest_created_by_script(self):
        self.bare = self.standard_bare()
        self.assertFalse(os.path.exists(self.dest))
        r = self.run_pull()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(os.path.isdir(self.dest))
        self.assertTrue(os.path.isdir(os.path.join(self.dest, '.git')))

    def test_nothing_written_outside_dest(self):
        self.bare = self.standard_bare()
        before = sorted(os.listdir(self.root))
        r = self.run_pull()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(sorted(os.listdir(self.root)), sorted(before + ['dest']))

    # --- failures: no leak, nothing removed --------------------------------

    def test_userinfo_never_echoed(self):
        remote = 'http://user:pass@example.invalid/repo.git'
        r = run_script(['--remote', remote, '--dest', self.dest],
                       self.env, cwd=self.root)
        self.assertEqual(r.returncode, 1)
        self.assertEqual(r.stdout, '')
        self.assertNotIn('user:pass', r.stdout + r.stderr)
        self.assertNotIn(remote, r.stdout + r.stderr)
        self.assertNotIn('fatal', r.stdout + r.stderr)
        self.assertFalse(os.path.exists(self.dest))

    def test_unreachable_remote_exit_1(self):
        missing = os.path.join(self.root, 'no', 'such', 'bare.git')
        r = run_script(['--remote', missing, '--dest', self.dest],
                       self.env, cwd=self.root)
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertIn('--remote', r.stderr)
        self.assertNotIn(missing, r.stdout + r.stderr)
        self.assertNotIn('fatal', r.stdout + r.stderr)
        self.assertFalse(os.path.exists(self.dest))

    def test_ext_remote_never_runs_its_command(self):
        # git refuses ext:: by default, so allow it through the environment: only the
        # script's own -c protocol.ext.allow=never then keeps the command from running
        marker = os.path.join(self.root, 'ext-ran')
        env = dict(self.env)
        env.update({'GIT_CONFIG_COUNT': '1',
                    'GIT_CONFIG_KEY_0': 'protocol.ext.allow',
                    'GIT_CONFIG_VALUE_0': 'always'})
        remote = 'ext::touch %s' % marker
        r = run_script(['--remote', remote, '--dest', self.dest], env, cwd=self.root)
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertFalse(os.path.exists(marker))
        self.assertFalse(os.path.exists(self.dest))

    def test_clone_failure_exit_1_and_no_remote_in_output(self):
        # the tag list works, the clone cannot create its dest (its parent is a file)
        self.bare = self.standard_bare()
        blocker = os.path.join(self.root, 'blocker')
        with open(blocker, 'w') as fh:
            fh.write('x')
        r = run_script(['--remote', self.bare, '--dest', os.path.join(blocker, 'dest')],
                       self.env, cwd=self.root)
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertIn('cannot clone', r.stderr)
        self.assertNotIn(self.bare, r.stdout + r.stderr)
        self.assertNotIn('fatal', r.stdout + r.stderr)

    def test_missing_release_tags_sibling_exit_1(self):
        self.bare = self.standard_bare()
        alone = os.path.join(self.root, 'alone')
        os.makedirs(alone)
        copy = os.path.join(alone, 'git-pull.sh')
        with open(SCRIPT) as src, open(copy, 'w') as out:
            out.write(src.read())
        r = subprocess.run(['sh', copy, '--remote', self.bare, '--dest', self.dest],
                           env=self.env, cwd=self.root, capture_output=True,
                           text=True, timeout=60)
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertIn('release-tags.sh not found', r.stderr)
        self.assertFalse(os.path.exists(self.dest))

    def test_partial_dest_left_on_failure(self):
        # tag on the MIDDLE commit, not the tip, and that commit's object removed from
        # the remote: depending on the git version either the clone itself fails (and git
        # removes a dest it made) or the clone finishes and the checkout fails. Both are
        # exit 1 with the remote unnamed; a dest that is left must hold no checked-out file.
        self.bare = make_repo(self.root, ['one', 'two', 'three'], [], self.env)
        work = os.path.join(self.root, 'work')
        assert run_git(['-C', work, 'push', '-q', self.bare,
                        'HEAD~1:refs/tags/v1.2.0'], self.env).returncode == 0
        commit = rev(self.bare, 'refs/tags/v1.2.0', self.env)
        obj = os.path.join(self.bare, 'objects', commit[:2], commit[2:])
        kept = os.path.join(self.root, 'kept', commit)
        os.makedirs(os.path.dirname(kept))
        os.rename(obj, kept)
        r = self.run_pull()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertIn('--dest', r.stderr)
        self.assertNotIn(self.bare, r.stdout + r.stderr)
        self.assertNotIn('fatal', r.stdout + r.stderr)
        if os.path.exists(self.dest):
            self.assertTrue(os.path.isdir(os.path.join(self.dest, '.git')))
            self.assertFalse(os.path.exists(os.path.join(self.dest, 'a.txt')))

    def test_checkout_failure_exit_1_and_dest_kept(self):
        # a git on PATH that passes every call through except checkout, which fails:
        # the clone has finished, so --dest holds .git and no checked-out file
        self.bare = self.standard_bare()
        real = shutil.which('git')
        shim_dir = os.path.join(self.root, 'shim')
        os.makedirs(shim_dir)
        shim = os.path.join(shim_dir, 'git')
        with open(shim, 'w') as fh:
            fh.write('#!/bin/sh\nfor a in "$@"; do\n  [ "$a" = checkout ] && exit 1\ndone\n'
                     'exec %s "$@"\n' % real)
        os.chmod(shim, 0o755)
        env = dict(self.env)
        env['PATH'] = shim_dir + os.pathsep + env.get('PATH', '')
        r = run_script(['--remote', self.bare, '--dest', self.dest], env, cwd=self.root)
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(r.stdout, '')
        self.assertIn('--dest', r.stderr)
        self.assertIn('cannot check out', r.stderr)
        self.assertNotIn('fatal', r.stdout + r.stderr)
        self.assertNotIn(self.bare, r.stdout + r.stderr)
        self.assertTrue(os.path.isdir(os.path.join(self.dest, '.git')))
        self.assertFalse(os.path.exists(os.path.join(self.dest, 'a.txt')))

    # --- usage -------------------------------------------------------------

    def test_usage_errors_exit_2(self):
        self.bare = self.standard_bare()
        cases = [
            [],
            ['--dest', self.dest],
            ['--remote', self.bare],
            ['--remote', self.bare, '--dest', self.dest, '--bogus'],
            ['--remote', self.bare, '--dest', self.dest, '--tag'],
            ['--remote', self.bare, '--dest', self.dest, '--tag', 'v1.0.0', '--previous'],
            ['--remote', self.bare, '--dest', self.dest, '--previous', '--tag', 'v1.0.0'],
            ['--remote', self.bare, '--dest', self.dest, 'positional'],
        ]
        for args in cases:
            r = run_script(args, self.env, cwd=self.root)
            self.assertEqual(r.returncode, 2, (args, r.stderr))
            self.assertEqual(r.stdout, '')
            self.assertFalse(os.path.exists(self.dest))

    # --- what a success looks like -----------------------------------------

    def test_tree_equals_tag_tree_and_detached(self):
        self.bare = self.standard_bare()
        r = self.run_pull()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(rev(self.dest, 'HEAD', self.env),
                         rev(self.bare, 'refs/tags/v1.10.0', self.env))
        self.assertEqual(rev(self.dest, 'HEAD^{tree}', self.env),
                         rev(self.bare, 'refs/tags/v1.10.0^{tree}', self.env))
        self.assertNotEqual(run_git(['-C', self.dest, 'symbolic-ref', '-q', 'HEAD'],
                                    self.env).returncode, 0)

    def test_annotated_tag_found_and_checked_out(self):
        self.bare = make_repo(self.root, ['one', 'two'], ['v1.2.0'],
                              self.env, annotated=('v1.2.0',))
        r = self.run_pull()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, 'v1.2.0\n')
        # refs/tags/... of an annotated tag is the tag object: peel to the commit
        self.assertEqual(rev(self.dest, 'HEAD', self.env),
                         rev(self.bare, 'refs/tags/v1.2.0^{commit}', self.env))
        self.assertEqual(rev(self.dest, 'HEAD^{tree}', self.env),
                         rev(self.bare, 'refs/tags/v1.2.0^{tree}', self.env))

    def test_lightweight_tag_found_and_checked_out(self):
        self.bare = make_repo(self.root, ['one', 'two'], ['v1.2.0'], self.env)
        r = self.run_pull()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, 'v1.2.0\n')
        self.assertEqual(rev(self.dest, 'HEAD', self.env),
                         rev(self.bare, 'refs/tags/v1.2.0', self.env))
        self.assertEqual(rev(self.dest, 'HEAD^{tree}', self.env),
                         rev(self.bare, 'refs/tags/v1.2.0^{tree}', self.env))

    def test_tag_wins_over_branch_of_same_name(self):
        self.bare = make_repo(self.root, ['one', 'two'], ['v1.2.0'], self.env)
        work = os.path.join(self.root, 'work')
        assert run_git(['-C', work, 'push', '-q', self.bare,
                        'HEAD~1:refs/heads/v1.2.0'], self.env).returncode == 0
        tag_commit = rev(self.bare, 'refs/tags/v1.2.0', self.env)
        branch_commit = rev(self.bare, 'refs/heads/v1.2.0', self.env)
        self.assertNotEqual(tag_commit, branch_commit)
        r = self.run_pull()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, 'v1.2.0\n')
        self.assertEqual(rev(self.dest, 'HEAD', self.env), tag_commit)
        self.assertNotEqual(rev(self.dest, 'HEAD', self.env), branch_commit)

    # --- script properties --------------------------------------------------

    def test_no_rm_in_script(self):
        import re
        with open(SCRIPT) as fh:
            text = fh.read()
        self.assertNotRegex(text, r'\brm\b')
        self.assertNotIn('sed -i', text)
        for line in text.splitlines():
            stripped = line.strip()
            if stripped.startswith('>') or ' > ' in stripped or ' >> ' in stripped:
                self.fail('write redirect found: %s' % stripped)
        self.assertIn('export GIT_TERMINAL_PROMPT=0', text)


if __name__ == '__main__':
    unittest.main()
