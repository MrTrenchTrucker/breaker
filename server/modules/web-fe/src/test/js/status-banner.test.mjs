import test from 'node:test';
import assert from 'node:assert/strict';
import {
  parseStatus,
  nextBanner,
  TRANSCRIPTION_DOWN,
  SERVER_DOWN,
  SERVICE_DOWN,
} from '../../main/resources/web/js/status-banner.js';

// A deterministic formatter: the module must use the injected one, never a clock.
const FMT = (ms) => 'T' + ms;

function answer(status, body) {
  return { status, body };
}

function withServices(rows) {
  return answer(200, { services: rows });
}

function row(name, up) {
  return { name, up };
}

// A parsed known answer, as nextBanner expects.
function known(rows) {
  return parseStatus(withServices(rows));
}

test('parseStatus knows a 200 answer with one down service', () => {
  assert.deepEqual(parseStatus(withServices([row('whisper-server', false)])), {
    known: true,
    services: [{ name: 'whisper-server', up: false }],
  });
});

test('parseStatus keeps the list order and the up flag of every service', () => {
  const parsed = parseStatus(withServices([row('sync-api', true), row('whisper-server', false), row('db-1', true)]));
  assert.equal(parsed.known, true);
  assert.deepEqual(parsed.services, [
    { name: 'sync-api', up: true },
    { name: 'whisper-server', up: false },
    { name: 'db-1', up: true },
  ]);
});

test('parseStatus rejects a 401 answer', () => {
  assert.deepEqual(parseStatus(answer(401, { services: [row('sync-api', true)] })), { known: false });
});

test('parseStatus rejects a 5xx answer', () => {
  assert.deepEqual(parseStatus(answer(503, { services: [row('sync-api', true)] })), { known: false });
});

test('parseStatus rejects a null body', () => {
  assert.deepEqual(parseStatus(answer(200, null)), { known: false });
});

test('parseStatus rejects a body whose services is not an array', () => {
  assert.deepEqual(parseStatus(answer(200, { services: { name: 'sync-api', up: true } })), { known: false });
  assert.deepEqual(parseStatus(answer(200, {})), { known: false });
});

test('parseStatus rejects an empty services array', () => {
  assert.deepEqual(parseStatus(withServices([])), { known: false });
});

test('parseStatus rejects more than twenty services', () => {
  const rows = [];
  for (let i = 0; i < 21; i += 1) rows.push(row('svc-' + i, true));
  assert.deepEqual(parseStatus(withServices(rows)), { known: false });
});

test('parseStatus accepts exactly twenty services', () => {
  const rows = [];
  for (let i = 0; i < 20; i += 1) rows.push(row('svc-' + i, true));
  assert.equal(parseStatus(withServices(rows)).known, true);
});

test('parseStatus rejects a row that is not an object', () => {
  assert.deepEqual(parseStatus(withServices(['sync-api'])), { known: false });
  assert.deepEqual(parseStatus(withServices([null])), { known: false });
});

test('parseStatus rejects a name that is not a plain string', () => {
  assert.deepEqual(parseStatus(withServices([row(7, true)])), { known: false });
  assert.deepEqual(parseStatus(withServices([row(null, true)])), { known: false });
});

test('parseStatus rejects a name with a character outside letters digits and dash', () => {
  for (const name of ['sync_api', 'sync api', 'sync@api', 'sync.api']) {
    assert.deepEqual(parseStatus(withServices([row(name, true)])), { known: false }, name);
  }
});

test('parseStatus rejects an empty name', () => {
  assert.deepEqual(parseStatus(withServices([row('', true)])), { known: false });
});

test('parseStatus accepts a forty character name and rejects a forty one character name', () => {
  assert.equal(parseStatus(withServices([row('a'.repeat(40), true)])).known, true);
  assert.deepEqual(parseStatus(withServices([row('a'.repeat(41), true)])), { known: false });
});

test('parseStatus rejects a non-boolean up', () => {
  assert.deepEqual(parseStatus(withServices([row('sync-api', 'yes')])), { known: false });
  assert.deepEqual(parseStatus(withServices([row('sync-api', 1)])), { known: false });
  assert.deepEqual(parseStatus(withServices([row('sync-api', null)])), { known: false });
});

test('parseStatus rejects duplicate names', () => {
  assert.deepEqual(parseStatus(withServices([row('sync-api', true), row('sync-api', false)])), { known: false });
});

test('nextBanner records the first-seen time of a down service and keeps it while it stays down', () => {
  const memory = new Map();
  const first = nextBanner(memory, known([row('sync-api', false)]), 1000, FMT);
  assert.deepEqual(first.memory, new Map([['sync-api', 1000]]));
  assert.deepEqual(first.lines, [SERVER_DOWN + ' T1000.']);
  const second = nextBanner(first.memory, known([row('sync-api', false)]), 5000, FMT);
  assert.deepEqual(second.memory, new Map([['sync-api', 1000]]));
  assert.deepEqual(second.lines, [SERVER_DOWN + ' T1000.']);
});

