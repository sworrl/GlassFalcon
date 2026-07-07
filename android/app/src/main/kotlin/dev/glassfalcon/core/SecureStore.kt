// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest data protection for everything sensitive GlassFalcon writes to disk — flight records
 * (lat/lon), flight dumps (telemetry), and secrets (API/Windy keys).
 *
 * Threat model: **physical device capture**. If the phone is lost/seized, files pulled off the
 * app's storage should be unrecoverable ciphertext, not plaintext location history. The key never
 * leaves the Android Keystore (hardware-backed / TEE, StrongBox where the device has it); it can't
 * be exported even with root, so the encrypted files can't be decrypted off-device.
 *
 * Payload encryption is AES-256-GCM (authenticated: tampering fails the tag rather than yielding
 * garbage). Small blobs get [encrypt]/[decrypt]; large streaming logs get [encryptingStream].
 *
 * Honest limit on [secureDelete]: overwriting a file's bytes is reliable on a raw block device, but
 * flash storage with wear-levelling (every modern phone) may keep the old physical pages around
 * behind the FTL. The real guarantee against recovery is that the data was encrypted in the first
 * place — once the file is unlinked and its key stays sealed in the Keystore, any leftover flash
 * pages are ciphertext. The overwrite pass is defense-in-depth, not the primary control.
 */
object SecureStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val DATA_KEY_ALIAS = "gf_data_key_v1"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    // ── AES-256-GCM key in the Android Keystore ──────────────────────────────
    private fun dataKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(DATA_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                DATA_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No setUserAuthenticationRequired: the app must read/write these files in the
                // background (auto flight-record save, dump-on-takeoff) with the screen possibly
                // off, so a per-op unlock isn't workable here. Protection is at-rest against a
                // pulled disk image, not against a running, unlocked device.
                .build(),
        )
        return gen.generateKey()
    }

    // ── Blob encryption (flight records, small files) ────────────────────────
    /** IV is prepended to the ciphertext: [12-byte IV][GCM ciphertext+tag]. */
    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, dataKey())
        val ct = c.doFinal(plain)
        return c.iv + ct
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > GCM_IV_BYTES) { "ciphertext too short" }
        val iv = blob.copyOfRange(0, GCM_IV_BYTES)
        val ct = blob.copyOfRange(GCM_IV_BYTES, blob.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, dataKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return c.doFinal(ct)
    }

    fun writeEncrypted(f: File, plain: ByteArray) = f.writeBytes(encrypt(plain))
    fun readEncrypted(f: File): ByteArray = decrypt(f.readBytes())

    // ── Streaming encryption (large flight dumps) ────────────────────────────
    /** Wraps [out] so everything written is AES-GCM encrypted. Writes the 12-byte IV first, then
     *  returns a [CipherOutputStream]; the GCM tag is flushed when the returned stream is closed. */
    fun encryptingStream(out: OutputStream): OutputStream {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, dataKey())
        out.write(c.iv)
        return CipherOutputStream(out, c)
    }

    /** Inverse of [encryptingStream]: reads the leading IV, returns a decrypting stream. */
    fun decryptingStream(inp: InputStream): InputStream {
        val iv = ByteArray(GCM_IV_BYTES)
        var n = 0
        while (n < GCM_IV_BYTES) {
            val r = inp.read(iv, n, GCM_IV_BYTES - n)
            if (r < 0) break
            n += r
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, dataKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return CipherInputStream(inp, c)
    }

    // ── EncryptedSharedPreferences (secrets) ─────────────────────────────────
    fun encryptedPrefs(ctx: Context, name: String): SharedPreferences {
        val master = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx, name, master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * One-time migration of a legacy PLAINTEXT SharedPreferences into an encrypted one. Copies any
     * String/Boolean/Float/Int/Long values across, then clears the plaintext store so the secret no
     * longer sits unencrypted on disk. No-op once the plaintext file is empty.
     */
    fun migratePlaintextPrefs(ctx: Context, legacyName: String, into: SharedPreferences) {
        val legacy = ctx.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        val all = legacy.all
        if (all.isEmpty()) return
        into.edit().apply {
            for ((k, v) in all) when (v) {
                is String -> putString(k, v)
                is Boolean -> putBoolean(k, v)
                is Float -> putFloat(k, v)
                is Int -> putInt(k, v)
                is Long -> putLong(k, v)
            }
            apply()
        }
        legacy.edit().clear().apply()
    }

    // ── Forensics-resistant deletion ─────────────────────────────────────────
    /**
     * Overwrite [f]'s contents ([passes] passes: random, then a final zero pass), fsync, truncate,
     * then unlink. See the class doc for the flash-storage caveat — combined with the fact the file
     * was AES-GCM encrypted, this leaves no recoverable plaintext. Best-effort: never throws.
     */
    fun secureDelete(f: File, passes: Int = 3) {
        if (!f.exists()) return
        runCatching {
            val len = f.length()
            if (len > 0) RandomAccessFile(f, "rws").use { raf ->
                val buf = ByteArray(64 * 1024)
                val rnd = SecureRandom()
                for (pass in 0 until passes) {
                    raf.seek(0)
                    if (pass == passes - 1) buf.fill(0) else rnd.nextBytes(buf)
                    var written = 0L
                    while (written < len) {
                        if (pass != passes - 1) rnd.nextBytes(buf)
                        val n = minOf(buf.size.toLong(), len - written).toInt()
                        raf.write(buf, 0, n)
                        written += n
                    }
                    raf.fd.sync()
                }
                raf.setLength(0)
            }
        }
        runCatching { f.delete() }
    }
}
