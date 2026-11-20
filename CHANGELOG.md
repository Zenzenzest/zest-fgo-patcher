# Changelog

## 1.2 — 2026-11-20

### Fixed

- Fixed the startup black screen: blocking `FirebaseScript.Initialize` stalled the game's boot sequence. Firebase, analytics, and Crashlytics are now allowed to initialize, and only App Check provider/token errors are suppressed.
- Re-enabled the `EntityScriptUtil.GetStringValue` name fixes (`Altria → Artoria`, `Tam Lin → Faerie Knight`); the startup stall was unrelated to this hook.
- Rebuilt the native hook for arm64-v8a and armeabi-v7a.

### Changed

- Version bumped to 1.2 (code 3); signed with the same release key as 1.1.

## 1.1 — 2026-11-05

### Added

- Complete UI redesign: responsive website-style layout, dark theme by default with a light toggle, cyan accent, real app icon, and a contact/About dialog.
- NP skip: double-tap during a Noble Phantasm holds `Time.timeScale` at 8× and restores it when the NP ends.
- Ascension dialogue tap skip.
- `Altria → Artoria` and `Tam Lin → Faerie Knight` name fixes.
- Telemetry blocking for Firebase/App Check, Crashlytics, and Repro.
- Two-column layout on screens at least 900dp wide; single scrollable column on phones.
- Release APK is now signed with the project release key.

### Changed

- **No donor library.** Production builds use the bundled `libfgohook.so`; the patcher no longer requires or injects a donor library.
- Production output uses `io.zest.betterfgo.en` / `io.zest.betterfgo` (no test-only packages).
- Launcher label is **Zest FGO Patcher**; version bumped to 1.1.
- The injected loader now loads only `libfgohook`.

### Removed

- Donor import and armv7 merge UI.
- The legacy donor-library dependency and donor cache requirement.
- The NP subtitle suppression experiment (not shipped).

### Fixed

- Display hooks install immediately after `il2cpp_init`, so the startup frame-rate cap is actually overridden.
- Hooks resolve on `il2cpp_init` instead of a fixed delay, fixing cold-start crashes.
- TimeScale blocking now only holds the 8× value during an NP skip and forwards normal speed changes.

## 1.0 — 2026-10-27

- Initial public release: on-device patching, 60 FPS, NP skip, ascension dialogue skip, name fixes, and a dark UI.
