#!/bin/sh
# vpn-bind.sh - verify-only check that Breaker's ports listen on allowed
# addresses only. It reads the listener table (ss -ltnH, or the file named by
# VPN_BIND_LISTENERS_FILE for tests) and reports one line per port:
#
#     PORT STATUS ADDR
#
# STATUS is one of: pass, loopback, exposed, not-listening.
# Exit codes: 0 every port passes; 1 at least one exposed port;
# 2 usage error; 3 no exposure but at least one port not listening.
#
# It never binds, never changes anything, never makes a network call, and
# never prints the contents of its environment or listener file beyond the
# per-port line.

set -u

# The script only parses ASCII address literals; pin the C locale so POSIX
# bracket expressions and numeric tests behave the same on every host.
LC_ALL=C
export LC_ALL

usage_error() {
    printf 'vpn-bind.sh: %s\n' "$1" >&2
    exit 2
}

# ---- argument parsing ------------------------------------------------------

ALLOW_PRIVATE=0
VPN_ADDRS=""
PORTS_RAW=""
HAVE_VPN_ADDR=0
HAVE_PORTS=0

while [ $# -gt 0 ]; do
    case "$1" in
        --vpn-addr)
            [ $# -ge 2 ] || usage_error "--vpn-addr requires a value"
            VPN_ADDRS="$VPN_ADDRS $2"
            HAVE_VPN_ADDR=1
            shift 2
            ;;
        --vpn-addr=*)
            VPN_ADDRS="$VPN_ADDRS ${1#--vpn-addr=}"
            HAVE_VPN_ADDR=1
            shift
            ;;
        --ports)
            [ $# -ge 2 ] || usage_error "--ports requires a value"
            [ -n "$2" ] || usage_error "--ports requires a non-empty value"
            PORTS_RAW="$2"
            HAVE_PORTS=1
            shift 2
            ;;
        --ports=*)
            PORTS_RAW="${1#--ports=}"
            [ -n "$PORTS_RAW" ] || usage_error "--ports requires a non-empty value"
            HAVE_PORTS=1
            shift
            ;;
        --allow-private)
            ALLOW_PRIVATE=1
            shift
            ;;
        --help|-h)
            printf 'usage: vpn-bind.sh --vpn-addr ADDR [--vpn-addr ADDR...] --ports P1,P2,... [--allow-private]\n'
            exit 0
            ;;
        *)
            usage_error "unknown option $1"
            ;;
    esac
done

[ $HAVE_VPN_ADDR -eq 1 ] || usage_error "--vpn-addr is required (at least one)"
[ $HAVE_PORTS -eq 1 ] || usage_error "--ports is required"

# ---- helpers -----------------------------------------------------------------

# unzerofy N: _UZ_OUT holds N with leading zeros stripped (at least one digit)
unzerofy() {
    _uz="$1"
    while [ ${#_uz} -gt 1 ]; do
        case "$_uz" in
            0*) _uz="${_uz#0}" ;;
            *) break ;;
        esac
    done
    _UZ_OUT="$_uz"
}

is_ipv4_literal() {
    addr="$1"
    case "$addr" in
        *.*.*.*) ;;
        *) return 1 ;;
    esac
    OLDIFS=$IFS
    IFS=.
    set -- $addr
    IFS=$OLDIFS
    [ $# -eq 4 ] || return 1
    for o in "$1" "$2" "$3" "$4"; do
        [ -n "$o" ] || return 1
        case "$o" in
            *[!0-9]*) return 1 ;;
        esac
        [ ${#o} -le 3 ] || return 1
        unzerofy "$o"
        [ "$_UZ_OUT" -le 255 ] || return 1
    done
    return 0
}

is_ipv6_literal() {
    a="$1"
    [ "$a" = "::" ] && return 0
    case "$a" in
        *[!0-9a-fA-F:]*) return 1 ;;
    esac
    case "$a" in
        *::*)
            case "${a#*::}" in
                *::*) return 1 ;;
            esac
            ;;
    esac
    case "$a" in
        *::*) a="${a%%::*}:${a#*::}" ;;
    esac
    OLDIFS=$IFS
    IFS=:
    set -- $a
    IFS=$OLDIFS
    [ $# -ge 1 ] && [ $# -le 8 ] || return 1
    empties=0
    for g in "$@"; do
        if [ -n "$g" ]; then
            case "$g" in
                *[!0-9a-fA-F]*) return 1 ;;
            esac
            [ ${#g} -le 4 ] || return 1
        else
            empties=$((empties + 1))
            [ $empties -le 1 ] || return 1
        fi
    done
    return 0
}

is_addr_literal() {
    case "$1" in
        *:*) is_ipv6_literal "$1" ;;
        *) is_ipv4_literal "$1" ;;
    esac
}

