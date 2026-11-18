# Milestone 2 — Local GGUF inference: build report

Date: 2026-11-18
Status: **complete for the planned scope**, debug APK built and JVM tests passing. Native inference was
compiled and smoke-tested on the build device, but **no real GGUF model could be run** here (see Limitations).

## Scope

Milestone 2 adds a real, offline GGUF pipeline:

- Pure-Kotlin GGUF parser used for import validation and re-validation.
- llama.cpp backend behind the existing `InferenceEngine` interface, packaged as a JNI shared library.
- SAF-based model loading (no copying, no broad storage permissions), one model resident at a time.
- Streaming generation with stop/cancel, per-model runtime configuration and memory warnings.
- Honest fallback to the demo engine when the native library is not loadable for the device ABI.

## Environment

- Termux on Android (aarch64), JDK 21, Gradle 8.11.1, Android SDK 35, Build Tools 35.0.0,
  AGP 8.7.3, Kotlin 2.0.21, CMake 4.2.2, Ninja 1.13.2.
- Native toolchain: Termux clang 21.1.8 targeting `aarch64-unknown-linux-android24`.
  The official Android NDK is not usable inside Termux because it ships an x86_64-hosted toolchain.
- `aapt2` override from Termux, as in Milestone 1.
- llama.cpp pinned at tag **v1.0.0** (source at `$HOME/llama-src/llama.cpp-1.0.0`).
- No Android device/emulator was attached, and no model file was downloaded or added, so end-to-end
  generation on a phone is **not verified** in this environment.

## Commands

```bash
# Native library (optional; the .so files are committed)
./scripts/build-native.sh          # LLAMA_SRC=... JOBS=2

# App
./gradlew :app:testDebugUnitTest -Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2
./gradlew :app:assembleDebug     -Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2
```

Results:

- `testDebugUnitTest`: **BUILD SUCCESSFUL**, 30 tests, 0 failures (26 → 30 with GGUF and config tests).
- `assembleDebug`: **BUILD SUCCESSFUL**
- APK: `app/build/outputs/apk/debug/app-debug.apk` (69 MB debug)
- APK SHA-256: `92f19f3ec9ac5ca3ad9d86025ee07c8785258d0a16344222bc170d10374073d4`
- Package `com.localmind.app.debug`, version `0.2.0-debug` (versionCode 2)
- Signature: debug certificate, APK Signature Scheme v2
- Native payload (`lib/arm64-v8a/`): `liblocalmind_llama.so` 5,464,224 bytes,
  `libc++_shared.so` 1,423,696 bytes (plus AndroidX/DataStore helpers)
- Permissions unchanged: **no `INTERNET`**

## Native build

The JNI bridge lives in `app/src/main/cpp/localmind_llama.cpp`; `CMakeLists.txt` adds the pinned
llama.cpp tree as a subproject with tests/tools/examples/server/common disabled, `GGML_NATIVE=OFF`,
no OpenMP and static llama/ggml libraries built with position-independent code.

```bash
cmake -S app/src/main/cpp -B build-native -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
  -DLLAMA_SRC=$HOME/llama-src/llama.cpp-1.0.0 -DCMAKE_POSITION_INDEPENDENT_CODE=ON
cmake --build build-native --target localmind_llama -j2
```

- The static archives are linked into `liblocalmind_llama.so` with `-fvisibility=hidden`; only the JNI entry
  points and llama's C API are exported.
- Termux has no static libc++ for this target, so `$PREFIX/lib/libc++_shared.so` is packaged next to the
  library. It depends only on `libc`, `libm` and `libdl`.
- The result was stripped (`-O3`, Release) before packaging: 6.3 MB → 5.3 MB.
- `scripts/build-native.sh` reproduces the whole step and copies both `.so` files into
  `app/src/main/jniLibs/arm64-v8a`. They are committed so a plain Gradle build needs neither the sources nor
  the NDK.

### Smoke test actually performed

The library was `dlopen`ed on the build device (same ABI as the APK payload):

```text
backend: CPU : NEON = 1 | ARM_FMA = 1 | LLAMAFILE = 1 | REPACK = 1 |
jni generate: present
```

This verifies the shared libraries resolve, `llama_backend_init()` runs, the CPU kernels are enabled and the
JNI symbols match the Kotlin declarations. It does **not** verify model loading or generation.

## Implemented

### GGUF validation (`core/gguf`)

- Reads only the header: magic, version (2/3), metadata key/value pairs and tensor descriptors with size limits.
- Extracts architecture, name, parameter count, tensor quantizations (ranked by element count), training context,
  block/embedding/attention-head shape and the embedded chat template.
- Rejects non-GGUF, unsupported versions, absurd tensor/metadata counts and truncated files with specific messages.
- Validation runs at import and can be repeated from the Models screen (e.g. after the provider moved the file).
- Only `VALID` models may be loaded; migrated Milestone 1 rows are re-validated when the Models screen opens.

