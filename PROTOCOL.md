# NotchDrop wire protocol (v1)

Mac and phone talk directly over TCP on the local network: the same Wi-Fi, or the Mac on the phone's hotspot. No internet is involved.

| | Mac | Phone |
|---|---|---|
| Listen port | 47820 | 47821 |
| Bonjour / NSD | `_notchdrop._tcp`, TXT `id=<uuid> role=mac` | `_notchdrop._tcp`, TXT `id=<uuid> role=phone` |

## Pairing

The Mac shows a QR code that encodes a single-use 32-byte key:

```
notchdrop://pair?v=1&id=<macId>&n=<name>&k=<base64url key, no padding>&p=47820&a=<ip,ip>
```

The phone connects to each address in `a` in `pair` mode using that key. After the hellos succeed, both sides store each other's id, name and key, plus the last-known addresses. The key lives only in app-private storage.

## Framing

Every frame is a `u32` big-endian length followed by the payload. Frames are at most 4 MB.

## Handshake (plaintext JSON frames)

```
client → {"v":1, "mode":"pair"|"session", "from":"<id>", "nonce":"<b64 32B>"}
server → {"v":1, "from":"<id>", "nonce":"<b64 32B>"}   or   {"error":"…"}
```

Each side derives its keys as follows:

- `c2s = HKDF-SHA256(key, salt = clientNonce‖serverNonce, info = "notchdrop c2s", 32)`
- `s2c` is the same with `info = "notchdrop s2c"`.

Every later frame is AES-256-GCM `ciphertext‖tag`. The nonce is `00000000 ‖ u64be counter`, with a separate counter per direction starting at 0. A wrong key fails on the first frame. Test vectors: `NotchDrop --vectors`, asserted in `ProtocolTest.kt`.

## Messages (inside encryption)

The first plaintext byte gives the message type: `0x01` is UTF-8 JSON, `0x02` is a raw file chunk (up to 256 KB).

```
C: hello {t, id, name, platform, addrs[], port}     S: hello {…}
C: offer {items:[{kind:"file",name,size,mime} | {kind:"text",text}]}
S: accept
C: <chunks of file 1> file_end {sha256}  … for each file in order
C: done                                              S: received {ok:true}
C: ping → S: pong        C: bye        either: error {message}
```

The receiver writes each file to a temp file, checks its SHA-256, then moves it into place. On the Mac that is `~/Downloads/From <phone>`; on Android it is `Download/From <Mac>` via MediaStore.

## Reaching the other device

A connection attempt races all known candidates in parallel and keeps the first that authenticates. Candidates come from:

- Bonjour/NSD results
- the addresses in the peer's last hello
- on the Mac only, the default gateway, which is the phone when the Mac is on its hotspot

Each hello carries the sender's current IPv4 addresses, so either side refreshes the other's view whenever it connects.

- The Mac pings every 45 s and whenever its network changes.
- The phone pings when Wi-Fi changes or the app opens.
- Android binds sockets to the Wi-Fi network when the target is on its subnet, so Wi-Fi without internet still works.

## Interop tests

`mac/.build/debug/NotchDrop --interop-server <keyhex>` and `--interop-send <port> <keyhex> <file> <text>` are driven by `android/app/src/test/.../InteropTest.kt`. That test exercises Android→Mac and Mac→Android against the real Swift implementation.
