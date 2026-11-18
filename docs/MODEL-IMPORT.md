# Importing and managing models

## Current state

LocalMind Milestone 1 can **register** GGUF models; it cannot load or run them yet. The chat uses the built-in
demo engine and says so in the header and empty state. Selecting a default model records your choice for
Milestone 2 but does not load the file now.

## Import steps

1. Put a `.gguf` file somewhere readable by Android's document picker (Downloads, Drive, SD card, etc.).
2. Open **Models** and tap **Import**.
3. Select one or more files with the system picker and confirm.
4. LocalMind stores:
   - display name and size from `OpenableColumns`;
   - the content URI;
   - a persisted read grant when the provider supports it;
   - detected format and quantization tag.
5. Use the overflow menu on a model to set it as the default, view file details, or remove it from the library.

The file is **not copied** into app storage. Removing a model from the library does **not** delete the file.
LocalMind does not scan the filesystem or request broad storage permissions.

## Validation

- Only file names ending in `.gguf` (case-insensitive) are marked usable. Other files can still appear in the
  library as **Unsupported** so you can inspect and remove them; they cannot be selected as default.
- Quantization tags such as `Q4_K_M`, `Q5_K_M`, `Q8_0`, `IQ2_XXS`, `F16` and `BF16` are detected from the name.
- **This is metadata only.** A native backend must still verify the GGUF magic, version and tensor layout. A file
  can be named `.gguf` and be corrupt; Milestone 2 will report that as a load error.

## Compatibility and backend

No inference backend is bundled yet. Milestone 2 will evaluate a maintained llama.cpp Android binding and
integrate it behind the existing `InferenceEngine` interface. At that point:

- Supported quantizations will be those compiled into that backend (llama.cpp generally supports Q2–Q8, IQ and
  F16 variants, but the app will publish its exact list and reject unsupported files with a clear error).
- Models will run on CPU by default. GPU/accelerator support depends on the backend build, device drivers and
  available Android APIs; it will be opt-in and shown as unavailable when not compatible.
- No model will ever be downloaded automatically.

## Memory guidance (not enforced yet)

Model memory is roughly file size plus context/KV-cache overhead, so a 4 GB device should stick to small models
(≤1–2 GB) and modest context. These are guidelines, not hardcoded gates.

Milestone 2 will estimate requirements dynamically using `ActivityManager` memory info, the low-RAM flag and the
model's metadata, warn before loading a large model, and never pick thread counts or RAM budgets aggressively on
its own.

## Permissions and reboot behavior

Imports use `ACTION_OPEN_DOCUMENT`-style selection and `takePersistableUriPermission(READ)`. Most document
providers keep access across reboots. If a provider refuses the persisted grant, the entry remains but access may
fail later; re-import the file in that case. LocalMind catches permission errors at import time and reports them.

## Planned: workspace folders

Project folders follow the same principle: the user picks a folder through the Storage Access Framework, and the
agent only sees that subtree after explicit approval. That is Milestone 4 scope and is not enabled in this build.
