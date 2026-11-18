# deploy-common.sh - shared helpers for the Breaker deploy scripts.
#
# SOURCE this file, never execute it:
#
#     SCRIPT_DIR=$(dirname "$0")
#     . "$SCRIPT_DIR/deploy-common.sh"
#
# It prints nothing at source time. It defines:
#
#   deploy_fail MSG...              print "deploy: MSG" on stderr, return 1
#   deploy_addr_class ADDR          print one verdict word: private, loopback,
#                                   public, wildcard or empty; exit 0 for
#                                   private/loopback, 1 otherwise
#   deploy_url_host ADDR            print ADDR as a URL host: [ADDR] when it
#                                   contains a colon (IPv6) or is the wildcard
#                                   `*`; one bracket pair already present is
#                                   kept; anything else unchanged
#   deploy_health_urls BIND S W WEB print three lines, one per health URL:
#                                   http://HOST:S/health, http://HOST:W/health
#                                   and http://HOST:WEB/health, HOST from
#                                   deploy_url_host
#   deploy_env_mode_ok PATH         exit 0 iff PATH is a regular file, mode 0600
#   deploy_compose_version_ok ANSWER exit 0 iff the compose version named by a
#                                   `docker compose version` ANSWER has a MAJOR
#                                   component that is numerically >= 2; 1.x,
#                                   an empty answer and unparsable text fail
#   deploy_preflight                check ENV_FILE, COMPOSE_FILE, STAGING_PARENT
#   deploy_check_health URL...      run health-check.sh, retrying every
#                                   HEALTH_STEP seconds (default 2) until it
#                                   passes or HEALTH_WAIT seconds (default 60)
#                                   are spent; 0 on a pass, else the last code
#   deploy_record_schema OUT DB...  run backup-data.py --record-schema
#   deploy_has_data DB...           run backup-data.py --has-data, return its code
#   deploy_db_paths                 print the two host database paths, one per
#                                   line; an absent volume prints an empty line
#   deploy_running_image_tag        print the image tag of EVERY running
#                                   breaker service: the one tag when they all
#                                   agree; `mixed: svc=tag, ...` when they
#                                   DIFFER; `unknown` when nothing is running
#                                   or ANY read fails. Exit 0 only when the
#                                   answer is a single tag
#
# POSIX sh, no bashisms. It never prints an environment VALUE, only a NAME or
# a verdict word. The address ranges are the ones in scripts/vpn-bind.sh; the
# pinning test in tests/unit/server/deploy/test_deploy_common.py proves the
# two agree address by address.
#
# A sourced file has no path of its own, so the sibling scripts are found
# through SCRIPT_DIR (every caller sets it before sourcing) or, failing that,
# through the sourcing script's own path.

# CDPATH= (empty) is deliberate: it stops cd from printing the resolved path.
# shellcheck disable=SC1007
DEPLOY_SCRIPTS_DIR=${SCRIPT_DIR:-$(CDPATH= cd "$(dirname "$0")" && pwd)}

deploy_fail() {
    printf 'deploy: %s\n' "$*" >&2
    return 1
}

