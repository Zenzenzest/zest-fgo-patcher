# Zest FGO Patcher

An on-device patcher for **Fate/Grand Order (EN/NA and JP)**. No PC and no donor APK — the patcher injects Zest's own native hook.

## Features

- **60 FPS** unlock
- **NP skip** — double-tap during the Noble Phantasm
- **Ascension dialogue skip** — tap to advance the result dialogue
- **Name fixes** — `Altria → Artoria`, `Tam Lin → Faerie Knight`
- **Telemetry blocking** — Firebase/App Check, Crashlytics, and Repro
- **Modern responsive UI** — dark by default (light toggle), one column on phones, two columns on tablets

## Requirements

- Android 8.0+ (arm64-v8a / armeabi-v7a)
- Fate/Grand Order EN or JP installed, or a vanilla `.xapk`
- No donor app required

This project does **not** ship game files or signing keys.

## Usage

1. Open **Zest FGO Patcher**.
2. Tap **Patch installed Fate/GO …**, or **Select XAPK and patch**.
3. Install the patched copy. It uses its own package id and sits next to vanilla and other mods:
   - EN: `io.zest.betterfgo.en`
   - JP: `io.zest.betterfgo`
4. Sign in with a transfer code (the patched app starts fresh).

The APK is saved to Downloads as `Patched_FGO_EN_<version>.apk` or `Patched_FGO_JP_<version>.apk`.

## Notes

- Each install of Zest FGO Patcher generates its own per-device signing key. Builds produced by the same install update cleanly; builds from a different install need a fresh game install.
- The patcher reads the vanilla game already on your phone; it does not modify the vanilla app.
- The native hook is bundled in the patcher and injected into the patched copy for every ABI the game ships.
- The app contains no game data and no donor library. Game updates may require re-patching.

## Build from source

Requirements: JDK 17, Android SDK 36, Android NDK r27c at `$HOME/ndk/android-ndk-r27c`.

```bash
./fgohook/build.sh          # build libfgohook.so for arm64-v8a + armeabi-v7a
./loader/build.sh           # regenerate app/src/main/assets/fgo/loader.mpe
./gradlew :app:assembleRelease
```

`assembleRelease` produces an unsigned APK; sign it with your own release key before distributing. Use `./gradlew :app:assembleDebug` for local testing.

## Contacts

- GitHub: [Zenzenzest/zest-fgo-patcher](https://github.com/Zenzenzest/zest-fgo-patcher)
- Email: [zenzenzest@gmail.com](mailto:zenzenzest@gmail.com)
- X: [@realzenzenzest](https://x.com/realzenzenzest)
- Discord: `zenzenzest`

## Credits

- [Morphe Patcher](https://github.com/MorpheApp/morphe-patcher) — on-device patching engine (GPLv3)
- [Dobby](https://github.com/jmpews/Dobby) — inline hooking (Apache-2.0)
- BetterFGO / Rayshift — inspired the feature set

Not affiliated with Aniplex, Lasengle, Type-Moon, or Rayshift.

See [CHANGELOG.md](CHANGELOG.md) for release notes.
