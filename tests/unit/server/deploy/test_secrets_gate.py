"""Pins for the deploy secrets gate: .gitignore, the tracked files and the CI secret scan.

Every test READS the tracked files (`git ls-files` when .git exists, else a walk that skips
.git); nothing is built or run. The guards each return their own message, and each has a
control that fails with ONLY that guard removed. A control never creates a real key file in
the tree: the fake inputs are in-memory lists or text built from a real file with ONE edit.

What this gate does NOT do: it cannot see a secret VALUE inside an ordinary file (the CI
gitleaks job does that), and it does not look at git history.
"""
import os
import re
import subprocess
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..', '..'))
GITIGNORE_REL = '.gitignore'
GITLEAKS_REL = '.github/gitleaks.toml'
WORKFLOW_REL = '.github/workflows/checks.yml'

# A1: every one of these is a whole line of .gitignore.
GITIGNORE_NAMES = ['.env', '*.pem', '*.key', '*.crt', '*.p12', '*.jks', '*.keystore', 'secrets/']

# Guard (a): secret-looking tracked basenames. `.env.example` IS allowed.
SECRET_EXTENSIONS = ('.pem', '.key', '.p12', '.jks', '.keystore')

# Guard (b): the private-key block header, built from PIECES so this file never
# contains the literal header.
_BEGIN = "BEGIN"
_WORD_RUN = r"[A-Z0-9 ]*"
_PRIVATE = "PRIVATE KEY"
PRIVATE_KEY_RE = re.compile(_BEGIN + _WORD_RUN + _PRIVATE)

# Guard (c): the Java keystore magic bytes.
KEYSTORE_MAGIC = b'\xfe\xed\xfe\xed'

# Guard (d): a paths allowlist entry must not match a key-material path.
KEY_MATERIAL_PATHS = (
    'server/modules/deploy/secrets/sync-api-service-key',
    'server/modules/deploy/signing/release.jks',
    'server/modules/deploy/keys/server.keystore',
    'server/modules/deploy/keys/server.pem',
    'server/modules/deploy/keys/server.key',
    'server/modules/deploy/keys/server.p12',
)
PATHS_ENTRY_RE = re.compile(r"paths\s*=\s*\['''([^']*)'''\]")
WORKFLOW_JOB_RE = re.compile(r'(?m)^  secrets:\s*$')


def read_text(rel):
    with open(os.path.join(REPO_ROOT, rel), 'r', encoding='utf-8') as handle:
        return handle.read()


def read_bytes(rel):
    with open(os.path.join(REPO_ROOT, rel), 'rb') as handle:
        return handle.read()


def tracked_files():
    """The tracked files, repo-relative and posix-separated."""
    if os.path.isdir(os.path.join(REPO_ROOT, '.git')):
        done = subprocess.run(['git', 'ls-files'], cwd=REPO_ROOT,
                              capture_output=True, text=True, check=True)
        return [line for line in done.stdout.split('\n') if line]
    found = []
    for dirpath, dirnames, filenames in os.walk(REPO_ROOT):
        dirnames[:] = [name for name in dirnames if name != '.git']
        for name in filenames:
            full = os.path.join(dirpath, name)
            found.append(os.path.relpath(full, REPO_ROOT).replace(os.sep, '/'))
    return sorted(found)


def tracked_texts():
    """(rel, text) for every tracked file that reads as UTF-8 text (no NUL)."""
    out = []
    for rel in tracked_files():
        raw = read_bytes(rel)
        if b'\x00' in raw:
            continue
        try:
            out.append((rel, raw.decode('utf-8')))
        except UnicodeDecodeError:
            continue
    return out


def tracked_heads():
    """(rel, first bytes) for every tracked file."""
    out = []
    for rel in tracked_files():
        with open(os.path.join(REPO_ROOT, rel), 'rb') as handle:
            out.append((rel, handle.read(8)))
    return out


def gitignore_problem(text):
    """A1: every secret name is a whole line of .gitignore."""
    lines = text.split('\n')
    for name in GITIGNORE_NAMES:
        if name not in lines:
            return 'gitignore: %s is not a whole line' % name
    return None


