// e2etest — exercises a deployed relay end-to-end exactly like the phone +
// browser do: start → ingest (encrypted INIT+MEDIA) → two subscribers (one
// late, must get the cached INIT) → burn → verify lockout.
//
//	go run ./cmd/e2etest -base https://drone.falcontechnix.com -secret …
package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

func die(f string, a ...any) { fmt.Printf("FAIL: "+f+"\n", a...); os.Exit(1) }
func ok(f string, a ...any)  { fmt.Printf("ok: "+f+"\n", a...) }

func encFrame(gcm cipher.AEAD, salt []byte, ctr uint64, typ byte, plain []byte) []byte {
	nonce := make([]byte, 12)
	copy(nonce, salt)
	binary.BigEndian.PutUint64(nonce[4:], ctr)
	out := []byte{typ}
	out = append(out, nonce...)
	return append(out, gcm.Seal(nil, nonce, plain, nil)...)
}

func main() {
	base := flag.String("base", "http://127.0.0.1:8090", "relay base URL")
	secret := flag.String("secret", "", "publisher secret")
	flag.Parse()
	wsBase := strings.Replace(strings.Replace(*base, "https://", "wss://", 1), "http://", "ws://", 1)

	// 1) start
	req, _ := http.NewRequest("POST", *base+"/api/stream/start", strings.NewReader(`{"ttlSeconds":0}`))
	req.Header.Set("Authorization", "Bearer "+*secret)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != 200 {
		die("start: err=%v code=%v", err, resp)
	}
	var s struct{ StreamId, IngestToken, ViewerToken string }
	_ = json.NewDecoder(resp.Body).Decode(&s)
	ok("start → streamId=%s", s.StreamId)

	// bad secret must 401
	req2, _ := http.NewRequest("POST", *base+"/api/stream/start", strings.NewReader(`{}`))
	req2.Header.Set("Authorization", "Bearer wrong")
	r2, _ := http.DefaultClient.Do(req2)
	if r2.StatusCode != 401 {
		die("bad secret got %d, want 401", r2.StatusCode)
	}
	ok("bad publisher secret → 401")

	// 2) publisher connects; bad ingest token must be rejected
	if c, _, err := websocket.DefaultDialer.Dial(wsBase+"/ingest/"+s.StreamId+"?t=nope", nil); err == nil {
		if _, _, err := c.ReadMessage(); err == nil {
			die("bad ingest token accepted")
		}
		c.Close()
	}
	ok("bad ingest token → rejected")

	pub, _, err := websocket.DefaultDialer.Dial(wsBase+"/ingest/"+s.StreamId+"?t="+s.IngestToken, nil)
	if err != nil {
		die("ingest dial: %v", err)
	}

	// AES-256-GCM like the phone
	key := make([]byte, 32)
	salt := make([]byte, 4)
	_, _ = rand.Read(key)
	_, _ = rand.Read(salt)
	blk, _ := aes.NewCipher(key)
	gcm, _ := cipher.NewGCM(blk)

	initFrame := encFrame(gcm, salt, 0, 0x01, []byte(`{"codec":"avc1.4d0028"}`))
	media := make([]byte, 9+1024)
	media[8] = 1
	_, _ = rand.Read(media[9:])
	mediaFrame := encFrame(gcm, salt, 1, 0x02, media)

	// 3) viewer 1 joins BEFORE frames flow
	sub1, _, err := websocket.DefaultDialer.Dial(wsBase+"/sub/"+s.StreamId+"?t="+s.ViewerToken, nil)
	if err != nil {
		die("sub1 dial: %v", err)
	}
	time.Sleep(300 * time.Millisecond)

	_ = pub.WriteMessage(websocket.BinaryMessage, initFrame)
	_ = pub.WriteMessage(websocket.BinaryMessage, mediaFrame)

	expect := func(c *websocket.Conn, want []byte, label string) {
		_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))
		_, got, err := c.ReadMessage()
		if err != nil {
			die("%s read: %v", label, err)
		}
		if !bytes.Equal(got, want) {
			die("%s: bytes differ (%d vs %d)", label, len(got), len(want))
		}
		ok("%s relayed verbatim (%dB)", label, len(got))
	}
	expect(sub1, initFrame, "sub1 INIT")
	expect(sub1, mediaFrame, "sub1 MEDIA")

	// decrypt check — proves E2E layout survives the relay untouched
	nonce := mediaFrame[1:13]
	plain, err := gcm.Open(nil, nonce, mediaFrame[13:], nil)
	if err != nil || !bytes.Equal(plain, media) {
		die("decrypt-after-relay failed: %v", err)
	}
	ok("ciphertext decrypts after relay → E2E intact")

	// 4) LATE viewer must get the cached INIT immediately
	sub2, _, err := websocket.DefaultDialer.Dial(wsBase+"/sub/"+s.StreamId+"?t="+s.ViewerToken, nil)
	if err != nil {
		die("sub2 dial: %v", err)
	}
	expect(sub2, initFrame, "late-joiner cached INIT")

	// 5) burn → viewers closed, rejoin rejected
	breq, _ := http.NewRequest("POST", *base+"/api/stream/burn",
		strings.NewReader(fmt.Sprintf(`{"streamId":%q}`, s.StreamId)))
	breq.Header.Set("Authorization", "Bearer "+*secret)
	breq.Header.Set("Content-Type", "application/json")
	br, err := http.DefaultClient.Do(breq)
	if err != nil || br.StatusCode != 200 {
		die("burn: %v %v", err, br)
	}
	ok("burn → 200")

	_ = sub1.SetReadDeadline(time.Now().Add(10 * time.Second))
	if _, _, err := sub1.ReadMessage(); err == nil {
		die("sub1 still open after burn")
	}
	ok("viewers disconnected on burn")

	if c, _, err := websocket.DefaultDialer.Dial(wsBase+"/sub/"+s.StreamId+"?t="+s.ViewerToken, nil); err == nil {
		_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
		if _, _, err := c.ReadMessage(); err == nil {
			die("rejoin after burn accepted")
		}
		c.Close()
	}
	ok("rejoin after burn → rejected")

	// burn is idempotent
	breq2, _ := http.NewRequest("POST", *base+"/api/stream/burn",
		strings.NewReader(fmt.Sprintf(`{"streamId":%q}`, s.StreamId)))
	breq2.Header.Set("Authorization", "Bearer "+*secret)
	if br2, _ := http.DefaultClient.Do(breq2); br2.StatusCode != 200 {
		die("second burn got %d, want 200", br2.StatusCode)
	}
	ok("burn idempotent")

	pub.Close()
	fmt.Println("\nALL PASS")
}
