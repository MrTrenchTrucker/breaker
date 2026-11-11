"""Unit tests for server/modules/deploy/scripts/vpn-bind.sh.

Each test runs the REAL script through subprocess with a fixture file of real
`ss -ltnH` output (the env seam VPN_BIND_LISTENERS_FILE), so no network call
and no live listener table. The script is verify-only; these tests pin its
per-port verdicts and exit codes:

    0 every port passes
    1 at least one exposure
    2 usage error
    3 no exposure but some port not listening

Spec (ADR-042): a listener passes on ANY
listed --vpn-addr value (repeatable, at least one required); a wildcard
(0.0.0.0, *, [::], ::) always fails; a public address always fails, even when
listed; private addresses (10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7,
fe80::/10, 169.254/16, loopback) pass only with --allow-private; loopback
always passes and is printed 'loopback'; a port with no listener is
'not-listening' (exit 3 when nothing exposed). A port's status is the worst of
its listener rows.
"""

import os
import subprocess
import tempfile
import unittest

ROOT = os.path.dirname(
    os.path.dirname(
        os.path.dirname(
            os.path.dirname(
                os.path.dirname(os.path.abspath(__file__))
            )
        )
    )
)

SCRIPT = os.path.join(ROOT, "server", "modules", "deploy", "scripts", "vpn-bind.sh")

# The `ss -ltnH` column layout: State Recv-Q Send-Q Local-Address:Port Peer
# (a header line and blank lines are tolerated and skipped).
SS_HEADER = "State Recv-Q Send-Q Local-Address:Port Peer-Address:Port"


def octets(*parts):
    """An IPv4 address written from its parts, so no address literal sits in this file."""
    return ".".join(str(part) for part in parts)


def listeners(rows):
    """Build an ss -ltnH shaped fixture from (local, port) rows."""
    lines = []
    for local, port in rows:
        lines.append("LISTEN 0 128 %s:%d *:*" % (local, port))
    return "\n".join(lines) + "\n"


