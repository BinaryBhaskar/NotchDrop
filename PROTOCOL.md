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
C: offer {id?, items:[{kind:"file",name,size,mime} | {kind:"text",text}]}
S: accept {resume?:[offset, …]}
C: <chunks of file 1> file_end {sha256}  … for each file in order
C: done                                              S: received {ok:true}
C: ping → S: pong        C: bye        either: error {message}
```

Instead of `accept`, the receiver may answer an offer with `error {message}`, for example when the user declines in the Mac's "Ask before receiving" prompt. The sender then reports that message and closes the connection. The Mac auto-declines after 45 s, well inside Android's 60 s read timeout.

### Cancelling and lost connections

Either side can stop a transfer that is underway. It sends `error {message, cancelled:true}`, where `message` is what the other side shows (for example "Stopped on MacBook Pro."), and then closes the connection. The other side shows "Transfer cancelled" with that message, and the receiver deletes any partial file.

- Once the receiver sends `accept`, the sender keeps reading the receiver's reply while it streams chunks. A cancel from the receiver therefore stops the sender immediately, without waiting for `done`.
- A transfer that makes no progress for **20 s** counts as failed with "Connection lost". This covers a dropped Wi-Fi network, which can leave a TCP socket open with nothing moving. Both apps apply this limit:
  - to every chunk read and chunk write;
  - to the wait for the final `received`.

Older versions send no `cancelled` flag. They treat the frame as a normal error and still stop.

The receiver writes each file to a temp file, checks its SHA-256, then moves it into place. On the Mac that is `~/Downloads/From <phone>`; on Android it is `Download/From <Mac>` via MediaStore.

### Resuming an interrupted transfer

An offer may carry an `id`, which the sender keeps for every retry of the same batch. When a transfer with an `id` fails for any reason other than a cancel, the receiver keeps what it has:
- the partial file it was writing;
- the files of that batch it already finished.

Each one has to have passed its checksum. The sender's app then offers a **Resume** button; nothing resumes on its own.

If the next offer has the same `id` and the same items (names, sizes, kinds and texts), the receiver's `accept` carries `resume`, one entry per item:
- `-1`: the receiver already has this item. The sender sends nothing for it: no chunks and no `file_end`.
- `n ≥ 0`: the receiver holds the first `n` bytes. The sender sends only the rest. It hashes those first `n` bytes from its own copy, so `file_end`'s SHA-256 still covers the whole file. If the file is now shorter than `n`, the sender fails with "… changed since the last attempt". Texts are always `0`.

If the checksum fails, the receiver throws that part away. Any other offer, or a cancel, discards everything kept. An offer without `id` gets a plain `accept`, so older versions are unaffected.

Kept files survive a restart:
- The Mac records them in `~/Library/Application Support/NotchDrop/resume.json`. Its partial files are hidden `.<uuid>.ndpart` files in the save folder.
- Android records them in its private `resume.json`. Its partial files are MediaStore downloads still marked pending.

At startup each app deletes leftover partial files that the kept batch doesn't need.

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

`InteropTest.kt` (in `android/app/src/test/…`) drives the real Swift implementation through two commands:
- `mac/.build/debug/NotchDrop --interop-server <keyhex> [cancel-after-bytes|-] [resume]`
- `mac/.build/debug/NotchDrop --interop-send <port> <keyhex> <file> <text> [cancel-after-bytes|-] [batch-id] [drop-after-bytes]`

It covers:
- transfers in both directions;
- a cancel from each side, in both directions;
- a sender that goes silent partway through, which the Mac must abandon after the 20 s limit;
- a dropped 48 MB transfer resumed in each direction, which must pick up where it stopped and arrive with the right checksum.
