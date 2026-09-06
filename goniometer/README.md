# Goniometer — handoff package

Start with **SPEC.md** — it's the source of truth for what this app does and
why, including the design decisions we talked through.

`app/` is a starter Android Studio project (Kotlin + Jetpack Compose):

- `SensorFusion.kt` — quaternion math, baseline-relative Euler decomposition
- `SessionState.kt` — the start/mark/stop state machine and result computation
- `Haptics.kt` — vibration patterns for eyes-free feedback
- `MainActivity.kt` — sensor registration + volume-key interception, wires
  everything together
- `ui/GoniometerScreen.kt`, `ui/RomChart.kt` — the two screens (big-button
  capture screen, results screen with the line graph)

## Opening this on the laptop

1. Open the `goniometer/` folder in Android Studio — it'll offer to
   generate the Gradle wrapper (`gradlew`) on first sync, which is normal;
   this package doesn't ship one since it wasn't built with the Android SDK.
2. **This code has not been compiled or run.** It was written without an
   Android SDK available to verify it, so expect some build-fixing before
   the first successful run — treat it as a strong starting skeleton, not
   finished code.
3. Everything at stake in early testing: does the volume-key interception
   actually suppress the system volume UI on your device/OS version, do the
   haptic patterns feel distinct by feel, and does the baseline-relative
   angle math hold up when the phone is held at a genuinely awkward angle.