for va in $VPN_ADDRS; do
    is_addr_literal "$va" || usage_error "--vpn-addr value $va is not an IPv4 or IPv6 literal"
done
# An empty or blank value adds no address at all.
set -- $VPN_ADDRS
[ $# -ge 1 ] || usage_error "--vpn-addr requires a non-empty value"

# ---- the requested ports ----------------------------------------------------

UNIQUE_PORTS=""
OLDIFS=$IFS
IFS=','
for p in $PORTS_RAW; do
    IFS=$OLDIFS
    [ -n "$p" ] || usage_error "--ports has an empty entry"
    case "$p" in
        *[!0-9]*) usage_error "--ports entry $p is not a number" ;;
    esac
    unzerofy "$p"
    [ ${#_UZ_OUT} -le 5 ] || usage_error "--ports entry $p is not in 1..65535"
    [ "$_UZ_OUT" -ge 1 ] || usage_error "--ports entry $p is not in 1..65535"
    [ "$_UZ_OUT" -le 65535 ] || usage_error "--ports entry $p is not in 1..65535"
    case " $UNIQUE_PORTS " in
        *" $_UZ_OUT "*) continue ;;
    esac
    UNIQUE_PORTS="$UNIQUE_PORTS $_UZ_OUT"
done
IFS=$OLDIFS

# ---- listener table ----------------------------------------------------------

LISTENERS=""
if [ -n "${VPN_BIND_LISTENERS_FILE:-}" ]; then
    [ -r "$VPN_BIND_LISTENERS_FILE" ] || usage_error "listener file is not readable"
    LISTENERS="$(cat "$VPN_BIND_LISTENERS_FILE")"
else
    LISTENERS="$(ss -ltnH)" || usage_error "ss -ltnH failed"
fi

# One line per (port, local-address) pair from the listener table. Blank lines
# and a header line (whose port field is not numeric) are skipped.
LISTENER_ROWS=""
while IFS= read -r rawline || [ -n "$rawline" ]; do
    [ -n "$rawline" ] || continue
    # ss -ltnH columns: State Recv-Q Send-Q Local-Address:Port Peer-Address:Port
    OLDIFS=$IFS
    IFS=' '
    set -- $rawline
    IFS=$OLDIFS
    [ $# -ge 4 ] || continue
    la="$4"
    case "$la" in
        *:*) ;;
        *) continue ;;
    esac
    port="${la##*:}"
    case "$port" in
        *[!0-9]* | '') continue ;;
    esac
    addr="${la%:*}"
    case "$addr" in
        \[*)
            addr="${addr#?}"
            addr="${addr%?}"
            ;;
    esac
    case "$addr" in
        *%*) addr="${addr%%%*}" ;;
    esac
    [ -n "$addr" ] || continue
    LISTENER_ROWS="$LISTENER_ROWS$port $addr
"
done <<EOF
$LISTENERS
EOF

# ---- classification -----------------------------------------------------------

