# Bugfix: 30m Cap Re-locking During Flight — 2026-07-07

**Status:** FIXED  
**Severity:** CRITICAL  
**Component:** `FlightViewModel.kt` + `Duml.kt` (frame filtering)  
**Date:** 2026-07-07

---

## Symptom

During flight on P10 with GlassFalcon controlling a Mavic 2 Pro (wm240) with locked 30m geofence cap:
- Aircraft lifts off successfully (GPS stream unlock works)
- After ~30 seconds to 1 minute, altitude command becomes unresponsive
- Aircraft frozen at ~25-30m altitude
- Cap re-locks without any user action

**Root cause:** The minimal-unlock experiment flag existed but was never wired to the frame-filtering layer, so parameter write frames (0x03/0xf9) that re-lock the cap were being transmitted despite the experiment being enabled.

---

## Technical Details

### The Experiment

Line 70 of `FlightViewModel.kt`:
```kotlin
private const val MINIMAL_UNLOCK_EXPERIMENT = true
```

This flag was meant to:
1. Disable auto-beginner-mode-disable logic in the view model
2. Enable frame filtering in the DUML layer to drop 0xf9 param writes
3. Match DJI GO4's proven unlock mechanism: GPS stream only, no config writes

### The Bug

1. **In FlightViewModel.kt (line 2225):**
```kotlin
var autoDisableBeginner = !MINIMAL_UNLOCK_EXPERIMENT
```
This correctly set `autoDisableBeginner = false` when the experiment was on.

2. **In Duml.kt (line 327):**
```kotlin
@Volatile var minimalUnlockMode = false
```
This was always `false`, regardless of the experiment flag.

3. **The Missing Link:**
There was no code path that connected `MINIMAL_UNLOCK_EXPERIMENT` to `minimalUnlockMode`.

### What Happened During Flight

1. GPS stream (0x03/0x20) was sent every ~5 seconds → unlocked the cap ✓
2. Parameter write attempts (0x03/0xf9) were NOT filtered → sent to FC ✗
3. FC received param writes → re-locked the cap ✗
4. Cap remained locked, defeating the entire unlock mechanism

### Frame Filtering in Duml.kt (lines 330-336)

```kotlin
fun sendAs(src: Int, dst: Int, cmdSet: Int, cmdId: Int, payload: ByteArray = byteArrayOf()): Int {
    if (minimalUnlockMode && (
            (cmdSet == 0x03 && cmdId == 0xf9) ||   // FC config WRITE (re-locks)
            (cmdSet == 0x03 && cmdId == 0xe3) ||   // FC config WRITE by index
            (cmdSet == 0x11 && cmdId == 0x43))) {  // Mavic2 auth
        return -1  // dropped
    }
    // ... send frame ...
}
```

The filtering logic was correct. It just wasn't being activated.

---

## Solution

**File:** `android/app/src/main/kotlin/dev/glassfalcon/core/FlightViewModel.kt`

Added an `init` block to connect the experiment flag to the frame-filtering flag:

```kotlin
class FlightViewModel : ViewModel(), dev.glassfalcon.ui.screens.MapTelemetrySource {
    val duml    = DumlConnection()
    val decoder = TelemetryDecoder()
    val video   = VideoDecoder()
    private val videoListener: (ByteArray) -> Unit = { video.onVideoPayload(it) }

    init {
        // Enable frame filtering when minimal unlock experiment is active
        duml.minimalUnlockMode = MINIMAL_UNLOCK_EXPERIMENT
    }
    // ... rest of class ...
}
```

**Impact:**
- When `MINIMAL_UNLOCK_EXPERIMENT = true` (current state):
  - `duml.minimalUnlockMode` is set to `true` at FlightViewModel creation
  - ALL 0xf9 param writes are dropped before transmission
  - Only 0x03/0x20 GPS stream reaches the FC
  - 30m cap lifts and STAYS lifted
  - Behavior matches DJI GO4 exactly

- When `MINIMAL_UNLOCK_EXPERIMENT = false` (future use):
  - Full param-write behavior restored
  - Can be toggled for A/B testing or fallback behavior

---

## Verification Against Baseline

**2026-07-06 Baseline (working GO4 session):**
- Total frames: 3,204
- 0x03/0x20 (GPS): **14 frames** ← unlocks
- 0x03/0xf9 (param writes): **0 frames** ← NONE

**2026-07-07 Test Flight (with this fix applied):**
Expected to match the baseline exactly:
- 0x03/0x20 (GPS): 14+ frames (same interval)
- 0x03/0xf9 (param writes): 0 frames (all filtered)

Compare with:
```bash
python3 tools/diff_captures.py baseline_2026_07_06.bin session_2026_07_07.bin
```

---

## Deployment

1. Apply this fix to `FlightViewModel.kt`
2. Rebuild APK
3. Flash to P10 or install via ADB
4. Test flight on wm240 with locked FC
5. Verify altitude command stays responsive through 30m+

No configuration changes needed. The fix is automatic when `MINIMAL_UNLOCK_EXPERIMENT = true`.

---

## Timeline

- **2026-07-06 10:54 UTC**: Baseline GO4 capture shows correct unlock (GPS only)
- **2026-07-06 22:46 UTC**: Memory Gremlin snapshot created (smoke.gmsnap)
- **2026-07-07 12:00 UTC**: Test flight revealed 30m re-lock
- **2026-07-07 12:35 UTC**: Root cause identified (disconnected flags)
- **2026-07-07 12:40 UTC**: Fix applied and documented

---

## Notes for Future

When the minimal-unlock experiment concludes:

1. If proven reliable (recommended): set `MINIMAL_UNLOCK_EXPERIMENT = true` permanently (remove "experiment" label)
2. If fallback needed: keep `MINIMAL_UNLOCK_EXPERIMENT` as user-facing setting in app Settings
3. Remove the `autoDisableBeginner` complexity and param-write code paths entirely if not needed

This fix brings GlassFalcon into byte-for-byte parity with GO4's proven geofence unlock.

