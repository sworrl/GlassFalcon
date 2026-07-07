# GO4 connect-from-LOCKED-FC capture — 2026-07-06 (wm240 Mavic 2 Pro + RC240)

Captured live via the `acc_write` kprobe on the Pixel 8 Pro (Magisk root,
`/sys/kernel/tracing`, `p:acc_probe acc_write count=%x2 b0..b3=+0/8/16/24(%x1):x64`).
Kprobe sees **outgoing** frames only (phone → RC → aircraft). 3,204 DUML frames,
`raw_traces/unlock_from_boot.txt`, from DJI GO 4 launch through ~70 s of connect.

**Scenario that makes this capture new:** the FC was actually in the 30 m "kid
mode" state at connect time (unlike the 2026-07-04 maxalt capture, which started
from an already-unlocked FC). So this is GO 4's real from-locked unlock sequence.

## HEADLINE — the "unlock" is the mobile-GPS stream, nothing else

GO 4 sent **zero `0x03/0xf9` config writes**. It did not touch beginner mode,
max height, or max radius. Its entire "get out of kid mode" behavior was:

1. **t+2.90s** `0x03/0xf7` `pl=7b1b9bde` — read-only param probe of
   `novice_func_enabled` (beginner mode), hash `0xde9b1b7b`.
2. **t+2.94s** `0x03/0xf7` `pl=940a5c42` — read-only probe of `max_radius`,
   hash `0x425c0a94`.
3. **t+4.68s onward** `0x03/0x20` **Send-GPS-to-Flyc**, every ~5 s (14 frames):
   the phone's own GPS position streamed to the FC. **This is what lifts the
   30 m ceiling.**

Confirms `glassfalcon-30m-cap-send-gps-to-flyc` against a genuinely locked FC:
the cap is not the beginner flag, it's the FC clamping until it receives a
mobile-position frame. GO 4 relies on exactly this and issues no disable write.

## `0x03/0x20` payload format (13 bytes, all little-endian) — CONFIRMED

    03 | lat×1e6 (int32) | lon×1e6 (int32) | unix_epoch_seconds (int32)

Sample: `03 4cfe1602 4935a2f9 14db4b6a`
  = flag 0x03, lat 35.192908, lon -106.357431, epoch 1783356180 (2026-07-06 16:43 UTC).
Earlier mis-read of the trailing 4 bytes as "seq + const db4b6a" was wrong — it's
the epoch int32; only its low byte changes over a minute, so the top 3 look fixed.

## GlassFalcon parity — byte-for-byte match, no change needed

`DumlCommands.sendGpsToFlyc(lat, lon, unixSec)` builds:
`0x03 + int32(lat*1e6) + int32(lon*1e6) + int32(unixSec)` → identical layout to GO 4.
FlightViewModel streams it every 5 s (added after the 2026-07-05 capture). Field-
confirmed this session: after GO 4 unlocked the FC, the P10 running GlassFalcon
was swapped in and was already out of kid mode, and it maintains the unlock with
its own 0x20 stream.

## Files
- `raw_traces/unlock_from_boot.txt` — full 3,204-frame capture
- `parse_bm.py` + `parse_kprobe.py` — patched to accept hex `count=0x..` (this
  session's kprobe emitted count in hex; the 07-04 probe emitted decimal).
  Run: `python3 parse_bm.py raw_traces/unlock_from_boot.txt`
