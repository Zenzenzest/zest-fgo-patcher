# libavalon Recreation — Design

## Intent

Replace the proprietary donor binary `libavalon.so` with an open-source native
hook library owned by this project. The patcher must no longer require a
BetterFGO donor APK, and the shipped app must contain no Rayshift binary,
telemetry, or server-side integration.

Target: **1:1 client-side feature parity** with the donor `libavalon` (EN 2.93 /
JP 2.139.5), plus our existing ascension tap hook.

## Why

- Removes the donor APK requirement (the biggest UX/legal problem).
- Removes Rayshift's drop telemetry (`RestClient.SubmitDropData`) and webview
  proxy (`webview-proxy-*.rayshift.io`).
- Keeps the repository fully GPLv3 and self-contained (vanilla game only).

## Goals

- Own inline-hook engine (bundled Dobby), arm64-v8a + armeabi-v7a.
- IL2CPP name-based resolution for EN and JP; no hardcoded offsets.
- Feature parity:
  - 60 FPS + `Time.timeScale` blocking.
  - NP skip on double-click + NP subtitle handling.
  - Name fixes (`Altria` → `Artoria`, `Tam Lin` → `Faerie Knight`).
  - Widescreen (`CommonUI.SetForceObi_16_9`).
  - Movie/audio skip (`WellFired` / movie commands).
  - AppCheck / Firebase / Crashlytics / Repro / Backtrace blocking.
  - Battle fast-forward / script skip (`DataMasterBase` / `Replaced`).
  - Ascension tap-to-advance (already implemented).
- Patcher injects/loads the new library; no donor, no `libavalon`, no
  `RestClient` stub in the final state.

## Non-goals

- Rayshift drop telemetry.
- Rayshift webview proxy; the official `webview.fate-go.us` /
  `view.fate-go.jp` URLs stay.
- Any server-side feature.
- Modifying the game's `libil2cpp.so`, metadata, or assets.
- iOS / x86 ABIs.

## Constraints

- Android 8.0+ (API 26+), arm64-v8a and armeabi-v7a.
- GPLv3; no proprietary binaries in the repo.
- Name-based lookups; game updates may rename methods (log and degrade, never
  crash).
- Existing Morphe patcher pipeline and `loader.mpe` remain.
- Existing package ids: `io.zest.betterfgo.en` / `io.zest.betterfgo`.

## Architecture

```
libfgohook.so
  jni_entry.cpp      JNI_OnLoad -> wait for libil2cpp -> install hooks
  il2cpp.cpp         domain/assembly/class/method/string helpers
  hooks.cpp          Dobby wrapper + HookSpec registry
  features/
    display.cpp      60fps, timeScale, widescreen
    telemetry.cpp    AppCheck/Firebase/Crashlytics/Repro/Backtrace blocking
    localization.cpp EntityScriptUtil.GetStringValue replacements
    ascension.cpp    tap -> clickNext()/stopVoice()
    np_skip.cpp      (Plan 2)
    movie.cpp        (Plan 2)
    battle.cpp       (Plan 3)
third_party/dobby/   vendored, statically linked
loader/              loads libfgohook.so
app/                 patcher injects libfgohook.so
```

Initialization order is fixed: `libfgohook.so` is loaded after the game starts
(via `libLoader`), then a background thread waits for `libil2cpp.so` and for
IL2CPP to finish initializing before resolving or hooking anything.

## Feature matrix (donor -> ours)

| Donor hook | Ours | Plan |
|---|---|---|
| `Application.set_targetFrameRate` | force 60 | 1 |
| `Time.set_timeScale` / `get_timeScale` | block changes | 1 |
| `CommonUI.SetForceObi_16_9` | force 16:9 | 1 |
| `EntityScriptUtil.GetStringValue` | name replacements | 1 |
| `FirebaseScript.Initialize` / `SetupAppCheckProvider` | block | 1 |
| `CrashReporter.Backtrace*` / `CrashlyticsAddCustomKey` | block | 1 |
| `AdManager.InitializeRepro` / `SetUserId` / `TrackEvent` / … | block | 1 |
| ascension result (ours) | tap -> `clickNext()` | 1 |
| `BattlePerformance.start/endNoblePhantasm` + `UICamera.Notify` | NP skip | 2 |
| `PlainSubtitleManager` / `OptionManager.get_DispNPSubtitle` | subtitle | 2 |
| `WellFired.USFGOPlayAudioEvent` / movie commands | movie skip | 2 |
| battle `DataMasterBase` / `Replaced` | battle fast-forward | 3 |
| `RestClient.SubmitDropData` | **not recreated** | — |
| webview proxy | **not recreated** | — |

## Risks

- **Dobby build** is the critical path. The vendored master has two known build
  issues with NDK r27 (Mach-O-only asm, missing `OSMemory` include). Fixing
  them is Task 1. Fallback: a known-good older Dobby commit, or ShadowHook
  1.1.1 (it failed `INIT_LINKER` on Android 17, so Dobby is preferred).
- **Battle fast-forward** needs the most reverse engineering; it is isolated in
  Plan 3 so it cannot block the rest.
- **Version fragility**: every hook is name-based. Each feature must log
  `failed to hook <name>` and continue instead of crashing.
- **ABI drift**: armv7 and arm64 must both be built and load-tested.

## Testing

- Host unit tests for pure logic (name replacement, hook-spec validation).
- On-device integration via a patched EN/JP build and `adb logcat -s FgoHook`.
  Each feature emits one line on install and one line when it fires.
- A feature is done only when the log proves it installed **and** a real
  in-game action proves it fired.
