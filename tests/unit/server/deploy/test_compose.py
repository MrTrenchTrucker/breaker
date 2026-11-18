"""Pins for the deploy compose file and its env example.

Every test READS the files; nothing is built or run. The compose file is parsed
with tools/yaml_subset.py, so it stays inside that reader's subset: whole-line
comments only, no document marker, no multi-line scalars, no anchors. Each guard
below has a control test that mutates the REAL file with one edit, checks the
text really changed, and requires the guard's own message. The guards cover:
the fixed project name, exactly the four services, the build blocks and image
lines, the restart policy, the profile gate, the required-variable form, one
fixed-subnet network with one fixed address per service, every host variable
equal to its own address variable and never a wildcard, every published port
starting with the bind variable, the secret-name form, the forbidden keys, the
hardening keys, the health checks, the named data volumes, the single read-only
service-key bind mount, and the env example names and placeholders.

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_compose.py'
"""
import os
import re
import sys
import unittest

ROOT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    '..', '..', '..', '..',
))
TOOLS = os.path.join(ROOT, 'tools')
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import yaml_subset  # noqa: E402

COMPOSE_REL = 'server/modules/deploy/docker-compose.yml'
ENV_EXAMPLE_REL = 'server/modules/deploy/.env.example'

SERVICES = ('sync-api', 'whisper-server', 'web-fe', 'training')
DEFAULT_SERVICES = ('sync-api', 'whisper-server', 'web-fe')
JVM_SERVICES = ('sync-api', 'whisper-server')
NETWORK_NAME = 'breaker'
PROJECT_NAME = 'breaker'

ADDRESS_VAR = {
    'sync-api': 'BREAKER_SYNC_API_ADDRESS',
    'whisper-server': 'BREAKER_WHISPER_ADDRESS',
    'web-fe': 'BREAKER_WEB_FE_ADDRESS',
    'training': 'BREAKER_TRAINING_ADDRESS',
}
HOST_VAR = {
    'sync-api': 'BREAKER_SYNC_API_HOST',
    'whisper-server': 'BREAKER_WHISPER_HOST',
}
PORT_VAR = {
    'sync-api': 'BREAKER_SYNC_API_PORT',
    'whisper-server': 'BREAKER_WHISPER_PORT',
    'web-fe': 'BREAKER_WEB_FE_PORT',
}
DB_VAR = {
    'sync-api': 'BREAKER_SYNC_API_DB',
    'whisper-server': 'BREAKER_WHISPER_DB',
}
DATA_DIR = {
    'sync-api': '/var/lib/breaker/sync-api',
    'whisper-server': '/var/lib/breaker/whisper-server',
}
VOLUME_NAME = {
    'sync-api': 'breaker_sync_api_data',
    'whisper-server': 'breaker_whisper_data',
}
IMAGE_TAG_VAR = 'BREAKER_IMAGE_TAG'
BIND_ADDR_VAR = 'BREAKER_BIND_ADDR'
SUBNET_VAR = 'BREAKER_NETWORK_SUBNET'
KEY_FILE_VAR = 'BREAKER_SYNC_API_SERVICE_KEY_FILE'
KEY_FILE_PATH = '/run/breaker/keys/sync-api-service-key'
KEY_HOST_VAR = 'BREAKER_SYNC_API_SERVICE_KEY_HOST'

SECRET_NAME_RE = re.compile(r'(?i)(KEY|TOKEN|PASSWORD|SECRET)')
INTERPOLATED_RE = re.compile(r'\$\{([A-Z][A-Z0-9_]*)(:[^}]*)?\}')
IPV4_RE = re.compile(r'\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b')
WILDCARD_RE = re.compile(r'(?i)(?:^|[^\w.])(?:0\.0\.0\.0|::(?:1)?|\[::(?:1)?\]|\*)(?:[^\w.]|$)')


def required(name):
    """The one accepted required-variable spelling: ${NAME:?message}."""
    return '${%s:?%s is required}' % (name, name)


# -- file helpers (no guards) -------------------------------------------------

def read_text(rel):
    with open(os.path.join(ROOT, rel), encoding='utf-8') as handle:
        return handle.read()


def load_compose():
    return yaml_subset.load(os.path.join(ROOT, COMPOSE_REL))


def parse_compose_text(text):
    return yaml_subset.loads(text, COMPOSE_REL)


def env_example_values(text):
    values = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        name, value = line.split('=')
        values[name.strip()] = value.strip()
    return values


def walk_strings(node):
    if isinstance(node, str):
        yield node
    elif isinstance(node, dict):
        for key, value in node.items():
            yield str(key)
            for item in walk_strings(value):
                yield item
    elif isinstance(node, list):
        for item in node:
            for text in walk_strings(item):
                yield text


def is_wildcard(value):
    return bool(value) and bool(WILDCARD_RE.search(value))


def _spec(doc, service):
    """A service mapping, or an empty one: a guard must report a problem, never
    raise, when a control has renamed or dropped the service."""
    services = doc.get('services') or {}
    spec = services.get(service)
    return spec if isinstance(spec, dict) else {}


def split_volume(entry):
    """Split a volume entry on ':' while ignoring colons inside ${...}."""
    masked = re.sub(r'\$\{[^}]*\}',
                    lambda match: match.group(0).replace(':', '\x00'), entry)
    return [part.replace('\x00', ':') for part in masked.split(':')]


# -- guards: each returns its own message, or None ----------------------------

def _services_problem(doc):
    """G1: exactly the four services, no more and no fewer."""
    if set(doc.get('services') or {}) != set(SERVICES):
        return ("the compose file must hold exactly the four services: %s"
                % ', '.join(SERVICES))
    return None


def _project_name_problem(doc):
    """G2: the fixed compose v2 project name, and no obsolete version key."""
    if doc.get('name') != PROJECT_NAME:
        return ("the top-level name must be '%s' (a fixed project name keeps the "
                "volume and container names across releases)" % PROJECT_NAME)
    if 'version' in doc:
        return "a top-level version key is obsolete in compose v2"
    return None


def _build_problem(doc):
    """G3: the three default services build from the repo root; training does not."""
    for service in DEFAULT_SERVICES:
        build = _spec(doc, service).get('build')
        if not isinstance(build, dict):
            return ("%s: a default service must carry a build block" % service)
        if build.get('context') != '../../..':
            return ("%s: the build context must be '../../..' (the repository root "
                    "of the checkout)" % service)
        expected = 'server/modules/deploy/images/%s/Dockerfile' % service
        if build.get('dockerfile') != expected:
            return "%s: the build dockerfile must be %s" % (service, expected)
    if 'build' in _spec(doc, 'training'):
        return "training: the profile service must not carry a build block"
    return None


