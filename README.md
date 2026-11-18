# LocalMind

Local-first AI workspace for Android. LocalMind is a native Kotlin + Jetpack Compose app for chatting with
on-device models and, in later milestones, running an approval-gated coding agent against a user-granted
project workspace.

This repository currently contains **Milestone 1 — Foundation**. Chat, persistence, streaming UI, model
registration and settings are real. Native GGUF inference, agent tools and workspaces are not implemented yet
and are clearly labeled in the app.

## Status

| Item | State |
| --- | --- |
| Debug APK | Built successfully (`app/build/outputs/apk/debug/app-debug.apk`) |
| Unit tests | 19 passing (`:app:testDebugUnitTest`) |
| Minimum Android | 10 (API 29) |
| Target SDK | 35 |
| AI backend | Demo engine (simulated, clearly labeled) |
| Network permission | **None** — the manifest has no `INTERNET` permission |

See [docs/MILESTONE-1.md](docs/MILESTONE-1.md) for the build report and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
for the design.

## What works in this build

- **Chat** with streamed responses, stop generation, regenerate, copy response, copy code, edit a user message
  and resend (later messages are truncated and regenerated), and automatic scroll that does not hijack the
  position while you are reading older messages.
- **Markdown rendering** with headings, lists, quotes, tables, links, inline code, fenced code blocks with
  heuristic syntax highlighting and a copy action. The parser is custom, pure Kotlin and unit-tested.
- **Conversation history drawer** with search by title, pin, rename, delete, grouping by date (Today,
  Yesterday, Previous 7 days, Older), plus context-usage indicator in the chat header.
- **Model registry** with Android Storage Access Framework import (`ACTION_OPEN_DOCUMENT`), persisted read
  grants, GGUF format and quantization detection, size/URI details, set default, remove from library (never
  deletes the file). Unsupported files are marked and cannot be selected.
- **Settings** persisted with DataStore: dark/light/system theme, system prompt, sampling parameters, context
  length, tool policy documentation, storage usage, delete all chats, backend status and about.
- **Projects tab** that documents the upcoming workspace model without pretending it exists.

## What is intentionally not implemented yet

- Native GGUF loading and inference (llama.cpp or another backend). The chat uses
  `FakeInferenceEngine`, and both the empty state and the header label it as a demo.
- Model resource estimation / memory warnings at load time.
- Agent orchestrator, tool registry, permission prompts, agent task panel.
- Workspace folder grants, file explorer, project indexing, context retrieval.
- File attachments, conversation summarization, chat paging, message search.
- Streaming persistence of partial tokens across process death (partial output is saved on stop, not per token).

No button in the app pretends to do something that is not implemented. Disabled/absent actions are documented
in the UI.

## Architecture at a glance

Single Gradle module with package-level modularity, ready for extraction into `core-*` / `feature-*` modules.

```text
app/src/main/kotlin/com/localmind/app
├── core
│   ├── domain/{model,repository}   # pure Kotlin entities + repository contracts
│   ├── data/{local,repository,settings}
│   ├── inference                   # InferenceEngine abstraction + demo backend
│   ├── ui/{theme,components,markdown}
│   └── util
├── di/AppContainer.kt              # manual DI
├── feature/{chat,models,projects,settings}
├── navigation
├── AppViewModel.kt
├── LocalMindApplication.kt
└── MainActivity.kt
```

Core interfaces: `InferenceEngine`, `ConversationRepository`, `ModelRepository`, `SettingsRepository`.
Planned interfaces (`ToolRegistry`, `ToolExecutor`, `PermissionManager`, `AgentOrchestrator`,
`WorkspaceRepository`, `ContextManager`) are intentionally **not** stubbed yet; they will be added with their
first real implementations in Milestones 3–5.

Stack: Kotlin 2.0.21, AGP 8.7.3, Compose BOM 2024.11.00, Material 3, Navigation Compose, Room 2.6.1 (KSP),
DataStore Preferences, Coroutines/Flow, manual dependency injection.

## Build

Requirements: JDK 17+ (JDK 21 verified), Android SDK Platform 35, Build Tools 35.0.0, Gradle 8.11.1
(the wrapper is committed).

```bash
# local.properties
sdk.dir=/path/to/Android/Sdk

./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Building in Termux (aarch64)

The Android SDK ships an x86_64 `aapt2` binary which cannot run on Termux. Install the Termux `aapt2` package and
pass it as an override; the override is not committed so normal builds are unaffected:

```bash
pkg install aapt2 openjdk-21
export JAVA_HOME=$PREFIX/lib/jvm/java-21-openjdk
./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2
```

The project was built and tested with this setup. `local.properties` is ignored by git.

> The debug APK is large (about 58 MB) because it packages the full Material icon set without shrinking.
> A release build with R8 will be significantly smaller; release signing is not configured yet.

## Importing models

See [docs/MODEL-IMPORT.md](docs/MODEL-IMPORT.md). Short version: open **Models → Import**, pick a `.gguf` file.
LocalMind stores only metadata plus a persisted read grant. The selected file will be used by the native backend
in Milestone 2; it is **not** loaded in this build.

## Privacy

- Conversations and settings are stored locally in Room and DataStore.
- No analytics, telemetry, network permission or background upload.
- The app never scans storage on its own; files enter the library only through the system document picker.

## Roadmap

1. **Foundation** — done in this milestone.
2. **Local inference** — llama.cpp-compatible Android backend, model validation, load/unload, memory checks,
   real streaming, backend configuration.
3. **Agent core** — structured tool protocol, registry, permission engine, approval UI, task panel.
4. **Workspace** — SAF folder grants, file explorer, project tree, context indexing.
5. **Coding tools** — search/read/write/edit with diff previews and confirmations.
6. **Advanced** — summarization, retrieval, paging, performance and UX polish.
