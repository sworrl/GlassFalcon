# GlassFalcon Encrypted Streaming, Relay Server Spec

This is the contract the **EncryptedStreamPlugin** (`StreamPublisher.kt`) speaks. Build a server to
this spec and the plugin streams to it with **zero server-side control needed**, the server is a
dumb, blind byte relay plus a tiny token API. It never sees plaintext video, never transcodes,
never records.

## Design in one paragraph

The phone AES-256-GCM-encrypts **each H.264 access unit itself**. The 256-bit content key is
generated on the phone, **never sent to the server**, and rides only in the viewer link's URL
`#fragment` (which browsers do not transmit in requests). The server relays opaque ciphertext from
the publisher's WebSocket to every viewer's WebSocket. The browser pulls the key out of the
fragment, decrypts with WebCrypto, and decodes with **WebCodecs** straight to a `<canvas>`. Because
the media is already end-to-end encrypted, the server needs no TLS-terminating media smarts, just
`wss://` and a map of rooms.

```
 Phone (plugin)                     Relay server (blind)                 Browser (watch.html)
 ─────────────                      ────────────────────                 ────────────────────
 drone H.264 AUs                    POST /api/stream/start  ──┐          GET /watch/<id>?t=…#k=<key>
   │ AES-256-GCM (key K)            (issues tokens, room)     │            │  key K from #fragment
   ▼                                                          │            ▼
 wss /ingest/<id>?t=<ingestTok> ──► fan-out ciphertext ──────►│──► wss /sub/<id>?t=<viewerTok>
                                    (never decrypts)                       │ WebCrypto decrypt(K)
 POST /api/stream/burn ───────────► delete room, close all                 ▼ WebCodecs → canvas
```

The server can decrypt **nothing**: it holds neither K nor any per-frame material beyond the
ciphertext it forwards.

---

## 1. HTTP token API

Two JSON endpoints. Auth is a single shared **publisher secret** (`Authorization: Bearer <secret>`)
that you configure on the server and enter once in the plugin. Viewers never authenticate with the
secret, they use the short-lived `viewerToken` minted per stream.

### `POST /api/stream/start`
Request:
```
Authorization: Bearer <publisherSecret>
Content-Type: application/json

{ "ttlSeconds": 0 }          // 0 = lifetime bound to the stream; burns on stop/disconnect
```
Response `200`:
```json
{
  "streamId":    "b1f9c3e2",     // opaque, unguessable (≥64 bits of entropy)
  "ingestToken": "…",            // authorises the publisher WS for this stream only
  "viewerToken": "…",            // authorises viewer WS(es) for this stream only
  "expiresAt":   1751760000      // optional unix seconds; a hard cap even if never burned
}
```
On bad/missing secret → `401`. The plugin surfaces `relay <code>: <body-snippet>` to the pilot.

`ttlSeconds > 0` (optional future use): also expire the room that many seconds after creation
regardless of stream state. `0` means "no fixed cap, burns on stop", the plugin's default.

### `POST /api/stream/burn`
```
Authorization: Bearer <publisherSecret>
{ "streamId": "b1f9c3e2" }
```
Immediately: invalidate both tokens, close the ingest + all viewer sockets, delete the room.
Response `200` (idempotent, burning an already-gone stream is still `200`). The plugin calls this
on **Stop**; you should also treat **publisher WS disconnect** as an implicit burn.

---

## 2. WebSocket relay

### `wss://<host>/ingest/<streamId>?t=<ingestToken>`
The publisher connects here. Validate `streamId` + `ingestToken` against the room; reject otherwise
(close code `4401`). Every **binary** message the publisher sends is forwarded **verbatim** to all
current viewers of that room. Ignore text messages. One publisher per room (reject a second).

### `wss://<host>/sub/<streamId>?t=<viewerToken>`
Viewers (the watch page) connect here. Validate `streamId` + `viewerToken`. Add the socket to the
room's viewer set; drop it on close. Forward nothing upstream, viewers are receive-only.

**The relay never inspects, buffers-to-disk, decrypts, or reorders payloads.** In-memory fan-out
only. If you want a tiny jitter buffer for late joiners you may cache *only the most recent
ciphertext INIT frame + the latest keyframe MEDIA frame* and replay those two to a newly-connected
viewer so it can start decoding before the next keyframe, but even that is optional (the publisher
resends INIT + keyframes periodically).

### `GET /watch/<streamId>?t=<viewerToken>`
Serve the static `watch.html` (below). It reads `streamId` from the path, `t` from the query, and
the key from the `#fragment`, then opens the `/sub/...` WebSocket. This page is identical for every
stream, it's just static hosting.

---

## 3. Wire format (what flows over the WebSockets)

Every binary WS message is one frame:

```
byte 0        : type   (0x01 = INIT, 0x02 = MEDIA)
bytes 1..12   : nonce  (12 bytes) = salt(4, per-stream random) ++ counter(8, big-endian, monotonic)
bytes 13..end : ciphertext = AES-256-GCM(key K, nonce, plaintext)   // 16-byte GCM tag appended
```

