// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// ── CRC tables (identical to Python version, from dji-firmware-tools) ────────

private val CRC8_TABLE = intArrayOf(
    0x00,0x5E,0xBC,0xE2,0x61,0x3F,0xDD,0x83,0xC2,0x9C,0x7E,0x20,0xA3,0xFD,0x1F,0x41,
    0x9D,0xC3,0x21,0x7F,0xFC,0xA2,0x40,0x1E,0x5F,0x01,0xE3,0xBD,0x3E,0x60,0x82,0xDC,
    0x23,0x7D,0x9F,0xC1,0x42,0x1C,0xFE,0xA0,0xE1,0xBF,0x5D,0x03,0x80,0xDE,0x3C,0x62,
    0xBE,0xE0,0x02,0x5C,0xDF,0x81,0x63,0x3D,0x7C,0x22,0xC0,0x9E,0x1D,0x43,0xA1,0xFF,
    0x46,0x18,0xFA,0xA4,0x27,0x79,0x9B,0xC5,0x84,0xDA,0x38,0x66,0xE5,0xBB,0x59,0x07,
    0xDB,0x85,0x67,0x39,0xBA,0xE4,0x06,0x58,0x19,0x47,0xA5,0xFB,0x78,0x26,0xC4,0x9A,
    0x65,0x3B,0xD9,0x87,0x04,0x5A,0xB8,0xE6,0xA7,0xF9,0x1B,0x45,0xC6,0x98,0x7A,0x24,
    0xF8,0xA6,0x44,0x1A,0x99,0xC7,0x25,0x7B,0x3A,0x64,0x86,0xD8,0x5B,0x05,0xE7,0xB9,
    0x8C,0xD2,0x30,0x6E,0xED,0xB3,0x51,0x0F,0x4E,0x10,0xF2,0xAC,0x2F,0x71,0x93,0xCD,
    0x11,0x4F,0xAD,0xF3,0x70,0x2E,0xCC,0x92,0xD3,0x8D,0x6F,0x31,0xB2,0xEC,0x0E,0x50,
    0xAF,0xF1,0x13,0x4D,0xCE,0x90,0x72,0x2C,0x6D,0x33,0xD1,0x8F,0x0C,0x52,0xB0,0xEE,
    0x32,0x6C,0x8E,0xD0,0x53,0x0D,0xEF,0xB1,0xF0,0xAE,0x4C,0x12,0x91,0xCF,0x2D,0x73,
    0xCA,0x94,0x76,0x28,0xAB,0xF5,0x17,0x49,0x08,0x56,0xB4,0xEA,0x69,0x37,0xD5,0x8B,
    0x57,0x09,0xEB,0xB5,0x36,0x68,0x8A,0xD4,0x95,0xCB,0x29,0x77,0xF4,0xAA,0x48,0x16,
    0xE9,0xB7,0x55,0x0B,0x88,0xD6,0x34,0x6A,0x2B,0x75,0x97,0xC9,0x4A,0x14,0xF6,0xA8,
    0x74,0x2A,0xC8,0x96,0x15,0x4B,0xA9,0xF7,0xB6,0xE8,0x0A,0x54,0xD7,0x89,0x6B,0x35,
)

