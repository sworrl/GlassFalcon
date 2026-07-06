// Frida hook to capture 0x11/0x43 signature computation from GO4
// Run: frida -H <phone-ip>:27042 -n dji.go.v4 -l hook_0x11_signature.js

const TAG = "[0x11-SIG]";
let frameCount = 0;
const MAX_FRAMES = 5;  // Capture 5 frames then exit

console.log("[*] Loading 0x11 signature hooks...");

// Hook 1: Intercept SHA256Signature computation
try {
  const libFlySafe = Module.findBaseAddress("libDJIFlySafeCore.so");
  if (!libFlySafe) {
    console.log("[-] libDJIFlySafeCore.so not loaded yet");
  } else {
    console.log("[+] libDJIFlySafeCore.so @ " + libFlySafe);

    // SHA256Signature(const string&, const string&)
    // C++ mangled: _ZN3dji7flysafe15SHA256SignatureERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_
    const SHA256SigAddr = libFlySafe.add(0x12345);  // Placeholder, will search by pattern

    // Try to find by export name
    try {
      const exps = libFlySafe.enumerateExports();
      let found = false;
      for (const exp of exps) {
        if (exp.name.includes("SHA256Signature") || exp.name.includes("sha256")) {
          console.log("[+] Found SHA256 export:", exp.name, "@", exp.address);

          Interceptor.attach(exp.address, {
            onEnter(args) {
              const key = Memory.readUtf8String(args[1].readPointer());
              const msg = Memory.readUtf8String(args[2].readPointer());
              console.log(`\n${TAG} Frame ${frameCount + 1}: SHA256Signature inputs`);
              console.log(`  Key (first 64 chars): ${key.substring(0, 64)}`);
              console.log(`  Key hex: ${Memory.readByteArray(args[1].readPointer(), 64)}`);
              console.log(`  Message (first 64 chars): ${msg.substring(0, 64)}`);
              console.log(`  Message hex: ${Memory.readByteArray(args[2].readPointer(), 64)}`);
            },
            onLeave(retval) {
              // retval is the return value (signature, 32 bytes)
              const sig = Memory.readByteArray(retval, 32);
              console.log(`${TAG} SHA256Signature output (32 B):`);
              console.log(`  ${hexdump(sig, {length: 32})}`);
              frameCount++;
              if (frameCount >= MAX_FRAMES) {
                console.log(`\n[!] Captured ${MAX_FRAMES} frames. Exiting.`);
                send({type: "done", frames: frameCount});
                Java.perform(() => {
                  const System = Java.use("java.lang.System");
                  System.exit(0);
                });
              }
            }
          });
          found = true;
          break;
        }
      }
      if (!found) {
        console.log("[-] SHA256Signature not found in exports, trying hardcoded offset...");
      }
    } catch (e) {
      console.log("[-] Export search failed:", e);
    }
  }
} catch (e) {
  console.log("[-] libDJIFlySafeCore.so hook failed:", e);
}

// Hook 2: Intercept GetRequestParamsAndSignature to see the 84-byte frame
try {
  const libFlySafe = Module.findBaseAddress("libDJIFlySafeCore.so");
  if (libFlySafe) {
    const exps = libFlySafe.enumerateExports();
    for (const exp of exps) {
      if (exp.name.includes("GetRequestParamsAndSignature")) {
        console.log("[+] Found GetRequestParamsAndSignature @", exp.address);

        Interceptor.attach(exp.address, {
          onLeave(retval) {
            const frame = Memory.readByteArray(retval, 84);
            console.log(`${TAG} 84-byte frame assembled:`);
            console.log(`  ${hexdump(frame, {length: 84})}`);
            console.log(`  Length header [0:4]:     ${hexdump(Memory.readByteArray(retval, 4), {length: 4})}`);
            console.log(`  Nonce [4:36]:            ${hexdump(Memory.readByteArray(retval.add(4), 32), {length: 32})}`);
            console.log(`  Device token [36:52]:    ${hexdump(Memory.readByteArray(retval.add(36), 16), {length: 16})}`);
            console.log(`  Signature [52:84]:       ${hexdump(Memory.readByteArray(retval.add(52), 32), {length: 32})}`);
          }
        });
        break;
      }
    }
  }
} catch (e) {
  console.log("[-] GetRequestParamsAndSignature hook failed:", e);
}

// Hook 3: Intercept whitebox key derivation
try {
  const libFlySafe = Module.findBaseAddress("libDJIFlySafeCore.so");
  if (libFlySafe) {
    const exps = libFlySafe.enumerateExports();
    for (const exp of exps) {
      if (exp.name.includes("GetWhiteBoxKeyChainString")) {
        console.log("[+] Found GetWhiteBoxKeyChainString @", exp.address);

        Interceptor.attach(exp.address, {
          onEnter(args) {
            console.log(`${TAG} WhiteBoxKeyChainString index:`, args[1]);
          },
          onLeave(retval) {
            const key = Memory.readByteArray(retval, 64);
            console.log(`${TAG} WhiteBoxKeyChainString output:`);
            console.log(`  ${hexdump(key, {length: 64})}`);
          }
        });
        break;
      }
    }
  }
} catch (e) {
  console.log("[-] GetWhiteBoxKeyChainString hook failed:", e);
}

console.log("[*] Hooks installed. Waiting for frames...");

function hexdump(buf, opts) {
  const length = opts?.length || buf.byteLength;
  const data = new Uint8Array(buf, 0, Math.min(length, buf.byteLength));
  let hex = "";
  for (let i = 0; i < data.length; i++) {
    hex += ("0" + data[i].toString(16)).slice(-2);
    if ((i + 1) % 16 === 0) hex += "\n  ";
    else hex += " ";
  }
  return hex;
}

recv(message => {
  if (message.type === "done") {
    console.log("[!] Frida hook completed");
  }
});
