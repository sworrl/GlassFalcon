# 2026-07-06 unlock session — wide (256 B/frame) acc_write capture

First capture with the **tx-max** profile (256 B/frame on `acc_write`), which defeats the old
32-byte truncation the README flagged as the blocker: *"our acc_write kprobe only captures 32
bytes per frame so it cannot even recover the full payloads."* **The full activation-crypto frames
are now recovered whole.**

- Aircraft: WM240 (Mavic 2), RC240 over USB-AOA → Pixel 8 Pro, DJI GO 4 `dji.go.v4`.
- Probe: `gf_probe` on `acc_write`, 32 words (256 B/frame), ftrace buffer bumped to 8 MB/CPU.
- Raw trace: `txmax-wide-acc_write.trace.txt` (4829 TX frames). Re-parse with `parse_0x11_crypto.py`.
- Buffer also contains a deliberate **beginner-mode ON→OFF toggle** (see `parse_beginner_toggle.py`).

## Activation crypto — `cmd_set 0x11` (phone → FC, src 02 → dst 03)

### `0x11/0x43` — 84 B, repeated (114 samples). Field structure now fully resolved:

| offset | bytes | value | nature |
|--------|-------|-------|--------|
| [0:4]   | 4  | `50000000` | **constant** — inner length 0x50 = 80, LE |
| [4:36]  | 32 | high-entropy, differs every frame | **VARYING** — block A (nonce / challenge-derived) |
| [36:52] | 16 | `d3006306bd44fe08200bfd10025716a5` | **constant across all 114 frames** — session anchor (key handle / app-device id) |
| [52:84] | 32 | high-entropy, differs every frame | **VARYING** — block B (response / MAC / ciphertext) |

Column-wise variance over 114 frames: exactly offsets 4–35 and 52–83 vary (64 of 84 bytes); the
4-byte length header and the 16-byte middle anchor are fixed. Two independent 32-byte blocks
bracketing a fixed 16-byte token is consistent with (nonce‖token‖MAC) or (challenge‖token‖response),
32-byte fields pointing at SHA-256 / 256-bit material.

### `0x11/0x15` — 64 B, sent once (t≈4.7 s), constant in session:
```
6fe71e0e4e93ca434ab8b9c780107fb75ae4bb529bae2519af3f10db47015933
8bcf9738eded28e1d2ca61b30ae603feb384f4d3162d33c0be86be2c26d64a8f
```
Two 32-byte blocks. Sent once at connect → session-key establishment / enrollment, vs. `0x43`'s
repeated per-frame auth.

### `0x03/0xcd` — 88 B (dst 0xb1). Connect-time, **already tested & ruled out** as the cap gate.
Header `010000f301000050000000…` then entropy. Kept for completeness; do not re-chase.

## Beginner-mode toggle (confirms the known command, un-truncated)
```
t= 0.000  02>03 0x03/0xf7 7b1b9bde        read beginner-mode param def (hash 0xde9b1b7b)
t=16.747  02>03 0x03/0xf9 7b1b9bde 01     SET beginner mode ON
t=36.939  02>03 0x03/0xf9 7b1b9bde 00     SET beginner mode OFF
```

## What this does NOT do
This is captured **structure**, not broken crypto. Block A/B are computed by DJI's SDK from the
FC's nonce + DJI's keys; reproducing them is still an RE effort against those keys. Two gaps remain:
1. **RX side not captured** — this run armed `acc_write` (TX) only. The FC's *challenge* travels on
   `acc_read` (RX). Arm `rx-wide` to get the other half of the handshake (challenge→response pairs).
2. **Cross-session** — the 16-byte anchor `d300…16a5` is fixed *within* this session. Capture a
   second fresh session to learn whether it is per-session (nonce-like) or per-device (an id).

## UPDATE — power-cycle characterization (2026-07-06, both gaps closed)

Pooled 167 `0x11/0x43` samples across **10 connection events** (2 app-restarts, 3 full power-cycles,
5 FC-only reboots, plus cable pulls — see `powercycles/`). Per-byte cardinality, time-sorted:

- `[0:4]` `50000000` — constant length (0x50 = 80 inner bytes).
- `[4:36]` 32 B — ~118–131 distinct/167 per byte position, no fixed bytes, no monotonic/counter
  window anywhere → **random per-frame nonce** (app-generated, not the FC's).
- `[36:52]` 16 B — all 16 bytes fixed = `d3006306bd44fe08200bfd10025716a5` → **static per-device
  token**; survives app-restart, full power-cycle, FC-only reboot, and cable pull.
- `[52:84]` 32 B — ~116–132 distinct/167, no structure → **per-frame signature / MAC**.

**So `0x11/0x43` = `nonce(32) ‖ device-token(16) ‖ sig(32)`** — signed-nonce authentication. The FC
replies only with short acks (`0x11/0x19`, `0x11/0x14`, `0x11/0x16`); the app authenticates itself,
it does not answer an FC challenge (RX `acc_read` capture confirmed the FC side carries no big
payload). Not replayable (fresh nonce+sig every frame), not forgeable without DJI's signing key. The
wire format is now fully mapped; the only remaining unknown is the key/algorithm producing the
signature — an offline binary RE of GO 4's native libs (libDJI) or FC firmware, not more DUML capture.