private val CRC16_TABLE = intArrayOf(
    0x0000,0x1189,0x2312,0x329b,0x4624,0x57ad,0x6536,0x74bf,
    0x8c48,0x9dc1,0xaf5a,0xbed3,0xca6c,0xdbe5,0xe97e,0xf8f7,
    0x1081,0x0108,0x3393,0x221a,0x56a5,0x472c,0x75b7,0x643e,
    0x9cc9,0x8d40,0xbfdb,0xae52,0xdaed,0xcb64,0xf9ff,0xe876,
    0x2102,0x308b,0x0210,0x1399,0x6726,0x76af,0x4434,0x55bd,
    0xad4a,0xbcc3,0x8e58,0x9fd1,0xeb6e,0xfae7,0xc87c,0xd9f5,
    0x3183,0x200a,0x1291,0x0318,0x77a7,0x662e,0x54b5,0x453c,
    0xbdcb,0xac42,0x9ed9,0x8f50,0xfbef,0xea66,0xd8fd,0xc974,
    0x4204,0x538d,0x6116,0x709f,0x0420,0x15a9,0x2732,0x36bb,
    0xce4c,0xdfc5,0xed5e,0xfcd7,0x8868,0x99e1,0xab7a,0xbaf3,
    0x5285,0x430c,0x7197,0x601e,0x14a1,0x0528,0x37b3,0x263a,
    0xdecd,0xcf44,0xfddf,0xec56,0x98e9,0x8960,0xbbfb,0xaa72,
    0x6306,0x728f,0x4014,0x519d,0x2522,0x34ab,0x0630,0x17b9,
    0xef4e,0xfec7,0xcc5c,0xddd5,0xa96a,0xb8e3,0x8a78,0x9bf1,
    0x7387,0x620e,0x5095,0x411c,0x35a3,0x242a,0x16b1,0x0738,
    0xffcf,0xee46,0xdcdd,0xcd54,0xb9eb,0xa862,0x9af9,0x8b70,
    0x8408,0x9581,0xa71a,0xb693,0xc22c,0xd3a5,0xe13e,0xf0b7,
    0x0840,0x19c9,0x2b52,0x3adb,0x4e64,0x5fed,0x6d76,0x7cff,
    0x9489,0x8500,0xb79b,0xa612,0xd2ad,0xc324,0xf1bf,0xe036,
    0x18c1,0x0948,0x3bd3,0x2a5a,0x5ee5,0x4f6c,0x7df7,0x6c7e,
    0xa50a,0xb483,0x8618,0x9791,0xe32e,0xf2a7,0xc03c,0xd1b5,
    0x2942,0x38cb,0x0a50,0x1bd9,0x6f66,0x7eef,0x4c74,0x5dfd,
    0xb58b,0xa402,0x9699,0x8710,0xf3af,0xe226,0xd0bd,0xc134,
    0x39c3,0x284a,0x1ad1,0x0b58,0x7fe7,0x6e6e,0x5cf5,0x4d7c,
    0xc60c,0xd785,0xe51e,0xf497,0x8028,0x91a1,0xa33a,0xb2b3,
    0x4a44,0x5bcd,0x6956,0x78df,0x0c60,0x1de9,0x2f72,0x3efb,
    0xd68d,0xc704,0xf59f,0xe416,0x90a9,0x8120,0xb3bb,0xa232,
    0x5ac5,0x4b4c,0x79d7,0x685e,0x1ce1,0x0d68,0x3ff3,0x2e7a,
    0xe70e,0xf687,0xc41c,0xd595,0xa12a,0xb0a3,0x8238,0x93b1,
    0x6b46,0x7acf,0x4854,0x59dd,0x2d62,0x3ceb,0x0e70,0x1ff9,
    0xf78f,0xe606,0xd49d,0xc514,0xb1ab,0xa022,0x92b9,0x8330,
    0x7bc7,0x6a4e,0x58d5,0x495c,0x3de3,0x2c6a,0x1ef1,0x0f78,
)

fun crc8(data: ByteArray, seed: Int = 0x77): Int {
    var crc = seed
    for (b in data) crc = CRC8_TABLE[(crc xor (b.toInt() and 0xff)) and 0xff]
    return crc
}

fun crc16(data: ByteArray, seed: Int = 0x3692): Int {
    var v = seed
    for (b in data) v = (v ushr 8) xor CRC16_TABLE[((b.toInt() and 0xff) xor v) and 0xff]
    return v and 0xffff
}

// ── DUML packet builder ───────────────────────────────────────────────────────

fun buildPacket(
    src: Int, dst: Int, seq: Int, cmdType: Int,
    cmdSet: Int, cmdId: Int, payload: ByteArray = byteArrayOf()
): ByteArray {
    val length = 11 + payload.size + 2
    val raw = ByteArray(length)
    raw[0] = 0x55.toByte()
    raw[1] = (length and 0xff).toByte()
    raw[2] = ((1 shl 2) or ((length ushr 8) and 0x03)).toByte()
    raw[3] = 0                                    // CRC8 placeholder
    raw[4] = src.toByte()
    raw[5] = dst.toByte()
    raw[6] = (seq and 0xff).toByte()
    raw[7] = ((seq ushr 8) and 0xff).toByte()
    raw[8] = cmdType.toByte()
    raw[9] = cmdSet.toByte()
    raw[10] = cmdId.toByte()
    payload.copyInto(raw, 11)
    // CRC8 covers only the 3-byte magic+length prefix (seed 0x77), see
    // docs/protocol.md. `crc8(raw, 3)` previously passed the *whole* buffer with
    // seed=3 (matched Int positionally instead of slicing), corrupting the header
    // checksum on every outgoing packet. Confirmed against a live capture: the
    // wm240 bench-takeoff frame's on-wire byte[3] only matches this buggy formula,
    // not the correct one, every command this SDK ever sent had a bad header CRC.
    raw[3] = crc8(raw.copyOfRange(0, 3)).toByte()
    val crc = crc16(raw.copyOf(length - 2))
    raw[length - 2] = (crc and 0xff).toByte()
    raw[length - 1] = ((crc ushr 8) and 0xff).toByte()
    return raw
}