### Model registry (Room 2)

- New columns: validation state/message, architecture, parameters, GGUF quantization, trained context, layer
  shape, chat template, per-model context length, threads, batch size and GPU layers.
- `MIGRATION_1_2` is additive; conversations and existing rows are preserved.
- Distinct load states (`NOT_LOADED`, `LOADING`, `LOADED`, `FAILED`) kept in sync with the runtime controller.

### Runtime control (`core/inference`)

- `LocalInferenceController` serializes load/unload/switch, blocks invalid files, caps context to the trained
  length and resolves threads/batch/GPU from per-model config with app-wide defaults.
- `ResourceEstimator` combines file size, a KV-cache formula from the real head shape and batch-dependent compute
  buffers, compares with `ActivityManager.MemoryInfo`, the low-RAM flag and current pressure, and classifies the
  fit as comfortable/tight/risky. Tight and risky loads require explicit confirmation; risky loads are labelled
  "Load anyway".
- Auto-load on the first send only: nothing is loaded when the app starts.

### llama.cpp engine

- `LlamaNative` + `LlamaCppEngine`: `/proc/self/fd/N` loading from the SAF descriptor, one handle per model,
  serialized free (waits for an in-flight generation).
- Chat templates are applied natively with `llama_chat_apply_template` using the GGUF template, falling back to
  ChatML when the file has none.
- Prompt tokenization, context-budget enforcement (oldest tokens dropped to fit `maxTokens`) and prompt decoding
  in `n_batch`-sized chunks.
- Sampler chain: penalties (last 64, repeat penalty) → top-k → top-p → temperature → distribution.
- Token-by-token streaming through a JNI callback; the callback returns `false` when the Kotlin flow is
  cancelled, which stops the loop at the next token.
- A `cancel` JNI call also sets an abort flag that llama.cpp's compute loop polls, so Stop interrupts prompt
  prefill as well instead of only taking effect after the first generated token.
- UTF-8-safe emission: only complete code points are passed to Kotlin, incomplete sequences are buffered.
- Generation stops on EOG, context exhaustion or cancellation; errors become `InferenceEvent.Failed`.
- `AppContainer` uses `LlamaCppEngine` when the library loads and the demo engine otherwise; the Models and
  Settings screens show which engine is active, and Load is disabled under the demo engine.

### UI

- Models: validation pills, architecture/params/quantization/layers, Load/Unload/Check file, configure dialog
  (context, threads, batch, GPU row disabled with explanation), details dialog, import summary.
- Chat: `Loading model…` status and errors when no model is selected/loadable; context indicator follows the
  selected model.
- Settings: CPU cores, total/free memory, low-RAM warning, default threads and batch size, live backend/GPU status,
  version `0.2.0 · Milestone 2`.

## Limitations

- **No end-to-end inference test.** This environment has no second Android device and no model file may be
  downloaded, so loading a real GGUF and generating text on a phone is untested. Treat the first device run as
  verification work.
- **arm64 only.** Only `arm64-v8a` is packaged (`abiFilters`). 32-bit devices and x86 emulators run the demo
  engine instead of failing to start.
- **CPU only.** The build has no GPU/Vulkan/OpenCL backend; the GPU toggle is disabled and reported as
  unavailable, and per-model GPU layers are forced to 0.
- **Seekable document required.** `llama.cpp` memory-maps `/proc/self/fd/N`. Providers that return a pipe
  (some cloud providers) will fail to load with a clear message; the file must be a real local file.
- **Tail truncation only.** When the prompt exceeds the context budget the oldest tokens are dropped, which can
  remove the system prompt or the beginning of a long conversation. There is no summarisation yet.
- **Fallback template.** Models without an embedded chat template use ChatML, which may not match their training
  format and can degrade output quality.
- **One model at a time.** Loading a second model unloads the first. Reload after changing configuration.
- **No tokenizer-level stop strings** and no structured output/grammar; stop relies on EOG tokens and the
  user-facing Stop button.
- Debug build is unshrunk (64 MB); release + R8 and signing are not configured yet.
- Room migration is covered by its exported schema v2, but there is no instrumentation test that executes it.

## Files of interest

| Path | Purpose |
| --- | --- |
| `app/src/main/cpp/localmind_llama.cpp` | JNI bridge (load, generate, free, UTF-8 buffering) |
| `app/src/main/cpp/CMakeLists.txt` | Native build definition |
| `scripts/build-native.sh` | Reproducible Termux/arm64 build |
| `app/src/main/kotlin/.../core/gguf/` | GGUF parser + SAF validator |
| `app/src/main/kotlin/.../core/inference/llama/` | Kotlin engine and JNI declarations |
| `app/src/main/kotlin/.../core/inference/LocalInferenceController.kt` | Load/unload state machine |
| `app/src/main/kotlin/.../core/inference/ResourceEstimator.kt` | Memory estimate and risk |
