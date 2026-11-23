# SharedCompute 1.3.2 - Android Local Qwen (text-only)

This experimental feature adds an on-device ARM64 llama-server binary to the APK.
It is separate from Mesh discovery/transfer and separate from the legacy RPC worker.
A Mesh MASTER may run this local model independently; being a Mesh MASTER **does not**
yet schedule distributed GGUF inference across Workers.

## Use on a Pixel/Android ARM64 device
1. Install the Android artifact from the feature branch GitHub Actions build. The
   existing installed debug APK might require uninstalling if CI debug signing differs.
   Back up any locally received Mesh files first.
2. Open SharedCompute, select MASTER (recommended to avoid simultaneous RPC memory use),
   and tap START MESH / DISCOVER.
3. Under LOCAL QWEN choose "CHOOSE EXISTING GGUF" and select
   Qwen3VL-2B-Instruct-Q4_K_M.gguf from Download/Files.
4. Android grants a content:// document, so the app makes **one local copy** in its
   private model directory for a stable mmap-compatible path. The original is untouched.
   Allow at least the model size plus 256 MiB free storage.
5. Wait for "QWEN READY" then type a question and tap SEND TO LOCAL QWEN.
   Subsequent launches reuse the cached file via START CACHED LOCAL QWEN.
6. STOP LOCAL QWEN releases the model memory and stops the localhost server.

## Implementation
- Native binary: libsharedcompute_llama.so (compiled as llama-server executable)
- Local endpoint: http://127.0.0.1:18082/v1/chat/completions (loopback only)
- Defaults: 2048-token context, 128-token response, up to four CPU threads
- No mmproj, no image input. The Qwen3-VL text GGUF may still be unsupported by
  the repository's pinned llama.cpp version; observe ACTIVITY/ERRORS if it fails to load.
- Import is a copy due to Android SAF content URI restrictions, not a re-download.
- App-private GGUF may be deleted if the app is uninstalled.
- A successful CI build is **not** proof of correct inference on real Android hardware.
- Stop local Qwen before starting memory-hungry RPC jobs.