// ── Frame ────────────────────────────────────────────────────────────────────

data class DumlFrame(
    val src: Int, val dst: Int, val seq: Int,
    val cmdType: Int, val cmdSet: Int, val cmdId: Int,
    val payload: ByteArray,
    val isAck: Boolean,
    val raw: ByteArray,
)

fun parseFrame(buf: ByteArray, offset: Int = 0): Pair<DumlFrame?, Int> {
    if (buf.size - offset < 4) return Pair(null, 0)
    if (buf[offset].toInt() and 0xff != 0x55) return Pair(null, 1)
    val length = (buf[offset + 1].toInt() and 0xff) or
                 ((buf[offset + 2].toInt() and 0x03) shl 8)
    if (length < 13 || length > 1024) return Pair(null, 1)
    if (buf.size - offset < length) return Pair(null, 0)
    val pkt = buf.copyOfRange(offset, offset + length)
    val src = pkt[4].toInt() and 0xff
    val dst = pkt[5].toInt() and 0xff
    val seq = (pkt[6].toInt() and 0xff) or ((pkt[7].toInt() and 0xff) shl 8)
    val cmdType = pkt[8].toInt() and 0xff
    val cmdSet  = pkt[9].toInt() and 0xff
    val cmdId   = pkt[10].toInt() and 0xff
    val payload = pkt.copyOfRange(11, length - 2)
    return Pair(
        DumlFrame(src, dst, seq, cmdType, cmdSet, cmdId, payload,
                  isAck = cmdType and 0x80 != 0, raw = pkt),
        length
    )
}

// ── Connection ────────────────────────────────────────────────────────────────

typealias FrameListener = (DumlFrame) -> Unit

enum class Transport { NONE, TCP, USB, AOA }

class DumlConnection {
    companion object {
        // COMM_DEV_TYPE (comm_mkdupc.py): MOBILE_APP=2 and PC=10 are distinct roles, 
        // PC is the desktop assistant tool (DJI Assistant 2), MOBILE_APP is the phone
        // app we're emulating. send() previously identified us as PC; every command
        // sent under that identity may have been silently dropped by the FC's
        // authority check before reaching the FLYC dispatcher.
        const val MOBILE_APP = 0x02
        const val PC   = 0x0a
        const val FC   = 0x03
        const val CAM  = 0x01
        const val GIMB = 0x04
        const val RC   = 0x06
        const val REQ  = 0x40

        // CDC-ACM USB control constants
        private const val CDC_SET_LINE_CODING     = 0x20
        private const val CDC_SET_CONTROL_LINE_STATE = 0x22
        private const val USB_RT_ACM              = 0x21  // host→device, class, interface
    }

    private val seq = AtomicInteger(0)
    private val listeners = CopyOnWriteArrayList<FrameListener>()

    // TCP transport
    @Volatile private var socket: Socket? = null

    // USB host (CDC-ACM direct to drone)
    @Volatile private var usbConn: UsbDeviceConnection? = null
    @Volatile private var usbOut:  UsbEndpoint? = null
    @Volatile private var usbIn:   UsbEndpoint? = null

    // AOA transport (T600 RC as USB host, phone as accessory)
    @Volatile private var aoaPfd: ParcelFileDescriptor? = null
    @Volatile private var aoaOut: FileOutputStream? = null

    @Volatile var transport = Transport.NONE; private set
    val isConnected get() = transport != Transport.NONE

    // Raw-stream capture sink (matches the `dd if=/dev/usb_accessory` format tools/usb_capture.py
    // expects): every chunk read off the wire, before any DUML parsing, so a bench-test session
    // can be diffed offline against a stock-app capture to verify unconfirmed opcodes.
    @Volatile private var captureSink: java.io.OutputStream? = null
    fun startCapture(out: java.io.OutputStream) { captureSink = out }
    fun stopCapture() {
        try { captureSink?.flush(); captureSink?.close() } catch (_: Exception) {}
        captureSink = null
    }
    private fun capture(buf: ByteArray, n: Int) {
        try { captureSink?.write(buf, 0, n) } catch (_: Exception) {}
    }

    fun addListener(l: FrameListener) { listeners.add(l) }
    fun removeListener(l: FrameListener) { listeners.remove(l) }

    private val videoListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()
    fun addVideoListener(l: (ByteArray) -> Unit) { videoListeners.add(l) }
    fun removeVideoListener(l: (ByteArray) -> Unit) { videoListeners.remove(l) }

    // ── TCP ─────────────────────────────────────────────────────────────────────