def _image_problem(doc):
    """G4: every service pins breaker/<service> with the required tag variable."""
    for service in SERVICES:
        image = _spec(doc, service).get('image')
        if not image:
            return "%s: no image line" % service
        expected = 'breaker/%s:%s' % (service, required(IMAGE_TAG_VAR))
        if image != expected:
            return "%s: the image must be %s" % (service, expected)
    return None


def _latest_problem(doc):
    """G5: no image tag is the moving tag latest."""
    for service in SERVICES:
        image = _spec(doc, service).get('image')
        if isinstance(image, str) and 'latest' in image:
            return "%s: the image tag must never be latest (rollback is the "\
                   "previous release tag)" % service
    return None


def _restart_problem(doc):
    """G6: every service restarts unless stopped."""
    for service in SERVICES:
        if _spec(doc, service).get('restart') != 'unless-stopped':
            return ("%s: restart must be unless-stopped (a dead worker ends the "
                    "process with exit 0, so on-failure would never restart it)"
                    % service)
    return None


def _profiles_problem(doc):
    """G7: training is gated behind its profile; the default three are not."""
    if _spec(doc, 'training').get('profiles') != ['training']:
        return "training: profiles must be [training]"
    for service in DEFAULT_SERVICES:
        if 'profiles' in _spec(doc, service):
            return "%s: a default service must not carry profiles" % service
    return None


def _required_variable_problem(doc):
    """G8: every interpolated variable uses the required ${NAME:?message} form."""
    for text in walk_strings(doc):
        for match in INTERPOLATED_RE.finditer(text):
            tail = match.group(2)
            if tail is None or not tail.startswith(':?'):
                return ("every interpolated variable must use the required form "
                        "${NAME:?message} with no default (got %s)" % match.group(0))
    return None


def _network_problem(doc):
    """G9: one network, a fixed subnet variable, a fixed address variable each."""
    nets = doc.get('networks')
    if not isinstance(nets, dict) or set(nets) != {NETWORK_NAME}:
        return "exactly one network named '%s' is required" % NETWORK_NAME
    config = (nets[NETWORK_NAME].get('ipam') or {}).get('config')
    if not isinstance(config, list) or not config:
        return "the '%s' network must carry an ipam config with a subnet" % NETWORK_NAME
    if config[0].get('subnet') != required(SUBNET_VAR):
        return ("the subnet must be the required variable %s" % required(SUBNET_VAR))
    seen = []
    for service in SERVICES:
        snets = _spec(doc, service).get('networks')
        if not isinstance(snets, dict) or set(snets) != {NETWORK_NAME}:
            return ("%s: the service must sit on the '%s' network only"
                    % (service, NETWORK_NAME))
        seen.append(snets[NETWORK_NAME].get('ipv4_address'))
    if len(set(seen)) != len(seen):
        return "each service needs its own address variable"
    for service, address in zip(SERVICES, seen):
        if address != required(ADDRESS_VAR[service]):
            return ("%s: ipv4_address must be the required variable %s"
                    % (service, required(ADDRESS_VAR[service])))
    return None


def _host_problem(doc):
    """G10: every host variable is its own address variable, never a wildcard.
    Only the two JVM services carry a host variable; the web-fe image listens on
    the port alone (its nginx template has no host), so it has none to pin."""
    for service in JVM_SERVICES:
        env = _spec(doc, service).get('environment') or {}
        host = env.get(HOST_VAR[service])
        if is_wildcard(host):
            return "%s: %s must never be a wildcard address" % (service, HOST_VAR[service])
        if host != required(ADDRESS_VAR[service]):
            return ("%s: %s must be the required address variable %s"
                    % (service, HOST_VAR[service], required(ADDRESS_VAR[service])))
    env = _spec(doc, 'sync-api').get('environment') or {}
    if env.get('BREAKER_SYNC_API_INTERNAL_HOST') != required(ADDRESS_VAR['sync-api']):
        return ("sync-api: BREAKER_SYNC_API_INTERNAL_HOST must be its own stack "
                "address variable %s" % required(ADDRESS_VAR['sync-api']))
    return None


def _ports_problem(doc):
    """G11: every published port starts with the bind variable and targets the
    service's own port variable; training publishes nothing."""
    for service in DEFAULT_SERVICES:
        ports = _spec(doc, service).get('ports')
        if not isinstance(ports, list) or not ports:
            return "%s: the default service must publish its port" % service
        expected = '%s:%s:%s' % (required(BIND_ADDR_VAR), required(PORT_VAR[service]),
                                 required(PORT_VAR[service]))
        for entry in ports:
            if not isinstance(entry, str):
                return "%s: a published port must be a string" % service
            if entry.startswith('0.0.0.0:'):
                return "%s: a published port must never bind 0.0.0.0" % service
            if entry.startswith(':'):
                return "%s: a published port must never have an empty bind address" % service
            if entry != expected:
                return ("%s: the published port must be %s" % (service, expected))
    if _spec(doc, 'training').get('ports'):
        return "training: no ports are published"
    return None


def _secret_form_problem(doc):
    """G12: a secret-looking name enters a service only as ${NAME:?message}. The
    one exception is the name that locates the mounted key FILE, which holds an
    absolute container path (a location, not the key)."""
    for service in SERVICES:
        env = _spec(doc, service).get('environment') or {}
        for name, value in env.items():
            if not SECRET_NAME_RE.search(name):
                continue
            if value == required(name):
                continue
            if name == KEY_FILE_VAR and isinstance(value, str) and value.startswith('/'):
                continue
            return ("%s: %s must enter the service as %s (the one exception is %s, "
                    "which names the mounted key file and holds an absolute path)"
                    % (service, name, required(name), KEY_FILE_VAR))
    return None


def _forbidden_keys_problem(doc):
    """G13: no privileged, network_mode, pid/ipc host sharing or docker socket."""
    for service in SERVICES:
        spec = _spec(doc, service)
        for key in ('privileged', 'network_mode'):
            if key in spec:
                return "%s: the key '%s' is not allowed" % (service, key)
        if 'pid' in spec or 'ipc' in spec:
            return "%s: pid/ipc host sharing is not allowed" % service
        for entry in spec.get('volumes') or []:
            if isinstance(entry, str) and '/var/run/docker.sock' in entry:
                return "%s: the docker socket must not be mounted" % service
    return None