# deploy_addr_class ADDR -> one verdict word on stdout; 0 private/loopback.
# The ranges are exactly vpn-bind.sh's: 10/8, 172.16/12 and the RFC1918
# third block, 100.64/10, 169.254/16, IPv6 fc00::/7 and fe80::/10, plus
# loopback. A wildcard spelling is refused, and anything else that is not
# private is public. ss writes IPv6 in brackets, so one bracket pair is accepted.
deploy_addr_class() {
    _dac_addr=${1:-}
    case "$_dac_addr" in
        \[*\]) _dac_addr=${_dac_addr#[}; _dac_addr=${_dac_addr%]} ;;
    esac
    if [ -z "$_dac_addr" ]; then
        printf 'empty\n'
        return 1
    fi
    case "$_dac_addr" in
        '*'|'0.0.0.0'|'::')
            printf 'wildcard\n'
            return 1
            ;;
    esac
    case "$_dac_addr" in
        *:*)
            # IPv6 is classed by the first group as ss writes it, exactly as
            # vpn-bind.sh does: fc00::/7 is fc/fd plus two hex digits, and
            # fe80::/10 is fe8x to febx. Short forms and site-local are
            # public, so the check fails closed.
            case "$_dac_addr" in
                ::1) printf 'loopback\n'; return 0 ;;
                [fF][cCdD][0-9a-fA-F][0-9a-fA-F]:*) printf 'private\n'; return 0 ;;
                [fF][eE][89abAB][0-9a-fA-F]:*) printf 'private\n'; return 0 ;;
            esac
            printf 'public\n'
            return 1
            ;;
    esac
    case "$_dac_addr" in
        127.*) printf 'loopback\n'; return 0 ;;
        10.*) printf 'private\n'; return 0 ;;
        192.168.*) printf 'private\n'; return 0 ;;
        169.254.*) printf 'private\n'; return 0 ;;
        172.*)
            case "$_dac_addr" in
                172.1[6-9].*) printf 'private\n'; return 0 ;;
                172.2[0-9].*) printf 'private\n'; return 0 ;;
                172.3[01].*) printf 'private\n'; return 0 ;;
            esac
            ;;
        100.*)
            case "$_dac_addr" in
                100.6[4-9].*) printf 'private\n'; return 0 ;;
                100.[789][0-9].*) printf 'private\n'; return 0 ;;
                100.1[01][0-9].*) printf 'private\n'; return 0 ;;
                100.12[0-7].*) printf 'private\n'; return 0 ;;
            esac
            ;;
    esac
    printf 'public\n'
    return 1
}

# deploy_url_host ADDR -> ADDR spelled as a URL host, one line, nothing else.
# An IPv6 literal is bracketed (`fd00::5` -> `[fd00::5]`) because `host:port`
# is ambiguous otherwise; one bracket pair already present is kept as it is.
# The wildcard `*` is bracketed too, so a health URL never carries a bare glob
# character. A plain IPv4 address or hostname is printed unchanged.
deploy_url_host() {
    _duh_addr=${1:-}
    case "$_duh_addr" in
        \[*\]) _duh_addr=${_duh_addr#[}; _duh_addr=${_duh_addr%]} ;;
    esac
    case "$_duh_addr" in
        *:*|'*') printf '[%s]\n' "$_duh_addr" ;;
        *) printf '%s\n' "$_duh_addr" ;;
    esac
}

# deploy_health_urls BIND SYNC_PORT WHISPER_PORT WEB_PORT -> three lines, one
# per health URL, host taken from deploy_url_host. Nothing else on stdout; a
# usage diagnostic goes to stderr through deploy_fail.
deploy_health_urls() {
    [ $# -eq 4 ] || {
        deploy_fail "deploy_health_urls requires BIND SYNC_PORT WHISPER_PORT WEB_PORT"
        return 2
    }
    _dhu_host=$(deploy_url_host "$1")
    printf 'http://%s:%s/health\n' "$_dhu_host" "$2"
    printf 'http://%s:%s/health\n' "$_dhu_host" "$3"
    printf 'http://%s:%s/health\n' "$_dhu_host" "$4"
}

# deploy_env_mode_ok PATH -> 0 iff PATH is a regular file whose mode is
# exactly 0600 (no group/other bits, no special bits). Prints nothing.
deploy_env_mode_ok() {
    [ -f "${1:-}" ] || return 1
    [ "$(stat -c '%a' "$1" 2>/dev/null)" = "600" ]
}

# deploy_dir_writable DIR -> 0 iff DIR is a directory whose owner-write bit
# is set. Root bypasses `test -w`, so the mode itself is read.
deploy_dir_writable() {
    [ -d "${1:-}" ] || return 1
    _ddw_mode=$(stat -c '%a' "$1" 2>/dev/null) || return 1
    [ -n "$_ddw_mode" ] || return 1
    _ddw_owner=$(printf '%s\n' "$_ddw_mode" | sed 's/.*\(.\)\(..\)$/\1/')
    case "$_ddw_owner" in
        2|3|6|7) return 0 ;;
        *) return 1 ;;
    esac
}

# deploy_env_value NAME -> the last NAME= value in ENV_FILE. Captured by the
# caller, never printed, so no value reaches a log or a message.
deploy_env_value() {
    sed -n "s/^$1=//p" "${ENV_FILE:-}" 2>/dev/null | tail -n 1
}

