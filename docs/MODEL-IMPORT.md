# Importing and running models

## Current state

LocalMind Milestone 2 can **register, validate, load and run** GGUF models on the CPU through a bundled
llama.cpp build (arm64-v8a). If the native library cannot be loaded on the device, or no model is selected, the
chat falls back to the built-in demo engine and says so in the header, the empty state and the Models screen.

## Import steps

1. Put a `.gguf` file somewhere readable by Android's document picker (Downloads, SD card, etc.).
2. Open **Models** and tap **Import**.
3. Select one or more files with the system picker and confirm.
4. LocalMind reads the GGUF header only, then stores:
   - display name and size from `OpenableColumns`;
   - the content URI;
   - a persisted read grant when the provider supports it;
   - header metadata: architecture, parameter count, tensor quantization, trained context, layer shape and chat
     template;
   - validation state (`valid`, `invalid`, `missing`, `unverified`);
   - default inference settings (context, threads, batch size).
5. Use the overflow menu to set the selected model, configure inference, re-validate, view details, or remove it.

The file is **not copied** into app storage. Removing a model from the library does **not** delete the file.
LocalMind does not scan the filesystem or request broad storage permissions.

## Validation

- The magic bytes, GGUF version (2 and 3), metadata table and tensor descriptors are parsed with size limits.
  Unsupported versions, absurd counts and truncated files are rejected with a specific message.
- Only `valid` models can be loaded. Other states show a pill in the Models list; **Check file** re-reads the
  header after a provider moved or revoked the file.
- `unverified` rows only appear when upgrading from Milestone 1; they are re-validated automatically when the
  Models screen opens.
- Quantization labels (`Q4_K`, `Q6_K`, `Q8_0`, `IQ2_XXS`, `F16`, …) come from the tensor types, ranked by
  element count, not just from the file name.

## Loading and running

- **Load** (or the first message sent to the selected model) opens the document through the persisted URI and
  passes `/proc/self/fd/N` to llama.cpp, which memory-maps the weights. The file is never copied.
- The backend is **CPU-only** and uses the model's context length capped to what it was trained for, the
  configured thread count and batch size.
- The GGUF chat template is applied natively. Models without a template fall back to ChatML.
- Generation streams token by token. **Stop** cancels the coroutine, which closes the callback channel and makes
  the native loop exit at the next token.
- Loading another model unloads the current one; unloading releases both the native context and the SAF
  descriptor.
- Changing context/threads/batch for a loaded model takes effect after unload + load; the app says so.
- If a prompt grows past the context budget, the oldest tokens are dropped. There is no summarisation yet, so a
  very long chat can lose its beginning (or the system prompt).

### Provider requirement

`llama.cpp` needs a seekable file. Providers that expose a pipe instead of a real file (some cloud providers)
cannot be memory-mapped; LocalMind reports the load failure instead of copying the file silently. Local files
from Downloads or an SD card work.

## Compatibility

- The bundled llama.cpp `v1.0.0` build supports the quantizations it was compiled with: the usual Q2–Q8, IQ and
  F16/BF16 families for supported architectures. An unsupported architecture or quantization produces a load
  error with the backend's message.
- Only `arm64-v8a` is packaged. 32-bit devices and x86 emulators run the demo engine.
- GPU acceleration is not compiled in; the UI shows it as unavailable and forces GPU layers to 0.
- No model is ever downloaded automatically, and there is no network permission.

## Memory guidance

The Load action estimates weights (file size) + KV cache (from the real head shape and chosen context) + compute
buffers, and compares the total with the device's available memory:

| Risk | Meaning |
| --- | --- |
| Comfortable | Estimate stays below ~65% of available memory; loads immediately |
| Tight | Above ~65% (or a low-RAM device); asks for confirmation |
| Risky | Above available memory; offers **Load anyway** with an out-of-memory warning |

A 4 GB phone should stick to small quantized models (≤1–2 GB) with a 2k–4k context. Android may kill the app if
free memory runs out, so close other apps before loading a large model.

## Permissions and reboot behavior

Imports use the system document picker and `takePersistableUriPermission(READ)`. Most providers keep access
across reboots. If a provider refuses the persisted grant, the entry remains but loading fails; re-import the
file in that case. LocalMind catches permission errors and reports them as `missing`.

## Planned: workspace folders

Project folders follow the same principle: the user picks a folder through the Storage Access Framework, and the
agent only sees that subtree after explicit approval. That is Milestone 4 scope and is not enabled in this build.