The `(key, nonce)` pair is never reused: the counter increments once per frame across **both** INIT
and MEDIA. GCM auth tag is 128-bit and included at the tail of the ciphertext (standard AEAD).

**Plaintext of an INIT frame** (`type 0x01`): UTF-8 JSON
```json
{ "codec": "avc1.4d0028" }   // WebCodecs codec string derived from the SPS (avc1.PPCCLL)
```
Sent on the first keyframe and roughly every 60 frames thereafter, so a mid-stream joiner can
`configure()` its decoder without the server ever knowing a viewer arrived.

**Plaintext of a MEDIA frame** (`type 0x02`):
```
bytes 0..7 : timestamp, microseconds, big-endian (monotonic; ~30 fps)
byte 8     : keyframe flag (1 = IDR/key, 0 = delta)
bytes 9..  : one Annex-B H.264 access unit (start-code-delimited NALs).
             Keyframe AUs are self-contained: SPS + PPS are prepended.
```

The relay treats all of this as opaque bytes. Only the phone and the browser ever parse it.

---

## 4. Ephemeral link + burn semantics (chosen behaviour)

- The **viewer link** is `https://<host>/watch/<streamId>?t=<viewerToken>#k=<base64url key>`.
  The `#k=` fragment holds the raw 32-byte AES key, base64url, no padding. Browsers never send the
  fragment to the server, so the key stays off the wire and out of your logs.
- **Token lifetime = the stream.** On **Stop** the plugin calls `/api/stream/burn`; the server drops
  the room and both tokens 410-Gone instantly. Also burn on publisher disconnect. Optionally honour
  `expiresAt` as a hard cap.
- No dashboard, no accounts, no server-side stream list required. Access is *only* the unguessable
  `streamId` + `viewerToken` + fragment key. Lose the link → lose access; burn → everyone's out.

Security notes to respect when you build it:
- Serve **only** over TLS (`https`/`wss`). The E2E layer protects the media, but TLS still protects
  the tokens and metadata.
- `streamId`, `ingestToken`, `viewerToken` must be cryptographically random and unguessable.
- Never log the `#fragment` (you'll never receive it, but don't reconstruct it client-side into any
  beacon either).
- Rate-limit `/api/stream/start` on the publisher secret.

---

## 5. Reference relay (Node.js, ~70 lines), optional starting point

This is a complete blind relay implementing the spec. Use it as-is or port to Go/whatever. Depends
on `express` and `ws`. Serves `watch.html` from the same directory.

```js
const crypto = require('crypto');
const express = require('express');
const { WebSocketServer } = require('ws');
const path = require('path');

const SECRET = process.env.PUBLISHER_SECRET || 'change-me';
const rooms = new Map(); // streamId -> { ingestTok, viewerTok, publisher, viewers:Set, expiresAt }
const rid = () => crypto.randomBytes(16).toString('hex');

const app = express();
app.use(express.json());

const auth = (req, res, next) =>
  req.get('authorization') === `Bearer ${SECRET}` ? next() : res.sendStatus(401);

app.post('/api/stream/start', auth, (req, res) => {
  const streamId = rid(), ingestTok = rid(), viewerTok = rid();
  const ttl = Number(req.body?.ttlSeconds) || 0;
  const expiresAt = ttl > 0 ? Math.floor(Date.now() / 1000) + ttl : 0;
  rooms.set(streamId, { ingestTok, viewerTok, publisher: null, viewers: new Set(), expiresAt });
  res.json({ streamId, ingestToken: ingestTok, viewerToken: viewerTok, expiresAt });
});

const burn = (id) => {
  const r = rooms.get(id); if (!r) return;
  r.publisher?.close(); r.viewers.forEach(v => v.close()); rooms.delete(id);
};
app.post('/api/stream/burn', auth, (req, res) => { burn(req.body?.streamId); res.sendStatus(200); });

app.get('/watch/:id', (req, res) => res.sendFile(path.join(__dirname, 'watch.html')));

const server = app.listen(process.env.PORT || 8080);
const wss = new WebSocketServer({ server });

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://x');
  const [, kind, id] = url.pathname.split('/');           // '', 'ingest'|'sub', '<streamId>'
  const tok = url.searchParams.get('t');
  const room = rooms.get(id);
  if (!room || (room.expiresAt && Date.now() / 1000 > room.expiresAt)) return ws.close(4401);

  if (kind === 'ingest') {
    if (tok !== room.ingestTok || room.publisher) return ws.close(4401);
    room.publisher = ws;
    ws.on('message', (data, isBinary) => {
      if (isBinary) room.viewers.forEach(v => v.readyState === 1 && v.send(data));
    });
    ws.on('close', () => burn(id));                        // publisher gone ⇒ burn
  } else if (kind === 'sub') {
    if (tok !== room.viewerTok) return ws.close(4401);
    room.viewers.add(ws);
    ws.on('close', () => room.viewers.delete(ws));
  } else ws.close(4404);
});
```

