#!/usr/bin/env python3
"""
Verify 0x11/0x43 signature computation offline.

Usage:
  python3 verify_0x11_signature.py \
    --key <hex> \
    --nonce <hex> \
    --device-token <hex> \
    --signature <hex>

Example:
  python3 verify_0x11_signature.py \
    --key "6a7c8d9e..." \
    --nonce "a1b2c3d4e5f6..." \
    --device-token "d3006306bd44fe08200bfd10025716a5" \
    --signature "7f8a9bac..."
"""

import hmac
import hashlib
import argparse
from binascii import hexlify, unhexlify

def verify_hmac_sha256(key_hex, nonce_hex, token_hex, sig_hex):
    """Verify if signature matches HMAC-SHA256(key, nonce || token)"""

    try:
        key = unhexlify(key_hex)
        nonce = unhexlify(nonce_hex)
        token = unhexlify(token_hex)
        expected_sig = unhexlify(sig_hex)
    except ValueError as e:
        print(f"[!] Invalid hex input: {e}")
        return False

    # Compute message: nonce || device_token
    message = nonce + token

    print(f"[*] Verifying HMAC-SHA256 signature")
    print(f"\n[Data]")
    print(f"  Key ({len(key)} bytes): {hexlify(key).decode()}")
    print(f"  Nonce ({len(nonce)} bytes): {hexlify(nonce).decode()}")
    print(f"  Device Token ({len(token)} bytes): {hexlify(token).decode()}")
    print(f"  Message ({len(message)} bytes): {hexlify(message).decode()}")
    print(f"  Expected Signature ({len(expected_sig)} bytes): {hexlify(expected_sig).decode()}")

    # Compute HMAC-SHA256
    computed_sig = hmac.new(key, message, hashlib.sha256).digest()

    print(f"\n[Computation]")
    print(f"  HMAC-SHA256(key, nonce || token)")
    print(f"  Computed Signature ({len(computed_sig)} bytes): {hexlify(computed_sig).decode()}")

    # Verify
    if computed_sig == expected_sig:
        print(f"\n[✓] SIGNATURE VERIFIED! ✓")
        print(f"  Algorithm: HMAC-SHA256")
        print(f"  Input format: nonce || device_token (48 bytes)")
        return True
    else:
        print(f"\n[✗] SIGNATURE MISMATCH")
        print(f"  Expected:  {hexlify(expected_sig).decode()}")
        print(f"  Computed:  {hexlify(computed_sig).decode()}")

        # Try other algorithms
        print(f"\n[*] Trying alternative algorithms...")

        # Try AES-CMAC
        try:
            from Crypto.Cipher import AES
            from Crypto.Hash import CMAC

            c = CMAC.new(key, ciphermod=AES)
            c.update(message)
            aes_cmac_sig = c.digest()
            if aes_cmac_sig == expected_sig:
                print(f"  [✓] Match: AES-CMAC(key, message)")
                return True
        except ImportError:
            pass
        except Exception as e:
            print(f"  [✗] AES-CMAC failed: {e}")

        # Try HMAC with other hash functions
        for hash_name in ['sha1', 'sha224', 'sha384', 'sha512']:
            h = hmac.new(key, message, getattr(hashlib, hash_name))
            if h.digest()[:32] == expected_sig[:32]:
                print(f"  [✓] Partial match: HMAC-{hash_name.upper()}")

        return False

def try_multiple_inputs(key_hex, nonce_hex, token_hex, sig_hex):
    """Try different message formats"""

    print(f"\n[*] Trying alternative message formats...")

    try:
        key = unhexlify(key_hex)
        nonce = unhexlify(nonce_hex)
        token = unhexlify(token_hex)
        expected_sig = unhexlify(sig_hex)
    except ValueError:
        return

    formats = [
        ("nonce || token", nonce + token),
        ("token || nonce", token + nonce),
        ("nonce", nonce),
        ("token", token),
    ]

    for name, message in formats:
        sig = hmac.new(key, message, hashlib.sha256).digest()
        match = "✓" if sig == expected_sig else " "
        print(f"  [{match}] HMAC-SHA256(key, {name:20s}) = {hexlify(sig[:8]).decode()}...")
        if sig == expected_sig:
            print(f"       → Found! Use format: {name}")

def main():
    parser = argparse.ArgumentParser(
        description="Verify 0x11/0x43 HMAC-SHA256 signature computation",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Verify with captured Frida data
  python3 verify_0x11_signature.py \\
    --key 6a7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b \\
    --nonce a1b2c3d4e5f6778899aabbccddeeff0011223344556677889900aabbccddee \\
    --device-token d3006306bd44fe08200bfd10025716a5 \\
    --signature 7f8a9bacddef0123456789abcdef0123456789abcdef0123456789abcdef01

  # Test with sample data
  python3 verify_0x11_signature.py \\
    --key 0011223344556677889900aabbccddee \\
    --nonce 0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f \\
    --device-token d3006306bd44fe08200bfd10025716a5 \\
    --signature <expected-hmac-output>
        """
    )

    parser.add_argument("--key", required=True, help="Hex-encoded signing key")
    parser.add_argument("--nonce", required=True, help="Hex-encoded nonce (32 bytes)")
    parser.add_argument("--device-token", required=True, help="Hex-encoded device token (16 bytes)")
    parser.add_argument("--signature", required=True, help="Hex-encoded expected signature (32 bytes)")
    parser.add_argument("--try-formats", action="store_true", help="Try alternative message formats")

    args = parser.parse_args()

    result = verify_hmac_sha256(args.key, args.nonce, args.device_token, args.signature)

    if not result and args.try_formats:
        try_multiple_inputs(args.key, args.nonce, args.device_token, args.signature)

    return 0 if result else 1

if __name__ == "__main__":
    exit(main())
