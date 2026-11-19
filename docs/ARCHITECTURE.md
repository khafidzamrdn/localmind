# Architecture

## Goals

- Offline-first: no network permission, no telemetry.
- Backend-agnostic inference so a llama.cpp engine can replace the demo engine without UI changes.
- Explicit, testable boundaries between UI, domain and data.
- A structure that can be split into Gradle modules later without moving code twice.

## Layers

```text
UI (Compose)  →  ViewModel  →  Repository contracts (domain)
                                     ↑
                       implementations in core/data
                                     ↓
                         Room · DataStore · SAF · engine
```

- `core/domain/model` — immutable Kotlin models (`Conversation`, `ChatMessage`, `LocalModel`,
  `GenerationParams`, `AppSettings`). No Android imports.
- `core/domain/repository` — contracts used by features.
- `core/data/local` — Room entities, DAOs, mappers and database. Entities never leak into features.
- `core/data/repository` — repository implementations, `Flow`-based observation and mapping.
- `core/data/settings` — DataStore-backed settings.
- `core/gguf` — pure-Kotlin GGUF header parser and a SAF-backed validator.
- `core/inference` — `InferenceEngine` contract, request/event types, the demo engine, the llama.cpp engine
  (`core/inference/llama`), the load/unload controller and the memory estimator. The JNI bridge and its
  native build live in `app/src/main/cpp`.
- `core/ui` — theme, reusable components, Markdown parser/renderer and code highlighter.
- `core/util` — pure formatting and grouping helpers.
- `di/AppContainer` — manual singleton graph created by `LocalMindApplication`.
- `feature/*` — screen + ViewModel for chat, models, projects and settings.
- `navigation` — destinations and the Compose `NavHost` scaffold.

Dependency direction is UI → domain ← data. `AppContainer` is the only place that wires implementations.

## Data model

Room database `localmind.db`, schema version 2, exported to `app/schemas/`.

| Table | Purpose | Notes |
| --- | --- | --- |
| `conversations` | chat metadata | `pinned`, `modelId`, `projectId` placeholder, `updatedAt` for ordering |
| `messages` | chat messages | FK to conversation with `CASCADE`, ordered by auto-increment id |
| `models` | model registry | unique SAF `uri`; metadata only, never file contents |

Schema 2 adds GGUF validation state/message, architecture, parameter count, tensor quantization, trained context,
block/embedding/head shape, chat template, per-model context length, thread count, batch size and GPU layers.
`MIGRATION_1_2` is additive; Milestone 1 rows start as `UNVERIFIED` and are re-validated when Models opens.

- Conversations are created lazily on the first user message; the title is derived from that message.
- Editing a user message updates it in place and deletes all later messages (`id > messageId`), which keeps
  branching predictable without a tree model.
- Deleting a conversation cascades its messages. Deleting a model only removes the registry row.
- `Flow` queries drive the drawer and the message list; message paging is deferred until conversations get large
  (Milestone 6).

## Chat and streaming flow

1. `ChatViewModel.sendDraft()` creates the conversation if needed, inserts the user message, then calls
   `generate()`.
2. `generate()` reads `AppSettings` (system prompt, sampling params, active model), loads history once, and
   collects `InferenceEngine.generate(InferenceRequest)`.
3. Tokens accumulate in a buffer. UI state is flushed at most every 45 ms so Markdown parsing and recomposition do
   not run per token. `Thinking` → `Generating` status is reflected in the header.
4. On completion the assistant message is persisted once with token usage. On stop/cancel, the partial buffer is
   persisted with `STOPPED` inside `NonCancellable`, after verifying the conversation still exists.
5. Regenerate deletes the latest assistant reply (only if it follows the latest user message) and reuses the same
   history. Errors surface in a banner with retry.

Scroll behavior uses `LazyColumn(reverseLayout = true)`. The viewport stays anchored at the newest item when it is
already at the bottom; a jump-to-latest button appears after scrolling up, so reading old messages is not
interrupted by incoming tokens.

## Inference abstraction

```kotlin
interface InferenceEngine {
    val id: String
    val displayName: String
    val isDemo: Boolean
    fun capabilities(): EngineCapabilities
    suspend fun loadModel(model: LocalModel, config: InferenceConfig): Result<Unit>
    suspend fun unload()
    fun generate(request: InferenceRequest): Flow<InferenceEvent>
}
```

