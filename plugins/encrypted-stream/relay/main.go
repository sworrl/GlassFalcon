// GlassFalcon encrypted-stream relay — implements SERVER_SPEC.md.
//
// A blind byte relay plus a tiny token API. It never sees plaintext video:
// every frame arrives AES-256-GCM-encrypted by the phone and is fanned out
// verbatim to viewers. The only cleartext byte the relay ever reads is the
// frame-type prefix (0x01 INIT / 0x02 MEDIA) so it can replay the latest
// INIT frame to late joiners. The keyframe flag lives inside the ciphertext,
// so (deliberately) no keyframe caching is possible — viewers wait for the
// publisher's next keyframe (~2s).
//
// Deployed on shodan behind Caddy at drone.falcontechnix.com:
//   /api/stream/start, /api/stream/burn  (Bearer publisher secret)
//   /ingest/<id>?t=…  /sub/<id>?t=…      (WebSocket)
//   /watch/<id>?t=…#k=…                  (static viewer page, embedded)
//
// Env: GF_PUBLISHER_SECRET (required), GF_LISTEN (default 127.0.0.1:8090)
package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

//go:embed watch.html
var watchHTML []byte

const (
	maxFrameBytes   = 8 << 20 // one encrypted access unit; 8MB is generous headroom
	viewerQueueLen  = 256     // per-viewer send queue; overflow = slow consumer, dropped
	maxViewers      = 32
	writeWait       = 10 * time.Second
	pingPeriod      = 30 * time.Second
	pongWait        = 75 * time.Second
	closeUnauth     = 4401
	startRatePerMin = 12
)

type viewer struct {
	conn *websocket.Conn
	send chan []byte
	once sync.Once
}

func (v *viewer) close() { v.once.Do(func() { close(v.send) }) }

type room struct {
	ingestTok string
	viewerTok string
	expiresAt int64 // unix seconds; 0 = no cap

	mu        sync.Mutex
	publisher *websocket.Conn
	viewers   map[*viewer]struct{}
	lastInit  []byte // latest type-0x01 ciphertext frame, replayed to late joiners
	burned    bool
}

type relay struct {
	secretHash [32]byte // sha256 of publisher secret; compared constant-time
	mu         sync.Mutex
	rooms      map[string]*room

	rlMu     sync.Mutex
	rlTokens float64
	rlLast   time.Time
}

func newID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

func (rl *relay) authorized(r *http.Request) bool {
	h := r.Header.Get("Authorization")
	const p = "Bearer "
	if !strings.HasPrefix(h, p) {
		return false
	}
	got := sha256.Sum256([]byte(strings.TrimPrefix(h, p)))
	return subtle.ConstantTimeCompare(got[:], rl.secretHash[:]) == 1
}

// allowStart is a small token bucket over /api/stream/start.
func (rl *relay) allowStart() bool {
	rl.rlMu.Lock()
	defer rl.rlMu.Unlock()
	now := time.Now()
	rl.rlTokens += now.Sub(rl.rlLast).Minutes() * startRatePerMin
	if rl.rlTokens > startRatePerMin {
		rl.rlTokens = startRatePerMin
	}
	rl.rlLast = now
	if rl.rlTokens < 1 {
		return false
	}
	rl.rlTokens--
	return true
}

func (rl *relay) handleStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !rl.authorized(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if !rl.allowStart() {
		http.Error(w, "rate limited", http.StatusTooManyRequests)
		return
	}
	var body struct {
		TTLSeconds int64 `json:"ttlSeconds"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&body)

	rm := &room{
		ingestTok: newID(),
		viewerTok: newID(),
		viewers:   map[*viewer]struct{}{},
	}
	if body.TTLSeconds > 0 {
		rm.expiresAt = time.Now().Unix() + body.TTLSeconds
	}
	id := newID()
	rl.mu.Lock()
	rl.rooms[id] = rm
	n := len(rl.rooms)
	rl.mu.Unlock()
	slog.Info("stream started", "streamId", id, "rooms", n)

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"streamId":    id,
		"ingestToken": rm.ingestTok,
		"viewerToken": rm.viewerTok,
		"expiresAt":   rm.expiresAt,
	})
}

func (rl *relay) burn(id string) {
	rl.mu.Lock()
	rm := rl.rooms[id]
	delete(rl.rooms, id)
	rl.mu.Unlock()
	if rm == nil {
		return
	}
	rm.mu.Lock()
	rm.burned = true
	pub := rm.publisher
	rm.publisher = nil
	vs := rm.viewers
	rm.viewers = map[*viewer]struct{}{}
	rm.mu.Unlock()
	if pub != nil {
		_ = pub.Close()
	}
	for v := range vs {
		v.close()
	}
	slog.Info("stream burned", "streamId", id)
}

func (rl *relay) handleBurn(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !rl.authorized(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var body struct {
		StreamID string `json:"streamId"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&body)
	rl.burn(body.StreamID) // idempotent: burning a gone stream is still 200
	w.WriteHeader(http.StatusOK)
}

func (rl *relay) room(id string) *room {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	rm := rl.rooms[id]
	if rm != nil && rm.expiresAt > 0 && time.Now().Unix() > rm.expiresAt {
		return nil // janitor will burn it
	}
	return rm
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  64 << 10,
	WriteBufferSize: 64 << 10,
	// Publishers are native apps; viewers hold an unguessable per-stream
	// token that IS the credential. Origin adds nothing here.
	CheckOrigin: func(*http.Request) bool { return true },
}

func wsReject(w http.ResponseWriter, r *http.Request) {
	if c, err := upgrader.Upgrade(w, r, nil); err == nil {
		_ = c.WriteControl(websocket.CloseMessage,
			websocket.FormatCloseMessage(closeUnauth, "unauthorized"), time.Now().Add(writeWait))
		_ = c.Close()
	}
}

