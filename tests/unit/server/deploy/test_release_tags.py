"""Unit tests for scripts/release-tags.sh.

Drives the script via subprocess, both with a --tags-file and with tag lists
piped on stdin. Every test is proven red first.

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_release_tags.py'
"""
import os
import subprocess
import unittest

SCRIPT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    '..', '..', '..', '..',
    'server', 'modules', 'deploy', 'scripts', 'release-tags.sh',
))


def run_script(args, tags=None):
    """Run release-tags.sh with args, optionally piping tags on stdin."""
    cmd = ['sh', SCRIPT] + list(args)
    return subprocess.run(cmd, input=tags, capture_output=True, text=True)


def run_script_file(args, tags_file):
    """Run release-tags.sh with --tags-file pointing at a temp file."""
    import tempfile
    with tempfile.NamedTemporaryFile(mode='w', suffix='.tags', delete=False) as fh:
        fh.write(tags_file)
        name = fh.name
    try:
        cmd = ['sh', SCRIPT] + list(args) + ['--tags-file', name]
        return subprocess.run(cmd, capture_output=True, text=True)
    finally:
        os.unlink(name)


class ReleaseTagsTest(unittest.TestCase):

    # --- numeric vs text order ------------------------------------------

    def test_numeric_order_latest(self):
        tags = "1.9.9\n1.2.9\n1.2.10\n1.10.0\n"
        result = run_script(['latest'], tags=tags)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), '1.10.0')

    def test_numeric_order_previous(self):
        tags = "1.9.9\n1.2.9\n1.2.10\n1.10.0\n"
        result = run_script(['previous'], tags=tags)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), '1.9.9')

    # --- v prefix -------------------------------------------------------

    def test_v_prefix_printed_as_written(self):
        tags = "v2.0.0\n1.0.0\n"
        result = run_script(['latest'], tags=tags)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), 'v2.0.0')

    def test_v_and_plain_same_version_first_seen(self):
        tags = "v1.2.3\n1.2.3\n"
        result = run_script(['latest'], tags=tags)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), 'v1.2.3')

    def test_v_and_plain_previous_first_seen(self):
        tags = "1.2.3\nv1.2.3\n2.0.0\n"
        result = run_script(['previous'], tags=tags)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), '1.2.3')

    # --- junk tags ignored ----------------------------------------------

    def test_junk_tags_ignored(self):
        tags = "\n".join([
            "1.0.0",
            "1.2.3-rc1",
            "1.2.3+build",
            "rollback-pre-z1roles-20261110",
            "sherpa-onnx-asr-v1.13.8",
            "",
            "1.2.3\r",
            "notaversion",
            "v",
        ]) + "\n"
        result = run_script(['latest'], tags=tags)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), '1.2.3')

    # --- CRLF -----------------------------------------------------------

    def test_crlf_input(self):
        tags = "1.0.0\r\n2.0.0\r\n"
        result = run_script(['latest'], tags=tags)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), '2.0.0')

    # --- edge cases -----------------------------------------------------

    def test_blank_file_exit_3(self):
        result = run_script(['latest'], tags="")
        self.assertEqual(result.returncode, 3)
        self.assertEqual(result.stdout, '')

    def test_single_tag_latest_ok(self):
        result = run_script(['latest'], tags="3.0.0\n")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), '3.0.0')

    def test_single_tag_previous_exit_3(self):
        result = run_script(['previous'], tags="3.0.0\n")
        self.assertEqual(result.returncode, 3)
        self.assertEqual(result.stdout, '')

    def test_duplicates_and_v_duplicate(self):
        tags = "2.0.0\n2.0.0\nv2.0.0\n1.0.0\n"
        result = run_script(['latest'], tags=tags)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), '2.0.0')

    def test_leading_zero_ignored(self):
        tags = "01.2.3\n1.2.3\n"
        result = run_script(['latest'], tags=tags)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), '1.2.3')

    def test_two_part_and_four_part_ignored(self):
        tags = "1.2\n1.2.3.4\n1.2.3\n"
        result = run_script(['latest'], tags=tags)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), '1.2.3')

    def test_huge_part_ignored(self):
        tags = "12345678901.2.3\n1.2.3\n"
        result = run_script(['latest'], tags=tags)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), '1.2.3')

    # --- usage errors ---------------------------------------------------

    # --- the nine-digit edge and surrounding whitespace ------------------

    def test_nine_digit_part_accepted_and_ten_digit_ignored_in_each_part(self):
        for template in ('%s.2.3', '1.%s.3', '1.2.%s'):
            accepted = template % '123456789'
            too_long = template % '1234567890'
            both = run_script(['latest'], tags="0.0.1\n%s\n%s\n" % (too_long, accepted))
            self.assertEqual(both.returncode, 0)
            self.assertEqual(both.stdout, accepted + '\n')
            only_long = run_script(['latest'], tags="0.0.1\n%s\n" % too_long)
            self.assertEqual(only_long.returncode, 0)
            self.assertEqual(only_long.stdout, '0.0.1\n')

    def test_trailing_and_leading_whitespace_is_trimmed_and_accepted(self):
        for raw in ("1.2.3  \n", "1.2.3\t\n", "1.2.3 \r\n", "  1.2.3\n", "\t1.2.3\r\n"):
            result = run_script(['latest'], tags=raw)
            self.assertEqual(result.returncode, 0, repr(raw))
            self.assertEqual(result.stdout, '1.2.3\n', repr(raw))

    def test_usage_no_command_exit_2(self):
        result = run_script([])
        self.assertEqual(result.returncode, 2)

    def test_usage_unknown_command_exit_2(self):
        result = run_script(['bogus'])
        self.assertEqual(result.returncode, 2)

    def test_usage_unknown_option_exit_2(self):
        result = run_script(['latest', '--bogus'])
        self.assertEqual(result.returncode, 2)

    def test_usage_tags_file_no_value_exit_2(self):
        result = run_script(['latest', '--tags-file'])
        self.assertEqual(result.returncode, 2)

    def test_usage_tags_file_unreadable_exit_2(self):
        result = run_script_file(['latest'], tags_file="1.0.0\n")
        # point at a path that doesn't exist by using a nonexistent name
        import tempfile
        name = tempfile.mktemp(suffix='.tags')
        cmd = ['sh', SCRIPT, 'latest', '--tags-file', name]
        r2 = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(r2.returncode, 2)

    # --- script properties ----------------------------------------------

    def test_no_git_call(self):
        with open(SCRIPT) as fh:
            text = fh.read()
        import re
        self.assertNotRegex(text, r'\bgit\b')

    def test_no_write_command(self):
        with open(SCRIPT) as fh:
            text = fh.read()
        for line in text.splitlines():
            stripped = line.strip()
            if stripped.startswith('>') or ' > ' in stripped or ' >> ' in stripped:
                self.fail('write redirect found: %s' % stripped)
        self.assertNotIn('sed -i', text)
        import re
        self.assertNotRegex(text, r'\brm\b')