def _hardening_problem(doc):
    """G14: every default service drops the capabilities and refuses new ones."""
    for service in DEFAULT_SERVICES:
        spec = _spec(doc, service)
        if spec.get('security_opt') != ['no-new-privileges:true']:
            return "%s: security_opt must be [no-new-privileges:true]" % service
        if spec.get('cap_drop') != ['ALL']:
            return "%s: cap_drop must be [ALL]" % service
    for service in SERVICES:
        if 'read_only' in _spec(doc, service):
            return "%s: read_only is not part of this slice" % service
    return None


def _healthcheck_problem(doc):
    """G15: the two JVM services probe their own /health with curl; the other
    two carry no health check."""
    for service in JVM_SERVICES:
        spec = _spec(doc, service)
        check = spec.get('healthcheck')
        if not isinstance(check, dict):
            return "%s: the JVM service must carry a healthcheck" % service
        test = check.get('test')
        if not isinstance(test, list) or 'curl' not in test or '-fsS' not in test:
            return "%s: the healthcheck must run curl -fsS" % service
        expected = 'http://%s:%s/health' % (required(ADDRESS_VAR[service]),
                                            required(PORT_VAR[service]))
        if expected not in test:
            return ("%s: the healthcheck must probe %s with its own address and "
                    "port variable" % (service, expected))
    for service in ('web-fe', 'training'):
        if 'healthcheck' in _spec(doc, service):
            return ("%s: no healthcheck (the image carries no curl)" % service)
    return None


RELEASES_SYNC_ENTRY = 'breaker_releases:/var/lib/breaker/releases:ro'
RELEASES_WEB_ENTRY = 'breaker_releases:/srv/breaker/releases:ro'


def _volumes_problem(doc):
    """G16: one named volume per data service at the path the image creates; the
    DB path sits inside it; web-fe carries only the release volume, read-only;
    sync-api also mounts the release volume read-only; training carries no volume."""
    declared = set(doc.get('volumes') or {})
    for service in JVM_SERVICES:
        spec = _spec(doc, service)
        entries = spec.get('volumes') or []
        path = DATA_DIR[service]
        mounted = any(isinstance(entry, str) and split_volume(entry)[0] == VOLUME_NAME[service]
                      and split_volume(entry)[1] == path for entry in entries)
        if not mounted:
            return ("%s: the named volume %s must mount at %s"
                    % (service, VOLUME_NAME[service], path))
        if VOLUME_NAME[service] not in declared:
            return ("%s: the named volume %s must be declared at the top level"
                    % (service, VOLUME_NAME[service]))
        env = spec.get('environment') or {}
        db = env.get(DB_VAR[service])
        if not (isinstance(db, str) and db.startswith(path + '/')):
            return "%s: the DB path must sit inside %s" % (service, path)
    sync_entries = _spec(doc, 'sync-api').get('volumes') or []
    if sync_entries.count(RELEASES_SYNC_ENTRY) != 1:
        return ("sync-api: the release volume breaker_releases mounts once at "
                "/var/lib/breaker/releases, read-only")
    if _spec(doc, 'web-fe').get('volumes') != [RELEASES_WEB_ENTRY]:
        return ("web-fe: the only volume is breaker_releases at "
                "/srv/breaker/releases, read-only")
    if _spec(doc, 'training').get('volumes'):
        return "training: no volume is needed"
    return None


def _bind_mount_problem(doc):
    """G17: exactly one host bind mount, read-only, sourcing the required host
    variable and targeting the path the key-file variable names."""
    declared = set(doc.get('volumes') or {})
    binds = []
    for service in SERVICES:
        for entry in _spec(doc, service).get('volumes') or []:
            if not isinstance(entry, str):
                continue
            parts = split_volume(entry)
            if (len(parts) in (2, 3) and parts[0] in declared
                    and (len(parts) == 2 or parts[2] in ('ro', 'rw'))):
                continue
            binds.append((service, entry))
    if len(binds) != 1:
        return ("exactly one host bind mount is allowed (the service key file); "
                "found %d" % len(binds))
    _, entry = binds[0]
    parts = split_volume(entry)
    if len(parts) != 3 or parts[2] != 'ro':
        return "the service key bind mount must be read-only (:ro)"
    if parts[0] != required(KEY_HOST_VAR):
        return ("the bind mount source must be the required variable %s"
                % required(KEY_HOST_VAR))
    env = _spec(doc, 'sync-api').get('environment') or {}
    if parts[1] != env.get(KEY_FILE_VAR):
        return ("the bind mount target must be the path %s names" % KEY_FILE_VAR)
    return None


def _env_example_problem(text, compose_text):
    """G18: the env example names both ways, holds no secret value and uses
    private-space addresses as stated placeholders."""
    values = env_example_values(text)
    referenced = set(match.group(1) for match in INTERPOLATED_RE.finditer(compose_text))
    for name in sorted(referenced):
        if name not in values:
            return ("the env example has no line for %s, which the compose file "
                    "interpolates" % name)
    for name in sorted(values):
        if name not in referenced:
            return ("the env example names %s, which the compose file never "
                    "interpolates" % name)
    for line in text.splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith('#') and ('<' in stripped or '>' in stripped):
            return "the env example must not carry <...> tokens: %s" % stripped
    for name in sorted(values):
        if SECRET_NAME_RE.search(name):
            value = values[name]
            if value and not value.startswith('${'):
                return ("the secret-looking name %s must hold an empty value or a "
                        "${VAR} reference" % name)
    for value in values.values():
        for address in IPV4_RE.findall(value):
            if not address.startswith('10.20.30.'):
                return ("the address %s is not a 10.20.30.x private-space "
                        "placeholder" % address)
    stated = [line for line in text.splitlines()
              if line.strip().startswith('#') and '10.20.30' in line
              and 'placeholder' in line.lower()]
    if not stated:
        return ("the env example must say in a comment that the 10.20.30.x "
                "addresses are placeholders")
    return None