# deploy_required_names COMPOSE -> the union of the ${NAME:?...} names of the
# compose file and the NAME= lines of the .env.example beside it, one per
# line. A missing compose file contributes nothing; the caller still checks
# that the compose file exists.
deploy_required_names() {
    _drn_compose=${1:-}
    _drn_names=""
    if [ -n "$_drn_compose" ] && [ -f "$_drn_compose" ]; then
        _drn_names=$(awk '
            {
                s = $0
                while (match(s, /\$\{[A-Z][A-Z0-9_]*:\?[^}]*\}/)) {
                    tok = substr(s, RSTART, RLENGTH)
                    rest = substr(tok, 3)
                    q = index(rest, ":?")
                    if (q > 0) print substr(rest, 1, q - 1)
                    s = substr(s, RSTART + RLENGTH)
                }
            }
        ' "$_drn_compose" | sort -u)
    fi
    if [ -n "$_drn_compose" ]; then
        _drn_example=$(dirname "$_drn_compose")/.env.example
        if [ -f "$_drn_example" ]; then
            _drn_names=$(printf '%s\n%s\n' "$_drn_names" \
                "$(sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' "$_drn_example")" \
                | sort -u)
        fi
    fi
    printf '%s\n' "$_drn_names" | sort -u | grep -v '^$'
}

# deploy_compose_version_ok ANSWER -> 0 iff the compose version named by a
# `docker compose version` ANSWER has a MAJOR component that is NUMERICALLY
# >= 2. The whole answer is read, so both "Docker Compose version v2.38.1" and
# a bare "v5.6.0" work and a leading "v" is optional. An empty answer, a 1.x
# version and any text with no parsable version number are refused (1). The
# caller runs `docker compose version` itself, so a docker that fails stays the
# caller's own hard failure and the two diagnostics ("not available" versus
# "not v2") remain distinct. One rule, shared: install.sh, update.sh and
# rollback.sh call this instead of keeping a copy.
deploy_compose_version_ok() {
    _dcv_answer=${1:-}
    _dcv_num=$(printf '%s\n' "$_dcv_answer" | sed -n \
        -e 's/^.*version[[:space:]][[:space:]]*v\{0,1\}\([0-9][0-9.]*\).*$/\1/p' \
        -e 's/^v\{0,1\}\([0-9][0-9.]*\)[[:space:]]*$/\1/p')
    [ -n "$_dcv_num" ] || return 1
    _dcv_major=${_dcv_num%%.*}
    case "$_dcv_major" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "$_dcv_major" -ge 2 ]
}

# deploy_preflight reads the caller's globals ENV_FILE, COMPOSE_FILE and
# STAGING_PARENT. Each check prints one line and returns 1 on failure. The
# bind check runs before the required-NAME loop so an empty BREAKER_BIND_ADDR
# is reported as the verdict word "empty" (and so removing that arm alone is
# observable, which the addendum's mutant requires).
deploy_preflight() {
    _dp_env=${ENV_FILE:-}
    _dp_compose=${COMPOSE_FILE:-}
    _dp_staging=${STAGING_PARENT:-}

    # 1. the env file's mode is exactly 0600; deploy_env_mode_ok also refuses
    #    a missing or non-regular file, so this one guard decides both
    if ! deploy_env_mode_ok "$_dp_env"; then
        deploy_fail "ENV_FILE mode is not 0600"
        return 1
    fi
    # 2. the bind address class: private or loopback only
    _dp_bind=$(deploy_addr_class "$(deploy_env_value BREAKER_BIND_ADDR)") || true
    case "$_dp_bind" in
        private|loopback) ;;
        *)
            deploy_fail "BREAKER_BIND_ADDR is ${_dp_bind:-empty}"
            return 1
            ;;
    esac
    # 3. every required NAME of the compose file and .env.example is present
    #    with a non-empty value, except BREAKER_IMAGE_TAG (the caller sets it
    #    from the release tag)
    _dp_names=$(deploy_required_names "$_dp_compose")
    _dp_bad=""
    while IFS= read -r _dp_name; do
        [ -n "$_dp_name" ] || continue
        [ "$_dp_name" = "BREAKER_IMAGE_TAG" ] && continue
        if [ -z "$(deploy_env_value "$_dp_name")" ]; then
            _dp_bad=$_dp_name
            break
        fi
    done <<EOF
