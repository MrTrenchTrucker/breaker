"""Pins for the deploy image recipes under server/modules/deploy/images/.

Every test READS the recipe files; nothing is built or run. Each guard below has
a control test that mutates the REAL file with one edit, checks the text really
changed, and requires the guard's own message. The guards cover: pinned base
tag, COPY only inside the service's own trees, non-root USER set after every
root-requiring step, no secret-looking ENV/ARG default, no EXPOSE line in any
recipe, exec-form ENTRYPOINT on the service launcher, the single-layer apt
install, the data directory owned before the volume, the web-fe nginx package
and foreground runtime, and the allowlist shape/coverage/width/equality of
Dockerfile.dockerignore (deny-first, allow-only, allow tree, no secret-looking
allow, no whole-tree wildcard, the allow lines equal the COPY-derived set, and
every COPY source reachable through the allow lines).

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_images.py'
"""
import os
import re
import unittest

ROOT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    '..', '..', '..', '..',
))

SERVICES = ('sync-api', 'whisper-server', 'web-fe')
JVM_SERVICES = ('sync-api', 'whisper-server')

# The module directory of each service, and the recipe assets that belong to it.
MODULE_DIR = {
    'sync-api': 'server/modules/sync-api/',
    'whisper-server': 'server/modules/whisper-server/',
    'web-fe': 'server/modules/web-fe/',
}
OWNED_ROOTS = {
    'sync-api': ('server/modules/sync-api/',),
    'whisper-server': ('server/modules/whisper-server/',),
    'web-fe': ('server/modules/web-fe/src/main/resources/web/',
               'server/modules/web-fe/build/web/js/vendor/',
               'server/modules/deploy/images/web-fe/'),
}

BASE_TAG = {
    'sync-api': 'eclipse-temurin:25-jre',
    'whisper-server': 'eclipse-temurin:25-jre',
    'web-fe': 'debian:trixie-slim',
}

# The one named shared file web-fe serves, and the exact allow line that names
# it. No wildcard, no folder allow: the ruling amends "own tree only" to "own
# tree plus this one named shared file".
WEB_FE_SHARED_FILE = 'shared/modules/ui-tokens/src/main/resources/tokens.css'
WEB_FE_TOKENS_ALLOW = '!' + WEB_FE_SHARED_FILE
# The install-tree leaf the JVM allowlists must hold: the one COPY-needed
# '**' line. It is the only '**' a JVM allowlist has.
INSTALL_ALLOW = {
    'sync-api': '!server/modules/sync-api/build/install/sync-api/**',
    'whisper-server': '!server/modules/whisper-server/build/install/whisper-server/**',
}
# The two COPY-needed '**' leaves the web-fe allowlist must hold: the served
# static resources tree and the vendored copy folder copyWebLibs writes. They
# are the only '**' lines a web-fe allowlist has.
WEB_FE_STATIC_ALLOWS = (
    '!server/modules/web-fe/src/main/resources/web/**',
    '!server/modules/web-fe/build/web/js/vendor/**',
)

# The web-fe site template, and the exact /health answer the host probe needs:
# HTTP 200 with the body {"status":"ok"} and a JSON content type.
WEB_FE_TEMPLATE_REL = 'server/modules/deploy/images/web-fe/nginx.conf.template'
WEB_FE_HEALTH_LOCATION = 'location = /health {'
WEB_FE_HEALTH_BODY = '{"status":"ok"}'

# The web-fe copied trees uid 10001 must read: the served html tree and the
# two recipe assets. A restrictive host umask (077) otherwise bakes 0600
# modes into the image through COPY.
WEB_FE_HTML_TREE = '/usr/share/nginx/html'
WEB_FE_READABLE_ASSETS = (
    '/etc/nginx/nginx.conf.template',
    '/usr/local/bin/docker-entrypoint.sh',
)

SECRET_NAME_RE = re.compile(r'(?i)(KEY|TOKEN|PASSWORD|SECRET)')
SECRET_BASENAME_RE = re.compile(r'(?i)^(\.env|.*\.(key|jks|p12|pem|keystore))$')


# -- file helpers (no guards) -------------------------------------------------

def read_text(rel):
    with open(os.path.join(ROOT, rel), encoding='utf-8') as handle:
        return handle.read()


def dockerfile_rel(service):
    return 'server/modules/deploy/images/%s/Dockerfile' % service


def ignore_rel(service):
    return 'server/modules/deploy/images/%s/Dockerfile.dockerignore' % service


def noncomment_lines(text):
    out = []
    for raw in text.splitlines():
        stripped = raw.strip()
        if stripped and not stripped.startswith('#'):
            out.append(stripped)
    return out


def copy_sources(text):
    out = []
    for raw in text.splitlines():
        stripped = raw.strip()
        if not stripped.startswith('COPY '):
            continue
        parts = stripped.split()
        index = 1
        while index < len(parts) and parts[index].startswith('--'):
            index += 1
        rest = parts[index:]
        if rest:
            out.append(rest[0])
    return out