def basename_problem(files):
    """(a) no tracked file has a secret-looking basename."""
    for rel in files:
        base = rel.rsplit('/', 1)[-1]
        if base == '.env':
            return 'secret-looking tracked basename: %s' % rel
        if base.endswith(SECRET_EXTENSIONS):
            return 'secret-looking tracked basename: %s' % rel
        if 'secrets' in rel.split('/')[:-1]:
            return 'secret-looking tracked secrets directory: %s' % rel
    return None


def private_key_problem(texts):
    """(b) no tracked text file holds a private-key block header."""
    for rel, text in texts:
        if PRIVATE_KEY_RE.search(text):
            return 'private-key block header in tracked text: %s' % rel
    return None


def keystore_magic_problem(files):
    """(c) no tracked file starts with the Java keystore magic bytes."""
    for rel, head in files:
        if head[:4] == KEYSTORE_MAGIC:
            return 'java keystore magic bytes at the start of a tracked file: %s' % rel
    return None


def gitleaks_problem(toml_text, workflow_text):
    """(d) the CI secret scan still extends the defaults and runs, and no
    allowlist paths entry matches a key-material path."""
    if 'useDefault = true' not in toml_text:
        return 'gitleaks: useDefault = true is missing from .github/gitleaks.toml'
    for entry in PATHS_ENTRY_RE.findall(toml_text):
        try:
            pattern = re.compile(entry)
        except re.error:
            return 'gitleaks: allowlist paths entry is not a valid regex: %s' % entry
        for path in KEY_MATERIAL_PATHS:
            if pattern.search(path):
                return ('gitleaks: allowlist paths entry %s matches the key-material path %s'
                        % (entry, path))
    if not WORKFLOW_JOB_RE.search(workflow_text):
        return 'checks.yml: the secrets: job key is missing'
    if 'name: Secret scan' not in workflow_text:
        return 'checks.yml: the Secret scan job name is missing'
    return None


class GitignorePin(unittest.TestCase):
    """A1: the .gitignore whole-line pin, with a control per name."""

    def setUp(self):
        self.real = read_text(GITIGNORE_REL)

    def test_every_secret_name_is_a_whole_line(self):
        self.assertIsNone(gitignore_problem(self.real), gitignore_problem(self.real))

    def test_a_missing_name_fails_the_pin(self):
        for name in GITIGNORE_NAMES:
            fake = self.real.replace(name + '\n', '', 1)
            self.assertNotEqual(fake, self.real, name)
            message = gitignore_problem(fake)
            self.assertTrue(message, name)
            self.assertIn(name, message)


class BasenameGuard(unittest.TestCase):
    """(a) no tracked file has a secret-looking basename."""

    def setUp(self):
        self.real = tracked_files()

    def test_no_tracked_file_has_a_secret_basename(self):
        self.assertIsNone(basename_problem(self.real), basename_problem(self.real))

    def test_env_example_is_allowed(self):
        self.assertIsNone(basename_problem(['server/modules/deploy/.env.example']))

    def test_a_tracked_env_file_fails_the_guard(self):
        fake = ['server/modules/deploy/.env' if rel == 'server/modules/deploy/.env.example' else rel
                for rel in self.real]
        self.assertNotEqual(fake, self.real)
        message = basename_problem(fake)
        self.assertTrue(message)
        self.assertIn('secret-looking', message)

    def test_a_tracked_pem_fails_the_guard(self):
        fake = ['server/modules/deploy/keys/server.pem' if rel == 'server/modules/deploy/.env.example' else rel
                for rel in self.real]
        self.assertNotEqual(fake, self.real)
        message = basename_problem(fake)
        self.assertTrue(message)
        self.assertIn('secret-looking', message)

    def test_a_tracked_jks_fails_the_guard(self):
        fake = ['server/modules/deploy/signing/release.jks' if rel == 'server/modules/deploy/.env.example' else rel
                for rel in self.real]
        self.assertNotEqual(fake, self.real)
        message = basename_problem(fake)
        self.assertTrue(message)
        self.assertIn('secret-looking', message)

    def test_a_tracked_keystore_fails_the_guard(self):
        fake = ['server/modules/deploy/keys/server.keystore' if rel == 'server/modules/deploy/.env.example' else rel
                for rel in self.real]
        self.assertNotEqual(fake, self.real)
        message = basename_problem(fake)
        self.assertTrue(message)
        self.assertIn('secret-looking', message)

    def test_a_tracked_secrets_directory_fails_the_guard(self):
        fake = ['server/modules/deploy/secrets/sync-api-service-key'
                if rel == 'server/modules/deploy/.env.example' else rel
                for rel in self.real]
        self.assertNotEqual(fake, self.real)
        message = basename_problem(fake)
        self.assertTrue(message)
        self.assertIn('secret-looking', message)


