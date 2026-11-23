# SharedCompute Mesh 1.3 (experimental)

SharedCompute now includes a network control plane on both Android and Windows.

## Start
- Android: install the **SharedCompute-Android** artifact APK and select **START MESH / DISCOVER**. Choose MASTER, WORKER or AUTO.
- Windows: extract **both** `SharedCompute-Mesh.exe` and `SharedCompute-LAN-Host.exe` from the **SharedCompute-LAN-Host** artifact. Run the Mesh EXE directly, or press **OPEN MESH NETWORK** in the LAN Host.
- Place devices on the same trusted Wi-Fi/LAN. Use UDP 50555 (multicast and broadcast) and TCP 50554 (TLS). On Windows, allow these ports on the *Private* network when prompted by the firewall.
- WORKER devices automatically request pairing with a detected MASTER; the MASTER **must approve** first-time pairing. In AUTO mode, an explicit MASTER wins, otherwise the device with the smaller stable ID becomes the coordinator.
- Once paired, select a master and click SEND FILE (max 2 MiB). Windows saves to `mesh-data/inbox`; Android saves to its private `mesh-inbox` directory.
- Paired devices remember tokens/certificate pins locally. Do **not** share keys, pair codes or `mesh-data` publicly.

## Limits
- Discovery advertisement is not authenticated. First-time trust is based on the advertised fingerprint and human approval: independently verify device identity if the LAN may be hostile. Same app name or signature does **not** establish trust.
- Wi-Fi client isolation, VPN, Android system network permission and Windows Firewall may prevent discovery.
- This Mesh layer handles discovery, association and small files, *not distributed GGUF inference*. Android MASTER can receive pairings and files but the current APK does not coordinate model execution. The existing legacy RPC tunnel still has its own credentials and requires its own setup; Mesh pairing alone does not attach a node to llama.cpp.
- Android worker RPC may turn off for thermal, RAM or charging reasons without disabling Mesh discovery.
- Code and CI can be checked automatically; Android/Windows interoperability must still be tested on physical devices. Use APK and EXE from the **same completed Actions run**.
