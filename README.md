# LocalMind

Local-first AI workspace for Android. LocalMind is a native Kotlin + Jetpack Compose app for chatting with
on-device models and, in later milestones, running an approval-gated coding agent against a user-granted
project workspace.

This repository currently contains **Milestone 3 — Termux command tools**. Chat, persistence, streaming UI,
GGUF validation, model management and a bundled llama.cpp CPU backend are real. The first agent tool is also
real: the model may propose a shell command, but it only runs in Termux after you approve it. File, workspace
and network tools are not implemented yet and are clearly labeled in the app.

## Status

| Item | State |
| --- | --- |
| Debug APK | Built successfully (`app/build/outputs/apk/debug/app-debug.apk`) |
| Unit tests | 50 passing (`:app:testDebugUnitTest`) |
| Minimum Android | 10 (API 29) |
| Target SDK | 35 |
| AI backend | llama.cpp `v1.0.0`, CPU, arm64-v8a (demo engine fallback) |
| Termux tools | Opt-in, off by default; every command needs approval (**not yet verified on a device**) |
| Network permission | **None** — the manifest has no `INTERNET` permission |

See [docs/MILESTONE-2.md](docs/MILESTONE-2.md) for the latest build report and
[docs/MILESTONE-1.md](docs/MILESTONE-1.md) for the foundation report.

## What works in this build

- **Chat** with streamed responses, stop generation, regenerate, copy response, copy code, edit a user message
  and resend (later messages are truncated and regenerated), and automatic scroll that does not hijack the
  position while you are reading older messages.
- **Markdown rendering** with headings, lists, quotes, tables, links, inline code, fenced code blocks with
  heuristic syntax highlighting and a copy action. The parser is custom, pure Kotlin and unit-tested.
- **Conversation history drawer** with search by title, pin, rename, delete, grouping by date (Today,
  Yesterday, Previous 7 days, Older), plus context-usage indicator in the chat header.
- **Model registry and runtime** with Android Storage Access Framework import (`ACTION_OPEN_DOCUMENT`),
  persisted read grants, GGUF header validation (magic/version/metadata/tensors), parameter count, architecture,
  tensor quantization, trained context and chat template, per-model context/threads/batch configuration,
  load/unload with a pre-load memory estimate and confirmation for tight/risky fits, and removal that never
  deletes the file.
- **Real local inference** through the bundled llama.cpp JNI backend: native chat templating, tokenizer-level
  context budgeting, streaming tokens with a working stop button, automatic unload on model switch, and a
  clearly labeled demo fallback when the native library or a loadable model is unavailable.
- **Settings** persisted with DataStore: dark/light/system theme, system prompt, sampling parameters, context
  length, tool policy documentation, storage usage, delete all chats, backend status and about.
- **Termux command tool (opt-in).** When enabled, the model is told about one tool and may propose a single
  shell command in a `<tool_call>` block. The command is shown in a review card and **never runs automatically**;
  tapping *Run in Termux* sends it through Termux's `RUN_COMMAND` service and the captured stdout/stderr/exit
  code is added back as a tool result. Output is capped for context safety, the wait limit is 30 s, and the
  result can be stopped, declined, or cancelled. A manual **Termux console** in Settings tests the same bridge
  without a model in the loop.
- **Projects tab** that documents the upcoming workspace model without pretending it exists.

## What is intentionally not implemented yet

- GPU acceleration. The bundled llama.cpp build is CPU-only; the GPU row is disabled and says so.
- Non-arm64 native builds. Only `arm64-v8a` is packaged; other ABIs use the demo engine.
- File read/write/delete tools, multi-step agent orchestration and an agent task panel. Only the Termux
  command bridge exists today.
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
│   ├── gguf                        # pure-Kotlin GGUF parser + SAF validator
│   ├── inference                   # contracts, controller, estimator, demo + llama.cpp engines
│   ├── ui/{theme,components,markdown}
│   └── util
├── di/AppContainer.kt              # manual DI
├── feature/{chat,models,projects,settings}
├── navigation
├── AppViewModel.kt
├── LocalMindApplication.kt
└── MainActivity.kt

app/src/main/cpp/localmind_llama.cpp   # JNI bridge to llama.cpp
app/src/main/jniLibs/arm64-v8a/        # committed native payload
scripts/build-native.sh                # reproducible native build
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

### Rebuilding the native backend (optional)

`liblocalmind_llama.so` and `libc++_shared.so` are committed under `app/src/main/jniLibs/arm64-v8a`, so a normal
Gradle build needs no NDK. To rebuild them from the pinned llama.cpp `v1.0.0` source:

```bash
pkg install cmake ninja clang
LLAMA_SRC=$HOME/llama-src/llama.cpp-1.0.0 ./scripts/build-native.sh
```

The script uses Termux clang (target `aarch64-unknown-linux-android24`) because the official NDK's toolchain is
x86_64-hosted and cannot run here. See [docs/MILESTONE-2.md](docs/MILESTONE-2.md) for details.

> The debug APK is large (about 69 MB) because it packages the full Material icon set and the native libraries
> without shrinking. A release build with R8 will be significantly smaller; release signing is not configured yet.

## Importing and running models

See [docs/MODEL-IMPORT.md](docs/MODEL-IMPORT.md). Short version: open **Models → Import**, pick a `.gguf` file.
LocalMind validates the header without reading the weights, stores only metadata plus a persisted read grant, and
loads the file on demand (first send, or the **Load** button). The file itself is never copied or deleted.

## Termux tool setup

LocalMind never executes a shell itself. Commands run under Termux's user after you approve them.

1. Install Termux from F-Droid (package `com.termux`). Version **0.109 or newer** is required so stdout,
   stderr and the exit code can be returned to LocalMind.
2. Grant LocalMind *Run commands in Termux*: Android Settings → Apps → LocalMind → Permissions → Additional
   permissions. The manifest requests `com.termux.permission.RUN_COMMAND`.
3. Inside Termux run:

   ```bash
   echo 'allow-external-apps=true' >> ~/.termux/termux.properties
   ```

   Then force-stop and reopen Termux.
4. Open **Settings → Agent & tool policy → Open Termux console** and run `echo ok` to confirm the bridge.
5. Only then enable **Termux commands**, ask the model for something like "list my Termux home directory", and
   approve the proposal with **Run in Termux**.

The wait limit does not kill a command inside Termux; it only stops LocalMind waiting for the result. A failed
permission or `allow-external-apps` check is reported as a setup hint in the result card.

## Privacy

- Conversations and settings are stored locally in Room and DataStore.
- No analytics, telemetry, network permission or background upload.
- The app never scans storage on its own; files enter the library only through the system document picker.

## Roadmap

1. **Foundation** — done.
2. **Local inference** — done: llama.cpp CPU backend, GGUF validation, load/unload, memory checks, real
   streaming, backend configuration.
3. **Agent core** — in progress: Termux command tool, parser, approval card and result loop shipped; file
   tools, permission engine and task panel remain.
4. **Workspace** — SAF folder grants, file explorer, project tree, context indexing.
5. **Coding tools** — search/read/write/edit with diff previews and confirmations.
6. **Advanced** — summarization, retrieval, paging, performance and UX polish.