def run_blocks(text):
    return re.findall(r'(?ms)^RUN .*?(?=^\S|\Z)', text)


# -- guards: each returns its own message, or None ----------------------------

def _from_problem(service, text):
    """G1: FROM must be the pinned base tag, never a moving tag."""
    tag = BASE_TAG[service]
    if not re.search(r'(?m)^FROM %s[ \t]*$' % re.escape(tag), text):
        return "%s: FROM must be the pinned base '%s'" % (service, tag)
    return None


def _under_owned(service, path):
    cleaned = path.strip().rstrip('/')
    for root in OWNED_ROOTS[service]:
        root = root.rstrip('/')
        if cleaned == root or cleaned.startswith(root + '/'):
            return True
    return False


def _copy_tree_problem(service, text):
    """G2: every COPY source must sit inside the service's own trees, or be the
    one named shared file web-fe serves."""
    bad = [source for source in copy_sources(text)
           if not _under_owned(service, source)
           and source != WEB_FE_SHARED_FILE]
    if bad:
        return ("%s: COPY source outside the service's own tree: %s"
                % (service, ', '.join(bad)))
    return None


def _user_problem(service, text):
    """G3: a fixed non-root USER, set after every root-requiring step."""
    instructions = []
    for raw in text.splitlines():
        stripped = raw.strip()
        if stripped and not stripped.startswith('#'):
            instructions.append(stripped)
    users = [line for line in instructions if line.startswith('USER ')]
    if not users:
        return "%s: no USER instruction; the container must not run as root" % service
    value = users[-1][len('USER '):].strip()
    if value in ('root', '0'):
        return "%s: USER must name the fixed non-root account, not '%s'" % (service, value)
    last_user = max(index for index, line in enumerate(instructions)
                    if line.startswith('USER '))
    after = instructions[last_user + 1:]
    if any(line.startswith(('RUN ', 'COPY ')) for line in after):
        return ("%s: USER must come after every RUN and COPY "
                "(nothing root-requiring after it)" % service)
    return None


def _secret_env_problem(service, text):
    """G4: no ENV/ARG default on a secret-looking variable name."""
    pattern = re.compile(
        r'(?m)^(ENV|ARG)[ \t]+([A-Za-z_][A-Za-z0-9_]*)(?:=([^\s]*))?[ \t]*$')
    for match in pattern.finditer(text):
        kind, name, value = match.group(1), match.group(2), match.group(3)
        if SECRET_NAME_RE.search(name) and value:
            return ("%s: %s %s carries a default value; a secret-looking variable "
                    "must be passed in, not baked in" % (service, kind, name))
    return None


def _expose_problem(service, text):
    """G5: no EXPOSE line at all. EXPOSE is documentation only and the compose
    file publishes the ports, so a recipe must not carry one."""
    if re.search(r'(?m)^EXPOSE\b', text):
        return ("%s: no EXPOSE line is allowed; the ports are published by the "
                "compose file, not documented in the recipe" % service)
    return None


def _entry_problem(service, text):
    """G6: exec-form ENTRYPOINT on the service's own launcher."""
    match = re.search(r'(?m)^ENTRYPOINT[ \t]+(.+?)[ \t]*$', text)
    if not match:
        return "%s: no ENTRYPOINT instruction" % service
    line = match.group(1).strip()
    if service in JVM_SERVICES:
        target = '/opt/breaker/%s/bin/%s' % (service, service)
        if not (line.startswith('[') and '"%s"' % target in line):
            return ("%s: ENTRYPOINT must be exec form on the installDist "
                    "launcher %s" % (service, target))
    else:
        if not (line.startswith('[') and '/docker-entrypoint.sh"' in line):
            return ("web-fe: ENTRYPOINT must be exec form on "
                    "/usr/local/bin/docker-entrypoint.sh")
    return None


def _apt_curl_problem(service, text):
    """G7: curl installed and the apt lists removed in the same RUN layer."""
    if service not in JVM_SERVICES:
        return None
    ok = any(('apt-get install' in block and 'curl' in block
              and 'rm -rf /var/lib/apt/lists/*' in block)
             for block in run_blocks(text))
    if not ok:
        return ("%s: curl must be installed and the apt lists removed in the "
                "same RUN layer" % service)
    return None


def _data_dir_problem(service, text):
    """G8: the data directory is created and owned before the volume mounts."""
    if service not in JVM_SERVICES:
        return None
    directory = '/var/lib/breaker/%s' % service
    ok = any(('mkdir -p %s' % directory in block
              and 'chown breaker:breaker %s' % directory in block)
             for block in run_blocks(text))
    if not ok:
        return ("%s: %s must be created and owned by the non-root user "
                "before the volume mounts" % (service, directory))
    return None


def _nginx_package_problem(service, text):
    """G9: web-fe installs nginx-light from the Debian archive, one layer."""
    if service != 'web-fe':
        return None
    ok = any(('apt-get install' in block and 'nginx-light' in block
              and 'rm -rf /var/lib/apt/lists/*' in block)
             for block in run_blocks(text))
    if not ok:
        return ("web-fe: nginx-light must be installed from the Debian archive "
                "in the same layer as the apt cleanup")
    return None