func (rl *relay) handleIngest(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/ingest/")
	rm := rl.room(id)
	if rm == nil || subtle.ConstantTimeCompare([]byte(r.URL.Query().Get("t")), []byte(rm.ingestTok)) != 1 {
		wsReject(w, r)
		return
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	rm.mu.Lock()
	if rm.burned || rm.publisher != nil { // one publisher per room
		rm.mu.Unlock()
		_ = conn.WriteControl(websocket.CloseMessage,
			websocket.FormatCloseMessage(closeUnauth, "publisher exists"), time.Now().Add(writeWait))
		_ = conn.Close()
		return
	}
	rm.publisher = conn
	rm.mu.Unlock()
	slog.Info("publisher connected", "streamId", id)

	conn.SetReadLimit(maxFrameBytes)
	_ = conn.SetReadDeadline(time.Now().Add(pongWait))
	conn.SetPongHandler(func(string) error { return conn.SetReadDeadline(time.Now().Add(pongWait)) })
	stopPing := make(chan struct{})
	go func() {
		t := time.NewTicker(pingPeriod)
		defer t.Stop()
		for {
			select {
			case <-t.C:
				_ = conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(writeWait))
			case <-stopPing:
				return
			}
		}
	}()

	for {
		mt, data, err := conn.ReadMessage()
		if err != nil {
			break
		}
		_ = conn.SetReadDeadline(time.Now().Add(pongWait))
		if mt != websocket.BinaryMessage || len(data) == 0 {
			continue // spec: ignore text messages
		}
		rm.mu.Lock()
		if len(data) > 0 && data[0] == 0x01 { // INIT — cache for late joiners
			rm.lastInit = data
		}
		for v := range rm.viewers {
			select {
			case v.send <- data:
			default: // slow consumer: drop the viewer, never stall the stream
				delete(rm.viewers, v)
				v.close()
			}
		}
		rm.mu.Unlock()
	}
	close(stopPing)
	rl.burn(id) // publisher gone ⇒ burn (spec §4)
}

func (rl *relay) handleSub(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/sub/")
	rm := rl.room(id)
	if rm == nil || subtle.ConstantTimeCompare([]byte(r.URL.Query().Get("t")), []byte(rm.viewerTok)) != 1 {
		wsReject(w, r)
		return
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	v := &viewer{conn: conn, send: make(chan []byte, viewerQueueLen)}
	rm.mu.Lock()
	if rm.burned || len(rm.viewers) >= maxViewers {
		rm.mu.Unlock()
		_ = conn.Close()
		return
	}
	rm.viewers[v] = struct{}{}
	if rm.lastInit != nil { // instant decoder config for late joiners
		v.send <- rm.lastInit
	}
	n := len(rm.viewers)
	rm.mu.Unlock()
	slog.Info("viewer joined", "streamId", id, "viewers", n)

	// Writer: drain queue; ping keeps the tunnel path alive pre-stream.
	go func() {
		t := time.NewTicker(pingPeriod)
		defer t.Stop()
		defer conn.Close()
		for {
			select {
			case data, ok := <-v.send:
				if !ok {
					_ = conn.WriteControl(websocket.CloseMessage,
						websocket.FormatCloseMessage(websocket.CloseNormalClosure, "stream ended"),
						time.Now().Add(writeWait))
					return
				}
				_ = conn.SetWriteDeadline(time.Now().Add(writeWait))
				if err := conn.WriteMessage(websocket.BinaryMessage, data); err != nil {
					return
				}
			case <-t.C:
				_ = conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(writeWait))
			}
		}
	}()

	// Reader: viewers are receive-only; this just surfaces close/pong.
	conn.SetReadLimit(4096)
	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			break
		}
	}
	rm.mu.Lock()
	delete(rm.viewers, v)
	rm.mu.Unlock()
	v.close()
}

func (rl *relay) handleWatch(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer") // never leak ?t= via referer
	w.Header().Set("X-Robots-Tag", "noindex, nofollow")
	_, _ = w.Write(watchHTML)
}

func (rl *relay) janitor() {
	for range time.Tick(30 * time.Second) {
		now := time.Now().Unix()
		rl.mu.Lock()
		var expired []string
		for id, rm := range rl.rooms {
			if rm.expiresAt > 0 && now > rm.expiresAt {
				expired = append(expired, id)
			}
		}
		rl.mu.Unlock()
		for _, id := range expired {
			rl.burn(id)
		}
	}
}

func main() {
	secret := os.Getenv("GF_PUBLISHER_SECRET")
	if secret == "" {
		slog.Error("GF_PUBLISHER_SECRET is required")
		os.Exit(1)
	}
	listen := os.Getenv("GF_LISTEN")
	if listen == "" {
		listen = "127.0.0.1:8090"
	}
	rl := &relay{secretHash: sha256.Sum256([]byte(secret)), rooms: map[string]*room{}, rlTokens: startRatePerMin, rlLast: time.Now()}
	go rl.janitor()

	mux := http.NewServeMux()
	mux.HandleFunc("/api/stream/start", rl.handleStart)
	mux.HandleFunc("/api/stream/burn", rl.handleBurn)
	mux.HandleFunc("/ingest/", rl.handleIngest)
	mux.HandleFunc("/sub/", rl.handleSub)
	mux.HandleFunc("/watch/", rl.handleWatch)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		rl.mu.Lock()
		n := len(rl.rooms)
		rl.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "rooms": n})
	})

	slog.Info("glassfalcon relay listening", "addr", listen)
	srv := &http.Server{Addr: listen, Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	if err := srv.ListenAndServe(); err != nil {
		slog.Error("server exited", "err", err)
		os.Exit(1)
	}
}