class PrivateKeyGuard(unittest.TestCase):
    """(b) no tracked text file holds a private-key block header."""

    def setUp(self):
        self.real = tracked_texts()

    def test_no_tracked_text_file_holds_a_private_key_header(self):
        self.assertIsNone(private_key_problem(self.real), private_key_problem(self.real))

    def test_a_header_fails_the_guard(self):
        rel = 'server/modules/deploy/.env.example'
        text = dict(self.real)[rel]
        header = _BEGIN + ' RSA ' + _PRIVATE
        fake = text + '\n' + header + '\n'
        self.assertNotEqual(fake, text)
        message = private_key_problem([(rel, fake)])
        self.assertTrue(message)
        self.assertIn('private-key', message)


class KeystoreMagicGuard(unittest.TestCase):
    """(c) no tracked file starts with the Java keystore magic bytes."""

    def setUp(self):
        self.real = tracked_heads()

    def test_no_tracked_file_starts_with_the_keystore_magic(self):
        self.assertIsNone(keystore_magic_problem(self.real), keystore_magic_problem(self.real))

    def test_the_magic_fails_the_guard(self):
        rel = 'server/modules/deploy/.env.example'
        real_head = dict(self.real)[rel]
        fake_head = KEYSTORE_MAGIC + real_head[4:]
        self.assertNotEqual(fake_head, real_head)
        message = keystore_magic_problem([(rel, fake_head)])
        self.assertTrue(message)
        self.assertIn('keystore', message)


class GitleaksGuard(unittest.TestCase):
    """(d) the CI secret scan config and job are intact."""

    def setUp(self):
        self.real_toml = read_text(GITLEAKS_REL)
        self.real_workflow = read_text(WORKFLOW_REL)

    def test_the_ci_secret_scan_is_intact(self):
        self.assertIsNone(gitleaks_problem(self.real_toml, self.real_workflow),
                          gitleaks_problem(self.real_toml, self.real_workflow))

    def test_a_dropped_default_fails_the_guard(self):
        fake = self.real_toml.replace('useDefault = true', 'useDefault = false', 1)
        self.assertNotEqual(fake, self.real_toml)
        message = gitleaks_problem(fake, self.real_workflow)
        self.assertTrue(message)
        self.assertIn('useDefault', message)

    def test_a_keystore_allowlist_fails_the_guard(self):
        fake = self.real_toml.replace(
            "paths = ['''shared/modules/web-libs/libsodium/libsodium-wrappers\\.mjs''']",
            "paths = ['''server/modules/deploy/keys/.*\\.keystore''']", 1)
        self.assertNotEqual(fake, self.real_toml)
        message = gitleaks_problem(fake, self.real_workflow)
        self.assertTrue(message)
        self.assertIn('allowlist', message)

    def test_an_invalid_allowlist_regex_fails_the_guard(self):
        fake = self.real_toml.replace(
            "paths = ['''shared/modules/web-libs/libsodium/libsodium-wrappers\\.mjs''']",
            "paths = ['''server/modules/deploy/keys/[unclosed''']", 1)
        self.assertNotEqual(fake, self.real_toml)
        message = gitleaks_problem(fake, self.real_workflow)
        self.assertTrue(message)
        self.assertIn('valid regex', message)

    def test_a_dropped_job_key_fails_the_guard(self):
        fake = self.real_workflow.replace('  secrets:\n', '  other:\n', 1)
        self.assertNotEqual(fake, self.real_workflow)
        message = gitleaks_problem(self.real_toml, fake)
        self.assertTrue(message)
        self.assertIn('secrets:', message)

    def test_a_dropped_job_name_fails_the_guard(self):
        fake = self.real_workflow.replace('    name: Secret scan\n', '', 1)
        self.assertNotEqual(fake, self.real_workflow)
        message = gitleaks_problem(self.real_toml, fake)
        self.assertTrue(message)
        self.assertIn('Secret scan', message)


if __name__ == '__main__':
    unittest.main()