# classify_addr ADDR -> sets CLASS: wildcard|loopback|private|public|other
classify_addr() {
    addr="$1"
    case "$addr" in
        '*' | '0.0.0.0' | '::') CLASS=wildcard; return 0 ;;
    esac
    case "$addr" in
        *:*)
            # IPv6 is classed by the first group as ss writes it: fc00::/7 (unique local) is a first group of
            # exactly four hex digits, fc or fd then two more; fe80::/10 (link-local) is fe8x to febx, four
            # digits. Short forms (fc::5, fe8::1) are other addresses and fec0::/10 (site-local) is deprecated:
            # all of them are PUBLIC, so the check fails closed.
            case "$addr" in
                ::1) CLASS=loopback; return 0 ;;
                [fF][cCdD][0-9a-fA-F][0-9a-fA-F]:*) CLASS=private; return 0 ;;
                [fF][eE][89abAB][0-9a-fA-F]:*) CLASS=private; return 0 ;;
            esac
            CLASS=public
            return 0
            ;;
    esac
    case "$addr" in
        127.*) CLASS=loopback; return 0 ;;
        10.*) CLASS=private; return 0 ;;
        192.168.*) CLASS=private; return 0 ;;
        169.254.*) CLASS=private; return 0 ;;
        172.*)
            case "$addr" in
                172.1[6-9].*) CLASS=private; return 0 ;;
                172.2[0-9].*) CLASS=private; return 0 ;;
                172.3[01].*) CLASS=private; return 0 ;;
            esac
            ;;
        100.*)
            case "$addr" in
                100.6[4-9].*) CLASS=private; return 0 ;;
                100.[789][0-9].*) CLASS=private; return 0 ;;
                100.1[01][0-9].*) CLASS=private; return 0 ;;
                100.12[0-7].*) CLASS=private; return 0 ;;
            esac
            ;;
    esac
    CLASS=public
}

# is_listed_addr ADDR -> 0 when ADDR is one of the --vpn-addr values
is_listed_addr() {
    for va in $VPN_ADDRS; do
        [ "$va" = "$1" ] && return 0
    done
    return 1
}

# ---- per-port verdicts ---------------------------------------------------------
# Rank: exposed=3, pass/loopback=1, no row at all = not-listening. A port's
# status is the worst (highest rank) of its listener rows:
# - a wildcard or a public address is always exposed, listed or not;
# - a private address passes when it is listed or --allow-private is set;
# - loopback always passes and is printed 'loopback'.

ANY_EXPOSED=0
ANY_NOT_LISTENING=0
for pv in $UNIQUE_PORTS; do
    PORT_STATUS="not-listening"
    PORT_ADDR="-"
    PORT_RANK=0
    while IFS=' ' read -r rp ra; do
        [ -n "$rp" ] || continue
        [ "$rp" = "$pv" ] || continue
        classify_addr "$ra"
        case "$CLASS" in
            wildcard) ROW_STATUS="exposed"; ROW_RANK=3 ;;
            loopback) ROW_STATUS="loopback"; ROW_RANK=1 ;;
            private)
                if is_listed_addr "$ra" || [ $ALLOW_PRIVATE -eq 1 ]; then
                    ROW_STATUS="pass"; ROW_RANK=1
                else
                    ROW_STATUS="exposed"; ROW_RANK=3
                fi
                ;;
            *) ROW_STATUS="exposed"; ROW_RANK=3 ;;
        esac
        if [ $ROW_RANK -gt $PORT_RANK ]; then
            PORT_RANK=$ROW_RANK
            PORT_STATUS=$ROW_STATUS
            PORT_ADDR=$ra
        fi
    done <<EOF
$LISTENER_ROWS
EOF
    case "$PORT_STATUS" in
        exposed) ANY_EXPOSED=1 ;;
        not-listening) ANY_NOT_LISTENING=1 ;;
    esac
    printf '%s %s %s\n' "$pv" "$PORT_STATUS" "$PORT_ADDR"
done

if [ $ANY_EXPOSED -eq 1 ]; then
    exit 1
fi
if [ $ANY_NOT_LISTENING -eq 1 ]; then
    exit 3
fi
exit 0