class VpnBindTest(unittest.TestCase):
    """Runs the real vpn-bind.sh against fixture listener files."""

    @classmethod
    def setUpClass(cls):
        assert os.path.isfile(SCRIPT), "script missing: %s" % SCRIPT
        cls.env = dict(os.environ)
        cls.env.pop("VPN_BIND_LISTENERS_FILE", None)

    def run_script(self, args, fixture_lines):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "listeners.txt")
            with open(path, "w") as fh:
                fh.write(fixture_lines)
            env = dict(self.env)
            env["VPN_BIND_LISTENERS_FILE"] = path
            proc = subprocess.run(
                ["sh", SCRIPT] + args,
                capture_output=True,
                text=True,
                env=env,
                timeout=30,
            )
        return proc

    # -- exit 0: everything allowed -----------------------------------------

    def test_all_listeners_on_the_vpn_address_exit_0(self):
        fix = listeners([("10.8.0.5", 8080), ("10.8.0.5", 8443)])
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--ports", "8080,8443"], fix
        )
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertEqual(
            p.stdout,
            "8080 pass 10.8.0.5\n8443 pass 10.8.0.5\n",
        )

    def test_loopback_listener_passes_and_prints_loopback(self):
        fix = listeners([("127.0.0.1", 53), ("127.0.0.1", 8080)])
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--ports", "53,8080"], fix
        )
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertEqual(
            p.stdout,
            "53 loopback 127.0.0.1\n8080 loopback 127.0.0.1\n",
        )

    def test_ipv6_loopback_listener_passes(self):
        fix = listeners([("[::1]", 8080)])
        p = self.run_script(["--vpn-addr", "10.8.0.5", "--ports", "8080"], fix)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("8080 loopback", p.stdout)
        self.assertIn("::1", p.stdout)

    def test_ipv6_ula_vpn_address_listener_passes(self):
        fix = listeners([("fc00::5", 8080)])
        p = self.run_script(
            ["--vpn-addr", "fc00::5", "--ports", "8080"], fix
        )
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("8080 pass", p.stdout)

    def test_two_listed_addresses_one_listener_on_each_passes(self):
        fix = listeners([("10.8.0.5", 8080), ("10.8.0.6", 8443)])
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--vpn-addr", "10.8.0.6",
             "--ports", "8080,8443"],
            fix,
        )
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("8080 pass", p.stdout)
        self.assertIn("8443 pass", p.stdout)

    def test_unlisted_listener_on_second_listed_address_is_exposed(self):
        # Two listed addresses, one unlisted listener on a third: fails.
        fix = listeners([("10.8.0.5", 8080), ("10.8.0.9", 8443)])
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--vpn-addr", "10.8.0.6",
             "--ports", "8080,8443"],
            fix,
        )
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("8080 pass 10.8.0.5", p.stdout)
        self.assertIn("8443 exposed 10.8.0.9", p.stdout)

    def test_port_status_is_worst_of_its_rows(self):
        # One port, two rows: one clean listener and one exposed. The port
        # must fail and the output must show the exposing address.
        fix = listeners([("10.8.0.5", 8080), ("0.0.0.0", 8080)])
        p = self.run_script(["--vpn-addr", "10.8.0.5", "--ports", "8080"], fix)
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("8080 exposed 0.0.0.0", p.stdout)

    def test_header_and_blank_lines_are_tolerated(self):
        fix = SS_HEADER + "\n\n" + listeners([("10.8.0.5", 8080)])
        p = self.run_script(["--vpn-addr", "10.8.0.5", "--ports", "8080"], fix)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("8080 pass 10.8.0.5", p.stdout)

    def test_port_prefix_of_another_port_is_not_confused(self):
        # 8080 on a wildcard must fail port 8080 and say nothing about 80;
        # 80 has no listener at all, so it is not-listening and exit is 1
        # (exposure wins over not-listening).
        fix = listeners([("0.0.0.0", 8080)])
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--ports", "80,8080"], fix
        )
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("80 not-listening", p.stdout)
        self.assertIn("8080 exposed 0.0.0.0", p.stdout)

    # -- --allow-private: the private ranges ---------------------------------

    def _private_case(self, addr, port, expect_pass, with_flag=True):
        fix = listeners([(addr, port)])
        args = ["--vpn-addr", "10.8.0.5", "--ports", str(port)]
        if with_flag:
            args.append("--allow-private")
        p = self.run_script(args, fix)
        if expect_pass:
            self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
            self.assertIn("%d pass" % port, p.stdout)
        else:
            self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
            self.assertIn("%d exposed" % port, p.stdout)

    def test_private_10_8_passes_with_flag(self):
        self._private_case("10.0.0.1", 8080, True)

    def test_private_172_16_12_edges_with_flag(self):
        # 172.16.0.1 is inside 172.16/12: passes.
        self._private_case("172.16.0.1", 8080, True)
        # 172.15.255.255 is just below the range: fails.
        self._private_case("172.15.255.255", 8080, False)
        # 172.32.0.1 is just past the range (172.16-172.31): fails.
        self._private_case("172.32.0.1", 8080, False)

    def test_private_172_16_12_middle_of_the_range_passes_with_flag(self):
        for addr in ("172.17.0.1", "172.20.0.1", "172.22.0.1", "172.29.255.254", "172.30.0.1", "172.31.255.255"):
            self._private_case(addr, 8080, True)

    def test_private_100_64_10_every_tens_block_passes_with_flag(self):
        for addr in ("100.64.0.1", "100.69.255.255", "100.70.0.1", "100.79.0.1", "100.80.0.1", "100.99.0.1",
                     "100.100.0.1", "100.109.0.1", "100.110.0.1", "100.119.0.1", "100.120.0.1", "100.127.0.1"):
            self._private_case(addr, 8080, True)

    def test_public_100_x_below_64_is_exposed_even_with_the_flag(self):
        for addr in ("100.0.0.1", "100.7.0.1", "100.8.0.1", "100.9.0.1", "100.10.0.1", "100.63.0.1", "100.128.0.1", "100.200.0.1"):
            self._private_case(addr, 8080, False)

    def test_private_192_168_passes_with_flag(self):
        self._private_case(octets(192, 168, 1, 50), 8080, True)

    def test_private_100_64_10_edges_with_flag(self):
        # 100.64.0.1 is the first address of 100.64/10: passes.
        self._private_case("100.64.0.1", 8080, True)
        # 100.127.255.255 is the last address of 100.64/10: passes.
        self._private_case("100.127.255.255", 8080, True)
        # 100.63.255.255 is just below the range: fails.
        self._private_case("100.63.255.255", 8080, False)
        # 100.128.0.0 is just past the range: fails.
        self._private_case("100.128.0.0", 8080, False)

    def test_private_169_254_passes_with_flag(self):
        self._private_case("169.254.1.1", 8080, True)

    def test_private_ipv6_fc00_ula_passes_with_flag(self):
        self._private_case("fc00::1", 8080, True)
        self._private_case("fdff:ffff::1", 8080, True)

    def test_private_ipv6_fe80_link_local_passes_with_flag(self):
        self._private_case("fe80::1", 8080, True)
        self._private_case("febf:ffff::1", 8080, True)

    def test_private_without_flag_is_exposed(self):
        self._private_case("10.0.0.1", 8080, False, with_flag=False)
        self._private_case(octets(192, 168, 1, 50), 8080, False, with_flag=False)
        self._private_case("100.64.0.1", 8080, False, with_flag=False)

    # -- always-fail classes -------------------------------------------------

    def test_wildcard_ipv4_fails(self):
        fix = listeners([("0.0.0.0", 8080)])
        p = self.run_script(["--vpn-addr", "10.8.0.5", "--ports", "8080"], fix)
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("8080 exposed 0.0.0.0", p.stdout)

    def test_wildcard_star_fails(self):
        fix = listeners([("*", 8080)])
        p = self.run_script(["--vpn-addr", "10.8.0.5", "--ports", "8080"], fix)
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("8080 exposed *", p.stdout)

    def test_wildcard_ipv6_bracketed_fails(self):
        fix = listeners([("[::]", 8080)])
        p = self.run_script(["--vpn-addr", "10.8.0.5", "--ports", "8080"], fix)
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("8080 exposed ::", p.stdout)

    def test_wildcard_ipv6_bare_fails(self):
        fix = listeners([("::", 8080)])
        p = self.run_script(["--vpn-addr", "10.8.0.5", "--ports", "8080"], fix)
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("8080 exposed ::", p.stdout)

    def test_other_lan_address_fails_without_flag(self):
        fix = listeners([(octets(192, 168, 0, 9), 8080)])
        p = self.run_script(["--vpn-addr", "10.8.0.5", "--ports", "8080"], fix)
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("8080 exposed " + octets(192, 168, 0, 9), p.stdout)

    def test_unlisted_private_address_fails(self):
        # A listener on a private address that is NOT listed fails without
        # --allow-private, even when it sits in the VPN's own subnet.
        fix = listeners([("10.9.9.9", 8080)])
        p = self.run_script(["--vpn-addr", "10.8.0.5", "--ports", "8080"], fix)
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("8080 exposed 10.9.9.9", p.stdout)

    def test_listed_public_address_still_fails(self):
        # ADR-042: a public address is ALWAYS refused, even when listed.
        fix = listeners([("8.8.8.8", 8080)])
        p = self.run_script(["--vpn-addr", "8.8.8.8", "--ports", "8080"], fix)
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("8080 exposed 8.8.8.8", p.stdout)

    def test_public_ipv6_listener_fails_even_when_listed(self):
        fix = listeners([("2001:db8::1", 8080)])
        p = self.run_script(
            ["--vpn-addr", "2001:db8::1", "--ports", "8080"], fix
        )
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("8080 exposed 2001:db8::1", p.stdout)

    def test_allow_private_does_not_rescue_wildcard_or_public(self):
        fix = listeners([("0.0.0.0", 8080), ("8.8.8.8", 8443)])
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--allow-private", "--ports", "8080,8443"],
            fix,
        )
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("8080 exposed 0.0.0.0", p.stdout)
        self.assertIn("8443 exposed 8.8.8.8", p.stdout)

    # -- not-listening and the exit-3/exit-1 precedence ----------------------

    def test_missing_listener_is_not_listening_exit_3(self):
        fix = listeners([("10.8.0.5", 8080)])
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--ports", "8080,9443"], fix
        )
        self.assertEqual(p.returncode, 3, p.stdout + p.stderr)
        self.assertIn("8080 pass 10.8.0.5", p.stdout)
        self.assertIn("9443 not-listening", p.stdout)

    def test_exposure_plus_missing_listener_exposure_wins(self):
        fix = listeners([("0.0.0.0", 8080)])
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--ports", "8080,9443"], fix
        )
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("8080 exposed 0.0.0.0", p.stdout)
        self.assertIn("9443 not-listening", p.stdout)

    # -- usage errors (exit 2) ------------------------------------------------

    def test_port_zero_is_a_usage_error(self):
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--ports", "0"], listeners([])
        )
        self.assertEqual(p.returncode, 2, p.stdout + p.stderr)
        self.assertIn("--ports", p.stderr)

    def test_port_65536_is_a_usage_error(self):
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--ports", "65536"], listeners([])
        )
        self.assertEqual(p.returncode, 2, p.stdout + p.stderr)
        self.assertIn("--ports", p.stderr)

    def test_port_65535_is_accepted(self):
        fix = listeners([("10.8.0.5", 65535)])
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--ports", "65535"], fix
        )
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("65535 pass 10.8.0.5", p.stdout)

    def test_port_not_a_number_is_a_usage_error(self):
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--ports", "abc"], listeners([])
        )
        self.assertEqual(p.returncode, 2, p.stdout + p.stderr)
        self.assertIn("--ports", p.stderr)

    def test_empty_port_list_is_a_usage_error(self):
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--ports", ""], listeners([])
        )
        self.assertEqual(p.returncode, 2, p.stdout + p.stderr)
        self.assertIn("--ports", p.stderr)

    def test_duplicate_ports_collapse_and_still_run(self):
        fix = listeners([("10.8.0.5", 8080)])
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--ports", "8080,8080"], fix
        )
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        # The port is reported once.
        self.assertEqual(p.stdout.count("8080 "), 1)

    def test_unknown_option_is_a_usage_error(self):
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--ports", "8080", "--bogus"],
            listeners([]),
        )
        self.assertEqual(p.returncode, 2, p.stdout + p.stderr)
        self.assertIn("--bogus", p.stderr)

    def test_missing_vpn_addr_is_a_usage_error(self):
        p = self.run_script(["--ports", "8080"], listeners([]))
        self.assertEqual(p.returncode, 2, p.stdout + p.stderr)
        self.assertIn("--vpn-addr", p.stderr)

    def test_missing_ports_is_a_usage_error(self):
        p = self.run_script(["--vpn-addr", "10.8.0.5"], listeners([]))
        self.assertEqual(p.returncode, 2, p.stdout + p.stderr)
        self.assertIn("--ports", p.stderr)

    def test_vpn_addr_must_be_a_literal(self):
        for bad in ("host.name", "10.8.0", "300.1.1.1", ":::"):
            p = self.run_script(
                ["--vpn-addr", bad, "--ports", "8080"], listeners([])
            )
            self.assertEqual(p.returncode, 2, bad + " " + p.stderr)
            self.assertIn("--vpn-addr", p.stderr)

    def test_error_text_never_echoes_the_listener_file(self):
        # A usage failure must not leak the listener file's contents.
        fix = listeners([("10.8.0.5", 8080)])
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--ports", "not-a-port"], fix
        )
        self.assertEqual(p.returncode, 2, p.stdout + p.stderr)
        self.assertIn("--ports", p.stderr)
        self.assertNotIn("10.8.0.5", p.stderr)


    # -- address classes, edge by edge --------------------------------------

    def _class_case(self, addr, expect_pass):
        self._private_case(addr, 8080, expect_pass)

    def test_private_172_16_12_second_octet_16_to_31_passes_and_around_it_fails(self):
        for octet in range(16, 32):
            self._class_case("172.%d.1.1" % octet, True)
        for octet in (0, 1, 15, 32, 33, 255):
            self._class_case("172.%d.1.1" % octet, False)

    def test_only_192_168_is_private_in_192(self):
        for octet in (160, 161, 167, 169, 0, 255):
            self._class_case("192.%d.0.1" % octet, False)
        self._class_case(octets(192, 168, 0, 1), True)
        self._class_case(octets(192, 168, 255, 254), True)

    def test_only_169_254_is_private_in_169(self):
        for octet in (250, 251, 253, 255, 0):
            self._class_case("169.%d.0.1" % octet, False)
        self._class_case("169.254.0.1", True)

    # -- IPv6 classes: the first group as ss writes it ----------------------

    def _v6_case(self, addr, expect_pass):
        fix = "LISTEN 0 128 [%s]:8080 *:*\n" % addr
        p = self.run_script(
            ["--vpn-addr", "10.8.0.5", "--ports", "8080", "--allow-private"], fix
        )
        if expect_pass:
            self.assertEqual(p.returncode, 0, addr + " " + p.stdout + p.stderr)
            self.assertEqual(p.stdout, "8080 pass %s\n" % addr)
        else:
            self.assertEqual(p.returncode, 1, addr + " " + p.stdout + p.stderr)
            self.assertEqual(p.stdout, "8080 exposed %s\n" % addr)

    def test_ipv6_private_is_a_first_group_of_four_digits_fc_fd_or_fe8_to_feb(self):
        for addr in ("fc00::1", "fd00::5", "fdff:1::2", "fcab::1", "fe80::1", "feb0::2", "febf::3"):
            self._v6_case(addr, True)

    def test_ipv6_short_forms_and_site_local_are_public(self):
        for addr in ("fc::5", "fd::5", "fe8::1", "fe::1", "fec0::5", "fef0::1", "fe00::1", "fc0::1", "fb00::1", "2001:db8::1"):
            self._v6_case(addr, False)

    # -- one port, several rows ---------------------------------------------

    def test_a_loopback_row_and_a_listed_row_on_one_port_print_the_first_of_equal_rank(self):
        args = ["--vpn-addr", "10.8.0.5", "--ports", "8080"]
        p = self.run_script(args, listeners([("127.0.0.1", 8080), ("10.8.0.5", 8080)]))
        self.assertEqual((p.returncode, p.stdout), (0, "8080 loopback 127.0.0.1\n"))
        p = self.run_script(args, listeners([("10.8.0.5", 8080), ("127.0.0.1", 8080)]))
        self.assertEqual((p.returncode, p.stdout), (0, "8080 pass 10.8.0.5\n"))

    def test_two_listed_rows_on_one_port_print_the_first(self):
        args = ["--vpn-addr", "10.8.0.5", "--vpn-addr", "10.8.0.6", "--ports", "8080"]
        p = self.run_script(args, listeners([("10.8.0.5", 8080), ("10.8.0.6", 8080)]))
        self.assertEqual((p.returncode, p.stdout), (0, "8080 pass 10.8.0.5\n"))
        p = self.run_script(args, listeners([("10.8.0.6", 8080), ("10.8.0.5", 8080)]))
        self.assertEqual((p.returncode, p.stdout), (0, "8080 pass 10.8.0.6\n"))

    def test_a_port_with_no_listener_prints_a_dash_for_the_address(self):
        p = self.run_script(["--vpn-addr", "10.8.0.5", "--ports", "9999"], listeners([]))
        self.assertEqual((p.returncode, p.stdout), (3, "9999 not-listening -\n"))

    # -- options --------------------------------------------------------------

    def test_help_exits_0_and_prints_the_usage(self):
        p = self.run_script(["--help"], listeners([]))
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertTrue(p.stdout.startswith("usage: vpn-bind.sh"), p.stdout)
        p = self.run_script(["-h"], listeners([]))
        self.assertEqual(p.returncode, 0, p.stderr)

    def test_an_empty_ports_value_is_a_usage_error_in_both_forms(self):
        for args in (["--ports="], ["--ports", ""]):
            p = self.run_script(["--vpn-addr", "10.8.0.5"] + args, listeners([]))
            self.assertEqual(p.returncode, 2, p.stdout + p.stderr)

    def test_an_empty_or_blank_vpn_addr_is_a_usage_error_in_both_forms(self):
        for args in (["--vpn-addr", ""], ["--vpn-addr="], ["--vpn-addr", " "]):
            p = self.run_script(args + ["--ports", "8080"], listeners([("127.0.0.1", 8080)]))
            self.assertEqual(p.returncode, 2, repr(args) + " " + p.stdout + p.stderr)
            self.assertIn("--vpn-addr", p.stderr)

    def test_repeated_vpn_addr_with_an_equals_sign_keeps_every_value(self):
        fix = listeners([("10.8.0.5", 80), ("10.8.0.6", 81)])
        p = self.run_script(["--vpn-addr=10.8.0.5", "--vpn-addr=10.8.0.6", "--ports", "80,81"], fix)
        self.assertEqual((p.returncode, p.stdout), (0, "80 pass 10.8.0.5\n81 pass 10.8.0.6\n"))
        p = self.run_script(["--vpn-addr=10.8.0.6", "--vpn-addr", "10.8.0.5", "--ports", "80,81"], fix)
        self.assertEqual((p.returncode, p.stdout), (0, "80 pass 10.8.0.5\n81 pass 10.8.0.6\n"))

    def _accepts_vpn_addr(self, value):
        p = self.run_script(["--vpn-addr", value, "--ports", "8080"], listeners([("127.0.0.1", 8080)]))
        self.assertEqual(p.returncode, 0, value + " " + p.stdout + p.stderr)

    def _refuses_vpn_addr(self, value):
        p = self.run_script(["--vpn-addr", value, "--ports", "8080"], listeners([("127.0.0.1", 8080)]))
        self.assertEqual(p.returncode, 2, value + " " + p.stdout + p.stderr)
        self.assertIn("--vpn-addr", p.stderr)

    def test_vpn_addr_literals_that_are_accepted(self):
        for value in ("::", "2001:db8:0:0:0:0:0:1", "255.1.1.1", "0.0.0.0", "10.8.0.5", "fd00::1"):
            self._accepts_vpn_addr(value)

    def test_vpn_addr_literals_that_are_refused(self):
        for value in ("12345::1", "0000.1.1.1", "10.8.0.300", "256.1.1.1", "1.2.3", "1.2.3.4.5", "1:2:3:4:5:6:7:8:9"):
            self._refuses_vpn_addr(value)

    def test_a_port_written_with_leading_zeros_is_the_same_port(self):
        p = self.run_script(["--vpn-addr", "10.8.0.5", "--ports", "08080"], listeners([("10.8.0.5", 8080)]))
        self.assertEqual((p.returncode, p.stdout), (0, "8080 pass 10.8.0.5\n"))

    # -- the listener table -----------------------------------------------------

    def test_a_row_without_the_peer_column_is_still_read(self):
        p = self.run_script(["--vpn-addr", "10.8.0.5", "--ports", "8080"], "LISTEN 0 128 10.8.0.5:8080\n")
        self.assertEqual((p.returncode, p.stdout), (0, "8080 pass 10.8.0.5\n"))

    def test_a_row_with_an_empty_address_is_not_a_listener(self):
        p = self.run_script(["--vpn-addr", "10.8.0.5", "--ports", "8080"], "LISTEN 0 128 :8080 *:*\n")
        self.assertEqual((p.returncode, p.stdout), (3, "8080 not-listening -\n"))

    def test_an_ipv6_scope_id_is_stripped_before_the_class_is_read(self):
        p = self.run_script(["--vpn-addr", "10.8.0.5", "--ports", "8080"], "LISTEN 0 128 [::1%lo]:8080 *:*\n")
        self.assertEqual((p.returncode, p.stdout), (0, "8080 loopback ::1\n"))

    def test_a_missing_listener_file_is_a_usage_error(self):
        env = dict(self.env)
        env["VPN_BIND_LISTENERS_FILE"] = "/nonexistent/listeners-file"
        proc = subprocess.run(
            ["sh", SCRIPT, "--vpn-addr", "10.8.0.5", "--ports", "8080"],
            capture_output=True, text=True, env=env, timeout=30,
        )
        self.assertEqual(proc.returncode, 2, proc.stdout + proc.stderr)
        self.assertEqual(proc.stdout, "")