def _nginx_runtime_problem(service, text):
    """G10: web-fe renders the config at start and runs nginx in the foreground."""
    if service != 'web-fe':
        return None
    foreground = re.search(
        r'(?m)^CMD[ \t]+\["nginx",[ \t]*"-c",[ \t]*"/var/lib/nginx/nginx\.conf",'
        r'[ \t]*"-g",[ \t]*"daemon off;"\]$', text)
    if not foreground:
        return ("web-fe: nginx must run in the foreground from the rendered "
                "config, as the non-root user")
    if ('server/modules/deploy/images/web-fe/nginx.conf.template'
            not in copy_sources(text)):
        return ("web-fe: the site config template must be copied into the image")
    if ('server/modules/deploy/images/web-fe/docker-entrypoint.sh'
            not in copy_sources(text)):
        return ("web-fe: the entrypoint that renders the config must be copied "
                "into the image")
    return None


def _web_fe_readable_problem(service, text):
    """G21: web-fe makes the copied trees world-readable, so a restrictive host
    umask cannot bake 0600 modes into the image the non-root account runs."""
    if service != 'web-fe':
        return None
    blocks = run_blocks(text)
    if not any('chmod' in block and '-R' in block and 'a+rX' in block
               and WEB_FE_HTML_TREE in block for block in blocks):
        return ("web-fe: the copied html tree %s must be made world-readable "
                "with 'chmod -R a+rX' (a restrictive host umask must not bake "
                "0600 modes into the image)" % WEB_FE_HTML_TREE)
    for asset in WEB_FE_READABLE_ASSETS:
        ok = any('chmod' in block and ('a+rx' in block or 'a+rX' in block)
                 and asset in block for block in blocks)
        if not ok:
            return ("web-fe: %s must be made world-readable/executable with "
                    "'chmod a+rx' (a restrictive host umask must not bake 0600 "
                    "modes into the image)" % asset)
    return None


def _nginx_health_problem(text):
    """G20: the web-fe template answers /health with the exact body and a JSON
    type, inside the server block, before location /, and with no substitution
    in the new lines (envsubst replaces only the port)."""
    server = re.search(r'(?ms)^    server \{.*?^    \}', text)
    if not server:
        return "web-fe: the template must carry a server { ... } block"
    block = server.group(0)
    health = re.search(r'(?ms)^[ \t]*location = /health \{.*?^[ \t]*\}', text)
    if not health:
        return ("web-fe: the server block must carry a /health location "
                "(location = /health { ... })")
    inner = health.group(0)
    if WEB_FE_HEALTH_LOCATION not in block:
        return ("web-fe: the /health location must sit inside the server "
                "{ ... } block")
    if 'default_type application/json;' not in inner:
        return ("web-fe: the /health location must set the JSON content type "
                "(default_type application/json;)")
    if "return 200 '%s';" % WEB_FE_HEALTH_BODY not in inner:
        return ("web-fe: the /health location must answer 200 with the exact "
                "body '%s'" % WEB_FE_HEALTH_BODY)
    if block.find(WEB_FE_HEALTH_LOCATION) > block.find('location /'):
        return "web-fe: the /health location must sit before location /"
    if '$' in inner:
        return ("web-fe: the /health lines must hold no '$' (envsubst replaces "
                "only ${BREAKER_WEB_FE_PORT})")
    return None


def _deny_first_problem(service, text):
    """G11: the first non-comment line is '*' (default deny)."""
    lines = noncomment_lines(text)
    if not lines or lines[0] != '*':
        return ("%s: the first non-comment line of Dockerfile.dockerignore "
                "must be '*' (default deny)" % service)
    return None


def _allow_shape_problem(service, text):
    """G12: every non-comment line after '*' is a '!' allow line."""
    lines = noncomment_lines(text)
    for line in lines[1:]:
        if not line.startswith('!'):
            return ("%s: every non-comment line after '*' must be a '!' allow "
                    "line (got %r)" % (service, line))
    return None


def _allow_path_ok(service, path):
    """True when `path` (an allow line's target, '/' stripped) is at or under
    one of the service's own trees, or is the one named shared file web-fe
    serves."""
    cleaned = path.strip().rstrip('/')
    for root in OWNED_ROOTS[service]:
        root = root.rstrip('/')
        if cleaned == root or cleaned.startswith(root + '/'):
            return True
    if cleaned == WEB_FE_SHARED_FILE:
        return True
    return False


def _allow_tree_problem(service, text):
    """G13: every allow line is at or under the service trees, or is the one
    named shared file."""
    for line in noncomment_lines(text)[1:]:
        if not line.startswith('!'):
            continue
        path = line[1:].strip()
        if not _allow_path_ok(service, path):
            return ("%s: allow line outside the service's own tree: %s"
                    % (service, path))
    return None


