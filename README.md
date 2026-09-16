# udp-file-transfer — Stage 1: Basic UDP Loopback

Status: Stage 1 of 15 (see project handoff doc). This proves raw UDP
send/receive works before any protocol logic is built on top of it.

## Prerequisites

- **JDK 17+** (not just a JRE — you need `javac`). Check with:
  ```
  java -version
  javac -version
  ```
  If `javac` is missing, install a JDK (e.g. Eclipse Temurin 17 or 21) and
  make sure VS Code's Java extension points at it (Command Palette →
  "Java: Configure Java Runtime").
- **Maven** — VS Code's "Extension Pack for Java" bundles Maven support, or
  install standalone and check with `mvn -version`.
- **VS Code extensions**: "Extension Pack for Java" (includes Maven, Test
  Runner, Debugger).

## Project layout so far

```
udp-file-transfer/
├── pom.xml
├── src/main/java/nettransfer/
│   ├── Main.java              <- Stage 1 throwaway sender/receiver
│   └── net/UdpChannel.java    <- thin DatagramSocket wrapper (permanent)
├── src/test/java/nettransfer/net/UdpChannelTest.java
└── experiments/               <- empty for now, used from Stage 14
```

Every other package (`protocol`, `integrity`, `transfer`, `metrics`,
`control`, `llm`, `cli`) exists as an empty folder, ready for later stages.

## How to build

From the `udp-file-transfer/` folder:

```
mvn compile
```

This downloads Gson + JUnit 5 (needs internet the first time; Maven caches
them in `~/.m2` after that) and compiles everything under `src/main/java`.

In VS Code you can instead just open the folder — the Java extension will
auto-detect the Maven project and compile on save.

## How to run Stage 1

Open **two terminals** in the `udp-file-transfer/` folder.

Terminal 1 (start the receiver first — it must be listening before the
sender sends, since UDP has no connection setup to "wait" for it):
```
mvn exec:java -Dexec.mainClass="nettransfer.Main" -Dexec.args="receiver"
```

Terminal 2 (once terminal 1 shows "Waiting for a datagram..."):
```
mvn exec:java -Dexec.mainClass="nettransfer.Main" -Dexec.args="sender"
```

### Expected output

Terminal 1 (receiver):
```
[receiver] Binding UDP socket on port 9000 ...
[receiver] Waiting for a datagram (blocking on receive())...
[receiver] Got 17 bytes from /127.0.0.1:53214 -> "hello from sender"
```
(The sender's port number will differ each run — it's OS-assigned.)

Terminal 2 (sender):
```
[sender] Opening UDP socket on an ephemeral port...
[sender] Sending 17 bytes to /127.0.0.1:9000 from local port 53214
[sender] Sent. Exiting.
```

The receiver process keeps running after printing (Stage 1 only receives
once, then the try-with-resources block ends and it exits too — if you
don't see it exit, that's fine, `channel.receive()` already returned).

## How to run the automated test

```
mvn test
```

Expected: `Tests run: 3, Failures: 0, Errors: 0` for `UdpChannelTest`.

What each test proves, and why it matters later:
- `receiverGetsExactBytesSenderSent` — UDP delivers byte-exact payloads on
  loopback (no corruption at this layer under normal conditions).
- `receiveTimesOutWhenNothingArrives` — `setSoTimeout` works. This is the
  exact mechanism Stage 7 (timeout detection) depends on.
- `twoChannelsCanBindDifferentEphemeralPortsSimultaneously` — confirms
  ephemeral port allocation, so sender and receiver never collide.

## Common errors and fixes

| Symptom | Cause | Fix |
|---|---|---|
| Windows Firewall popup on first run | Java is opening a UDP socket and Windows is asking permission | Click "Allow access" (private networks is enough). This is expected, not a bug. |
| `BindException: Address already in use` on the receiver | Port 9000 already held by another process (maybe a receiver you forgot to stop) | Stop the other process, or change `PORT` in `Main.java` temporarily |
| Receiver hangs forever, sender says "Sent" but nothing prints | Ran sender before receiver was ready, or a firewall silently dropped the packet | Always start the receiver first; check the firewall prompt was accepted |
| `mvn: command not found` | Maven not installed / not on PATH | Install Maven or open the folder in VS Code and let the Java extension manage it |
| `package nettransfer.net does not exist` type errors | Wrong working directory, or `src/main/java` layout broken | Run `mvn compile` from inside `udp-file-transfer/` (the folder containing `pom.xml`) |

## What Stage 1 deliberately does NOT do

No headers, no chunking, no ACKs, no retries, no integrity checks. `Main.java`
here is throwaway scaffolding — it will be replaced by `cli.Cli` and
`control.TransferController` from Stage 12 onward. Don't over-build this file.

## Viva-readiness notes for this stage

**Q: Why does the receiver have to start before the sender?**
UDP is connectionless — there's no three-way handshake like TCP that would
let the OS queue up an incoming SYN. If nothing is bound to port 9000 and
listening, an incoming datagram is simply dropped (or, on some OSes,
triggers an ICMP Port Unreachable back to the sender). `DatagramSocket.send()`
does not know or care whether anyone is listening; it fires and forgets.

**Q: What's the difference between `DatagramSocket` and `DatagramPacket`?**
`DatagramSocket` is the endpoint (like a mailbox) — it's what you bind to a
port and call `send`/`receive` on. `DatagramPacket` is one message — it
carries the byte buffer plus destination (when sending) or source (when
receiving) address/port. One socket sends/receives many packets over its
lifetime.

**Q: Why wrap `DatagramSocket` in `UdpChannel` instead of using it directly
in `Main`?**
Separation of concerns: everything above this layer (sender/receiver logic,
later the impairment shim) should only depend on "send bytes, receive bytes,
maybe with a timeout" — not on raw socket API details. This also gives us a
single seam to insert the `ImpairmentShim` later without touching
`SenderEngine`/`ReceiverEngine`.

**Q: What happens if you send a datagram larger than the receiver's buffer?**
It gets silently truncated to the buffer size, with data loss and no error.
That's part of why we've deliberately capped the application payload at
1024 bytes and used a 2048-byte receive buffer — comfortable headroom for
header + payload, well under the ~65507-byte theoretical UDP datagram limit
and well under typical path MTU (~1500 bytes for Ethernet), which is also
part of *why* we chose a small chunk size (avoids IP fragmentation — see the
protocol design doc for Stage 3).