$_dp_names
EOF
    if [ -n "$_dp_bad" ]; then
        deploy_fail "$_dp_bad is missing or empty"
        return 1
    fi
    # 4. the required tools: only what the scripts really invoke. The sqlite3
    #    CLI is not one of them (every schema read goes through python3's
    #    sqlite3 module), so the module import is checked instead of a binary
    #    nothing calls. gradlew is checked later, in the checkout, by the build.
    for _dp_tool in git docker curl python3; do
        if ! command -v "$_dp_tool" >/dev/null 2>&1; then
            deploy_fail "required tool missing: $_dp_tool"
            return 1
        fi
    done
    if ! python3 -c 'import sqlite3' >/dev/null 2>&1; then
        deploy_fail "python3 cannot import the sqlite3 module"
        return 1
    fi
    # 5. docker compose must be v2 or newer: the MAJOR is compared
    #    numerically, so v2.38.1 and v5.6.0 pass while docker-compose v1
    #    (1.29.2) is refused. deploy_compose_version_ok owns the rule.
    _dp_cv=$(docker compose version 2>/dev/null) || {
        deploy_fail "docker compose version is not available (compose v2 is required)"
        return 1
    }
    if ! deploy_compose_version_ok "$_dp_cv"; then
        deploy_fail "docker compose version is not v2"
        return 1
    fi
    # 6. buildx is optional: missing buildx is a NOTE, not a failure
    if ! docker buildx version >/dev/null 2>&1; then
        echo "deploy: NOTE docker buildx is not available"
    fi
    # 7. the compose file exists
    if [ ! -f "$_dp_compose" ]; then
        deploy_fail "COMPOSE_FILE is missing or not a regular file"
        return 1
    fi
    # 8. the staging parent is a writable directory
    if ! deploy_dir_writable "$_dp_staging"; then
        deploy_fail "STAGING_PARENT is not a writable directory"
        return 1
    fi
    return 0
}

# deploy_check_health URL [URL...] -> health-check.sh --url U --url U ...
# --timeout "$TIMEOUT", retried until it passes or the wait bound is spent.
#
# A release that is still coming up must not false-fail the deploy, so the
# single-GET helper is called again every HEALTH_STEP seconds (default 2)
# until it returns 0 or the total budget of HEALTH_WAIT seconds (default 60)
# is used up; after the bound the LAST exit code is returned, so a permanently
# failing release still fails closed. HEALTH_STEP=0 spends the budget one try
# per unit and never sleeps, which is how the tests pin the exact try count.
# health-check.sh itself stays one GET per URL: the retry lives here only, so
# every caller (install, update, post-rollback, post-recovery) gets the wait.
deploy_check_health() {
    [ $# -ge 1 ] || {
        deploy_fail "deploy_check_health requires at least one URL"
        return 2
    }
    _dch_timeout=${TIMEOUT:-5}
    _dch_step=${HEALTH_STEP:-2}
    _dch_wait=${HEALTH_WAIT:-60}
    case "$_dch_step" in
        ''|*[!0-9]*) _dch_step=2 ;;
    esac
    case "$_dch_wait" in
        ''|*[!0-9]*) _dch_wait=60 ;;
    esac
    if [ "$_dch_step" -eq 0 ]; then
        _dch_max=$_dch_wait
    else
        _dch_max=$(( _dch_wait / _dch_step ))
    fi
    [ "$_dch_max" -ge 1 ] || _dch_max=1
    _dch_urls=""
    for _dch_u in "$@"; do
        _dch_urls="$_dch_urls --url $_dch_u"
    done
    # The URLs are caller-supplied literals; split them back into words.
    # shellcheck disable=SC2086
    set -- $_dch_urls
    _dch_rc=1
    _dch_try=0
    while [ "$_dch_try" -lt "$_dch_max" ]; do
        _dch_rc=0
        "$DEPLOY_SCRIPTS_DIR/health-check.sh" "$@" --timeout "$_dch_timeout" || _dch_rc=$?
        [ "$_dch_rc" -eq 0 ] && return 0
        _dch_try=$((_dch_try + 1))
        [ "$_dch_try" -lt "$_dch_max" ] || break
        sleep "$_dch_step"
    done
    return "$_dch_rc"
}

