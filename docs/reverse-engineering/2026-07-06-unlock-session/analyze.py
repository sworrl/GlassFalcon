import sys, struct
from parse_kprobe import crc8, crc16, LINE_RE
def parse(path):
    ev=[]
    for line in open(path, errors="replace"):
        m=LINE_RE.search(line)
        if not m: continue
        pid,cpu,ts,count,b0,b1,b2,b3=m.groups()
        count=int(count,0)
        words=[int(b0,16),int(b1,16),int(b2,16),int(b3,16)]
        buf=b"".join(struct.pack("<Q",w) for w in words)[:min(count,32)]
        ev.append((float(ts),buf))
    return ev
def scan(buf):
    i,n=0,len(buf)
    while i<n-4:
        if buf[i]!=0x55: i+=1; continue
        length=buf[i+1]|((buf[i+2]&0x03)<<8)
        if length<13 or length>1024 or i+length>n: i+=1; continue
        if crc8(buf[i:i+3])!=buf[i+3]: i+=1; continue
        pkt=buf[i:i+length]
        if crc16(pkt[:-2])!=(pkt[-2]|(pkt[-1]<<8)): i+=1; continue
        yield pkt[4],pkt[5],pkt[8],pkt[9],pkt[10],pkt[11:length-2]
        i+=length
ev=parse(sys.argv[1])
t0=ev[0][0]
frames=[]
for ts,buf in ev:
    for src,dst,ctype,cs,cid,pl in scan(buf):
        frames.append((ts-t0,src,dst,cs,cid,pl))
# distinct cmds: first-seen time, count, sample payload, dst
seen={}
for rel,src,dst,cs,cid,pl in frames:
    k=(cs,cid)
    if k not in seen: seen[k]=[rel,0,pl.hex(),dst]
    seen[k][1]+=1
print("=== distinct GO4 commands (first-seen t, count, dst, sample payload) ===")
for (cs,cid),(rel,cnt,hx,dst) in sorted(seen.items(), key=lambda kv: kv[1][0]):
    print(f"  t+{rel:6.2f}  0x{cs:02x}/0x{cid:02x}  n={cnt:<4} ->{dst:02x}  {hx[:48]}")
print("\n=== full connect handshake, first 6.0 s, chronological ===")
for rel,src,dst,cs,cid,pl in frames:
    if rel<=6.0:
        print(f"  t+{rel:6.3f} {src:02x}->{dst:02x} 0x{cs:02x}/0x{cid:02x} {pl.hex()}")
