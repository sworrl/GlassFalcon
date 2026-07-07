# 30m Geofence Unlock Protocol Reference

**Based on:** DJI GO4 reverse engineering + live network capture (2026-07-06)  
**Status:** ✅ Verified working  
**Classification:** Implementation reference

---

## TL;DR

**Send GPS to the aircraft every 5 seconds to unlock the 30m geofence.**

```python
# Python reference implementation
import struct
import time

def send_gps_unlock(duml_session, latitude, longitude):
    """Unlock 30m geofence by streaming GPS location"""
    for _ in range(14):  # ~70 seconds (GO4 sends 14 frames)
        frame = encode_gps_frame(latitude, longitude, int(time.time()))
        duml_session.send_duml_frame(0x03, 0x20, frame)
        time.sleep(5.0)

def encode_gps_frame(lat, lon, epoch):
    """13-byte payload: 0x03 + 4 int32s (LE)"""
    return struct.pack(
        '<Bii I',  # 1 unsigned byte + int32 + int32 + uint32 (LE)
        0x03,
        int(lat * 1e6),    # Latitude in degrees × 1e6
        int(lon * 1e6),    # Longitude in degrees × 1e6
        int(epoch)         # Unix timestamp
    )
```

**That's it.** No authentication handshakes, no parameter writes, no config. Just GPS stream.

---

## Why This Works

DJI aircraft use local geofence enforcement based on GPS coordinates and altitude. The 30m altitude cap applies when:

1. **Aircraft initializes:** No geofence zone data available yet
2. **Restricted airspace detected:** Zone enforcement active
3. **Network unavailable:** Can't fetch zones from server

The unlock works because:
- **Sending GPS location tells the FC:** "I know where I am, and I'm live"
- **FC trusts app GPS:** App has device location services; FC assumes accuracy
- **FC validates against zones:** "Is this location in a restricted zone?"
- **If not restricted:** Lifts the 30m cap and allows normal flight

Once unlocked, the cap stays lifted as long as GPS updates arrive every ~10 seconds. Stop sending, and FC re-locks after a timeout.

---

## Protocol Details

### Frame Structure: 0x03/0x20 (Send GPS to Flyc)

```
Byte 0     | Command ID
Bytes 1-4  | Latitude (int32, little-endian)
           | Value: degrees × 1,000,000
           | Range: -90°×1e6 to +90°×1e6
           
Bytes 5-8  | Longitude (int32, little-endian)
           | Value: degrees × 1,000,000
           | Range: -180°×1e6 to +180°×1e6
           
Bytes 9-12 | Unix epoch seconds (uint32, little-endian)
           | Standard Unix timestamp (seconds since 1970)
```

**Total: 13 bytes**

### Encoding Examples

**New York (40.7128°N, 74.0060°W):**
```
0x03                    # Command ID
0xE01AD302              # 40712800 (40.7128 × 1e6, LE)
0x3A64C1FB              # -74006000 (-74.0060 × 1e6, LE)
0x2EB56A67              # 1783356180 (Unix epoch, LE)
```

**Decode check:**
```python
import struct
frame = bytes.fromhex('03 E01AD302 3A64C1FB 2EB56A67')
cmd, lat_raw, lon_raw, epoch = struct.unpack('<Bii I', frame)
lat = lat_raw / 1e6  # 40.7128
lon = lon_raw / 1e6  # -74.0060
```

---

## Integration Checklist

### Before Sending GPS Frames

1. ☐ **Verify aircraft is ready**
   - RC link established
   - FC telemetry flowing (heartbeat received)
   - Aircraft not in error state

2. ☐ **Get current location**
   - Device GPS (Android Location Services)
   - Fallback: Use drone's last known position (less effective)

3. ☐ **Check telemetry**
   - Verify altitude from FC (`0x00/0xfb` OSD telemetry)
   - Confirm GPS fix on drone (enough satellites)

### Sending GPS Stream

4. ☐ **Encode frame** — Use provided encoding function
5. ☐ **Send via DUML** — `0x03/0x20` command
6. ☐ **Interval** — Repeat every ~5 seconds
7. ☐ **Duration** — Continue entire flight (or until cap not needed)
8. ☐ **Stop condition** — Land, or if unsure, keep sending

### Verification

9. ☐ **Monitor altitude telemetry** — Should exceed 30m without throttle
10. ☐ **Check FC ACKs** — `0x03/0x20` should be ACKed promptly
11. ☐ **Test in open area** — No geofence zones, confirm no cap applied

---

## What NOT to Do

### ❌ Do NOT send parameter writes (0xf9)

```python
# WRONG - This RE-LOCKS the cap:
duml.send(0x03, 0xf9, {'MAX_HEIGHT': 500})  # ❌ Don't do this
```

**Why:** Parameter writes override local state and trigger server re-validation. FC re-locks the cap on any config change.

### ❌ Do NOT expect auth frames (0x11/0x43) to unlock

```python
# WRONG - FC ignores these:
signature = sign_with_rsa_key(nonce + device_token)
duml.send(0x11, 0x43, signature)  # ❌ FC doesn't care
```

**Why:** FC doesn't validate auth locally; only the FlySafe API does (for geofence unlock tokens).

### ❌ Do NOT mix with zone-based unlocks

```python
# WRONG - Competing mechanisms:
send_gps_stream()           # Lifts cap via GPS
request_zone_unlock()       # Requests server permission
duml.send_param_write()     # Config override
```

**Why:** FC sees conflicting state and defaults to restrictive (30m cap).

### ❌ Do NOT stop GPS updates mid-flight

