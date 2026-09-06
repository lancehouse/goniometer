# Goniometer App — Technical Spec (handoff to Claude Code)

Eyes-free, hand-held Android goniometer for physiotherapy use. Therapist watches
the patient, not the screen, throughout the measurement. Everything needed to
operate the app during a rep is doable by feel (physical buttons + haptics);
the screen is only read *after* pressing Stop.

This document is the source of truth for behavior. The `app/` folder next to
this file is a starter Android Studio (Kotlin + Jetpack Compose) project with
the core logic stubbed/implemented — it has **not** been compiled or run**
(no Android SDK in the environment that generated it), so expect to fix build
errors, tune constants, and iterate on-device.

## 1. Sensor approach

- Use `Sensor.TYPE_GAME_ROTATION_VECTOR` (accelerometer + gyroscope fusion,
  no magnetometer). Chosen over `TYPE_ROTATION_VECTOR` because clinics are
  full of metal (chairs, plinths, equipment) that corrupts magnetometer
  readings. Game rotation vector has no absolute heading reference and will
  drift slowly over time, but sessions here are short (well under a minute
  typically), so drift is negligible.
- Sample at `SensorManager.SENSOR_DELAY_GAME` (~50 Hz) — smooth enough for a
  line graph and fast enough not to miss end-range peaks.
- Each sample from the sensor is a quaternion `(x, y, z, w)` via
  `SensorManager.getQuaternionFromVector()`.

## 2. Calibration / zero reference

- The phone is **hand-held**, not strapped — its orientation when the
  therapist presses Start is arbitrary and different every session.
- On **Start**, capture the current quaternion as `baseline`.
- For every subsequent sample `q`, compute the relative rotation:
  `qRel = baseline.inverse() * q`
- Decompose `qRel` into three Euler angles (roll, pitch, yaw) using a fixed
  intrinsic order (XYZ). Because this decomposition happens *relative to
  baseline*, it self-corrects for however awkwardly the phone was being held
  at the moment Start was pressed — there is no dependency on world axes or a
  fixed mounting orientation.
- **Known limitation:** Euler decomposition can exhibit gimbal-lock artifacts
  if the motion swings the middle axis of the chosen order close to ±90°, and
  a compound movement will show some cross-talk into the non-dominant
  channels. This is acceptable — the app only needs to reliably identify the
  *largest* channel, and minor bleed into the smaller two is expected/normal
  rather than a defect.
- **Robust fallback metric:** also compute the scalar total angular
  displacement directly from the quaternion, immune to gimbal lock:
  `totalAngleDeg = 2 * acos(clamp(|qRel.w|, -1, 1)) * (180 / PI)`
  Not shown prominently in v1, but compute and store it per-sample so it's
  available if the three-channel breakdown ever looks wrong on-device.

## 3. Session state machine

States: `IDLE → RECORDING → STOPPED`

- **Start** (from IDLE): capture baseline quaternion, clear sample buffer,
  clear marks list, transition to RECORDING, start the sensor sample timer,
  fire the Start haptic pattern.
- **Mark** (only while RECORDING): append `Mark(timestampMs, sampleIndex)` to
  the marks list. **Unbounded** — zero, one, or many marks per session, no
  fixed count. Fire the Mark haptic pattern. No-op if not RECORDING.
- **Stop** (from RECORDING): freeze the sample buffer, transition to
  STOPPED, compute results (§4), fire the Stop haptic pattern.
- **Clear** (from STOPPED or RECORDING): discard everything, return to IDLE.
  On-screen button only (see §5 — cannot be bound to the power key).
- Same physical action toggles Start/Stop: IDLE→RECORDING on press,
  RECORDING→STOPPED on press again.

## 4. Result computation (on Stop)

For each of the three channels (roll, pitch, yaw), compute
`range = max(angle over all samples) − min(angle over all samples)`.

- The channel with the largest `range` is the **primary result** — shown
  large and prominent.
- The other two channels are shown smaller, alongside the primary figure.
- Channels are **not** labeled anatomically (not "flexion," not "sagittal
  plane") since the phone's hold angle is different every time and there is
  no fixed mapping to a real-world plane. Use neutral labels (e.g. "Range A /
  B / C" or similar — open to bikeshedding on-device).
- Marks are not used to segment the data or pick the plane — they're pure
  annotations on the continuous recording, rendered as points/ticks on the
  graph and available for the therapist's reference (e.g., "that's where I
  hit end-range").

## 5. Input mapping

| Action | Physical | On-screen |
|---|---|---|
| Start / Stop (toggle) | Volume Up | Large button |
| Mark | Volume Down | Large button |
| Clear / reset | — (not interceptable) | Button only |

- Volume keys are intercepted in the Activity (`dispatchKeyEvent` /
  `onKeyDown`, consuming `KEYCODE_VOLUME_UP` / `KEYCODE_VOLUME_DOWN` so the
  system volume UI never appears) and only while the app is foregrounded
  with the screen on (`FLAG_KEEP_SCREEN_ON` — the therapist doesn't need to
  look at it, but the app does need to stay alive and un-locked).
- The power button is OS-reserved (lock/sleep, emergency SOS on long-press
  on most devices) — apps cannot intercept it. Confirmed dead end, not
  attempted in this build.
- On-screen buttons are large touch targets (the whole lower portion of the
  screen, effectively) so they're findable without precise aim.

## 6. Haptic feedback

Distinct, learnable-by-feel `VibrationEffect` patterns via `Vibrator` /
`VibratorManager`:

- **Start:** single short buzz (~80ms)
- **Mark:** double short buzz (two ~40ms pulses, ~60ms gap)
- **Stop:** single long buzz (~200ms)
- **Clear:** three short buzzes

Exact timings are a starting point — tune on real hardware, ears/thumb are
the test instrument here, not a screenshot.

## 7. UI layout (screen only matters after Stop, or for setup)

- **Idle/recording screen:** essentially just the two large buttons
  (Start/Stop, Mark) — big, high-contrast, thumb-reachable one-handed. Recording
  state should be visible at a glance (color/pulse) for the rare moment
  someone does glance down, but nothing depends on it being seen.
- **Results screen (after Stop):**
  - One large numeral + unit (°) for the primary (largest-range) channel.
  - Two smaller numerals for the other two channels, positioned alongside.
  - A line graph of all three angle traces over the session duration, x-axis
    = time, y-axis = degrees relative to baseline. Mark timestamps rendered
    as vertical ticks or dots on the graph. The dominant channel's trace
    should be visually emphasized (heavier line/color) over the other two.
  - Clear button to discard and return to idle.

## 8. Data model (see `SessionState.kt`)

```
data class Sample(val tMs: Long, val roll: Float, val pitch: Float, val yaw: Float, val totalAngleDeg: Float)
data class Mark(val tMs: Long, val sampleIndex: Int)
data class SessionResult(val samples: List<Sample>, val marks: List<Mark>,
                          val ranges: FloatArray /* [roll, pitch, yaw] */,
                          val primaryChannelIndex: Int)
```

## 9. Open items for the laptop session

- Tune haptic timings/patterns on real hardware.
- Decide final neutral channel labels (Range A/B/C vs. something else).
- Decide whether to persist/export sessions (CSV? Not scoped yet — ask
  before building, wasn't part of the original ask).
- Build, run, and fix whatever doesn't compile — this scaffold was written
  without an Android SDK available to verify it.
