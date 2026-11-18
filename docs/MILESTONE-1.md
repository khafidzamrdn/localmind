# Milestone 1 — Foundation: build report

Date: 2026-11-18
Status: **complete**, debug APK built and JVM tests passing.

## Environment

- Termux on Android (aarch64), JDK 21, Gradle 8.11.1 (wrapper), Android SDK Platform 35,
  Build Tools 35.0.0, AGP 8.7.3, Kotlin 2.0.21.
- `aapt2` from the Termux package via `-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2`
  (the SDK's x86_64 binary cannot execute on this architecture). Nothing about this override is committed.
- No Android device or emulator was attached to this environment, so the UI was not exercised interactively.
  Runtime claims below are limited to build, static verification and JVM tests.

## Commands run

```bash
./gradlew :app:assembleDebug   -Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2
./gradlew :app:testDebugUnitTest -Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2
```

Results:

- `assembleDebug`: **BUILD SUCCESSFUL**
- `testDebugUnitTest`: **BUILD SUCCESSFUL**, 19 tests, 0 failures
- APK: `app/build/outputs/apk/debug/app-debug.apk` (~58 MB debug)
- APK SHA-256: `5c7dba779c4cc080ecdc5415dd1b80b859b963104649e69d1de35d0923de1998`
- Package: `com.localmind.app.debug` (debug suffix), version `0.1.0-debug`
- Signature: Android debug certificate, APK Signature Scheme v2 verified with `apksigner`
- `minSdkVersion 29`, `targetSdkVersion 35`, `compileSdkVersion 35`
- Permissions: no `INTERNET`; the only entry is the AndroidX auto-generated
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`

The 58 MB size comes from the unshrunk debug build packaging `material-icons-extended`. Release + R8 is not
configured yet; a release build would need a signing config.

## Completed in this milestone

- Android project with Gradle Kotlin DSL, version catalog, Compose + Material 3 theme (dark default, light and
  system modes), adaptive launcher icon, edge-to-edge layout, bottom navigation.
- Chat screen:
  - streaming responses with stop, regenerate, retry after failure;
  - Markdown rendering, syntax-highlighted fenced code with copy, copy response, clickable links;
  - user message edit → truncate later messages → regenerate;
  - reverse-layout scrolling that preserves reading position with a jump-to-latest button;
  - context-usage estimate against the configured context length;
  - status indicator (Ready / Thinking / Generating / Error), demo disclosure, model selector sheet.
- Conversation drawer: new chat, search by title, pin, rename, delete, date grouping, active highlight.
- Room database v1 with conversations, messages (cascade delete), model registry; exported schema.
- DataStore settings: theme, system prompt, temperature, top-p, top-k, repeat penalty, max tokens, context
  length, active model.
- Model library:
  - multi-document picker import with persisted read permissions;
  - metadata query for display name and size off the main thread;
  - `.gguf` validation and quantization detection; unsupported files registered as unusable;
  - set/clear default, remove from library without deleting the file, details dialog.
- Projects tab documenting the scoped workspace model and roadmap; no fake actions.
- Settings: appearance, default model entry point, system prompt editor/reset, generation sliders, context
  selector, tool policy disclosure, database size + delete-all, backend status, about.
- Backend abstraction (`InferenceEngine`) with a streaming demo implementation.
- Unit tests for Markdown, inline formatting, formatters/grouping and model metadata.
- Documentation: README, architecture, model import guide.

## Not implemented (by design for M1)

- Model loading/unloading, GGUF header validation, resource estimation, native inference.
- Agent orchestrator, tools, permission execution, workspace access, task panel.
- Attachments, summarization, paging, full-text message search, instrumentation/UI tests.
- Release signing/shrinking and Play-ready packaging.

## Verification gaps and risks

- UI was not launched on a device; first-run layout, keyboard insets and SAF interaction need device testing.
- Imported URI permissions rely on the document provider supporting persistable grants. Failure is caught, but
  access may be lost after reboot on unusual providers; the app would then need a re-import.
- `LocalModel` validation is filename-based in this milestone. Magic/version checks must happen in the native
  loader during Milestone 2.
- Partial generation is persisted on stop, not per token; process death during generation loses the partial
  reply (the user message remains).
- Demo engine conditionally picks canned responses based on prompt keywords. This is intentionally obvious and
  labeled; it is not a model.
- Debug APK is large and unminified.

## Recommended next actions

1. Install the APK on an Android 10+ device and verify keyboard/insets, drawer, import flow and dark/light
   rendering.
2. Add Compose UI tests and a Room instrumentation test to close the runtime verification gap.
3. Start Milestone 2 by evaluating maintained llama.cpp Android bindings, then implement
   `InferenceEngine` with GGUF validation, memory estimation and cancellation.
