# SharedCompute Mesh 1.3 (prototype)
Both Windows and Android may be **MASTER**, **WORKER**, or **AUTO**.
- MASTER: accepts approved peer associations, receives files, may coordinate peers.
- WORKER: advertises its presence and automatically requests pairing with a discovered MASTER.
- AUTO: supports both; when two AUTO nodes meet, their stable device IDs determine which initiates pairing.
- First association MUST be approved on the receiving MASTER, then a fresh 256-bit bearer token is stored locally. Existing associations can reconnect with their stored token.
- Discovery: UDP multicast 239.255.50.55:50555 with broadcast fallback. Multicast announcements are not authenticated; each TLS connection checks the discovered certificate SHA-256 and pins it after pairing. This is not a substitute for verifying the device physically on first pairing.
- Control/file channel: TLS 50554, 4-byte big-endian JSON frames. Transfers limited to 2 MiB; file names sanitized, received into private inbox.
- No ADB, no cloud and no model download.
- On Android, MASTER does **not** start the native RPC worker; WORKER and AUTO start the original CPU RPC worker on localhost 50052 plus the existing authenticated tunnel on 50552.
- On Windows, run SharedCompute-Mesh.exe or host/mesh.py separately from the Windows LAN Host for now. Mesh membership/file transfer is **not yet integrated with llama.cpp RPC scheduling**.
- The Android MASTER does **not** run llama-server, host a GGUF or distribute inference. That requires an Android-side LLM coordinator and compatible RPC client; selecting MASTER currently enables pairing and file coordination only.
- Master must keep the app open to approve first-time pair requests.
- Protect Windows mesh-data/identity.json and mesh-data/mesh-key.pem. Keep Android private app data intact to retain pairings.
- Do not expose port 50554, 50552 or UDP 50555 to the internet.
- Wi-Fi guest isolation, hotspot restrictions, multicast/firewall filtering may prevent discovery; allow UDP 50555 and TCP 50554 on trusted local networks.
- Protocol is application-specific, inspired by the zero-config LAN discovery pattern in LocalSend; it does **not** interoperate with the LocalSend app.

## Getting started
1. Install a CI-built SharedCompute Android APK version 1.3+ and Windows SharedCompute-Mesh.exe from the same revision.
2. Connect both to the same private Wi-Fi. Start each app.
3. Choose MASTER on either Windows or Android; select WORKER or AUTO on the other.
4. The WORKER should discover the MASTER and initiate pairing automatically.
5. The MASTER approves the request on its screen. Pairing identity and token are stored for later reconnections.
6. Select the paired MASTER and send a **small** file as an integration test.
7. For distributed LLM inference, continue to use the separate Windows LAN Host while the Mesh-RPC connection layer remains experimental.

## Security limits
The initial association is approval-based trust on first use. An attacker controlling the LAN discovery response could attempt first-pairing impersonation; verify device names and the physical device before approval. This prototype is not intended for untrusted networks. No executable file is run after receipt.

## Test status
The Windows-Python Mesh TLS pairing and file path was smoke-tested on localhost, not on mixed Windows/Android devices. Android/Windows integration must be verified on physical devices before production use.