    fun connectTcp(host: String, port: Int = 10000) {
        disconnect()
        Thread {
            try {
                val s = Socket(host, port).also { socket = it }
                transport = Transport.TCP
                rxLoopTcp(s)
            } catch (_: Exception) {
                transport = Transport.NONE
            }
        }.apply { isDaemon = true; name = "duml-tcp-rx"; start() }
    }

    // ── USB (CDC-ACM, RC240 or wm240 direct) ────────────────────────────────

    fun connectUsb(device: UsbDevice, manager: UsbManager): Boolean {
        disconnect()

        // Find CDC Data interface (class 0x0A) with bulk IN + bulk OUT endpoints.
        // Interface 0 is typically CDC Communications (control), interface 1 is CDC Data.
        var dataIface: UsbInterface? = null
        var epIn:  UsbEndpoint? = null
        var epOut: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // CDC Data class = 0x0A; also accept vendor class (0xFF) with bulk endpoints
            if (iface.interfaceClass !in intArrayOf(0x0A, 0xFF)) continue
            var tmpIn: UsbEndpoint? = null
            var tmpOut: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN)  tmpIn  = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT) tmpOut = ep
            }
            if (tmpIn != null && tmpOut != null) {
                dataIface = iface; epIn = tmpIn; epOut = tmpOut; break
            }
        }
        if (dataIface == null) return false

        val conn = manager.openDevice(device) ?: return false
        conn.claimInterface(dataIface, true)

        // CDC SET_LINE_CODING: 115200 baud, 8N1
        val lineCoding = ByteArray(7).also { b ->
            val rate = 115200
            b[0] = (rate and 0xff).toByte()
            b[1] = ((rate ushr 8)  and 0xff).toByte()
            b[2] = ((rate ushr 16) and 0xff).toByte()
            b[3] = ((rate ushr 24) and 0xff).toByte()
            b[4] = 0  // 1 stop bit
            b[5] = 0  // no parity
            b[6] = 8  // 8 data bits
        }
        conn.controlTransfer(USB_RT_ACM, CDC_SET_LINE_CODING, 0, 0, lineCoding, 7, 2000)
        // CDC SET_CONTROL_LINE_STATE: DTR + RTS
        conn.controlTransfer(USB_RT_ACM, CDC_SET_CONTROL_LINE_STATE, 0x0003, 0, null, 0, 2000)

        usbConn = conn; usbIn = epIn; usbOut = epOut
        transport = Transport.USB

        Thread {
            rxLoopUsb(conn, epIn!!)
        }.apply { isDaemon = true; name = "duml-usb-rx"; start() }

        return true
    }

    // ── AOA (T600 RC as USB host, phone is accessory, data via /dev/usb_accessory) ──

    fun connectAccessory(accessory: UsbAccessory, manager: UsbManager): Boolean {
        disconnect()
        val pfd = manager.openAccessory(accessory) ?: return false
        aoaPfd = pfd
        aoaOut = FileOutputStream(pfd.fileDescriptor)
        transport = Transport.AOA
        Thread { rxLoopAoa(pfd) }.apply { isDaemon = true; name = "duml-aoa-rx"; start() }
        return true
    }

    // ── Send ─────────────────────────────────────────────────────────────────

    fun send(dst: Int, cmdSet: Int, cmdId: Int, payload: ByteArray = byteArrayOf()): Int =
        sendAs(MOBILE_APP, dst, cmdSet, cmdId, payload)

    /** Send under an explicit source identity. Almost everything uses [MOBILE_APP] (0x02), but the
     *  index-based FlyC param protocol (0x03/0xe0..0xe3) is only honored for the PC/assistant
     *  identity ([PC] = 0x0a), confirmed live on wm240; the FC silently ignores those under 0x02. */
    fun sendAs(src: Int, dst: Int, cmdSet: Int, cmdId: Int, payload: ByteArray = byteArrayOf()): Int {
        val s = seq.incrementAndGet() and 0xffff
        val pkt = buildPacket(src, dst, s, REQ, cmdSet, cmdId, payload)
        when (transport) {
            Transport.TCP -> { socket?.outputStream?.write(pkt); capture(pkt, pkt.size) }
            Transport.USB -> { usbConn?.bulkTransfer(usbOut, pkt, pkt.size, 2000); capture(pkt, pkt.size) }
            Transport.AOA -> aoaWrap(pkt)
            Transport.NONE -> Unit
        }
        return s
    }

    private fun aoaWrap(pkt: ByteArray) {
        val frame = ByteArray(8 + pkt.size)
        frame[0] = 0x55.toByte(); frame[1] = 0xcc.toByte()
        frame[2] = 0x49.toByte(); frame[3] = 0x57.toByte()  // tag: DUML channel
        val len = pkt.size
        frame[4] = (len         and 0xff).toByte()
        frame[5] = (len ushr  8 and 0xff).toByte()
        frame[6] = (len ushr 16 and 0xff).toByte()
        frame[7] = (len ushr 24 and 0xff).toByte()
        pkt.copyInto(frame, 8)
        try { aoaOut?.write(frame) } catch (_: Exception) {}
        // Capture the outgoing frame exactly as wrapped, the existing captures/
        // sniffs are downlink-only (dst=2, all telemetry); this records our own
        // app→FC bytes too, so unverified opcodes (autoTakeoff/autoLand/RTH) can
        // finally be checked against a real FC response.
        capture(frame, frame.size)
    }

    // ── Disconnect ───────────────────────────────────────────────────────────

    fun disconnect() {
        transport = Transport.NONE
        socket?.close(); socket = null
        usbConn?.close(); usbConn = null; usbIn = null; usbOut = null
        aoaOut?.close(); aoaOut = null
        aoaPfd?.close(); aoaPfd = null
    }

    // ── RX loops ─────────────────────────────────────────────────────────────

    private fun dispatchAcc(acc: ByteArray): ByteArray {
        var off = 0
        while (off < acc.size) {
            val (frame, consumed) = parseFrame(acc, off)
            if (consumed == 0) break
            off += maxOf(consumed, 1)
            frame?.let { f -> listeners.forEach { it(f) } }
        }
        return if (off > 0) acc.copyOfRange(off, acc.size) else acc
    }

    private fun rxLoopTcp(s: Socket) {
        val buf = ByteArray(4096)
        var acc = byteArrayOf()
        try {
            val input = s.inputStream
            while (transport == Transport.TCP) {
                val n = input.read(buf)
                if (n < 0) break
                capture(buf, n)
                acc += buf.copyOf(n)
                acc = dispatchAcc(acc)
            }
        } catch (_: Exception) {}
        if (transport == Transport.TCP) transport = Transport.NONE
    }

    private fun rxLoopUsb(conn: UsbDeviceConnection, epIn: UsbEndpoint) {
        val buf = ByteArray(epIn.maxPacketSize.coerceAtLeast(512))
        var acc = byteArrayOf()
        try {
            while (transport == Transport.USB) {
                val n = conn.bulkTransfer(epIn, buf, buf.size, 100)
                if (n < 0) continue  // timeout, retry
                capture(buf, n)
                acc += buf.copyOf(n)
                acc = dispatchAcc(acc)
            }
        } catch (_: Exception) {}
        if (transport == Transport.USB) transport = Transport.NONE
    }

    private fun rxLoopAoa(pfd: ParcelFileDescriptor) {
        val buf = ByteArray(16384)
        var acc = byteArrayOf()
        try {
            val input = FileInputStream(pfd.fileDescriptor)
            while (transport == Transport.AOA) {
                val n = input.read(buf)
                if (n < 0) break
                capture(buf, n)
                acc += buf.copyOf(n)
                acc = dispatchAoaAcc(acc)
            }
        } catch (_: Exception) {}
        if (transport == Transport.AOA) transport = Transport.NONE
    }

    // Parse 55cc outer frames; dispatch DUML inner frames from 4957 payloads.
    // 4a57 (video) frames are silently consumed, video pipeline is separate.
    private fun dispatchAoaAcc(acc: ByteArray): ByteArray {
        var i = 0
        while (i + 8 <= acc.size) {
            if (acc[i] == 0x55.toByte() && acc[i + 1] == 0xcc.toByte()) {
                val len = (acc[i + 4].toInt() and 0xff) or
                          ((acc[i + 5].toInt() and 0xff) shl 8) or
                          ((acc[i + 6].toInt() and 0xff) shl 16) or
                          ((acc[i + 7].toInt() and 0xff) shl 24)
                if (len < 0 || len > 65536) { i++; continue }
                if (i + 8 + len > acc.size) break  // wait for more data
                val payload = acc.copyOfRange(i + 8, i + 8 + len)
                when {
                    acc[i + 2] == 0x49.toByte() && acc[i + 3] == 0x57.toByte() ->
                        dispatchAcc(payload)
                    acc[i + 2] == 0x4a.toByte() && acc[i + 3] == 0x57.toByte() ->
                        videoListeners.forEach { it(payload) }
                }
                i += 8 + len
            } else {
                i++
            }
        }
        return if (i > 0) acc.copyOfRange(i, acc.size) else acc
    }
}