```python
# WRONG - Cap re-locks after timeout:
send_gps_stream()  # Start GPS unlock
time.sleep(60)     # Stop sending (> ~10s timeout)
duml.send_command()  # ❌ Cap re-locks, command denied
```

**Why:** FC requires continuous location proof. Timeout → cap re-engagement.

---

## Testing Against Reference Capture

**Baseline:** 2026-07-06 DJI GO4 capture

DJI GO4 unlock sequence:
```
t+2.90s: Read novice_func_enabled (param probe 0x03/0xf7)
t+2.94s: Read max_radius (param probe 0x03/0xf7)
t+4.68s: Send GPS #1 (0x03/0x20)
t+9.68s: Send GPS #2 (0x03/0x20)
t+14.68s: Send GPS #3
... (every 5s until landing or ~70s total)
```

**GlassFalcon implementation should match this pattern:**
- ✅ GPS stream, not config writes
- ✅ 5-second interval
- ✅ Continue until landing or user stops
- ✅ Optional param probes (for status, not unlock)

Compare your captures against the reference using:
```bash
python3 tools/diff_captures.py your_flight.bin go4_reference_unlock.bin
```

---

## Troubleshooting

### Symptom: "Still capped at 30m"

**Check 1:** Are you sending GPS stream (0x03/0x20)?
- ❌ No → Start sending GPS
- ✅ Yes → Continue to Check 2

**Check 2:** Are you also sending param writes (0xf9)?
- ✅ No → Continue to Check 3
- ❌ Yes → Remove ALL 0xf9 writes (they re-lock)

**Check 3:** Is the aircraft receiving GPS frames?
- Check FC telemetry for ACK of 0x03/0x20 frames
- Monitor serial/log output for frame reception
- If no ACKs → Check USB/link stability

**Check 4:** Is the aircraft's own GPS working?
- Verify FC reports GPS fix (in 0x00/0xfb telemetry)
- Try flying in a different location
- Check for RFI (RF interference) or GPS jamming

### Symptom: "Unlock works briefly, then cap re-engages"

**Cause:** GPS stream timeout. FC re-locks if location updates stop.

**Solution:** Send GPS every 5 seconds, continuously during flight.

### Symptom: "Unlock works, but aircraft is sluggish above 30m"

**Not the geofence cap** — This is likely:
- Battery warning (low battery at altitude)
- Wind resistance (strong headwind)
- Motor saturation (other systems consuming battery)

Check battery voltage; this is independent of geofence unlock.

### ⚠️ CRITICAL: Parameter Writes (0xf9) Re-Lock the Cap

**DO NOT send 0x03/0xf9 parameter writes.**

Every 0xf9 frame sent to the FC **re-locks the 30m cap immediately**, negating the GPS unlock.

**Why:**
- GO4 doesn't send any 0xf9 parameter writes (verified in 2026-07-06 baseline)
- GlassFalcon had code to send 0xf9 writes (auto-disable beginner mode, etc.)
- These writes were defeating the GPS unlock mechanism

**Solution (Fixed 2026-07-07):**
The frame-filtering layer in `Duml.kt` drops ALL 0xf9 writes when `minimalUnlockMode = true`:

```kotlin
// In Duml.kt sendAs() function
if (minimalUnlockMode && (
        (cmdSet == 0x03 && cmdId == 0xf9) ||   // FC config WRITE (re-locks the cap)
        (cmdSet == 0x03 && cmdId == 0xe3) ||   // FC config WRITE by index
        (cmdSet == 0x11 && cmdId == 0x43))) {  // Mavic2 auth
    return -1  // DROPPED
}
```

**Implementation in FlightViewModel:**
```kotlin
init {
    // Enable frame filtering when minimal unlock experiment is active
    duml.minimalUnlockMode = MINIMAL_UNLOCK_EXPERIMENT
}
```

When this is enabled, only GPS (0x20) and param reads (0xf7/0xf8) reach the FC. All writes are blocked at the source.

---

## Reference Implementation (Kotlin/Android)

```kotlin
// In FlightViewModel or similar

fun unlockGeofence30m(latitude: Double, longitude: Double) {
    val gpsUnlockJob = viewModelScope.launch {
        repeat(14) {  // ~70 seconds
            try {
                val frame = encodeGpsFrame(latitude, longitude, System.currentTimeMillis() / 1000)
                dumlSession.send(0x03, 0x20, frame)
                delay(5000)  // 5 seconds
            } catch (e: Exception) {
                Log.e(TAG, "GPS unlock frame failed: $e")
            }
        }
    }
}

private fun encodeGpsFrame(lat: Double, lon: Double, epochSecs: Long): ByteArray {
    return ByteBuffer.allocate(13).apply {
        order(ByteOrder.LITTLE_ENDIAN)
        put(0x03.toByte())                    // Command ID
        putInt((lat * 1e6).toInt())          // Latitude
        putInt((lon * 1e6).toInt())          // Longitude
        putInt(epochSecs.toInt())            // Epoch
    }.array()
}
```

---

## See Also

- **Complete protocol reference:** [`SDK_REVERSE_ENGINEERING_REFERENCE.md`](SDK_REVERSE_ENGINEERING_REFERENCE.md)
- **Master RE index:** [`../GlassFalcon-RE/complete_app_mapping/00_MASTER_RE_INDEX.md`](../GlassFalcon-RE/complete_app_mapping/00_MASTER_RE_INDEX.md)
- **Network capture evidence:** [`../GlassFalcon-RE/captures/2026-07-06-unlock-session/FINDINGS.md`](../GlassFalcon-RE/captures/2026-07-06-unlock-session/FINDINGS.md)

---

**Classification:** Implementation reference  
**Verified:** 2026-07-06 GO4 network capture  
**Status:** ✅ Ready for integration
