// The admin status banner of ADR-044 point 4, as two pure functions and no
// page. parseStatus(answer) decides whether a status answer is one this module
// understands. nextBanner(memory, parsed, nowMs, formatTime) turns a known
// answer into the banner lines and remembers, per service, the first time THIS
// browser saw it down. There is no DOM, no fetch and no clock of its own: the
// time source and the HH:MM formatter are injected, so a test is deterministic.

// The three banner sentences, without their HH:MM: the injected formatTime
// fills the time and the module appends the final period. Plain strings only,
// never HTML.
export const TRANSCRIPTION_DOWN = 'Transcription is down since';
export const SERVER_DOWN = 'The server is down since';
export const SERVICE_DOWN = 'is down since';

// Letters, digits and dash, 1..40 characters.
const NAME_RE = /^[A-Za-z0-9-]{1,40}$/;
const MAX_SERVICES = 20;

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

// The default formatter: local HH:MM from a Date built on the given ms.
export function formatLocalTime(ms) {
  const at = new Date(ms);
  const hours = String(at.getHours()).padStart(2, '0');
  const minutes = String(at.getMinutes()).padStart(2, '0');
  return hours + ':' + minutes;
}

// A known answer is status 200 and a body whose services is an array of 1..20
// objects, each with a plain-string name and a boolean up, with no duplicate
// name. Anything else is unknown.
export function parseStatus(answer) {
  if (!isObject(answer) || answer.status !== 200) return { known: false };
  const body = answer.body;
  if (!isObject(body)) return { known: false };
  const services = body.services;
  if (!Array.isArray(services) || services.length < 1 || services.length > MAX_SERVICES) {
    return { known: false };
  }
  const seen = new Set();
  const rows = [];
  for (const row of services) {
    if (!isObject(row)) return { known: false };
    const name = row.name;
    const up = row.up;
    if (typeof name !== 'string' || !NAME_RE.test(name)) return { known: false };
    if (typeof up !== 'boolean') return { known: false };
    if (seen.has(name)) return { known: false };
    seen.add(name);
    rows.push({ name, up });
  }
  return { known: true, services: rows };
}

// The memory is a Map, never a plain object: a service called `constructor`
// must not look already-seen-down through Object.prototype.
function asMap(memory) {
  if (memory instanceof Map) return memory;
  const map = new Map();
  if (isObject(memory)) {
    for (const name of Object.keys(memory)) map.set(name, memory[name]);
  }
  return map;
}

// One line per down service, in list order. A service seen down keeps its
// first-seen time while it stays down; a service seen up is dropped, so a later
// outage restarts its clock. An unknown answer changes nothing and says nothing.
export function nextBanner(memory, parsed, nowMs, formatTime = formatLocalTime) {
  const map = asMap(memory);
  if (!isObject(parsed) || parsed.known !== true) {
    return { memory: map, lines: [] };
  }
  for (const service of parsed.services) {
    if (service.up) {
      map.delete(service.name);
    } else if (!map.has(service.name)) {
      map.set(service.name, nowMs);
    }
  }
  const lines = [];
  for (const service of parsed.services) {
    if (service.up) continue;
    const when = formatTime(map.get(service.name));
    if (service.name === 'whisper-server') {
      lines.push(TRANSCRIPTION_DOWN + ' ' + when + '.');
    } else if (service.name === 'sync-api') {
      lines.push(SERVER_DOWN + ' ' + when + '.');
    } else {
      lines.push(service.name + ' ' + SERVICE_DOWN + ' ' + when + '.');
    }
  }
  return { memory: map, lines };
}
