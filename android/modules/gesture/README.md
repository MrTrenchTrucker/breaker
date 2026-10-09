# Gesture - README

Shake-to-wake: detect a shake gesture from accelerometer samples and fire a callback. The detector turns a stream of raw `Sample` values into the single event "a shake happened", and begins a take via the app's wiring - that wiring lives outside this module.

## What it does

The module's public door is the `ShakeHandle` (`start()`/`stop()`), built on the `ShakeDetector`; the pure, JVM-testable core is still `ShakeDetector`. You feed it samples through `feed(...)`, and while it has been started with `start()` each sample advances an internal state machine; when the pattern matches, a callback fires. When stopped with `stop()`, samples are ignored entirely until `start()` is called again. There is no Android dependency in this file - every `Sample` can come from anywhere, which is what lets the algorithm be exercised off synthetic data.

## How it works

The detector does two things per sample:

1. Runs a one-pole high-pass filter over the acceleration **magnitude** (the vector length of `x, y, z`, in m/s^2, divided by gravity so it reads in g). The filtered value is `hp = mag - lp`, where the low-pass term `lp` drifts toward `mag`.
2. Watches that filtered value for positive-going threshold crossings - a moment where it rises above the crossing threshold after having been below it - and remembers when each one happened, within a sliding time window.

If two samples arrive far apart, the state that would have been carried forward is reset; a sample whose timestamp does not increase is ignored outright (see Timestamps) rather than resetting anything.

## The constants

| Name | Value | Role |
| --- | --- | --- |
| `THRESHOLD_G` | `2.0` | Magnitude, in g, above which a positive-going step counts as a crossing. |
| `CROSSING_COUNT` | `3` | Crossings needed within the window to fire. |
| `WINDOW_MS` | `500` | Window span (inclusive: an exact 500 ms span still counts). Crossings older than this are pruned before a decision. |
| `COOLDOWN_MS` | `2000` | Refractory after a fire; one shake fires once. |
| `CUTOFF_HZ` | `2.0` | Sets the high-pass time constant: `T = 1 / (2*pi*2.0) approx 79.6 ms`. Combined with each sample's timestamp, it yields `alpha(dt) = dt / (T + dt)`, so the filter follows the data rate rather than a fixed clock. |
| `LARGE_GAP_MS` | `1000` | A gap larger than this between samples resets the crossing list, the "above threshold" flag, and the cooldown timestamp; the low-pass term is kept and re-seeds on the next sample. The out-of-gap sample itself triggers no detection. |

All of these are named constants on `ShakeDetector` with KDoc stating their value and role; they exist as a seam so behaviour can be inspected and changed without hunting through arithmetic.

## Start / stop

`start()` enables listening; `stop()` disables it. While stopped, every sample is discarded - no state changes accumulate. Calling `start()` again resumes from whatever the current state is (a gap on the first fed sample then falls out under the gap rule). This keeps the sensor quiet by default and on battery.

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
