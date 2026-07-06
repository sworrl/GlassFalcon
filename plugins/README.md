# Plugins

GlassFalcon plugins are optional features, toggled per device, that not every pilot wants shipped on. A plugin has up to two halves:

- **In-app (Kotlin).** The part that runs inside the Android app, registered in `PluginRegistry`. It lives in the app module at `android/app/src/main/kotlin/dev/glassfalcon/core/plugin/`, because it compiles into the app.
- **Server / companion.** Anything that runs off the phone (a relay server, a viewer page, a spec). That lives here, one directory per plugin.

## Shipped plugins

### encrypted-stream

End-to-end-encrypted re-streaming of the drone's live video. The phone AES-256-GCM-encrypts each H.264 access unit, a blind relay forwards ciphertext only, and a browser decrypts with a key carried in the viewer link's fragment, then decodes with WebCodecs.

- [`encrypted-stream/SERVER_SPEC.md`](encrypted-stream/SERVER_SPEC.md): the relay contract (token API, wire format, a reference Node relay).
- [`encrypted-stream/watch.html`](encrypted-stream/watch.html): the reference viewer page.
- In-app source: `android/app/src/main/kotlin/dev/glassfalcon/core/plugin/stream/`.

## Local plugins (private, not committed)

`plugins/local/` is gitignored; nothing under it is uploaded. Put private or customized plugins there. If it contains a `kotlin/` subdirectory, the Android app build adds it as an extra Kotlin source directory, so local plugin source compiles into a local build. Register a local plugin by adding it to `PluginRegistry.all` in your working tree.
