# GlassFalcon — authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

import frida, time

SOURCE = r"""
var base = Process.findModuleByName("libDJICSDKCommon.so").base;
var fn2b38 = new NativeFunction(base.add(0xb2b38), 'void', ['pointer','pointer','pointer']);
var tbl = base.add(0x19d718);

function decrypt_entry(dp, sz, kp) {
    var ct = new Uint8Array(dp.readByteArray(sz));
    var km = new Uint8Array(kp.readByteArray(16));
    var state = Memory.alloc(64);
    var ctBuf = Memory.alloc(sz);
    ctBuf.writeByteArray(Array.from(ct));
    for (var i = 0; i < 32; i++) state.add(i).writeU8(0);
    for (var i = 0; i < 16; i++) state.add(16+i).writeU8(km[i]);
    var pt = [];
    var nblocks = sz >> 4;
    for (var b = 0; b < nblocks; b++) {
        fn2b38(ctBuf.add(b*16), state, tbl);
        var s = new Uint8Array(state.readByteArray(32));
        var block = [];
        for (var i = 0; i < 16; i++) block.push(s[i] ^ s[16+i]);
        pt = pt.concat(block);
        for (var i = 0; i < 16; i++) { state.add(i).writeU8(block[i]); state.add(16+i).writeU8(ct[b*16+i]); }
    }
    var last = pt[pt.length-1];
    var real = (last>=1&&last<=16) ? pt.slice(0, pt.length-last) : pt;
    return real.map(function(b){return ('0'+b.toString(16)).slice(-2);}).join('');
}

var tableAddr = base.add(0x196688);
var results = [];
for (var idx = 0; idx < 288; idx++) {
    var e = tableAddr.add(idx*24);
    var dp = e.readPointer(), sz = e.add(8).readU64().toNumber(), kp = e.add(16).readPointer();
    if (dp.isNull() || sz===0) continue;
    try { results.push({idx:idx, sz:sz, hex:decrypt_entry(dp,sz,kp)}); }
    catch(ex) { results.push({idx:idx, err:ex.message}); }
}
send({results:results});
"""

msgs = []
def on_message(msg, data):
    if msg['type'] == 'send': msgs.append(msg['payload'])

dev = frida.get_device_manager().add_remote_device("192.168.13.163:27042")
pid = next(p.pid for p in dev.enumerate_processes() if 'phone' in p.name)
session = dev.attach(pid)
script = session.create_script(SOURCE)
script.on('message', on_message)
script.load()
time.sleep(30)
script.unload()
session.detach()

if msgs and 'results' in msgs[0]:
    for r in msgs[0]['results']:
        if 'err' in r:
            print(f"[{r['idx']:3d}] ERR: {r['err']}")
        else:
            h = r['hex']
            # try ASCII decode
            try: s = bytes.fromhex(h).decode('utf-8', errors='replace')
            except: s = '?'
            print(f"[{r['idx']:3d}] sz={r['sz']:2d}  {h}  |{s}|")
else:
    print("No results:", msgs)
