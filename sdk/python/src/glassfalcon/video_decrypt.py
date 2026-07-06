# GlassFalcon, authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

"""
DJI wm240 port 8914 video stream reader and AES-CTR decryptor.

Frame format (all little-endian):
  [0:2]   magic: 55 06 (video) or 55 07 (audio/meta)
  [2:4]   00 00 (reserved)
  [4:8]   uint32 total frame size including this 16-byte header
  [8:12]  uint32 frame counter / session timestamp
  [12:16] uint32 flags (01 00 00 00 for video)
  [16:]   AES-128-CTR encrypted payload

AES-CTR key comes from the MSDK handshake on port 10000.
Use msdk_mitm.py to capture the session key when DJI Go4 connects.

Without a key: frames are saved as raw .enc files for later decryption.
With a key: frames are decrypted and written as raw H.264 Annex B to stdout
            or a pipe, ready for ffmpeg -i pipe:0 to consume.
"""

import socket
import struct
import threading
import time
from pathlib import Path
from typing import Callable, Optional

HOST = "192.168.42.2"
PORT = 8914
MAGIC_VIDEO = (0x55, 0x06)
MAGIC_META  = (0x55, 0x07)
HDR_LEN     = 16


def _parse_hdr(buf: bytes, off: int) -> Optional[dict]:
    if off + HDR_LEN > len(buf):
        return None
    if buf[off] != 0x55 or buf[off+1] not in (0x06, 0x07, 0x08, 0x09):
        return None
    total = struct.unpack_from("<I", buf, off+4)[0]
    if total < HDR_LEN or total > 65536:
        return None
    return {
        "type":    buf[off+1],
        "total":   total,
        "counter": struct.unpack_from("<I", buf, off+8)[0],
        "flags":   struct.unpack_from("<I", buf, off+12)[0],
    }


