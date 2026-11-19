# Milestone 3 — Agent loop with smart approval: build report

Date: 2026-11-19
Status: **complete for the planned scope**, debug APK built and 63 JVM tests passing. The Termux bridge was
verified on the build device (`echo ok`, exit 0). Multi-step behaviour with a real on-device model is still
limited by model size and has not been exhaustively exercised (see Limitations).

## Scope

Milestone 3 turns the single-command Termux tool into a small agent:

- Plan → act → observe prompt: the model picks one command, receives its result, then decides the next step.
- Bounded loop in `ChatViewModel`: at most **6 commands** per user request.
- Risk policy in `CommandPolicy`: `AUTO_READ`, `REVIEW`, `BLOCKED`.
- Smart mode auto-runs only a conservative read-only allowlist; writes, deletes, installs and network commands
  still need a tap; hard-blocked commands never run.
- Per-step audit in the chat: proposal card, policy note, result card, `auto` label, blocked state.
- Duplicate-command detection, stop after two consecutive failures, and Stop cancelling the whole turn.

## Policy

`AUTO_READ` (examples): `pwd`, `whoami`, `id`, `uname`, `date`, `uptime`, `echo`, `printf`, `ls`, `cat`, `head`,
`tail`, `wc`, `grep`/`egrep`/`fgrep`, `find`, `stat`, `file`, `du`, `df`, `which`, `type`, `command`, `basename`,
`dirname`, `realpath`, `readlink`, `md5sum`, `sha1sum`, `sha256sum`, `cut`, `tr`, `sort`, `tree`, `getprop`,
`termux-info`, `ps`, and safe `git` subcommands (`status`, `diff`, `log`, `show`, `branch`, `remote`, `rev-parse`,
`ls-files`, `describe`, `blame`, `shortlog`, `grep`).

Guards that force `REVIEW`: redirection or input (`>`, `<`, `tee`), command separators (`;`, `&&`, `||`),
command substitution/backticks, background jobs, `find -exec/-delete/-fprint`, `sort -o`, `tree -o`,
`tail -f`, `date -s`, `grep --pre`, writes such as `git commit`/`git branch -D`, and commands touching
`.ssh`, `id_rsa`, `id_ed25519`, `.env`, `.gnupg`, keystores or credentials.

`BLOCKED` (never runs): `sudo`, `su`, reboot/shutdown/poweroff, `mkfs`, `dd of=/dev/…`, `pm uninstall|clear`,
`settings put`, `am force-stop`, piping into `sh`/`bash`/`python`/`perl`/`ruby`/`node`, `chmod -R 777 /`,
`chown -R … /`, fork bombs, multi-line commands, and catastrophic recursive `rm` (`/`, `~`, `*`, `/data/data`,
`/sdcard`, `/system`, `/vendor`, …). A blocked automatic step still produces a `BLOCKED` tool result so the
model can choose a safer step; it is never executed.

## Commands

```bash
cd localmind
export JAVA_HOME=$PREFIX/lib/jvm/java-21-openjdk
./gradlew :app:testDebugUnitTest :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2
```

Results:

- `testDebugUnitTest`: **BUILD SUCCESSFUL**, 63 tests, 0 failures (50 → 63 with `CommandPolicyTest` and the
  `auto`/`blocked` codec cases).
- `assembleDebug`: **BUILD SUCCESSFUL**
- APK: `app/build/outputs/apk/debug/app-debug.apk` (about 68 MB debug)
- APK SHA-256: `a3e7928a99aa94a436b2159b0bd1d0038a5b28a00345a1986c784710e6366159`
- Package `com.localmind.app.debug`, version `0.4.0-debug` (versionCode 7)
- Manifest unchanged: `com.termux.permission.RUN_COMMAND`, `<queries>` for `com.termux`, non-exported
  `TermuxResultReceiver`, **no `INTERNET` permission**

## What changed

| Area | Change |
| --- | --- |
| `core/tool/CommandPolicy.kt` | New risk classifier, allowlist, guards and hard blocks |
| `core/tool/ToolResultCodec.kt` | `BLOCKED` status, `auto` flag, `blocked()` result |
| `core/tool/ToolRegistry.kt` | Agent system prompt and environment brief |
| `feature/chat/ChatViewModel.kt` | Bounded loop, auto-run, duplicate/failure stops, shared cancel |
| `feature/chat/ChatScreen.kt` | Step counter, policy note, blocked card, Run disabled when blocked |
| `feature/chat/components/ToolCallCard.kt` | Blocked pill, hidden Run, auto-approved label |
| `feature/settings/SettingsScreen.kt` | Auto-run read-only switch and accurate copy |
| `core/data/settings` + domain | `agentAutoReadOnly` persisted (default on) |
| Tests | `CommandPolicyTest` (9 cases) and 4 new codec cases |

## Limitations

- Model quality dominates reliability. A 0.6B–1.7B model often drifts after 2–3 steps; 4B+ with a larger
  context is noticeably better but slower on a phone.
- The read-only allowlist is conservative. Some genuinely safe commands still require a tap, which costs one
  interaction but not safety.
- `grep -r` inside the home directory can still read sensitive files and is auto-approved if the command text
  does not mention a guarded path. Output stays local and capped, but a future workspace grant should scope it.
- No file tools, no workspace grant, no task panel; those remain later milestones.
- The 30 s wait limit stops LocalMind waiting, not the command inside Termux.