# deploy_record_schema OUTFILE DB [DB...] -> python3 backup-data.py
# --record-schema OUTFILE --db DB [--db DB...]; returns its exit code.
deploy_record_schema() {
    [ $# -ge 2 ] || {
        deploy_fail "deploy_record_schema requires OUTFILE and at least one DB"
        return 2
    }
    _drs_out=$1
    shift
    # The --db argv is positional parameters built from individually QUOTED
    # values, never a concatenated string word-split at the call site: a host
    # path containing a space must survive as ONE argument. POSIX sh has no
    # arrays, so each path is consumed from the FRONT of $@ while its flag
    # pair is appended at the BACK; the loop bound is the original count.
    _drs_n=$#
    _drs_i=0
    while [ "$_drs_i" -lt "$_drs_n" ]; do
        _drs_db=$1
        shift
        set -- "$@" --db "$_drs_db"
        _drs_i=$((_drs_i + 1))
    done
    python3 "$DEPLOY_SCRIPTS_DIR/backup-data.py" --record-schema "$_drs_out" "$@"
}

# deploy_has_data DB [DB...] -> python3 backup-data.py --has-data --db DB ...
# Returns its exit code: 0 at least one database exists as a regular file,
# 3 none does, 2 a refusal.
deploy_has_data() {
    [ $# -ge 1 ] || {
        deploy_fail "deploy_has_data requires at least one DB"
        return 2
    }
    # The --db argv is positional parameters built from individually QUOTED
    # values, never a concatenated string word-split at the call site: a host
    # path containing a space must survive as ONE argument. POSIX sh has no
    # arrays, so each path is consumed from the FRONT of $@ while its flag
    # pair is appended at the BACK; the loop bound is the original count.
    _dhd_n=$#
    _dhd_i=0
    while [ "$_dhd_i" -lt "$_dhd_n" ]; do
        _dhd_db=$1
        shift
        set -- "$@" --db "$_dhd_db"
        _dhd_i=$((_dhd_i + 1))
    done
    python3 "$DEPLOY_SCRIPTS_DIR/backup-data.py" --has-data "$@"
}

