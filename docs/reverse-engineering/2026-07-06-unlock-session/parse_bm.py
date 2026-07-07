#!/usr/bin/env python3
"""Parse acc_write kprobe capture, reassemble DUML frames, and surface the
one-shot config/param-set commands (the toggle traffic) hiding in the periodic
telemetry noise. Reuses the CRC8/CRC16 scanner from parse_kprobe.py."""
import re, struct, sys
from parse_kprobe import crc8, crc16, LINE_RE

def parse_lines(path):
    events = []
    with open(path, errors="replace") as f:
        for line in f:
            m = LINE_RE.search(line)
            if not m:
                continue
            pid, cpu, ts, count, b0, b1, b2, b3 = m.groups()
            count = int(count, 0)
            words = [int(b0, 16), int(b1, 16), int(b2, 16), int(b3, 16)]
            buf = b"".join(struct.pack("<Q", w) for w in words)[:min(count, 32)]
            events.append((float(ts), pid, count, buf))
    return events

def scan(buf):
    i, n = 0, len(buf)
    while i < n - 4:
        if buf[i] != 0x55:
            i += 1; continue
        length = buf[i+1] | ((buf[i+2] & 0x03) << 8)
        if length < 13 or length > 1024 or i + length > n:
            i += 1; continue
        if crc8(buf[i:i+3]) != buf[i+3]:
            i += 1; continue
        pkt = buf[i:i+length]
        if crc16(pkt[:-2]) != (pkt[-2] | (pkt[-1] << 8)):
            i += 1; continue
        src, dst = pkt[4], pkt[5]
        cmd_type, cmd_set, cmd_id = pkt[8], pkt[9], pkt[10]
        payload = pkt[11:length-2]
        yield src, dst, cmd_set, cmd_id, payload, bool(cmd_type & 0x80)
        i += length

def main(path):
    events = parse_lines(path)
    t0 = events[0][0] if events else 0
    frames = []
    tally = {}
    for ts, pid, count, buf in events:
        for src, dst, cs, cid, pl, ack in scan(buf):
            frames.append((ts - t0, src, dst, cs, cid, pl.hex(), ack))
            k = (cs, cid)
            tally[k] = tally.get(k, 0) + 1

    print(f"parsed {len(events)} events, {len(frames)} DUML frames\n", file=sys.stderr)
    print("=== cmd_set/cmd_id tally (sorted by count, rarest last) ===")
    for (cs, cid), n in sorted(tally.items(), key=lambda kv: -kv[1]):
        print(f"  0x{cs:02x}/0x{cid:02x} = {n}")

    # One-shot / rare frames: a toggle is sent once, telemetry is sent hundreds of times.
    print("\n=== RARE frames (cmd_set/cmd_id count <= 3) — candidate toggle commands ===")
    for rel, src, dst, cs, cid, hx, ack in frames:
        if tally[(cs, cid)] <= 3:
            print(f"  t+{rel:7.2f}s {src:02x}->{dst:02x} set=0x{cs:02x} id=0x{cid:02x} ack={ack} pl={hx}")

    # FLYC (0x03) frames specifically — beginner/limit params live here
    print("\n=== all FLYC (cmd_set=0x03) frames, chronological ===")
    for rel, src, dst, cs, cid, hx, ack in frames:
        if cs == 0x03:
            print(f"  t+{rel:7.2f}s {src:02x}->{dst:02x} id=0x{cid:02x} ack={ack} pl={hx}")

if __name__ == "__main__":
    main(sys.argv[1])
