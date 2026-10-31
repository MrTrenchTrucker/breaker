# Gesture - README

Shake-to-wake: detect a shake gesture from accelerometer samples and fire a callback. The detector turns a stream of raw `Sample` values into the single event "a shake happened", and hands the callback to the app's wiring, which decides what a shake does - that wiring lives outside this module.

## What it does

The module's public door is the `ShakeHandle`: `ShakeHandle.create(onShake, port: ShakePort)` wires a port to the detector, and `start(): Boolean` / `stop()` run it. The port is `ShakePort`; its `SensorManager`-backed implementation is `SensorManagerShakeSource`. `ShakeDetector` is module-internal - the pure, JVM-testable core the handle drives. The detector tests feed the detector directly; the handle tests pass a fake `ShakePort`. You feed it samples through `feed(...)`, and while it has been started with `start()` each sample advances an internal state machine; when the pattern matches, a callback fires. When stopped with `stop()`, samples are ignored entirely until `start()` is called again. `SensorManagerShakeSource.kt` is the only file that imports `android.*`; the rest of the module is plain Kotlin, so every `Sample` can come from anywhere - which is what lets the algorithm be exercised off synthetic data.

## How it works

The detector does two things per sample:

1. Runs a one-pole high-pass filter over the acceleration **magnitude** (the vector length of `x, y, z`, in m/s^2, divided by gravity so it reads in g). The filtered value is `hp = mag - lp`, where the low-pass term `lp` drifts toward `mag`.
2. Watches that filtered value for positive-going threshold crossings - a moment where it rises above the crossing threshold after having been below it - and remembers when each one happened, within a sliding time window.

If two samples arrive far apart, the state that would have been carried forward is reset; a sample whose timestamp does not increase is ignored outright (see Timestamps) rather than resetting anything. A sample whose magnitude is not finite (NaN, +Inf, -Inf) is dropped from the filter and the crossing list only: it leaves the low-pass term and the crossings unchanged, but its timestamp still advances and is measured by the gap rule, which can still reset the detection state; any finite magnitude is capped at `MAX_SAMPLE_G` (3.3 g) so one extreme-but-finite reading cannot drive the low-pass term far enough to blind the filter.

## The constants

| Name | Value | Role |
| --- | --- | --- |
| `THRESHOLD_G` | `0.65` | Magnitude, in g, above which a positive-going step counts as a crossing. |
| `MAX_SAMPLE_G` | `3.3` | Cap on a single finite sample magnitude (`2 * (THRESHOLD_G + the 1.0 g crossing point)`); one extreme-but-finite reading is held to this so it cannot drive the low-pass term far enough to blind the detector. Non-finite magnitudes are dropped before the cap is applied. |
| `CROSSING_COUNT` | `3` | Crossings needed within the window to fire. |
| `WINDOW_MS` | `500` | Window span (inclusive: an exact 500 ms span still counts). Crossings older than this are pruned before a decision. |
| `COOLDOWN_MS` | `2000` | Refractory after a fire; one shake fires once. |
| `CUTOFF_HZ` | `2.0` | Sets the high-pass time constant: `T = 1 / (2*pi*2.0) approx 79.6 ms`. Combined with each sample's timestamp, it yields `alpha(dt) = dt / (T + dt)`, so the filter follows the data rate rather than a fixed clock. |
| `LARGE_GAP_MS` | `1000` | A gap larger than this between samples resets the crossing list, the "above threshold" flag, and the cooldown timestamp; the low-pass term is kept and re-seeds on the next sample. The out-of-gap sample itself triggers no detection. |

All of these are named constants on `ShakeDetector` with KDoc stating their value and role; they exist as a seam so behaviour can be inspected and changed without hunting through arithmetic.

## Start / stop

`start()` enables listening; `stop()` disables it. While stopped, every sample is discarded - no state changes accumulate. Both `start()` and `stop()` reset the detector's filter state (the low-pass term, the previous-magnitude bookkeeping, the last timestamp, the cooldown, and the crossing list), so a `start()` after a `stop()` begins fresh rather than resuming from where the detector left off. This keeps the sensor quiet by default and on battery.

The handle's start/stop run on the main thread; its listener registers with no Handler, so samples and the onShake callback are delivered on the main looper - the handle is not thread-safe beyond that.

## Timestamps

The detector has no clock of its own. It never reads the wall time, calls `System.currentTimeMillis`, sleeps, or samples randomness; the only source of time is each `Sample.timestampMs`. A sample whose timestamp does not increase past the last-accepted one is ignored outright - it contributes nothing and measures no gap from it.

## Verified / not verified

The detection algorithm itself is verified on synthetic samples fed through `feed(...)`. What this delivery does **not** verify:

- Device tuning for reliable wake with no false wakes from ordinary walking - the target threshold/latency is a device choice, set on hardware, not here.
- The `SensorManager`-backed port (the `SensorManagerShakeSource` / handle implementation), which simply registers the accelerometer listener and forwards samples to the detector; it requires instrumented tests that are not part of this module's unit tests.

## Build and test

- Unit tests run on the JVM: `./gradlew :android:modules:gesture:testDebugUnitTest`.
- Contract suite: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_gesture_contract.py`.

Full module card: `AGENTS.md` in this folder.