# deploy_db_paths -> one HOST database path per line (sync-api, whisper-server).
#
# The volume keys come from COMPOSE_FILE's top-level `volumes:` section; docker
# prefixes them with the compose project name (`name:`), so the real volume is
# PROJECT_KEY. Each service's own `volumes:` block says where that key is
# mounted and its `environment:` gives BREAKER_SYNC_API_DB / BREAKER_WHISPER_DB
# (ENV_FILE wins when it sets the name). The host path is the volume's
# Mountpoint from `docker volume inspect -f '{{.Mountpoint}}'` plus the part of
# the container path below the mount - never a hard-coded path. Existence is
# decided ONCE, up front, by `docker volume ls --format '{{.Name}}'` matched as
# a WHOLE LINE: this runs BEFORE `up` creates the volumes, so asking
# `inspect` first would make a first install fail forever. A volume NOT listed
# is ABSENT and prints an EMPTY line for its database; `inspect` is only called
# for a LISTED volume, and a listed volume whose inspect fails or answers no
# mountpoint stays a HARD failure naming that volume. A failing `ls` itself
# (daemon down, permission) is a HARD failure naming the cause - never "no
# such volume". Prints nothing else and never an environment VALUE.
deploy_db_paths() {
    _ddp_compose=${COMPOSE_FILE:-}
    if [ -z "$_ddp_compose" ] || [ ! -f "$_ddp_compose" ]; then
        deploy_fail "COMPOSE_FILE is missing or not a regular file"
        return 1
    fi
    _ddp_project=$(awk '/^name:[[:space:]]*/ { sub(/^name:[[:space:]]*/, ""); print; exit }' "$_ddp_compose")
    if [ -z "$_ddp_project" ]; then
        deploy_fail "COMPOSE_FILE declares no top-level name:"
        return 1
    fi
    _ddp_records=$(awk '
        function trim(s) { gsub(/^[[:space:]]+|[[:space:]]+$/, "", s); return s }
        BEGIN { sec = ""; attr = "" }
        /^#/ { next }
        /^[[:space:]]*$/ { next }
        /^services:[[:space:]]*$/ { sec = "services"; attr = ""; next }
        /^volumes:[[:space:]]*$/ { sec = "volumes"; next }
        /^[A-Za-z_][A-Za-z0-9_]*:[[:space:]]*$/ { sec = "other"; attr = ""; next }
        sec == "services" && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ {
            svc = $1; sub(/:$/, "", svc); attr = ""; next
        }
        sec == "services" && /^    [A-Za-z0-9_-]+:[[:space:]]*$/ {
            attr = $1; sub(/:$/, "", attr); next
        }
        sec == "services" && attr == "environment" && /^      [A-Za-z_][A-Za-z0-9_]*:/ {
            nm = $1; sub(/:$/, "", nm)
            val = substr($0, index($0, ":") + 1); val = trim(val)
            if (val ~ /^".*"$/) val = substr(val, 2, length(val) - 2)
            print "env\t" svc "\t" nm "\t" val
            next
        }
        sec == "services" && attr == "volumes" && /^      -[[:space:]]+/ {
            rest = $0; sub(/^      -[[:space:]]+/, "", rest)
            line = rest; sub(/[[:space:]].*$/, "", line)
            n = split(line, pp, ":")
            if (n >= 2) print "vmount\t" svc "\t" pp[1] "\t" pp[2]
            next
        }
        sec == "volumes" && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ {
            key = $1; sub(/:$/, "", key); print "topvol\t" key; next
        }
    ' "$_ddp_compose")
    # The daemon's volume list, cached once for both databases. `inspect`
    # cannot answer "absent", it only fails, and on a first install the
    # volumes genuinely do not exist yet - so it must not be asked first.
    _ddp_list=''
    if ! _ddp_list=$(docker volume ls --format '{{.Name}}'); then
        deploy_fail "cannot list docker volumes: docker volume ls failed"
        return 1
    fi
    _ddp_out=''
    for _ddp_spec in 'sync-api|BREAKER_SYNC_API_DB' 'whisper-server|BREAKER_WHISPER_DB'; do
        _ddp_svc=${_ddp_spec%%|*}
        _ddp_var=${_ddp_spec##*|}
        _ddp_db=$(deploy_env_value "$_ddp_var")
        if [ -z "$_ddp_db" ]; then
            _ddp_db=$(printf '%s\n' "$_ddp_records" | awk -F '\t' \
                -v s="$_ddp_svc" -v n="$_ddp_var" \
                '$1 == "env" && $2 == s && $3 == n { print $4; exit }')
        fi
        if [ -z "$_ddp_db" ]; then
            deploy_fail "$_ddp_var is set neither in ENV_FILE nor in the $_ddp_svc environment"
            return 1
        fi
        _ddp_key=''
        _ddp_at=''
        while IFS=$(printf '\t') read -r _ddp_kind _ddp_rsvc _ddp_rkey _ddp_rcont; do
            [ "$_ddp_kind" = "vmount" ] || continue
            [ "$_ddp_rsvc" = "$_ddp_svc" ] || continue
            case "$_ddp_db" in
                "$_ddp_rcont"|"$_ddp_rcont"/*) _ddp_key=$_ddp_rkey; _ddp_at=$_ddp_rcont; break ;;
            esac
        done <<EOF
$_ddp_records
EOF
        if [ -z "$_ddp_key" ]; then
            deploy_fail "no named volume mounts the $_ddp_svc database $_ddp_db"
            return 1
        fi
        _ddp_known=0
        while IFS=$(printf '\t') read -r _ddp_tkind _ddp_tkey; do
            [ "$_ddp_tkind" = "topvol" ] || continue
            [ "$_ddp_tkey" = "$_ddp_key" ] && _ddp_known=1
        done <<EOF
$_ddp_records
EOF
        if [ "$_ddp_known" -ne 1 ]; then
            deploy_fail "no top-level volume named $_ddp_key"
            return 1
        fi
        _ddp_real="${_ddp_project}_${_ddp_key}"
        _ddp_listed=0
        while IFS= read -r _ddp_vol; do
            [ "$_ddp_vol" = "$_ddp_real" ] && _ddp_listed=1
        done <<EOF
$_ddp_list
EOF
        if [ "$_ddp_listed" -ne 1 ]; then
            # ABSENT, not unresolvable: `up` has not created it yet (first
            # install) or it was removed. The caller reads an empty path and
            # treats it as "nothing to back up".
            _ddp_out="${_ddp_out}
"
            continue
        fi
        _ddp_host=$(docker volume inspect -f '{{.Mountpoint}}' "$_ddp_real") || {
            deploy_fail "cannot resolve volume $_ddp_real"
            return 1
        }
        [ -n "$_ddp_host" ] || {
            deploy_fail "volume $_ddp_real has no mountpoint"
            return 1
        }
        case "$_ddp_db" in
            "$_ddp_at"/*) _ddp_suffix=${_ddp_db#"$_ddp_at"/} ;;
            *) _ddp_suffix=${_ddp_db##*/} ;;
        esac
        _ddp_out="${_ddp_out}${_ddp_host}/${_ddp_suffix}
"
    done
    printf '%s' "$_ddp_out"
}

# deploy_running_image_tag -> the image tag of EVERY running breaker service.
#
# The compose project's RUNNING services are listed with `docker compose ps`
# (one `SERVICE IMAGE` line per running container; a service that is not
# running is absent, so "nothing running" is an empty list), and each image
# reference is reduced to its tag. The answer is:
#   - the one tag, when every running service agrees (exit 0);
#   - `mixed: sync-api=1.2.0, web-fe=1.1.0` (services sorted by name) when
#     they DIFFER - the running tag is then UNKNOWN for a recovery decision
#     (exit 1);
#   - `unknown` when nothing is running, when the project name cannot be read
#     from COMPOSE_FILE, when `ps` fails, or when ANY service's image carries
#     no readable tag (exit 1).
# One container is never guessed from: the whole running set is read. The
# caller separates "a tag" from "unknown" by the EXIT CODE, never by parsing
# the answer. Prints nothing else; the tag is a release tag, not a secret.
deploy_running_image_tag() {
    _drit_compose=${COMPOSE_FILE:-}
    if [ -z "$_drit_compose" ] || [ ! -f "$_drit_compose" ]; then
        printf 'unknown\n'
        return 1
    fi
    _drit_project=$(awk '/^name:[[:space:]]*/ { sub(/^name:[[:space:]]*/, ""); print; exit }' "$_drit_compose")
    if [ -z "$_drit_project" ]; then
        printf 'unknown\n'
        return 1
    fi
    _drit_ps=$(docker compose -p "$_drit_project" ps --format '{{.Service}} {{.Image}}' 2>/dev/null) || {
        printf 'unknown\n'
        return 1
    }
    _drit_pairs=''
    while IFS= read -r _drit_line; do
        [ -n "$_drit_line" ] || continue
        _drit_svc=${_drit_line%% *}
        _drit_img=${_drit_line#* }
        # The tag is what follows the LAST colon, and only when that colon is
        # after the last slash (a registry port is not a tag). A digest-pinned
        # reference (@sha256:...) carries no tag at all.
        _drit_base=${_drit_img##*/}
        _drit_tag=''
        case "$_drit_img" in
            *@*) : ;;
            *)
                case "$_drit_base" in
                    *:*) _drit_tag=${_drit_base##*:} ;;
                esac
                ;;
        esac
        # A service whose tag cannot be read makes the WHOLE answer unknown:
        # the services that did answer are never used to guess.
        [ -n "$_drit_tag" ] || {
            printf 'unknown\n'
            return 1
        }
        _drit_pairs="$_drit_pairs$_drit_svc=$_drit_tag
"
    done <<EOF
$_drit_ps
EOF
    [ -n "$_drit_pairs" ] || {
        printf 'unknown\n'
        return 1
    }
    _drit_sorted=$(printf '%s\n' "$_drit_pairs" | sort -u | grep -v '^$')
    _drit_tags=$(printf '%s\n' "$_drit_sorted" | sed -n 's/^[^=]*=//p')
    _drit_first=$(printf '%s\n' "$_drit_tags" | sed -n '1p')
    _drit_all=1
    while IFS= read -r _drit_t; do
        [ -n "$_drit_t" ] || continue
        [ "$_drit_t" = "$_drit_first" ] || _drit_all=0
    done <<EOF
$_drit_tags
EOF
    if [ "$_drit_all" -eq 1 ]; then
        printf '%s\n' "$_drit_first"
        return 0
    fi
    _drit_detail=$(printf '%s\n' "$_drit_sorted" | awk '{ if (NR > 1) printf ", "; printf "%s", $0 } END { printf "\n" }')
    printf 'mixed: %s\n' "$_drit_detail"
    return 1
}
