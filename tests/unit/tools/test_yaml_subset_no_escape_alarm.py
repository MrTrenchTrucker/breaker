"""The alarm of the shared test case, run against test cases that loop and that return.

The alarm of yaml_support is what stops a reader that loops. Each case runs in a process of its own and what the alarm
does is asserted: it fails each sub-test that loops, it leaves no timer and no handler behind, and its shared limit is
a few seconds.

Run: python3 -m unittest discover -s tests/unit/tools -t tests/unit/tools
"""
import json
import os
import signal
import subprocess
import sys
import tempfile
import unittest
from typing import NamedTuple

from yaml_support import YamlCase

HERE = os.path.dirname(os.path.abspath(__file__))
GUARD_SECONDS = 30  # the looping case needs about a second; the guard only stops a run whose alarm is broken

# Two test cases that use the alarm of `yaml_support`, run in a process of their own: `Quiet` returns at once and
# shows what the alarm leaves behind, `Looping` has two sub-tests that never return. The script reports what
# happened in one JSON line and in its exit status.
ALARM_CASES = '''
import json
import signal
import sys
import unittest

sys.path.insert(0, sys.argv[1])
from yaml_support import YamlCase


class Quiet(YamlCase):
    HANG_SECONDS = 1

    def test_returns_at_once(self):
        pass


class Looping(YamlCase):
    HANG_SECONDS = 1

    def test_never_returns(self):
        for number in range(2):
            with self.subTest(number=number):
                while True:
                    pass


case = {"quiet": Quiet, "looping": Looping}[sys.argv[2]]
handler = signal.getsignal(signal.SIGALRM)
result = unittest.TestResult()
unittest.defaultTestLoader.loadTestsFromTestCase(case).run(result)
print(json.dumps({
    "failures": [text.strip().splitlines()[-1] for _, text in result.failures],
    "errors": [text.strip().splitlines()[-1] for _, text in result.errors],
    "timer": signal.getitimer(signal.ITIMER_REAL),
    "handler_restored": signal.getsignal(signal.SIGALRM) is handler,
}))
sys.exit(0 if result.wasSuccessful() else 1)
'''


class Watched(NamedTuple):
    timed_out: bool  # still running after GUARD_SECONDS, so it was killed
    returncode: int  # None after a time-out
    stdout: str
    stderr: str

    def report(self):
        """The JSON line the script printed last (only a process that got to the end printed one)."""
        return json.loads(self.stdout.strip().splitlines()[-1])


def run_alarm_case(which):
    """Run the case `which` ("quiet" or "looping") of ALARM_CASES in a process of its own, and say what happened."""
    with tempfile.TemporaryDirectory() as folder:
        script = os.path.join(folder, "alarm_cases.py")
        with open(script, "w", encoding="utf-8") as handle:
            handle.write(ALARM_CASES)
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
        try:
            done = subprocess.run([sys.executable, script, HERE, which], cwd=folder, env=env, capture_output=True,
                                  text=True, timeout=GUARD_SECONDS)
        except subprocess.TimeoutExpired:
            return Watched(True, None, "", "")
    return Watched(False, done.returncode, done.stdout, done.stderr)


@unittest.skipUnless(hasattr(signal, "SIGALRM"), "the alarm needs SIGALRM")
class AlarmTest(YamlCase):
    """The alarm of the shared test case is what stops a reader that loops, so it is run against test cases that
    loop and that return, each in a process of its own, and what it does is asserted: it fails each sub-test that
    loops, it leaves no timer and no handler behind, and its shared limit is a few seconds."""

    @classmethod
    def setUpClass(cls):
        cls.looping = run_alarm_case("looping")
        cls.quiet = run_alarm_case("quiet")

    def test_a_looping_test_is_failed_by_the_alarm_once_for_each_sub_test_that_loops(self):
        done = self.looping
        self.assertFalse(done.timed_out,
                         f"the looping case was still running after {GUARD_SECONDS} s: nothing stopped it")
        self.assertEqual(done.returncode, 1, done.stderr)
        report = done.report()
        self.assertEqual(report["errors"], [])
        self.assertEqual(len(report["failures"]), 2, "the alarm must reach both sub-tests, not only the first")
        for message in report["failures"]:
            with self.subTest(message=message):
                self.assertIn("ran for more than", message)
                self.assertIn("is looping", message)
                self.assertIn("Looping.test_never_returns", message, "the message should name the test that looped")

    def test_the_alarm_leaves_no_timer_running_when_a_test_ends(self):
        done = self.quiet
        self.assertEqual((done.timed_out, done.returncode), (False, 0), done.stderr)
        self.assertEqual(done.report()["timer"], [0.0, 0.0], "the timer was left running")

    def test_the_alarm_puts_back_the_handler_it_found_when_a_test_ends(self):
        done = self.quiet
        self.assertEqual((done.timed_out, done.returncode), (False, 0), done.stderr)
        self.assertTrue(done.report()["handler_restored"], "the handler of the alarm was left installed")

    def test_the_shared_limit_is_a_few_seconds(self):
        self.assertGreater(YamlCase.HANG_SECONDS, 0, "a limit of 0 switches the alarm off")
        self.assertLessEqual(YamlCase.HANG_SECONDS, 10, "a reader that loops must be stopped within seconds")


if __name__ == "__main__":
    unittest.main()