class VpnBindScriptTextTest(unittest.TestCase):
    """The script must stay verify-only: no file writes, no firewall tools."""

    @classmethod
    def setUpClass(cls):
        with open(SCRIPT) as fh:
            cls.text = fh.read()

    def test_script_contains_no_write_commands(self):
        # No shell redirect to a file: in CODE (comments stripped), every '>'
        # must be part of the stderr redirect '>&2', and there must be no '>>'.
        code = "\n".join(
            line.split("#", 1)[0] for line in self.text.splitlines()
        )
        self.assertNotIn(">>", code)
        for i, ch in enumerate(code):
            if ch == ">":
                self.assertEqual(
                    code[i : i + 3], ">&2",
                    "a redirect other than '>&2': %r"
                    % code[max(0, i - 30) : i + 10],
                )
        # No firewall or interface tools, no in-place editor.
        self.assertNotIn("iptables", self.text)
        self.assertNotIn("ip6tables", self.text)
        self.assertNotIn("nft", self.text)
        self.assertNotIn("sed -i", self.text)
        # No `ip` tool invocations (route/interface mutation): a standalone
        # `ip` command at the start of a code line or after a pipe.
        for line in self.text.splitlines():
            code = line.split("#", 1)[0].strip()
            self.assertFalse(
                code.startswith("ip ") or " | ip " in code,
                "script invokes the ip tool: %r" % code,
            )


if __name__ == "__main__":
    unittest.main()