test('nextBanner drops a service seen up so a later outage restarts the clock', () => {
  let memory = nextBanner(new Map(), known([row('sync-api', false)]), 1000, FMT).memory;
  memory = nextBanner(memory, known([row('sync-api', true)]), 2000, FMT).memory;
  assert.deepEqual(memory, new Map());
  const later = nextBanner(memory, known([row('sync-api', false)]), 9000, FMT);
  assert.deepEqual(later.memory, new Map([['sync-api', 9000]]));
  assert.deepEqual(later.lines, [SERVER_DOWN + ' T9000.']);
});

test('nextBanner returns the memory unchanged and no lines for an unknown answer', () => {
  const memory = new Map([['sync-api', 1234]]);
  const out = nextBanner(memory, parseStatus(answer(500, null)), 5000, FMT);
  assert.deepEqual(out.memory, new Map([['sync-api', 1234]]));
  assert.deepEqual(out.lines, []);
  const out2 = nextBanner(memory, { known: false }, 5000, FMT);
  assert.deepEqual(out2.memory, new Map([['sync-api', 1234]]));
  assert.deepEqual(out2.lines, []);
});

test('nextBanner writes one line per down service in list order', () => {
  const out = nextBanner(
    new Map(),
    known([row('whisper-server', false), row('sync-api', true), row('db-1', false)]),
    1000,
    FMT,
  );
  assert.deepEqual(out.lines, [TRANSCRIPTION_DOWN + ' T1000.', 'db-1 ' + SERVICE_DOWN + ' T1000.']);
});

test('nextBanner uses the injected formatTime', () => {
  const out = nextBanner(new Map(), known([row('db-1', false)]), 480, (ms) => 'at ' + ms);
  assert.deepEqual(out.lines, ['db-1 ' + SERVICE_DOWN + ' at 480.']);
});

test('nextBanner says Transcription for whisper-server', () => {
  const out = nextBanner(new Map(), known([row('whisper-server', false)]), 1000, FMT);
  assert.deepEqual(out.lines, ['Transcription is down since T1000.']);
});

test('nextBanner says The server for sync-api', () => {
  const out = nextBanner(new Map(), known([row('sync-api', false)]), 1000, FMT);
  assert.deepEqual(out.lines, ['The server is down since T1000.']);
});

test('nextBanner says the name for any other validated name', () => {
  const out = nextBanner(new Map(), known([row('db-1', false)]), 1000, FMT);
  assert.deepEqual(out.lines, ['db-1 is down since T1000.']);
});

test('a hostile name is rejected by parseStatus and never echoed in a line', () => {
  const hostile = '<img src=x onerror=alert(1)>';
  const parsed = parseStatus(withServices([row(hostile, false)]));
  assert.deepEqual(parsed, { known: false });
  const out = nextBanner(new Map(), parsed, 1000, FMT);
  assert.deepEqual(out.lines, []);
  assert.equal(out.lines.join(' ').includes('<img'), false);
});

test('the four prototype-trap names behave as ordinary names', () => {
  for (const name of ['constructor', 'toString', 'hasOwnProperty', 'valueOf']) {
    const memory = nextBanner(new Map(), known([row(name, false)]), 1000, FMT).memory;
    assert.deepEqual(memory, new Map([[name, 1000]]), name);
    const kept = nextBanner(memory, known([row(name, false)]), 5000, FMT);
    assert.deepEqual(kept.memory, new Map([[name, 1000]]), name);
    assert.deepEqual(kept.lines, [name + ' ' + SERVICE_DOWN + ' T1000.'], name);
    const dropped = nextBanner(kept.memory, known([row(name, true)]), 6000, FMT);
    assert.deepEqual(dropped.memory, new Map(), name);
  }
});

test('parseStatus rejects a duplicate prototype-trap name', () => {
  for (const name of ['constructor', 'toString', 'hasOwnProperty', 'valueOf']) {
    assert.deepEqual(parseStatus(withServices([row(name, true), row(name, false)])), { known: false }, name);
  }
});

test('the default formatTime reads local HH:MM from the given ms', () => {
  const ms = new Date(2026, 0, 2, 9, 5).getTime();
  const out = nextBanner(new Map(), known([row('db-1', false)]), ms);
  const at = new Date(ms);
  const expected = String(at.getHours()).padStart(2, '0') + ':' + String(at.getMinutes()).padStart(2, '0');
  assert.deepEqual(out.lines, ['db-1 ' + SERVICE_DOWN + ' ' + expected + '.']);
});