def _allow_secret_problem(service, text):
    """G14: no allow line matches a secret-looking name."""
    for line in noncomment_lines(text)[1:]:
        if not line.startswith('!'):
            continue
        path = line[1:].strip().rstrip('/')
        base = path.rsplit('/', 1)[-1]
        if SECRET_BASENAME_RE.match(base) or base == 'secrets':
            return ("%s: allow line matches a secret-looking name: %s"
                    % (service, path))
    return None


def _allow_width_problem(service, text):
    """G15: no allow line is a whole-tree wildcard. An allow line may be a
    file-exact line or a COPY-needed '**' leaf, but never a '**' glob that
    sweeps a whole tree."""
    for line in noncomment_lines(text)[1:]:
        if not line.startswith('!'):
            continue
        path = line[1:].strip()
        if '**' not in path:
            continue
        if service in INSTALL_ALLOW and line == INSTALL_ALLOW[service]:
            continue
        if service == 'web-fe' and line in WEB_FE_STATIC_ALLOWS:
            continue
        return ("%s: allow line is a whole-tree wildcard, not a COPY-needed "
                "path: %s" % (service, path))
    return None


def _tokens_allow_problem(service, text):
    """G17: the one named shared file web-fe serves is allowed by a FILE-EXACT
    line, with no wildcard and no folder allow."""
    if service != 'web-fe':
        return None
    lines = noncomment_lines(text)
    if WEB_FE_TOKENS_ALLOW not in lines:
        return ("web-fe: the tokens allow line must be file-exact %r (no "
                "wildcard, no folder allow)" % WEB_FE_TOKENS_ALLOW)
    return None


def _ignore_patterns(text):
    """(allow, kind, path) per non-comment line. kind is 'deny', 'tree'
    ('**' re-include) or 'file' (file-exact re-include)."""
    patterns = []
    for line in noncomment_lines(text):
        if line.startswith('!'):
            path = line[1:].strip()
            kind = 'tree' if '**' in path else 'file'
            patterns.append((True, kind, path))
        else:
            patterns.append((False, 'deny', line))
    return patterns


def _allowed(source, patterns):
    """The real rule: a file source is covered by a file-exact allow line equal
    to it; a directory source 'x/' is covered by the line '!x/**'."""
    cleaned = source.rstrip('/')
    if source.endswith('/'):
        return any(allow and kind == 'tree' and path == cleaned + '/**'
                   for allow, kind, path in patterns)
    return any(allow and kind == 'file' and path == cleaned
               for allow, kind, path in patterns)


def _copy_covered_problem(service, dockerfile_text, ignore_text):
    """G18: every COPY source is reachable through the allow lines."""
    patterns = _ignore_patterns(ignore_text)
    for source in copy_sources(dockerfile_text):
        if not _allowed(source, patterns):
            return ("%s: COPY source %s is not covered by any allow line"
                    % (service, source))
    return None


def _allow_set_problem(service, dockerfile_text, ignore_text):
    """G19: the allow lines must EQUAL the set derived from this Dockerfile's
    COPY sources: a directory source 'x/' derives '!x/**', a file source 'x'
    derives '!x'. Any ancestor, directory-only or wider line fails it."""
    derived = set()
    for source in copy_sources(dockerfile_text):
        cleaned = source.rstrip('/')
        derived.add('!' + cleaned + ('/**' if source.endswith('/') else ''))
    actual = set(line for line in noncomment_lines(ignore_text)
                 if line.startswith('!'))
    extra = sorted(actual - derived)
    missing = sorted(derived - actual)
    if not extra and not missing:
        return None
    parts = []
    if extra:
        parts.append('extra allow line(s): ' + ', '.join(extra))
    if missing:
        parts.append('missing allow line(s): ' + ', '.join(missing))
    return ("%s: allow lines must equal the set derived from the COPY sources "
            "(%s)" % (service, '; '.join(parts)))


DOCKERFILE_GUARDS = (
    _from_problem,
    _copy_tree_problem,
    _user_problem,
    _secret_env_problem,
    _expose_problem,
    _entry_problem,
    _apt_curl_problem,
    _data_dir_problem,
    _nginx_package_problem,
    _nginx_runtime_problem,
    _web_fe_readable_problem,
)

IGNORE_GUARDS = (
    _deny_first_problem,
    _allow_shape_problem,
    _allow_tree_problem,
    _allow_secret_problem,
    _allow_width_problem,
    _tokens_allow_problem,
)


def dockerfile_problems(service, text):
    problems = []
    for guard in DOCKERFILE_GUARDS:
        message = guard(service, text)
        if message:
            problems.append(message)
    return problems


def ignore_problems(service, dockerfile_text, ignore_text):
    problems = []
    for guard in IGNORE_GUARDS:
        message = guard(service, ignore_text)
        if message:
            problems.append(message)
    for guard in (_copy_covered_problem, _allow_set_problem):
        message = guard(service, dockerfile_text, ignore_text)
        if message:
            problems.append(message)
    return problems


