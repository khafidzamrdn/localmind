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
- `core/inference` — `InferenceEngine` contract, request/event types and the demo engine.
- `core/ui` — theme, reusable components, Markdown parser/renderer and code highlighter.
- `core/util` — pure formatting and grouping helpers.
- `di/AppContainer` — manual singleton graph created by `LocalMindApplication`.
- `feature/*` — screen + ViewModel for chat, models, projects and settings.
- `navigation` — destinations and the Compose `NavHost` scaffold.

Dependency direction is UI → domain ← data. `AppContainer` is the only place that wires implementations.

## Data model

Room database `localmind.db`, schema version 1, exported to `app/schemas/`.

| Table | Purpose | Notes |
| --- | --- | --- |
| `conversations` | chat metadata | `pinned`, `modelId`, `projectId` placeholder, `updatedAt` for ordering |
| `messages` | chat messages | FK to conversation with `CASCADE`, ordered by auto-increment id |
| `models` | model registry | unique SAF `uri`; only metadata, never file contents |

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
    suspend fun loadModel(model: LocalModel): Result<Unit>
    suspend fun unload()
    fun generate(request: InferenceRequest): Flow<InferenceEvent>
}
```

`FakeInferenceEngine` streams deterministic Markdown with a simulated prefill delay. It deliberately ignores the
selected model file and never performs I/O. `isDemo` drives the `DEMO` label and the in-chat disclosure.

To add a native backend (Milestone 2):

1. Implement `InferenceEngine` as an Android binding around a llama.cpp-compatible library (JNI or maintained
   Kotlin binding). Keep the flow cancellation-aware and run inference off the main thread.
2. Validate GGUF magic/version and backend-supported quantization at load time; map failures to
   `Result.failure` with actionable messages.
3. Estimate memory (`ActivityManager.MemoryInfo`, low-RAM flag, file size + context) and require confirmation for
   large models rather than hardcoding limits.
4. Emit `InferenceEvent.Failed` for recoverable errors so the UI can offer retry.
5. Bind the implementation in `AppContainer`; no feature code has to change.

## Agent architecture (planned, not implemented)

Milestone 3 will add, behind interfaces:

- `ToolRegistry` — descriptors, JSON schemas, risk class.
- Structured tool protocol parser with fallback for models without native tool calling.
- `PermissionManager` — read-only / write / delete / execute / network policies, scoped to a workspace grant.
- `AgentOrchestrator` — bounded iteration loop, timeout, cancellation, approval state.
- Task panel fed by observable step events (no chain-of-thought exposure).

Tools will only accept a `WorkspaceGrant`; there is no unrestricted filesystem API in the design.

## Testing

Pure logic is tested on the JVM: Markdown block parsing, inline spans, code highlighting, formatting/grouping and
model metadata detection. Compose UI tests and Room instrumentation tests require a device/emulator and are not
configured yet.

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