Two implementations exist:

- `FakeInferenceEngine` — deterministic Markdown with a simulated prefill delay. It never reads the selected
  file and never performs I/O. `isDemo` drives the `DEMO` label and the in-chat disclosure.
- `LlamaCppEngine` — the real backend. Model weights are opened through the persisted SAF URI, handed to native
  code as `/proc/self/fd/N`, and memory-mapped by llama.cpp. One handle is resident at a time.

`AppContainer` instantiates `LlamaCppEngine` only if its shared library loads; otherwise the demo engine is used
and every relevant screen says so.

### Loading, configuration and memory

`LocalInferenceController` is the single owner of load state and serializes load/unload/switch with a mutex. It
refuses models that are not `VALID`, unloads the previous model before loading the next, clamps context to the
model's trained length and resolves threads/batch from the per-model row with app-wide defaults. Room's
`loadState` mirrors the runtime state (`UNLOADED`, `LOADING`, `LOADED`, `FAILED`).

`ResourceEstimator` estimates weights (file size) + KV cache (from block/head/embedding shape and context) +
batch-dependent compute buffers, compares the total with `ActivityManager.MemoryInfo`, and returns a
comfortable/tight/risky classification. Tight and risky loads require explicit confirmation in the UI.

Loading happens only on an explicit user action: the Load button or the first send to the selected model. App
start never loads weights.

### Native generation

`core/inference/llama/LlamaNative.kt` declares the JNI surface; `app/src/main/cpp/localmind_llama.cpp`
implements it against llama.cpp `v1.0.0`:

1. `loadModel` builds model/context params (context ≤ trained length, `n_batch`/`n_ubatch`, CPU threads) and
   returns an opaque handle.
2. `generate` formats the conversation with the GGUF chat template (ChatML fallback), tokenizes, drops the oldest
   tokens if the context budget is exceeded, and decodes the prompt in batch-sized chunks.
3. Sampling uses penalties → top-k → top-p → temperature → distribution.
4. Each token piece is appended to a UTF-8 buffer; only complete code points are sent to Kotlin. The callback's
   `false` return stops generation, and `cancel(handle)` sets an abort flag polled by llama.cpp's compute loop so
   Stop also interrupts prefill.
5. `freeModel` waits for an in-flight generation before releasing the context and the SAF descriptor.

Errors are converted to `InferenceEvent.Failed` with actionable text (out of memory, unsupported quantization,
unseekable document, template failure).

## Agent architecture (Termux tool)

The first agent slice is implemented behind `core/tool` and `core/termux`:

- `ToolRegistry` — the single `termux_run` descriptor and the plan→act→observe system prompt.
- `ToolCallParser` — structured `<tool_call>` parsing with a fallback for models without native tool calling.
- `ToolResultCodec` — JSON results wrapped in `<tool_response>`, including `auto` and `blocked` states.
- `CommandPolicy` — read-only allowlist, review-by-default for everything else, and a hard-block list.
- `ChatViewModel` — bounded loop (6 commands), cancellation, approval state, duplicate and consecutive-failure
  stops. It re-invokes the model after each result and only auto-runs `AUTO_READ` proposals.
- In-chat step cards and result cards provide the audit trail; there is no hidden execution path.

`PermissionManager`, workspace-scoped file tools and the task panel remain planned. A future workspace grant
will be required before file tools are added; there is still no unrestricted filesystem API in the design.

## Testing

Pure logic is tested on the JVM: Markdown block parsing, inline spans, code highlighting, formatting/grouping,
model metadata detection, GGUF parsing (synthetic files), inference configuration defaults, the tool protocol
and the command risk policy. 63 tests run with `:app:testDebugUnitTest`. Compose UI tests, Room migration
instrumentation tests and native inference tests require a device/emulator and are not configured yet.

## Moving to multi-module

The packages map directly to the intended modules:

| Current package | Future module |
| --- | --- |
| `core/domain` | `core:domain` (pure Kotlin) |
| `core/data` | `core:data` |
| `core/inference` | `core:inference` |
| `core/ui`, `core/util` | `core:ui`, `core:common` |
| `feature/chat` | `feature:chat` |
| `feature/models` | `feature:models` |
| `feature/projects` | `feature:projects` |
| `feature/settings` | `feature:settings` |
| `di`, `navigation` | `app` |

There are no circular dependencies between feature packages today.