def _recipe_files(service):
    return [dockerfile_rel(service), ignore_rel(service)]


class ImageRecipesTest(unittest.TestCase):

    def test_exactly_the_three_runnable_recipes_exist(self):
        problems = []
        for service in SERVICES:
            for rel in _recipe_files(service):
                if not os.path.isfile(os.path.join(ROOT, rel)):
                    problems.append('missing ' + rel)
        images_root = os.path.join(ROOT, 'server', 'modules', 'deploy', 'images')
        if os.path.isdir(images_root):
            for name in sorted(os.listdir(images_root)):
                path = os.path.join(images_root, name)
                if os.path.isdir(path) and name not in SERVICES:
                    problems.append('unexpected image dir ' + name)
                if os.path.isfile(path) and name not in SERVICES:
                    problems.append('unexpected file ' + name)
        self.assertEqual([], problems, 'image recipe set: %r' % (problems,))

    def test_every_recipe_reads_as_one_ascii_text(self):
        problems = []
        for service in SERVICES:
            for rel in _recipe_files(service):
                with open(os.path.join(ROOT, rel), 'rb') as handle:
                    raw = handle.read()
                try:
                    raw.decode('ascii')
                except UnicodeDecodeError:
                    problems.append('not ascii ' + rel)
        self.assertEqual([], problems, 'recipe text: %r' % (problems,))


class DockerfilePins(unittest.TestCase):

    def _assert_clean(self, service):
        problems = dockerfile_problems(service, read_text(dockerfile_rel(service)))
        self.assertEqual([], problems, '%s recipe: %r' % (service, problems))

    def test_sync_api_recipe_is_pinned(self):
        self._assert_clean('sync-api')

    def test_whisper_server_recipe_is_pinned(self):
        self._assert_clean('whisper-server')

    def test_web_fe_recipe_is_pinned(self):
        self._assert_clean('web-fe')


class IgnorePins(unittest.TestCase):

    def _assert_clean(self, service):
        problems = ignore_problems(
            service, read_text(dockerfile_rel(service)),
            read_text(ignore_rel(service)))
        self.assertEqual([], problems, '%s allowlist: %r' % (service, problems))

    def test_sync_api_allowlist_is_an_allowlist(self):
        self._assert_clean('sync-api')

    def test_whisper_server_allowlist_is_an_allowlist(self):
        self._assert_clean('whisper-server')

    def test_web_fe_allowlist_is_an_allowlist(self):
        self._assert_clean('web-fe')


class TemplatePins(unittest.TestCase):
    """The web-fe site template is pinned by reading it, not by building."""

    def test_web_fe_template_carries_the_health_location(self):
        problems = [_nginx_health_problem(read_text(WEB_FE_TEMPLATE_REL))]
        problems = [message for message in problems if message]
        self.assertEqual([], problems, 'web-fe template: %r' % (problems,))

    def test_web_fe_template_reads_as_one_ascii_text(self):
        with open(os.path.join(ROOT, WEB_FE_TEMPLATE_REL), 'rb') as handle:
            raw = handle.read()
        try:
            raw.decode('ascii')
        except UnicodeDecodeError:
            self.fail('the web-fe template is not ascii')