def _key_comment_block(text):
    """The contiguous comment lines directly above the key host variable."""
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if line.startswith(KEY_HOST_VAR):
            block = []
            for previous in reversed(lines[:index]):
                if previous.strip().startswith('#'):
                    block.append(previous)
                else:
                    break
            return '\n'.join(reversed(block))
    return ''


def _key_file_rules_problem(text):
    """G19: the env example states the service key file's runtime rules: the
    43-character alphabet, the owner-only mode, and that the file must exist
    before the stack comes up."""
    block = _key_comment_block(text)
    if '43' not in block or 'URL-safe alphabet' not in block:
        return ("the env example must state that the service key file is "
                "exactly 43 characters of the URL-safe alphabet")
    if '0600' not in block:
        return ("the env example must state that the service key file is "
                "owner-only (mode 0600)")
    if 'must exist before' not in block:
        return ("the env example must state that the service key file must "
                "exist before up")
    return None


def _key_mount_pointer_problem(text):
    """G20: the compose file carries a comment above the service key bind mount
    that points at the env example rules."""
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if KEY_HOST_VAR in line and ':ro' in line:
            previous = lines[index - 1].strip() if index else ''
            if previous.startswith('#') and '.env.example' in previous:
                return None
            return ("the compose file must carry a comment above the service key "
                    "bind mount that points at the .env.example rules")
    return None


TEXT_GUARDS = (
    _key_mount_pointer_problem,
)


def compose_text_problems(text):
    problems = []
    for guard in TEXT_GUARDS:
        message = guard(text)
        if message:
            problems.append(message)
    return problems


COMPOSE_GUARDS = (
    _services_problem,
    _project_name_problem,
    _build_problem,
    _image_problem,
    _latest_problem,
    _restart_problem,
    _profiles_problem,
    _required_variable_problem,
    _network_problem,
    _host_problem,
    _ports_problem,
    _secret_form_problem,
    _forbidden_keys_problem,
    _hardening_problem,
    _healthcheck_problem,
    _volumes_problem,
    _bind_mount_problem,
)


def compose_problems(doc):
    problems = []
    for guard in COMPOSE_GUARDS:
        message = guard(doc)
        if message:
            problems.append(message)
    return problems


def env_example_problems(text, compose_text):
    problems = []
    message = _env_example_problem(text, compose_text)
    if message:
        problems.append(message)
    message = _key_file_rules_problem(text)
    if message:
        problems.append(message)
    return problems


class ComposeFilePins(unittest.TestCase):

    def test_the_compose_file_parses_inside_the_yaml_subset(self):
        problems = compose_problems(load_compose())
        self.assertEqual([], problems, 'compose file: %r' % (problems,))

    def test_the_env_example_is_named_both_ways(self):
        problems = env_example_problems(read_text(ENV_EXAMPLE_REL), read_text(COMPOSE_REL))
        self.assertEqual([], problems, 'env example: %r' % (problems,))

    def test_the_compose_file_points_at_the_key_file_rules(self):
        problems = compose_text_problems(read_text(COMPOSE_REL))
        self.assertEqual([], problems, 'compose text: %r' % (problems,))

    def test_the_compose_file_is_ascii(self):
        with open(os.path.join(ROOT, COMPOSE_REL), 'rb') as handle:
            raw = handle.read()
        try:
            raw.decode('ascii')
        except UnicodeDecodeError:
            self.fail('the compose file is not ascii')
        with open(os.path.join(ROOT, ENV_EXAMPLE_REL), 'rb') as handle:
            raw = handle.read()
        try:
            raw.decode('ascii')
        except UnicodeDecodeError:
            self.fail('the env example is not ascii')


