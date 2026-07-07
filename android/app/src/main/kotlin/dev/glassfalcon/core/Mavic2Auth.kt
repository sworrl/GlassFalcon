// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.util.Log
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

object Mavic2Auth {
    private const val TAG = "Mavic2Auth"
    private const val NONCE_SIZE = 32
    private const val DEVICE_TOKEN_SIZE = 16
    private const val SIGNATURE_SIZE = 32
    private const val FRAME_SIZE = NONCE_SIZE + DEVICE_TOKEN_SIZE + SIGNATURE_SIZE

    private val DEVICE_TOKEN = hexStringToByteArray("d3006306bd44fe08200bfd10025716a5")

    // RSA private key extracted from DJI GO4 (PKCS#8 Base64)
    private val RSA_PRIVATE_KEY_B64: String = "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDCEYwdO3V2ANrhApjqyk7X8FH5AEaWly58kP9IDAhMqwtIbmcJrUK9oO9Afh3KZnOlDtjiowy733YqpLRO7WBvdbW/c4Dz/d3dy/m+6HMqxaak+GQQRHw/VPdKciaZ3eIZp4MWOyIQwiFSQvPTAo/Na8hV4SgBZHB3lGFw0yu+BmG+h32eIE6p4Y8EDCn+G+yzekX+taMrWTQIysledrygZSGPv1ukbdFDnH/xZEI0dCr9pZT+AZQl3o9a2aMyuRrHM0oupXKKiYl69Y8fKh1Tyd752rF6LrR5uOb9aOfXt18hb+3YL5P9rQ+ZRYbyHYFaxzBPA2jLq0KUQ+Dmg7YhAgMBAAECggEAL9pj0lF3BUHwtssNKdf42QZJMD0BKuDcdZrLV9ifs0f54EJY5enzKw8j76MpdV8N5QVkNX4/BZR0bs9uJogh31oHFs5EXeWbb7V8P7bRrxpNnSAijGBWwscQsyqymf48YlcL28949ujnjoEz3jQjgWOyYnrCgpVhphrQbCGmB5TcZnTFvHfozt/0tzuMj5na5lRnkD0kYXgr0x/SRZcPoCybSpc3t/B/9MAAboGaV/QQkTotr7VOuJfaPRjvg8rzyPzavo3evxsjXj7vDXbN4w0cbk/Uqn2JtvPQ8HoysmF2HdYvILZibvJmWH1hA58b4sn5s6AqFRjMOL7rHdD+gQKBgQD+IzoofmZK5tTxgO9sWsG71IUeshQP9fe159jKCehk1RfuIqqbRP0UcxJiw4eNjHs4zU0HeRL3iF5XfUs0FQanO/pp6YL1xgVdfQlDdTdk6KFHJ0sUJapnJn1S2k7IKfRKE1+rkofSXMYUTsgHF1fDp+gxy4yUMY+h9O+JlKVKOwKBgQDDfaDIblaSm+B0lyG//wFPynAeGd0Q8wcMZbQQ/LWMJZhMZ7fyUZ+A6eL/jB53a2tgnaw2rXBpMe1qu8uSpym2plU0fkgLAnVugS5+KRhOkUHyorcbpVZbs5azf7GlTydR5dI1PHF3Bncemoa6IsEvumHWgQbVyTTz/O9mlFafUwKBgQCvDebms8KUf5JY1F6XfaCLWGVl8nZdVCmQFKbA7Lg2lI5KS3jHQWsupeEZRORffU/3nXsc1apZ9YY+r6CYvI77rRXd1KqPzxos/o7d96TzjkZhc9CEjTlmmh2jb5rqx/Ns/xFcZq/GGH+cx3ODZvHeZQ9NFY+9GLJ+dfB2DX0ZtwKBgQC+9/lZ8telbpqMqpqwqRaJ8LMn5JIdHZu0E6IcuhFLr+ogMW3zTKMpVtGGXEXi2M/TWRPDchiO2tQX4Q5T2/KW19QCbJ5KCwPWiGF3owN4tNOciDGh0xkSidRc0xAh8bnyejSoBry8zlcNUVztdkgMLOGonvCjZWPSOTNQnPYluwKBgCV+WVftpTk3l+OfAJTaXEPNYdh7+WQjzxZKjUaDzx80Ts7hRo2U+EQT7FBjQQNqmmDnWtujo5p1YmJC0FT3n1CVa7g901pb3b0RcOziYWAoJi0/+kLyeo6XBhuLeZ7h90S70GGh1o0V/j/9N1jb5DCL4xKkvdYePPTSTku0BM+n"

    private var rsaPrivateKey: PrivateKey? = null

    init {
        if (RSA_PRIVATE_KEY_B64 != null) {
            try {
                val decodedKey = Base64.getDecoder().decode(RSA_PRIVATE_KEY_B64)
                val keySpec = PKCS8EncodedKeySpec(decodedKey)
                val keyFactory = KeyFactory.getInstance("RSA")
                rsaPrivateKey = keyFactory.generatePrivate(keySpec)
                Log.i(TAG, "[+] RSA private key loaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "[-] Failed to load RSA private key: ${e.message}")
            }
        }
    }

    fun generateFrame(): ByteArray {
        if (rsaPrivateKey == null) {
            Log.w(TAG, "RSA key not loaded; using captured frame replay")
            return generateFrameFallback()
        }

        val nonce = ByteArray(NONCE_SIZE)
        SecureRandom().nextBytes(nonce)

        val message = nonce + DEVICE_TOKEN

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(rsaPrivateKey)
        sig.update(message)
        val signature = sig.sign().take(SIGNATURE_SIZE).toByteArray()

        val innerFrame = ByteArray(FRAME_SIZE)
        nonce.copyInto(innerFrame, 0)
        DEVICE_TOKEN.copyInto(innerFrame, NONCE_SIZE)
        signature.copyInto(innerFrame, NONCE_SIZE + DEVICE_TOKEN_SIZE)

        val frame = ByteArray(4 + FRAME_SIZE)
        frame[0] = 0x50
        frame[1] = 0x00
        frame[2] = 0x00
        frame[3] = 0x00
        innerFrame.copyInto(frame, 4)

        Log.d(TAG, "[+] Generated 0x11/0x43 frame (${frame.size} bytes)")
        return frame
    }

    fun isKeyLoaded(): Boolean = rsaPrivateKey != null

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((s[i].digitToInt(16) shl 4) + s[i + 1].digitToInt(16)).toByte()
        }
        return data
    }
}

// FALLBACK: Use captured frame replay if RSA key unavailable
fun generateFrameFallback(): ByteArray {
    return FrameReplay.generateFrameAsNeeded()
}