class FiringControls(unittest.TestCase):
    """One control per guard branch: mutate the REAL file with one edit, require
    the guard's own message. assertNotEqual proves the edit changed the text."""

    def _mutate(self, rel, old, new):
        real = read_text(rel)
        sample = real.replace(old, new, 1)
        self.assertNotEqual(real, sample, 'the one edit must change ' + rel)
        return sample

    def _dockerfile_problems(self, service, sample):
        problems = dockerfile_problems(service, sample)
        self.assertTrue(problems, '%s: the mutated recipe must report a problem' % service)
        return problems

    def test_a_latest_base_tag_fails_the_from_guard(self):
        sample = self._mutate(
            dockerfile_rel('sync-api'),
            'FROM eclipse-temurin:25-jre', 'FROM eclipse-temurin:latest')
        problems = self._dockerfile_problems('sync-api', sample)
        self.assertIn(_from_problem('sync-api', sample), problems)

    def test_a_latest_debian_tag_fails_the_from_guard(self):
        sample = self._mutate(
            dockerfile_rel('web-fe'), 'FROM debian:trixie-slim', 'FROM debian:latest')
        problems = self._dockerfile_problems('web-fe', sample)
        self.assertIn(_from_problem('web-fe', sample), problems)

    def test_a_foreign_copy_source_fails_the_copy_tree_guard(self):
        sample = self._mutate(
            dockerfile_rel('sync-api'),
            'COPY server/modules/sync-api/build/install/sync-api/ /opt/breaker/sync-api/',
            'COPY server/modules/web-fe/src/main/resources/web/ /opt/breaker/sync-api/')
        problems = self._dockerfile_problems('sync-api', sample)
        self.assertIn(_copy_tree_problem('sync-api', sample), problems)

    def test_a_dropped_user_fails_the_user_guard(self):
        sample = self._mutate(
            dockerfile_rel('whisper-server'), 'USER breaker\n', '')
        problems = self._dockerfile_problems('whisper-server', sample)
        self.assertIn(_user_problem('whisper-server', sample), problems)

    def test_a_root_user_fails_the_user_guard(self):
        sample = self._mutate(
            dockerfile_rel('sync-api'), 'USER breaker', 'USER root')
        problems = self._dockerfile_problems('sync-api', sample)
        self.assertIn(_user_problem('sync-api', sample), problems)

    def test_a_late_user_fails_the_user_guard(self):
        sample = self._mutate(
            dockerfile_rel('sync-api'),
            'USER breaker\n# Exec form:',
            'USER breaker\nRUN true\n# Exec form:')
        problems = self._dockerfile_problems('sync-api', sample)
        self.assertIn(_user_problem('sync-api', sample), problems)

    def test_a_baked_in_secret_default_fails_the_secret_env_guard(self):
        sample = self._mutate(
            dockerfile_rel('sync-api'),
            'FROM eclipse-temurin:25-jre\n',
            'FROM eclipse-temurin:25-jre\n'
            'ENV BREAKER_SYNC_API_SERVICE_KEY=change-me\n')
        problems = self._dockerfile_problems('sync-api', sample)
        self.assertIn(_secret_env_problem('sync-api', sample), problems)

    def test_an_expose_line_fails_the_expose_guard(self):
        sample = self._mutate(
            dockerfile_rel('sync-api'),
            'USER breaker\n',
            'USER breaker\nEXPOSE 8080\n')
        problems = self._dockerfile_problems('sync-api', sample)
        self.assertIn(_expose_problem('sync-api', sample), problems)

    def test_a_missing_entrypoint_fails_the_entry_guard(self):
        sample = self._mutate(
            dockerfile_rel('whisper-server'),
            'ENTRYPOINT ["/opt/breaker/whisper-server/bin/whisper-server"]\n', '')
        problems = self._dockerfile_problems('whisper-server', sample)
        self.assertIn(_entry_problem('whisper-server', sample), problems)

    def test_a_shell_form_entrypoint_fails_the_entry_guard(self):
        sample = self._mutate(
            dockerfile_rel('whisper-server'),
            'ENTRYPOINT ["/opt/breaker/whisper-server/bin/whisper-server"]',
            'ENTRYPOINT /opt/breaker/whisper-server/bin/whisper-server')
        problems = self._dockerfile_problems('whisper-server', sample)
        self.assertIn(_entry_problem('whisper-server', sample), problems)

    def test_a_wrong_entrypoint_target_fails_the_entry_guard(self):
        sample = self._mutate(
            dockerfile_rel('sync-api'),
            'ENTRYPOINT ["/opt/breaker/sync-api/bin/sync-api"]',
            'ENTRYPOINT ["/opt/breaker/sync-api/bin/other"]')
        problems = self._dockerfile_problems('sync-api', sample)
        self.assertIn(_entry_problem('sync-api', sample), problems)

    def test_a_shell_form_web_entrypoint_fails_the_entry_guard(self):
        sample = self._mutate(
            dockerfile_rel('web-fe'),
            'ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]',
            'ENTRYPOINT /usr/local/bin/docker-entrypoint.sh')
        problems = self._dockerfile_problems('web-fe', sample)
        self.assertIn(_entry_problem('web-fe', sample), problems)

    def test_a_dirty_apt_layer_fails_the_apt_curl_guard(self):
        sample = self._mutate(
            dockerfile_rel('sync-api'),
            '    && rm -rf /var/lib/apt/lists/*\n', '')
        problems = self._dockerfile_problems('sync-api', sample)
        self.assertIn(_apt_curl_problem('sync-api', sample), problems)

    def test_a_dropped_data_directory_fails_the_data_dir_guard(self):
        sample = self._mutate(
            dockerfile_rel('whisper-server'),
            '    && chown breaker:breaker /var/lib/breaker/whisper-server',
            '    && true')
        problems = self._dockerfile_problems('whisper-server', sample)
        self.assertIn(_data_dir_problem('whisper-server', sample), problems)

    def test_a_missing_nginx_package_fails_the_nginx_package_guard(self):
        sample = self._mutate(
            dockerfile_rel('web-fe'),
            '    && apt-get install -y --no-install-recommends nginx-light gettext-base \\\n',
            '    && true \\\n')
        problems = self._dockerfile_problems('web-fe', sample)
        self.assertIn(_nginx_package_problem('web-fe', sample), problems)

    def test_a_backgrounded_nginx_fails_the_nginx_runtime_guard(self):
        sample = self._mutate(
            dockerfile_rel('web-fe'), '"-g", "daemon off;"', '"-g", "daemon on;"')
        problems = self._dockerfile_problems('web-fe', sample)
        self.assertIn(_nginx_runtime_problem('web-fe', sample), problems)

    def test_a_removed_template_fails_the_nginx_runtime_guard(self):
        sample = self._mutate(
            dockerfile_rel('web-fe'),
            'COPY server/modules/deploy/images/web-fe/nginx.conf.template '
            '/etc/nginx/nginx.conf.template\n', '')
        problems = self._dockerfile_problems('web-fe', sample)
        self.assertIn(_nginx_runtime_problem('web-fe', sample), problems)

    def test_a_removed_entrypoint_copy_fails_the_nginx_runtime_guard(self):
        sample = self._mutate(
            dockerfile_rel('web-fe'),
            'COPY server/modules/deploy/images/web-fe/docker-entrypoint.sh '
            '/usr/local/bin/docker-entrypoint.sh\n', '')
        problems = self._dockerfile_problems('web-fe', sample)
        self.assertIn(_nginx_runtime_problem('web-fe', sample), problems)

    def test_a_removed_readability_chmod_fails_the_web_fe_readable_guard(self):
        # G21: dropping the world-readable chmod lets a restrictive host umask
        # bake 0600 modes into the image; the guard must name the html tree.
        sample = self._mutate(
            dockerfile_rel('web-fe'),
            'RUN chmod -R a+rX /usr/share/nginx/html \\\n'
            '    && chmod a+rx /etc/nginx/nginx.conf.template'
            ' /usr/local/bin/docker-entrypoint.sh\n',
            'RUN true\n')
        problems = self._dockerfile_problems('web-fe', sample)
        self.assertIn(_web_fe_readable_problem('web-fe', sample), problems)

    def _ignore_problems(self, service, sample):
        problems = ignore_problems(
            service, read_text(dockerfile_rel(service)), sample)
        self.assertTrue(problems, '%s: the mutated allowlist must report a problem' % service)
        return problems

    def test_a_missing_deny_line_fails_the_deny_first_guard(self):
        sample = self._mutate(
            ignore_rel('sync-api'), '\n*\n!server/', '\n# *\n!server/')
        problems = self._ignore_problems('sync-api', sample)
        self.assertIn(_deny_first_problem('sync-api', sample), problems)

    def test_a_stray_non_allow_line_fails_the_allow_shape_guard(self):
        sample = self._mutate(
            ignore_rel('sync-api'), '\n*\n', '\n*\nserver/modules/sync-api/\n')
        problems = self._ignore_problems('sync-api', sample)
        self.assertIn(_allow_shape_problem('sync-api', sample), problems)

    def test_an_out_of_tree_allow_fails_the_allow_tree_guard(self):
        sample = self._mutate(
            ignore_rel('sync-api'),
            '!server/modules/sync-api/build/install/sync-api/**\n', '!/etc/**\n')
        problems = self._ignore_problems('sync-api', sample)
        self.assertIn(_allow_tree_problem('sync-api', sample), problems)

    def test_a_secret_allow_fails_the_allow_secret_guard(self):
        sample = self._mutate(
            ignore_rel('sync-api'),
            '!server/modules/sync-api/build/install/sync-api/**\n',
            '!server/modules/sync-api/build.key\n')
        problems = self._ignore_problems('sync-api', sample)
        self.assertIn(_allow_secret_problem('sync-api', sample), problems)

    def test_a_whole_tree_allow_fails_the_allow_width_guard(self):
        sample = self._mutate(
            ignore_rel('sync-api'),
            '!server/modules/sync-api/build/install/sync-api/**\n',
            '!server/modules/sync-api/**\n')
        problems = self._ignore_problems('sync-api', sample)
        self.assertIn(_allow_width_problem('sync-api', sample), problems)

    def test_an_extra_allow_line_fails_the_allow_set_guard(self):
        sample = self._mutate(
            ignore_rel('sync-api'), '\n*\n', '\n*\n!server/\n')
        problems = self._ignore_problems('sync-api', sample)
        self.assertIn(
            _allow_set_problem(
                'sync-api', read_text(dockerfile_rel('sync-api')), sample),
            problems)

    def test_an_extra_shared_allow_line_fails_the_allow_set_guard(self):
        sample = self._mutate(
            ignore_rel('web-fe'), '\n*\n', '\n*\n!shared/\n')
        problems = self._ignore_problems('web-fe', sample)
        self.assertIn(
            _allow_set_problem(
                'web-fe', read_text(dockerfile_rel('web-fe')), sample),
            problems)

    def test_a_widened_allow_line_fails_the_allow_set_guard(self):
        sample = self._mutate(
            ignore_rel('web-fe'),
            '!server/modules/web-fe/src/main/resources/web/**\n',
            '!server/modules/web-fe/**\n')
        problems = self._ignore_problems('web-fe', sample)
        self.assertIn(
            _allow_set_problem(
                'web-fe', read_text(dockerfile_rel('web-fe')), sample),
            problems)

    def test_a_wildcard_tokens_allow_fails_the_tokens_allow_guard(self):
        sample = self._mutate(
            ignore_rel('web-fe'),
            '!shared/modules/ui-tokens/src/main/resources/tokens.css\n',
            '!shared/modules/ui-tokens/src/main/resources/**\n')
        problems = self._ignore_problems('web-fe', sample)
        self.assertIn(_tokens_allow_problem('web-fe', sample), problems)

    def test_a_dropped_allow_line_fails_the_copy_covered_guard(self):
        sample = self._mutate(
            ignore_rel('sync-api'),
            '\n!server/modules/sync-api/build/install/sync-api/**\n', '\n')
        problems = self._ignore_problems('sync-api', sample)
        self.assertIn(
            _copy_covered_problem(
                'sync-api', read_text(dockerfile_rel('sync-api')), sample),
            problems)

    def test_a_dropped_tokens_allow_fails_the_copy_covered_guard(self):
        sample = self._mutate(
            ignore_rel('web-fe'),
            '\n!shared/modules/ui-tokens/src/main/resources/tokens.css\n', '\n')
        problems = self._ignore_problems('web-fe', sample)
        self.assertIn(
            _copy_covered_problem(
                'web-fe', read_text(dockerfile_rel('web-fe')), sample),
            problems)


    # -- the web-fe /health template guard (G20) -------------------------------

    def _template_problems(self, sample):
        problems = [_nginx_health_problem(sample)]
        problems = [message for message in problems if message]
        self.assertTrue(problems, 'the mutated template must report a problem')
        return problems

    def test_a_renamed_server_block_fails_the_health_guard(self):
        sample = self._mutate(
            WEB_FE_TEMPLATE_REL, '    server {\n', '    serverx {\n')
        problems = self._template_problems(sample)
        self.assertIn(_nginx_health_problem(sample), problems)

    def test_a_removed_health_location_fails_the_health_guard(self):
        sample = self._mutate(
            WEB_FE_TEMPLATE_REL,
            '        location = /health {\n'
            '            default_type application/json;\n'
            '            return 200 \'{"status":"ok"}\';\n'
            '        }\n\n', '')
        problems = self._template_problems(sample)
        self.assertIn(_nginx_health_problem(sample), problems)

    def test_a_health_location_outside_the_server_block_fails_the_health_guard(self):
        sample = self._mutate(
            WEB_FE_TEMPLATE_REL,
            '        location = /health {\n'
            '            default_type application/json;\n'
            '            return 200 \'{"status":"ok"}\';\n'
            '        }\n\n'
            '        location / {\n'
            '            try_files $uri $uri/ =404;\n'
            '        }\n'
            '    }\n',
            '        location / {\n'
            '            try_files $uri $uri/ =404;\n'
            '        }\n'
            '    }\n\n'
            '    location = /health {\n'
            '        default_type application/json;\n'
            '        return 200 \'{"status":"ok"}\';\n'
            '    }\n')
        problems = self._template_problems(sample)
        self.assertIn(_nginx_health_problem(sample), problems)

    def test_a_missing_json_type_fails_the_health_guard(self):
        sample = self._mutate(
            WEB_FE_TEMPLATE_REL,
            '            default_type application/json;\n', '')
        problems = self._template_problems(sample)
        self.assertIn(_nginx_health_problem(sample), problems)

    def test_a_503_health_answer_fails_the_health_guard(self):
        sample = self._mutate(
            WEB_FE_TEMPLATE_REL,
            '            return 200 \'{"status":"ok"}\';\n',
            '            return 503 \'{"status":"ok"}\';\n')
        problems = self._template_problems(sample)
        self.assertIn(_nginx_health_problem(sample), problems)

    def test_a_changed_health_body_fails_the_health_guard(self):
        sample = self._mutate(
            WEB_FE_TEMPLATE_REL,
            '            return 200 \'{"status":"ok"}\';\n',
            '            return 200 \'{"status":"fail"}\';\n')
        problems = self._template_problems(sample)
        self.assertIn(_nginx_health_problem(sample), problems)

    def test_a_health_location_after_location_slash_fails_the_health_guard(self):
        sample = self._mutate(
            WEB_FE_TEMPLATE_REL,
            '        location = /health {\n'
            '            default_type application/json;\n'
            '            return 200 \'{"status":"ok"}\';\n'
            '        }\n\n'
            '        location / {\n'
            '            try_files $uri $uri/ =404;\n'
            '        }\n',
            '        location / {\n'
            '            try_files $uri $uri/ =404;\n'
            '        }\n\n'
            '        location = /health {\n'
            '            default_type application/json;\n'
            '            return 200 \'{"status":"ok"}\';\n'
            '        }\n')
        problems = self._template_problems(sample)
        self.assertIn(_nginx_health_problem(sample), problems)

    def test_a_dollar_in_the_health_block_fails_the_health_guard(self):
        sample = self._mutate(
            WEB_FE_TEMPLATE_REL,
            '        location = /health {\n',
            '        location = /health {\n            set $x 1;\n')
        problems = self._template_problems(sample)
        self.assertIn(_nginx_health_problem(sample), problems)


if __name__ == '__main__':
    unittest.main()
