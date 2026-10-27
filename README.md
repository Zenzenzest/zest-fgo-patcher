# FGO Patcher

An on-device patcher for **Fate/Grand Order (EN/NA and JP)**. It produces a separate modded app:

- 60 FPS unlock
- NP animation skip
- Tap to advance ascension dialogue
- Widescreen and `Altria → Artoria` name fix

No PC needed. Patch the game already installed on your phone, or pick a vanilla `.xapk`.

## Requirements

- Android 8.0+ (arm64 / armeabi-v7a)
- Fate/Grand Order EN or JP — installed, or a vanilla XAPK
- A BetterFGO donor APK (e.g. Rayshift) for `libavalon.so`. It is auto-detected when installed; otherwise import one APK once.

This project does **not** ship game files, `libavalon.so`, or signing keys.

## Usage

1. Open FGO Patcher.
2. Tap **Patch installed Fate/GO …**, or **Select FGO XAPK and patch**.
3. Install the result. It uses its own package id and sits next to vanilla and Rayshift:
   - EN: `io.zest.betterfgo.en`
   - JP: `io.zest.betterfgo`
4. Sign in with a transfer code (the patched app starts fresh).

The APK is saved to Downloads as `Patched_<name>.apk`.

## Notes

- Each install of FGO Patcher generates its own signing key. Builds produced by the same install update cleanly.
- The donor cache is per-variant and refreshes when the donor version changes.
- If the XAPK is arm64-only, armv7 can optionally be merged from a matching donor.

## Credits

- [BetterFGO / Rayshift](https://rayshift.io/) — the original mod and `libavalon.so`. Proprietary; not included.
- [Morphe Patcher](https://github.com/MorpheApp/morphe-patcher) — on-device patching engine (GPLv3).
- [Dobby](https://github.com/jmpews/Dobby) — inline hooking, used through `libavalon.so`.

The ascension tap hook (`libfgohook.so`, source in `fgohook/`) is this project's own code.

## Build

JDK 17+ and Android SDK 36. Morphe comes from `settings.gradle.kts`; if GitHub Packages asks for credentials, add `gpr.user` / `gpr.key` to `~/.gradle/gradle.properties`.

```bash
./gradlew :app:assembleDebug
```

GPLv3 — see `LICENSE` and `NOTICE`.

Repo: https://github.com/Zenzenzest/zest-fgo-patcher