class Port8914Reader:
    """
    Connects to 192.168.42.2:8914 and parses the multiplexed video stream.

    Usage without key (capture for later):
        reader = Port8914Reader()
        reader.on_raw_frame(lambda hdr, payload: save(hdr, payload))
        reader.start()

    Usage with key (decrypt inline):
        reader = Port8914Reader(aes_key=bytes.fromhex("...32hex..."))
        reader.on_h264_data(lambda data: pipe.write(data))
        reader.start()
    """

    def __init__(self, host: str = HOST, port: int = PORT,
                 aes_key: Optional[bytes] = None):
        self._host    = host
        self._port    = port
        self._key     = aes_key
        self._sock    = None
        self._thread  = None
        self._running = False
        self._raw_cbs: list[Callable] = []   # fn(header_dict, payload_bytes)
        self._h264_cbs: list[Callable] = []  # fn(h264_bytes)
        self._log_cb: Optional[Callable] = None
        self._frame_count  = 0
        self._video_count  = 0
        self._bytes_in     = 0
        self._first_key    = None   # counter value at first frame (for CTR base)

    # ── callbacks ─────────────────────────────────────────────────────────────

    def on_raw_frame(self, fn: Callable):
        """Called with (header_dict, payload_bytes) for EVERY frame."""
        self._raw_cbs.append(fn)

    def on_h264_data(self, fn: Callable):
        """Called with decrypted H.264 bytes. Requires aes_key to be set."""
        self._h264_cbs.append(fn)

    def on_log(self, fn: Callable):
        self._log_cb = fn

    def set_key(self, key: bytes):
        """Set or update the AES-128-CTR key at runtime."""
        if len(key) not in (16, 24, 32):
            raise ValueError("AES key must be 16, 24, or 32 bytes")
        self._key = key
        self._first_key = None  # reset CTR base
        self._log(f"[8914] AES key set: {key.hex()[:16]}...")

    # ── lifecycle ─────────────────────────────────────────────────────────────

    def start(self):
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self):
        self._running = False
        if self._sock:
            try:
                self._sock.close()
            except OSError:
                pass

    def is_running(self) -> bool:
        return self._running and self._thread is not None and self._thread.is_alive()

    @property
    def stats(self) -> dict:
        return {
            "frames_total":  self._frame_count,
            "frames_video":  self._video_count,
            "bytes_in":      self._bytes_in,
            "key_loaded":    self._key is not None,
        }

    # ── internal ──────────────────────────────────────────────────────────────

    def _log(self, msg):
        if self._log_cb:
            self._log_cb(msg)

    def _run(self):
        while self._running:
            try:
                self._connect_and_read()
            except Exception as e:
                self._log(f"[8914] error: {e}")
            if self._running:
                time.sleep(2.0)
                self._log("[8914] reconnecting…")

    def _connect_and_read(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(10.0)
        sock.connect((self._host, self._port))
        self._sock = sock
        sock.settimeout(5.0)
        self._log(f"[8914] connected to {self._host}:{self._port}")

        buf = b""
        while self._running:
            try:
                chunk = sock.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                break
            buf += chunk
            self._bytes_in += len(chunk)

            i = 0
            while i < len(buf) - HDR_LEN:
                hdr = _parse_hdr(buf, i)
                if hdr is None:
                    i += 1
                    continue
                total = hdr["total"]
                if i + total > len(buf):
                    break
                payload = buf[i + HDR_LEN: i + total]
                self._dispatch(hdr, payload)
                i += total
            buf = buf[i:]

        sock.close()
        self._sock = None
        self._log("[8914] disconnected")

    def _dispatch(self, hdr: dict, payload: bytes):
        self._frame_count += 1

        for cb in self._raw_cbs:
            try:
                cb(hdr, payload)
            except Exception:
                pass

        if hdr["type"] == 0x06 and self._h264_cbs:
            self._video_count += 1
            data = self._decrypt(hdr, payload)
            if data:
                for cb in self._h264_cbs:
                    try:
                        cb(data)
                    except Exception:
                        pass

    def _decrypt(self, hdr: dict, payload: bytes) -> Optional[bytes]:
        if not self._key or not payload:
            return None
        try:
            from Crypto.Cipher import AES
        except ImportError:
            self._log("[8914] pycryptodome not installed, pip install pycryptodome")
            return None

        # AES-128-CTR: each frame starts at a unique counter block.
        # The frame counter field gives the session-relative position.
        # CTR nonce = first 12 bytes zeros, counter = frame counter (4 bytes LE).
        # Within a frame, counter increments by 1 per 16 bytes.
        ctr = hdr["counter"]
        nonce = b"\x00" * 8
        ctr_bytes = struct.pack("<I", ctr) + b"\x00" * 4
        initial_value = struct.unpack(">Q", ctr_bytes[:8])[0]

        try:
            cipher = AES.new(self._key, AES.MODE_CTR,
                             nonce=nonce,
                             initial_value=initial_value)
            return cipher.decrypt(payload)
        except Exception as e:
            self._log(f"[8914] decrypt error: {e}")
            return None


class EncryptedCapture:
    """
    Save the raw encrypted 8914 stream to disk for offline decryption once
    the key is recovered.  Format: each frame preceded by its 16-byte header.
    """

    def __init__(self, path: Path):
        self._path = path
        self._fh   = None
        self._lock = threading.Lock()

    def open(self):
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._fh = open(self._path, "wb")

    def write_frame(self, hdr: dict, payload: bytes):
        raw_hdr = (
            bytes([0x55, hdr["type"], 0x00, 0x00]) +
            struct.pack("<III", hdr["total"], hdr["counter"], hdr["flags"])
        )
        with self._lock:
            if self._fh:
                self._fh.write(raw_hdr + payload)

    def close(self):
        with self._lock:
            if self._fh:
                self._fh.close()
                self._fh = None


def decrypt_capture(enc_path: Path, key: bytes, out_path: Path):
    """
    Offline decryption of a saved .enc file.
    Writes raw H.264 Annex B to out_path, pipe through ffmpeg to view/mux.

    ffmpeg -i decrypted.h264 -c copy out.mp4
    """
    from Crypto.Cipher import AES
    if len(key) not in (16, 24, 32):
        raise ValueError("Key must be 16/24/32 bytes")

    frames_ok = frames_err = 0
    with open(enc_path, "rb") as fi, open(out_path, "wb") as fo:
        while True:
            hdr_raw = fi.read(HDR_LEN)
            if len(hdr_raw) < HDR_LEN:
                break
            if hdr_raw[0] != 0x55 or hdr_raw[1] not in (0x06, 0x07):
                # re-sync: skip a byte and retry
                continue
            total   = struct.unpack_from("<I", hdr_raw, 4)[0]
            counter = struct.unpack_from("<I", hdr_raw, 8)[0]
            frame_type = hdr_raw[1]
            payload_len = total - HDR_LEN
            payload = fi.read(payload_len)
            if len(payload) < payload_len:
                break
            if frame_type != 0x06:
                continue  # skip audio/meta frames

            nonce = b"\x00" * 8
            ctr_bytes = struct.pack("<I", counter) + b"\x00" * 4
            initial_value = struct.unpack(">Q", ctr_bytes[:8])[0]
            cipher = AES.new(key, AES.MODE_CTR,
                             nonce=nonce, initial_value=initial_value)
            try:
                fo.write(cipher.decrypt(payload))
                frames_ok += 1
            except Exception:
                frames_err += 1

    print(f"Decrypted {frames_ok} frames ({frames_err} errors) → {out_path}")
