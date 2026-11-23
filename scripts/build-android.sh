#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
: "${LLAMA_REF:?Set LLAMA_REF}"
[[ "$LLAMA_REF" =~ ^[[:xdigit:]]{40}$ ]]
if [[ ! -d llama.cpp/.git ]]; then git init llama.cpp; git -C llama.cpp remote add origin https://github.com/ggml-org/llama.cpp.git; fi
git -C llama.cpp fetch --depth 1 origin "$LLAMA_REF"
git -C llama.cpp checkout --detach FETCH_HEAD
cmake -S llama.cpp -B llama.cpp/build-android -G Ninja \
 -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
 -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static \
 -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_RPC=ON -DGGML_NATIVE=OFF \
 -DGGML_OPENMP=OFF -DGGML_BACKEND_DL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
 -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_SERVER=ON -DLLAMA_OPENSSL=OFF
cmake --build llama.cpp/build-android --target ggml-rpc-server llama-server -j 2
mkdir -p app/src/main/jniLibs/arm64-v8a app/src/main/assets
cp llama.cpp/build-android/bin/ggml-rpc-server app/src/main/jniLibs/arm64-v8a/libsharedcompute_rpc.so
cp llama.cpp/build-android/bin/llama-server app/src/main/jniLibs/arm64-v8a/libsharedcompute_llama.so
cp llama.cpp/LICENSE app/src/main/assets/llama.cpp-LICENSE.txt
printf '%s\n' "$LLAMA_REF" > app/src/main/assets/llama-revision.txt