class FiringControls(unittest.TestCase):
    """One control per guard branch: mutate the REAL file with one edit, require
    the guard's own message. assertNotEqual proves the edit changed the text."""

    def _mutate(self, rel, old, new):
        real = read_text(rel)
        sample = real.replace(old, new, 1)
        self.assertNotEqual(real, sample, 'the one edit must change ' + rel)
        return sample

    def _compose_problems(self, sample):
        problems = compose_problems(parse_compose_text(sample))
        self.assertTrue(problems, 'the mutated compose file must report a problem')
        return problems

    def _env_problems(self, sample):
        problems = env_example_problems(sample, read_text(COMPOSE_REL))
        self.assertTrue(problems, 'the mutated env example must report a problem')
        return problems

    def test_a_dropped_service_fails_the_services_guard(self):
        sample = self._mutate(COMPOSE_REL, '  training:', '  training2:')
        problems = self._compose_problems(sample)
        self.assertIn(_services_problem(parse_compose_text(sample)), problems)

    def test_a_renamed_project_fails_the_project_name_guard(self):
        sample = self._mutate(COMPOSE_REL, 'name: breaker', 'name: other')
        problems = self._compose_problems(sample)
        self.assertIn(_project_name_problem(parse_compose_text(sample)), problems)

    def test_a_version_key_fails_the_project_name_guard(self):
        sample = self._mutate(COMPOSE_REL, 'name: breaker\n', "name: breaker\nversion: '3.8'\n")
        problems = self._compose_problems(sample)
        self.assertIn(_project_name_problem(parse_compose_text(sample)), problems)

    def test_a_dropped_build_block_fails_the_build_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            '    build:\n      context: ../../..\n'
            '      dockerfile: server/modules/deploy/images/sync-api/Dockerfile\n', '')
        problems = self._compose_problems(sample)
        self.assertIn(_build_problem(parse_compose_text(sample)), problems)

    def test_a_wrong_context_fails_the_build_guard(self):
        sample = self._mutate(COMPOSE_REL, 'context: ../../..', 'context: .')
        problems = self._compose_problems(sample)
        self.assertIn(_build_problem(parse_compose_text(sample)), problems)

    def test_a_wrong_dockerfile_fails_the_build_guard(self):
        sample = self._mutate(
            COMPOSE_REL, 'dockerfile: server/modules/deploy/images/sync-api/Dockerfile',
            'dockerfile: server/modules/deploy/images/web-fe/Dockerfile')
        problems = self._compose_problems(sample)
        self.assertIn(_build_problem(parse_compose_text(sample)), problems)

    def test_a_training_build_block_fails_the_build_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '    image: breaker/training:${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}\n',
            '    build:\n      context: ../../..\n'
            '    image: breaker/training:${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}\n')
        problems = self._compose_problems(sample)
        self.assertIn(_build_problem(parse_compose_text(sample)), problems)

    def test_a_dropped_image_fails_the_image_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            '    image: breaker/sync-api:${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}\n', '')
        problems = self._compose_problems(sample)
        self.assertIn(_image_problem(parse_compose_text(sample)), problems)

    def test_a_wrong_image_name_fails_the_image_guard(self):
        sample = self._mutate(
            COMPOSE_REL, 'image: breaker/sync-api:${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}',
            'image: breaker/other:${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}')
        problems = self._compose_problems(sample)
        self.assertIn(_image_problem(parse_compose_text(sample)), problems)

    def test_a_latest_tag_fails_the_latest_guard(self):
        sample = self._mutate(
            COMPOSE_REL, 'image: breaker/sync-api:${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}',
            'image: breaker/sync-api:latest')
        problems = self._compose_problems(sample)
        self.assertIn(_latest_problem(parse_compose_text(sample)), problems)

    def test_an_on_failure_restart_fails_the_restart_guard(self):
        sample = self._mutate(COMPOSE_REL, 'restart: unless-stopped', 'restart: on-failure')
        problems = self._compose_problems(sample)
        self.assertIn(_restart_problem(parse_compose_text(sample)), problems)

    def test_a_wrong_training_profile_fails_the_profiles_guard(self):
        sample = self._mutate(COMPOSE_REL, '    profiles:\n      - training',
                              '    profiles:\n      - other')
        problems = self._compose_problems(sample)
        self.assertIn(_profiles_problem(parse_compose_text(sample)), problems)

    def test_a_default_service_profile_fails_the_profiles_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '    restart: unless-stopped\n',
            '    restart: unless-stopped\n    profiles:\n      - training\n')
        problems = self._compose_problems(sample)
        self.assertIn(_profiles_problem(parse_compose_text(sample)), problems)

    def test_a_defaulted_variable_fails_the_required_variable_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}',
            '${BREAKER_IMAGE_TAG:-latest}')
        problems = self._compose_problems(sample)
        self.assertIn(_required_variable_problem(parse_compose_text(sample)), problems)

    def test_a_renamed_network_fails_the_network_guard(self):
        sample = self._mutate(COMPOSE_REL, 'networks:\n  breaker:', 'networks:\n  other:')
        problems = self._compose_problems(sample)
        self.assertIn(_network_problem(parse_compose_text(sample)), problems)

    def test_a_literal_subnet_fails_the_network_guard(self):
        sample = self._mutate(
            COMPOSE_REL, 'subnet: ${BREAKER_NETWORK_SUBNET:?BREAKER_NETWORK_SUBNET is required}',
            'subnet: 10.20.30.0/24')
        problems = self._compose_problems(sample)
        self.assertIn(_network_problem(parse_compose_text(sample)), problems)

    def test_a_dropped_ipam_config_fails_the_network_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            '    ipam:\n      config:\n'
            '        - subnet: ${BREAKER_NETWORK_SUBNET:?BREAKER_NETWORK_SUBNET is required}\n',
            '    ipam:\n      driver: default\n')
        problems = self._compose_problems(sample)
        self.assertIn(_network_problem(parse_compose_text(sample)), problems)

    def test_a_dropped_service_network_fails_the_network_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            "    networks:\n      breaker:\n"
            "        ipv4_address: ${BREAKER_SYNC_API_ADDRESS:?BREAKER_SYNC_API_ADDRESS is required}\n", '')
        problems = self._compose_problems(sample)
        self.assertIn(_network_problem(parse_compose_text(sample)), problems)

    def test_a_literal_address_fails_the_network_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            'ipv4_address: ${BREAKER_SYNC_API_ADDRESS:?BREAKER_SYNC_API_ADDRESS is required}',
            'ipv4_address: 10.20.30.11')
        problems = self._compose_problems(sample)
        self.assertIn(_network_problem(parse_compose_text(sample)), problems)

    def test_a_shared_address_variable_fails_the_network_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            'ipv4_address: ${BREAKER_WHISPER_ADDRESS:?BREAKER_WHISPER_ADDRESS is required}',
            'ipv4_address: ${BREAKER_SYNC_API_ADDRESS:?BREAKER_SYNC_API_ADDRESS is required}')
        problems = self._compose_problems(sample)
        self.assertIn(_network_problem(parse_compose_text(sample)), problems)

    def test_a_wildcard_host_fails_the_host_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            'BREAKER_SYNC_API_HOST: ${BREAKER_SYNC_API_ADDRESS:?BREAKER_SYNC_API_ADDRESS is required}',
            'BREAKER_SYNC_API_HOST: 0.0.0.0')
        problems = self._compose_problems(sample)
        self.assertIn(_host_problem(parse_compose_text(sample)), problems)

    def test_a_foreign_host_variable_fails_the_host_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            'BREAKER_WHISPER_HOST: ${BREAKER_WHISPER_ADDRESS:?BREAKER_WHISPER_ADDRESS is required}',
            'BREAKER_WHISPER_HOST: ${BREAKER_WEB_FE_ADDRESS:?BREAKER_WEB_FE_ADDRESS is required}')
        problems = self._compose_problems(sample)
        self.assertIn(_host_problem(parse_compose_text(sample)), problems)

    def test_a_wildcard_internal_host_fails_the_host_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            'BREAKER_SYNC_API_INTERNAL_HOST: ${BREAKER_SYNC_API_ADDRESS:?BREAKER_SYNC_API_ADDRESS is required}',
            'BREAKER_SYNC_API_INTERNAL_HOST: 0.0.0.0')
        problems = self._compose_problems(sample)
        self.assertIn(_host_problem(parse_compose_text(sample)), problems)

    def test_a_dropped_public_port_fails_the_ports_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            '      - ${BREAKER_BIND_ADDR:?BREAKER_BIND_ADDR is required}:${BREAKER_SYNC_API_PORT:?BREAKER_SYNC_API_PORT is required}:${BREAKER_SYNC_API_PORT:?BREAKER_SYNC_API_PORT is required}\n', '')
        problems = self._compose_problems(sample)
        self.assertIn(_ports_problem(parse_compose_text(sample)), problems)

    def test_a_numeric_published_port_fails_the_ports_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            '      - ${BREAKER_BIND_ADDR:?BREAKER_BIND_ADDR is required}:${BREAKER_SYNC_API_PORT:?BREAKER_SYNC_API_PORT is required}:${BREAKER_SYNC_API_PORT:?BREAKER_SYNC_API_PORT is required}\n',
            '      - [8080]\n')
        problems = self._compose_problems(sample)
        self.assertIn(_ports_problem(parse_compose_text(sample)), problems)

    def test_each_service_a_bare_published_port_fails_the_ports_guard(self):
        for service, port in (('sync-api', 'BREAKER_SYNC_API_PORT'),
                              ('whisper-server', 'BREAKER_WHISPER_PORT'),
                              ('web-fe', 'BREAKER_WEB_FE_PORT')):
            old = ('      - ${BREAKER_BIND_ADDR:?BREAKER_BIND_ADDR is required}:'
                   '${%s:?%s is required}:${%s:?%s is required}\n'
                   % (port, port, port, port))
            sample = self._mutate(COMPOSE_REL, old, '      - 8080:8080\n')
            problems = self._compose_problems(sample)
            self.assertIn(_ports_problem(parse_compose_text(sample)), problems)

    def test_each_service_a_wildcard_published_port_fails_the_ports_guard(self):
        for service, port in (('sync-api', 'BREAKER_SYNC_API_PORT'),
                              ('whisper-server', 'BREAKER_WHISPER_PORT'),
                              ('web-fe', 'BREAKER_WEB_FE_PORT')):
            old = ('      - ${BREAKER_BIND_ADDR:?BREAKER_BIND_ADDR is required}:'
                   '${%s:?%s is required}:${%s:?%s is required}\n'
                   % (port, port, port, port))
            sample = self._mutate(COMPOSE_REL, old, '      - 0.0.0.0:8080:8080\n')
            problems = self._compose_problems(sample)
            self.assertIn(_ports_problem(parse_compose_text(sample)), problems)

    def test_each_service_an_empty_bind_published_port_fails_the_ports_guard(self):
        for service, port in (('sync-api', 'BREAKER_SYNC_API_PORT'),
                              ('whisper-server', 'BREAKER_WHISPER_PORT'),
                              ('web-fe', 'BREAKER_WEB_FE_PORT')):
            old = ('      - ${BREAKER_BIND_ADDR:?BREAKER_BIND_ADDR is required}:'
                   '${%s:?%s is required}:${%s:?%s is required}\n'
                   % (port, port, port, port))
            sample = self._mutate(COMPOSE_REL, old, '      - :8080:8080\n')
            problems = self._compose_problems(sample)
            self.assertIn(_ports_problem(parse_compose_text(sample)), problems)

    def test_a_wrong_container_port_fails_the_ports_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            '      - ${BREAKER_BIND_ADDR:?BREAKER_BIND_ADDR is required}:${BREAKER_SYNC_API_PORT:?BREAKER_SYNC_API_PORT is required}:${BREAKER_SYNC_API_PORT:?BREAKER_SYNC_API_PORT is required}',
            '      - ${BREAKER_BIND_ADDR:?BREAKER_BIND_ADDR is required}:8080:8080')
        problems = self._compose_problems(sample)
        self.assertIn(_ports_problem(parse_compose_text(sample)), problems)

    def test_a_training_port_fails_the_ports_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '    profiles:\n      - training\n',
            '    profiles:\n      - training\n'
            '    ports:\n      - ${BREAKER_BIND_ADDR:?BREAKER_BIND_ADDR is required}:8080:8080\n')
        problems = self._compose_problems(sample)
        self.assertIn(_ports_problem(parse_compose_text(sample)), problems)

    def test_a_baked_in_secret_value_fails_the_secret_form_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            'BREAKER_WHISPER_SYNC_API_SERVICE_KEY: ${BREAKER_WHISPER_SYNC_API_SERVICE_KEY:?BREAKER_WHISPER_SYNC_API_SERVICE_KEY is required}',
            'BREAKER_WHISPER_SYNC_API_SERVICE_KEY: change-me')
        problems = self._compose_problems(sample)
        self.assertIn(_secret_form_problem(parse_compose_text(sample)), problems)

    def test_a_defaulted_secret_value_fails_the_secret_form_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            'BREAKER_WHISPER_SYNC_API_SERVICE_KEY: ${BREAKER_WHISPER_SYNC_API_SERVICE_KEY:?BREAKER_WHISPER_SYNC_API_SERVICE_KEY is required}',
            'BREAKER_WHISPER_SYNC_API_SERVICE_KEY: ${BREAKER_WHISPER_SYNC_API_SERVICE_KEY:-change-me}')
        problems = self._compose_problems(sample)
        self.assertIn(_secret_form_problem(parse_compose_text(sample)), problems)

    def test_a_privileged_service_fails_the_forbidden_keys_guard(self):
        sample = self._mutate(COMPOSE_REL, '    restart: unless-stopped\n',
                              '    restart: unless-stopped\n    privileged: true\n')
        problems = self._compose_problems(sample)
        self.assertIn(_forbidden_keys_problem(parse_compose_text(sample)), problems)

    def test_a_host_network_mode_fails_the_forbidden_keys_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '    image: breaker/whisper-server:${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}\n',
            '    network_mode: host\n'
            '    image: breaker/whisper-server:${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}\n')
        problems = self._compose_problems(sample)
        self.assertIn(_forbidden_keys_problem(parse_compose_text(sample)), problems)

    def test_a_host_pid_fails_the_forbidden_keys_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '    image: breaker/web-fe:${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}\n',
            '    pid: host\n'
            '    image: breaker/web-fe:${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}\n')
        problems = self._compose_problems(sample)
        self.assertIn(_forbidden_keys_problem(parse_compose_text(sample)), problems)

    def test_a_docker_socket_mount_fails_the_forbidden_keys_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '      - breaker_sync_api_data:/var/lib/breaker/sync-api\n',
            '      - breaker_sync_api_data:/var/lib/breaker/sync-api\n'
            '      - /var/run/docker.sock:/var/run/docker.sock\n')
        problems = self._compose_problems(sample)
        self.assertIn(_forbidden_keys_problem(parse_compose_text(sample)), problems)

    def test_a_dropped_no_new_privileges_fails_the_hardening_guard(self):
        sample = self._mutate(COMPOSE_REL, '      - no-new-privileges:true',
                              '      - no-new-privileges:false')
        problems = self._compose_problems(sample)
        self.assertIn(_hardening_problem(parse_compose_text(sample)), problems)

    def test_a_dropped_cap_drop_fails_the_hardening_guard(self):
        sample = self._mutate(COMPOSE_REL, '      - ALL', '      - NET_BIND_SERVICE')
        problems = self._compose_problems(sample)
        self.assertIn(_hardening_problem(parse_compose_text(sample)), problems)

    def test_a_read_only_service_fails_the_hardening_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '    image: breaker/web-fe:${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}\n',
            '    read_only: true\n'
            '    image: breaker/web-fe:${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}\n')
        problems = self._compose_problems(sample)
        self.assertIn(_hardening_problem(parse_compose_text(sample)), problems)

    def test_a_dropped_healthcheck_fails_the_healthcheck_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            '    healthcheck:\n      test:\n        - CMD\n        - curl\n'
            '        - -fsS\n'
            '        - http://${BREAKER_SYNC_API_ADDRESS:?BREAKER_SYNC_API_ADDRESS is required}:${BREAKER_SYNC_API_PORT:?BREAKER_SYNC_API_PORT is required}/health\n'
            '      interval: 30s\n      timeout: 5s\n      retries: 3\n'
            '      start_period: 20s\n', '')
        problems = self._compose_problems(sample)
        self.assertIn(_healthcheck_problem(parse_compose_text(sample)), problems)

    def test_a_non_curl_healthcheck_fails_the_healthcheck_guard(self):
        sample = self._mutate(COMPOSE_REL, '        - curl', '        - wget')
        problems = self._compose_problems(sample)
        self.assertIn(_healthcheck_problem(parse_compose_text(sample)), problems)

    def test_a_wrong_healthcheck_url_fails_the_healthcheck_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            '        - http://${BREAKER_SYNC_API_ADDRESS:?BREAKER_SYNC_API_ADDRESS is required}:${BREAKER_SYNC_API_PORT:?BREAKER_SYNC_API_PORT is required}/health',
            '        - http://${BREAKER_WHISPER_ADDRESS:?BREAKER_WHISPER_ADDRESS is required}:${BREAKER_SYNC_API_PORT:?BREAKER_SYNC_API_PORT is required}/health')
        problems = self._compose_problems(sample)
        self.assertIn(_healthcheck_problem(parse_compose_text(sample)), problems)

    def test_a_web_fe_healthcheck_fails_the_healthcheck_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '    image: breaker/web-fe:${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}\n',
            '    image: breaker/web-fe:${BREAKER_IMAGE_TAG:?BREAKER_IMAGE_TAG is required}\n'
            '    healthcheck:\n      test: ["CMD", "true"]\n')
        problems = self._compose_problems(sample)
        self.assertIn(_healthcheck_problem(parse_compose_text(sample)), problems)

    def test_a_dropped_data_volume_fails_the_volumes_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '      - breaker_sync_api_data:/var/lib/breaker/sync-api\n', '')
        problems = self._compose_problems(sample)
        self.assertIn(_volumes_problem(parse_compose_text(sample)), problems)

    def test_an_undeclared_data_volume_fails_the_volumes_guard(self):
        sample = self._mutate(COMPOSE_REL, '  breaker_sync_api_data:\n', '')
        problems = self._compose_problems(sample)
        self.assertIn(_volumes_problem(parse_compose_text(sample)), problems)

    def test_a_web_fe_volume_fails_the_volumes_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '      - breaker_releases:/srv/breaker/releases:ro\n',
            '      - breaker_releases:/srv/breaker/releases:ro\n'
            '      - /etc/localtime:/etc/localtime:ro\n')
        problems = self._compose_problems(sample)
        self.assertIn(_volumes_problem(parse_compose_text(sample)), problems)

    def test_a_web_fe_releases_mount_read_write_fails_the_volumes_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '      - breaker_releases:/srv/breaker/releases:ro\n',
            '      - breaker_releases:/srv/breaker/releases\n')
        problems = self._compose_problems(sample)
        self.assertIn(_volumes_problem(parse_compose_text(sample)), problems)

    def test_a_dropped_web_fe_releases_mount_fails_the_volumes_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            '    volumes:\n      - breaker_releases:/srv/breaker/releases:ro\n', '')
        problems = self._compose_problems(sample)
        self.assertIn(_volumes_problem(parse_compose_text(sample)), problems)

    def test_a_training_volume_fails_the_volumes_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '  training:\n',
            '  training:\n    volumes:\n      - /etc/localtime:/etc/localtime:ro\n')
        problems = self._compose_problems(sample)
        self.assertIn(_volumes_problem(parse_compose_text(sample)), problems)

    def test_a_sync_api_releases_mount_read_write_fails_the_volumes_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '      - breaker_releases:/var/lib/breaker/releases:ro\n',
            '      - breaker_releases:/var/lib/breaker/releases\n')
        problems = self._compose_problems(sample)
        self.assertIn(_volumes_problem(parse_compose_text(sample)), problems)

    def test_a_duplicated_sync_api_releases_mount_fails_the_volumes_guard(self):
        line = '      - breaker_releases:/var/lib/breaker/releases:ro\n'
        sample = self._mutate(COMPOSE_REL, line, line + line)
        problems = self._compose_problems(sample)
        message = _volumes_problem(parse_compose_text(sample))
        self.assertIn('sync-api: the release volume breaker_releases', message)
        self.assertIn(message, problems)

    def test_a_dropped_sync_api_releases_mount_fails_the_volumes_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '      - breaker_releases:/var/lib/breaker/releases:ro\n', '')
        problems = self._compose_problems(sample)
        self.assertIn(_volumes_problem(parse_compose_text(sample)), problems)

    def test_a_db_path_outside_the_volume_fails_the_volumes_guard(self):
        sample = self._mutate(
            COMPOSE_REL, 'BREAKER_SYNC_API_DB: /var/lib/breaker/sync-api/sync-api.db',
            'BREAKER_SYNC_API_DB: /tmp/sync-api.db')
        problems = self._compose_problems(sample)
        self.assertIn(_volumes_problem(parse_compose_text(sample)), problems)

    def test_a_second_bind_mount_fails_the_bind_mount_guard(self):
        sample = self._mutate(
            COMPOSE_REL, '      - breaker_whisper_data:/var/lib/breaker/whisper-server\n',
            '      - breaker_whisper_data:/var/lib/breaker/whisper-server\n'
            '      - /etc/localtime:/etc/localtime:ro\n')
        problems = self._compose_problems(sample)
        self.assertIn(_bind_mount_problem(parse_compose_text(sample)), problems)

    def test_a_named_volume_with_a_bogus_third_part_counts_as_a_bind(self):
        sample = self._mutate(
            COMPOSE_REL, '      - breaker_releases:/srv/breaker/releases:ro\n',
            '      - breaker_releases:/srv/breaker/releases:bogus\n')
        problems = self._compose_problems(sample)
        message = _bind_mount_problem(parse_compose_text(sample))
        self.assertEqual(message, 'exactly one host bind mount is allowed '
                         '(the service key file); found 2')
        self.assertIn(message, problems)

    def test_an_undeclared_name_in_a_three_part_entry_counts_as_a_bind(self):
        sample = self._mutate(
            COMPOSE_REL, '      - breaker_releases:/srv/breaker/releases:ro\n',
            '      - breaker_other:/srv/breaker/releases:ro\n')
        problems = self._compose_problems(sample)
        message = _bind_mount_problem(parse_compose_text(sample))
        self.assertEqual(message, 'exactly one host bind mount is allowed '
                         '(the service key file); found 2')
        self.assertIn(message, problems)

    def test_a_writable_bind_mount_fails_the_bind_mount_guard(self):
        sample = self._mutate(
            COMPOSE_REL, ':/run/breaker/keys/sync-api-service-key:ro',
            ':/run/breaker/keys/sync-api-service-key')
        problems = self._compose_problems(sample)
        self.assertIn(_bind_mount_problem(parse_compose_text(sample)), problems)

    def test_a_wrong_bind_source_fails_the_bind_mount_guard(self):
        sample = self._mutate(
            COMPOSE_REL,
            '${BREAKER_SYNC_API_SERVICE_KEY_HOST:?BREAKER_SYNC_API_SERVICE_KEY_HOST is required}',
            '${BREAKER_OTHER:?BREAKER_OTHER is required}')
        problems = self._compose_problems(sample)
        self.assertIn(_bind_mount_problem(parse_compose_text(sample)), problems)

    def test_a_wrong_bind_target_fails_the_bind_mount_guard(self):
        sample = self._mutate(
            COMPOSE_REL, ':/run/breaker/keys/sync-api-service-key:ro',
            ':/run/breaker/keys/other:ro')
        problems = self._compose_problems(sample)
        self.assertIn(_bind_mount_problem(parse_compose_text(sample)), problems)

    def test_a_missing_env_line_fails_the_env_example_guard(self):
        sample = self._mutate(ENV_EXAMPLE_REL, 'BREAKER_WHISPER_PORT=\n', '')
        problems = self._env_problems(sample)
        self.assertIn(_env_example_problem(sample, read_text(COMPOSE_REL)), problems)

    def test_an_extra_env_line_fails_the_env_example_guard(self):
        sample = self._mutate(ENV_EXAMPLE_REL, 'BREAKER_IMAGE_TAG=\n',
                              'BREAKER_IMAGE_TAG=\nBREAKER_UNREFERENCED=value\n')
        problems = self._env_problems(sample)
        self.assertIn(_env_example_problem(sample, read_text(COMPOSE_REL)), problems)

    def test_an_angle_bracket_token_fails_the_env_example_guard(self):
        sample = self._mutate(ENV_EXAMPLE_REL, 'BREAKER_WHISPER_FORWARDER_URL=\n',
                              'BREAKER_WHISPER_FORWARDER_URL=<change me>\n')
        problems = self._env_problems(sample)
        self.assertIn(_env_example_problem(sample, read_text(COMPOSE_REL)), problems)

    def test_a_secret_literal_in_the_env_example_fails_the_env_example_guard(self):
        sample = self._mutate(ENV_EXAMPLE_REL, 'BREAKER_WHISPER_SYNC_API_SERVICE_KEY=\n',
                              'BREAKER_WHISPER_SYNC_API_SERVICE_KEY=change-me\n')
        problems = self._env_problems(sample)
        self.assertIn(_env_example_problem(sample, read_text(COMPOSE_REL)), problems)

    def test_a_real_looking_address_fails_the_env_example_guard(self):
        sample = self._mutate(ENV_EXAMPLE_REL, 'BREAKER_SYNC_API_ADDRESS=10.20.30.11',
                              'BREAKER_SYNC_API_ADDRESS=203.0.113.5')
        problems = self._env_problems(sample)
        self.assertIn(_env_example_problem(sample, read_text(COMPOSE_REL)), problems)

    def test_a_dropped_placeholder_comment_fails_the_env_example_guard(self):
        sample = self._mutate(
            ENV_EXAMPLE_REL,
            '# Example addresses in private space (10.20.30.x); placeholders, not real hosts.\n', '')
        problems = self._env_problems(sample)
        self.assertIn(_env_example_problem(sample, read_text(COMPOSE_REL)), problems)

    def test_a_dropped_key_length_rule_fails_the_key_file_rules_guard(self):
        sample = self._mutate(
            ENV_EXAMPLE_REL,
            '# the file named below must be exactly 43 characters of the URL-safe alphabet '
            'A-Z a-z 0-9 _ - with at most one trailing newline\n', '')
        problems = self._env_problems(sample)
        self.assertIn(_key_file_rules_problem(sample), problems)

    def test_a_dropped_owner_only_rule_fails_the_key_file_rules_guard(self):
        sample = self._mutate(
            ENV_EXAMPLE_REL,
            '# it must be owner-only: mode 0600, no group or other permission bits\n', '')
        problems = self._env_problems(sample)
        self.assertIn(_key_file_rules_problem(sample), problems)

    def test_a_dropped_must_exist_rule_fails_the_key_file_rules_guard(self):
        sample = self._mutate(
            ENV_EXAMPLE_REL,
            '# it must exist before `up`: a missing source path makes the container runtime '
            'create a directory there and sync-api refuses to start\n', '')
        problems = self._env_problems(sample)
        self.assertIn(_key_file_rules_problem(sample), problems)

    def test_a_dropped_key_mount_pointer_fails_the_pin(self):
        sample = self._mutate(
            COMPOSE_REL,
            "      # The host file's rules are in .env.example above "
            "BREAKER_SYNC_API_SERVICE_KEY_HOST.\n", '')
        problems = compose_text_problems(sample)
        self.assertIn(_key_mount_pointer_problem(sample), problems)


if __name__ == '__main__':
    unittest.main()
