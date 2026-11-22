# SharedCompute / CyberTiel distributed AI
Android ARM64 CPU RPC worker + Windows coordinator, not virtual RAM. Both binaries use the same pinned llama.cpp commit. No browser and no root. Experimental RPC is restricted to localhost; never expose it on a public or shared network. ADB grants debugging privileges: pair only devices you own and revoke the pairing after use.

## Download
Use [Actions](https://github.com/Frak89/SharedCompute/actions) and download both artifacts from the same successful run: **SharedCompute-Android** (debug APK) and **SharedCompute-Windows** (host and PowerShell launcher). No release APK is claimed until CI succeeds.

## Use
1. Install official Android Platform Tools on Windows (adb in PATH).
2. Install APK on ARM64 Android. Enable Developer Options and Wireless debugging. On the PC use adb pair IP:PAIR_PORT, enter the pairing code, then adb connect IP:CONNECT_PORT. The two ports differ. USB debugging also works. Run adb devices.
3. Open SharedCompute, choose a conservative memory budget and 2–4 CPU threads, then activate node while charging. The budget is a watchdog threshold, not reserved RAM or a hard kernel limit; RSS visibility can vary on Android. The app stops on severe thermal status or low system memory. 
4. Extract the Windows artifact and run the launcher in PowerShell from that directory:

    .\Start-SharedCompute.ps1 -Model 'E:\CYBER_AI\MODEL\Cyber-Tiel-Coder-35B-A3B-MTP-UD-Q5_K_S.gguf' -Serial @('192.168.1.50:37141') -Context 1024 -OffloadLayers 20

Replace the serial with the actual adb devices entry. Multiple nodes can be listed. Start with a small 0.5B/1.5B GGUF and tune OffloadLayers conservatively. Model placement depends on llama.cpp, not the app's budget. Use Ctrl+C to stop and remove forwarding; stop Android nodes manually.
5. CyberTiel Studio settings: OpenAI-compatible engine, model sharedcompute, endpoint http://127.0.0.1:8081/v1 (omit /v1 if the client adds it). After model loading, check Invoke-RestMethod http://127.0.0.1:8081/v1/models. Ollama is separate.

## Limits
The 35B model may not fit in a PC and a single phone. More memory is not necessarily more speed; Wi-Fi adds latency. CPU only; no NPU/GPU support, no training, no automatic LAN discovery. Initial implementation requires real-device validation. ADB tunnel is not a public service. Upstream MIT license included with artifacts.
