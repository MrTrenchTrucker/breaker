# AGENTS.md — android/modules/transport/

## Purpose

Whether the Local Server is reachable (TCP connect, 1.5 s timeout, and a 30 s cache that holds only a measured answer); it probes only the configured Local Server, so there is no cloud path to fall through to.
Core's `DictateUseCase` asks this probe and picks the engine itself
(**server-primary**); this module only answers the question and **never
contacts any other host** (Security Review fix #1).

**Build phase:** Phase 5, together with `stt-server`. Needs first: `core` (on main).

## Owns
Whether the Local Server is reachable (TCP connect, 1.5 s timeout, and a 30 s cache that holds only a measured answer); it probes only the configured Local Server, so there is no cloud path to fall through to.

## Public Interface
`TcpConnectivityProbe`, which implements `core.ConnectivityProbe`
(`isServerReachable(): Boolean`), the only port this module implements.
`android/app` constructs it as `TcpConnectivityProbe(serverUrlProvider, clock)`
and its DI wiring binds it into `core.DictateUseCase` (ADR-001), which makes the
routing decision itself from the probe's answer: which engine runs, the
`LOCAL_MODEL_MISSING` refusal, the server-primary fallback. This module never
imports `DictateUseCase` or a sibling module.

`TcpConnectivityProbe` also has `refresh(): Boolean`: probe now, ignoring the
30 s cache, and store the fresh answer (for example right after the user edits
the server address) - but only if that answer was measured, so a refresh that
finds no free slot or a still-parked lookup caches nothing. It is not on the
core port, so only the holder of the concrete probe (`android/app`) can call
it.

**Public types** — this list is the module's registry line, kept in the same
order and spelling:

- `TcpConnectivityProbe`

**How core uses the answer (`AppSettings.mode`, a `core.SttMode`):**
- `AUTO` (server-primary, the default): `DictateUseCase` asks this probe once,
  before the attempt. Reachable → the server engine, then the LLM formatter;
  not reachable → the on-device engine, then the rule-based formatter. A
  server failure after that is surfaced, not retried on the phone.
- `LOCAL`: always on-device, no probe. **No model installed → clear error
  (`LOCAL_MODEL_MISSING`), no server attempt, no misleading "Set API key."**
- `SERVER`: always the server, no probe; fails loudly if unreachable.

**Audio queue (ADR-002):** met by core, not by this module. `DictateUseCase`
records the whole clip before it asks the probe, so no audio waits on a probe
and none is dropped. This module holds no audio. Never block the UI.

**Threading:** the probe's resolve-plus-connect body runs on the bounded pool
of named daemon threads the module always had - a `SynchronousQueue`-backed
`ThreadPoolExecutor` with a 30 s worker idle window - now reached through the
pool's `asCoroutineDispatcher()` instead of a `Runnable` handed to it directly.
The caller's wait is a `runBlocking` that bridges into the budgeted wait on a
`CompletableDeferred`, and the per-instance lock that serialises cold probes is
a `Mutex`. Never block the UI.

**Why these primitives and not coroutines.** The threading above is a fact
about this module's code, and it is the worked example of the narrow exception
class named in the root order (root `AGENTS.md` section 6). The order holds for
every concurrent thing here that is not one of these three cases, and each case
is decided by a site rather than by preference:
- **No dispatcher supplies a dedicated daemon thread at default priority.** The
  shared default pools recycle workers for unrelated work, so the probe workers
  are created and named by hand in `ProbeExecutor` and the body runs on them
  through the pool's own `asCoroutineDispatcher()`, which hands the worker the
  work and nothing else of a coroutine.
- **`Mutex` is non-reentrant, has no timed acquire and `withLock` takes a
  suspend lambda.** It therefore cannot carry the admission gate: `claim`, both
  releases and `answering` are all non-suspend, reached from the submitted
  wrapper's `finally`, and a `withLock` none of them could call; the gate also
  checks and writes the two counters as one indivisible step, which is the
  property a `Mutex` would not add.
- **`answering` is non-suspend, so the slot token is a `ThreadLocal`, not a
  coroutine context element.** It is called from inside the body, before the
  value escapes, and its signature is pinned; a context element would need the
  same `ThreadLocal` behind it to be reachable from non-suspend code, and a
  plain `ThreadLocal` that a body that moved its work to another thread finds
  is empty and releases nothing, which is the safe failure.

The two admission counters are `AtomicInteger`s taken under the gate's monitor
and the in-flight marks live in the single-flight map; none of them is a
coroutine state that a `Mutex` or a channel would improve on.

**The bounded pool's design (`ProbeExecutor`).** The threading above is the
mechanism; this is the design it exists to hold. `ProbeExecutor`'s KDoc states
the contract the code keeps and points here for the reasoning, per topic:
- **Why not a single worker with a queue of one.** A name lookup cannot be
  interrupted: `InetAddress.getAllByName` hands the name to the OS resolver and
  waits on it, so a lookup against a blackholed resolver, a captive portal or a
  VPN mid-handshake parks its thread until the platform gives up on its own
  terms. Under the single worker + queue of one this replaced, one such lookup
  parked the ONE thread that served every probe in the JVM, and every later
  probe was refused without a connection attempt and answered "not reachable" -
  which the domain read as "the server is down" and routed on-device, with no
  way back short of a process restart. A wedged worker must therefore be
  counted and replaced, not waited on, and the two counters are what make that
  replacement honest.
- **Two counters, released at two different moments - the asymmetry is the
  design.** `occupied` is the SLOT: admitted and not yet ANSWERED, given back
  at PUBLISH by `answering`'s `finally`, which runs before the value it
  produced can be observed. `bodies` is the THREAD: held by any running body,
  answered or not, given back when the body ENDS - it returns, or it throws -
  in the submitted wrapper's `finally`. They are deliberately not the same
  moment: a body that has published its answer but is still inside its own task
  really is occupying an OS thread the pool cannot hand to anybody else, so
  releasing the thread at publish would let the pool over-subscribe itself into
  a rejection; and a body that has NOT yet answered is genuinely still looking,
  so holding its slot past the answer is what made a healthy address answer
  "not reachable" with no dial.
- **Admission needs both, together.** `claim` checks the slot and the thread
  under ONE lock and takes both or neither; separate checks would not be a
  style question - two callers could each pass one condition and fail the
  other, and both proceed, so the bound the pool promises would hold by luck.
  A refused attempt changes nothing: both conditions are tested before either
  counter is written, so there is no partial update to undo, and a refused
  caller takes no slot and no thread and has no token to release.
- **The two caps, and their headroom.** `MAX_WEDGED_PROBES` is 2: one wedged
  lookup, plus one healthy address that must still be dialled - a captive
  portal that swallows DNS for one address must not silence a different,
  perfectly good one - and it is the point at which "the network stack is
  comprehensively down" describes the state better than "another address needs
  probing": however many lookups hang, at most two UNANSWERED bodies are ever
  in flight. `POOL_MAX_THREADS` is the slot cap plus its ONE headroom thread,
  which is the pool's own `maximumPoolSize`, and it is a check, not a sizing:
  it is the count of bodies that have started and not yet ended, and it exists
  because the slot cap alone cannot see post-answer work. Exactly one headroom
  is load-bearing, because it makes `claim` the only refuser - sized to the
  cap instead, the JDK pool would refuse overflow work indistinguishably from
  a cap refusal and a neutralised `claim` guard would be undetectable.
- **At either cap the answer is "not reachable", at once, and the task never
  runs on the caller.** `execute` returns false rather than throwing: a
  `CallerRunsPolicy` would move an unbounded, uninterruptible lookup onto
  exactly the caller this pool exists to protect. The caller turns the false
  into "not reachable" without a connection attempt ever being made.
- **A submitted body MAY throw, and the containment sits in the launch
  block, not the body.** The body is a bare `Runnable` handed to `execute` by
  callers the module does not own, so the module cannot make every body catch
  its own throw; the one place the module controls, on every body, is the
  wrapper in `executeReporting`, where the throw is caught, the counters are
  given back by the `finally`, and the launched coroutine is kept on the
  normal-completion path. That is load-bearing, not incidental: a launched
  body that completed its coroutine EXCEPTIONALLY would hand the failure to
  the thread's uncaught-exception handler by the coroutine machinery's last
  resort, and on Android the documented default handler is what ends the
  process. The throw is not logged there on purpose: the body's caller already
  holds the failure (a production body publishes it to its deferred, a test
  body observes it through its own latch), and any work between the body
  ending and the counters coming back - even a stderr write the test runner
  captures - holds the slot and the thread while the next admission is judged
  and can refuse a body that should be admitted. Nothing is dropped that a
  caller cannot already see; nothing is handed to a thread the module does not
  own.
- **Process-wide, and never shut down.** This is a file-level singleton on
  purpose: an executor per probe instance would multiply threads - and file
  descriptors - by the number of probes built, and nothing ever tears a probe
  down, so a per-instance executor could only ever leak. Nothing owns the
  pool's lifetime, so it is never shut down, and the threads are daemon
  threads.
- **The honest limit: post-answer work is not bounded.** A body that hangs
  after answering holds its thread, and at `POOL_MAX_THREADS` further probes
  are refused as not measured until it ends. Today's post-answer work is
  socket close plus unmark: microseconds, reasoned, not measured.

## Invariants
- Reachable only when the configured Local Server accepts a TCP connect within
  1.5 s; refused, timed out or no network all answer "not reachable".
- A measured answer is cached for 30 s and can be refreshed on demand. Measured
  means the dial settled one way or another: a connection was accepted, or the
  connect produced a definite negative - refused, no network, a name that does
  not resolve, a body that threw, or a connect that ran out its own time, which
  normally settles inside the socket, just before the caller's budget runs out.
  NOT cached is only the probe that measured nothing: never dialled (no free
  slot, or this name's lookup still parked), or still running when the 1.5 s
  budget ran out - in practice a name whose lookup has not returned. A connect
  that timed out because its own socket timeout beat the caller's budget IS
  measured and cached; only when the caller's budget wins that race is the
  result not learned, and that is the case above.
- The probe never contacts any host other than the configured Local Server.
- Met and tested in core's `DictateUseCase`, listed so nobody rebuilds them
  here: server-primary picks the server when reachable and the phone when not
  (F1–F3); `LOCAL` with no model → `LOCAL_MODEL_MISSING` (fix #1 regression
  test); a probe never costs audio.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- The engines themselves (stt-*)
- Sync (sync)
- Routing between engines and the fallback decision (`core.DictateUseCase`)

## Test Locations
- Unit (Kotlin): `android/modules/transport/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:transport:test`
- Contract: `tests/contract/test_transport_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_transport_contract.py`
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.

## Test Requirement
Every test added or touched for this module must be proven to fail loudly:
break the protected behavior on purpose, confirm the test fails and says why,
then restore the code. A test that only ever passes proves nothing. This
applies to every tier and invariant listed above, not only the ones that seem
fragile.

## How This Module Is Built
One engineer owns this module and delivers it as one pull request, working on
one module at a time. The owner does not write the module's code. The owner:
- splits the work into sub-modules and has sub-agents write each one, code and
  tests;
- coordinates and orchestrates those sub-agents, checks every piece of their
  work, and sends back anything that is wrong until it is right;
- convenes a small council of sub-agents to advise on design, risks and tests
  before and during the build;
- hands the finished, checked module directly to the reviewer as a single pull
  request.
The owner's own work is orchestration, checking and correction, not writing
code.

This is how the project's own team builds modules. It is recommended for AI
agents, not required: an outside contributor may write the code themselves
(`.github/CONTRIBUTING.md`).

## Known Gotchas
- Probe TTL-cached 30 s, but only a measured answer is — never block dictation on the server.
- **A wedged name lookup is bounded, not cured.** `InetAddress.getAllByName` has
  no timeout and ignores interrupts, so one hung DNS lookup (captive portal, VPN,
  firewall dropping DNS) can pin a worker. `ProbeExecutor` caps that at
  `MAX_WEDGED_PROBES` (2) and makes lookups for one name single-flight, so a
  permanently hung host holds at most ONE slot. The cap counts lookups THAT HAVE
  NOT ANSWERED YET, not lookups in flight generally: a worker hands its slot back
  the moment it PUBLISHES its answer, not when its task body returns, so a worker
  that has already answered must never keep the cap full - otherwise a healthy
  address is refused and answered "not reachable" with no dial, while nothing at
  all is wedged. **Post-answer work is not bounded:** a body that hangs AFTER
  answering still holds its thread, and at pool max further probes are refused as
  not measured until it ends. Today's post-answer work is socket close plus
  unmark - microseconds, but REASONED, not measured. Two limits also remain, both
  deliberate: **two DIFFERENT hung hosts still fill the cap** and every later
  probe is then refused; and a re-probe of a parked host answers "not reachable"
  at once, so a host that is merely slow is indistinguishable from a permanently
  wedged one for the duration of its lookup - the caller gets that answer with no
  evidence a dial was attempted. That is the price of not spending a second slot;
  the alternative is a cap overrun that silences healthy addresses too.
  **A refusal names its cause, and the two caps do not share one.** `claim()`
  hands back a sealed `Claim` - the admission it took, or the cap that stopped
  it - and names the two caps apart: `NO_FREE_SLOT` when every slot of
  `MAX_WEDGED_PROBES` is held by a lookup that has not answered, and
  `NO_FREE_THREAD` when all `POOL_MAX_THREADS` (3) threads are held by bodies
  that have not ended, which its reason text says out loud. Every refusal means
  the same thing to a CALLER - nothing was measured, nothing is cached - and a
  different thing in a report. The claim is taken under ONE lock, so a refused
  attempt writes nothing, mints no release token, and cannot release a slot it
  did not hold; the type is sealed, so a cap added later without a branch fails
  to compile rather than silently misreporting the cause.
- Speech engines: `core.SttEngine` instances are wired into
  `core.DictateUseCase` by `android/app` (ADR-001), not into this module. It
  never sees an `SttEngine` and never imports `stt-ondevice` or `stt-server`.
