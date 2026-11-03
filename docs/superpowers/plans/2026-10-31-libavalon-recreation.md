# libavalon Recreation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the proprietary donor `libavalon.so` with our own open-source native hook library, reaching 1:1 client-side feature parity, so FGO Patcher no longer needs a BetterFGO donor APK.

**Architecture:** `libfgohook.so` is loaded by `libLoader`. A background thread waits for `libil2cpp.so` and for IL2CPP to finish initializing, then resolves classes/methods **by name** through the IL2CPP API and installs inline hooks with a bundled Dobby. Features live in small modules behind a single `hooks::install_all` registry. No game code or offsets are patched.

**Tech Stack:** C++17, Android NDK r27c, Dobby (vendored, statically linked), IL2CPP C API, Kotlin/Morphe patcher, smali loader.

**Spec:** `docs/superpowers/specs/2026-10-31-libavalon-recreation-design.md`

## Global Constraints

- Android 8.0+ (API 26), **arm64-v8a + armeabi-v7a**.
- GPLv3; no proprietary binaries, game files, or Rayshift code in the repo.
- All game lookups are by IL2CPP name; no hardcoded RVAs/offsets.
- Every hook failure must log and continue; never crash the game.
- Wait for IL2CPP to finish initializing before any API call or hook.
- Package ids stay `io.zest.betterfgo.en` / `io.zest.betterfgo`.
- Keep the existing Morphe patcher pipeline and `loader.mpe`.
- **Non-goals:** Rayshift drop telemetry, Rayshift webview proxy, server-side features.

## Review Focus

These are the inputs/conditions most likely to break a real user; each has a test in the task that owns it:

1. A game update renames a method → the affected feature must log `failed to hook …` and degrade, not crash (Tasks 3–8).
2. A method's signature/argc differs in EN, JP, or a future version → resolve returns null and is skipped (Tasks 2–8).
3. `GetStringValue` receives null/non-ASCII text → no crash, returns the original (Task 6).
4. A hook is installed before IL2CPP is ready → no crash; init waits (Task 1/2).
5. An AppCheck/telemetry hook returns the wrong type → the game must still reach the title screen (Tasks 7–8).
6. armv7 loads and hooks the same methods as arm64 (Task 10).

---

## File Structure

```
fgohook/
  CMakeLists.txt              builds libfgohook.so, links Dobby static
  build.sh                    NDK build for both ABIs
  include/
    log.h                     LOGI/LOGE -> logcat tag FgoHook
    il2cpp.h                  IL2CPP helper API
    hooks.h                   HookSpec registry API
    features.h                feature installer entry points
  src/
    jni_entry.cpp             JNI_OnLoad + init/wait thread
    il2cpp.cpp                domain/assembly/class/method/string helpers
    hooks.cpp                 Dobby wrapper + registry
    features/
      display.cpp             60fps, timeScale, widescreen
      telemetry.cpp           AppCheck/Firebase/Crashlytics/Repro/Backtrace
      localization.cpp        EntityScriptUtil.GetStringValue
      ascension.cpp           tap -> clickNext()/stopVoice()
  third_party/dobby/          vendored Dobby (patched for NDK r27)
  tests/host/
    test_localization.cpp     pure name-replacement tests
    test_hooks.cpp            HookSpec validation tests
```

The old single-file `fgohook.cpp` is split into the above; its current ascension
logic becomes `features/ascension.cpp`.

---

## Plan 1 — Self-contained core (this plan)

Deliverable: a patched EN/JP build that loads `libfgohook.so` **without
`libavalon`** and has 60 FPS, timeScale blocking, widescreen, name fixes,
AppCheck/telemetry blocking, and the ascension tap working.

### Task 1: Vendored Dobby + minimal self-contained library

**Files:**
- Create: `fgohook/third_party/dobby/` (vendored source)
- Create: `fgohook/CMakeLists.txt`, `fgohook/build.sh`
- Create: `fgohook/include/log.h`, `fgohook/src/jni_entry.cpp`
- Modify: `fgohook/fgohook.cpp` → replaced by the split sources (delete after Task 9)

**Interfaces:**
- Produces: `libfgohook.so` with no `DT_NEEDED` on `libavalon.so` / `libshadowhook.so`; logs `FgoHook: loaded`.

- [ ] **Step 1: Vendor Dobby at a pinned revision**

  Add `third_party/dobby` as a vendored directory (or git submodule) from
  `https://github.com/jmpews/Dobby`. Pin the revision in `third_party/dobby/VERSION`.

- [ ] **Step 2: Apply the two NDK-r27 build fixes**

  In `source/TrampolineBridge/ClosureTrampolineBridge/arm64/closure_bridge_arm64.asm`,
  replace the Apple-only relocations:

  ```asm
  adrp TMP_REG_0, cdecl(common_closure_bridge_handler)@PAGE
  add  TMP_REG_0, TMP_REG_0, cdecl(common_closure_bridge_handler)@PAGEOFF
  ```

  with the ELF form:

  ```asm
  adrp TMP_REG_0, cdecl(common_closure_bridge_handler)
  add  TMP_REG_0, TMP_REG_0, :lo12:cdecl(common_closure_bridge_handler)
  ```

  In `external/osbase/CMakeLists.txt`, add:

  ```cmake
  target_include_directories(osbase PRIVATE
    ${PROJECT_SOURCE_DIR}/source/Backend/UserMode)
  ```

- [ ] **Step 3: Add `CMakeLists.txt` + `build.sh`**

  CMake builds `dobby_static` with `-DPlugin.SymbolResolver=OFF` (we do not use
  `DobbySymbolResolver`) and links it into `libfgohook.so`:

  ```cmake
  cmake_minimum_required(VERSION 3.20)
  project(fgohook CXX C ASM)
  set(CMAKE_CXX_STANDARD 17)
  add_subdirectory(third_party/dobby dobby)
  add_library(fgohook SHARED src/jni_entry.cpp src/il2cpp.cpp src/hooks.cpp
    src/features/display.cpp src/features/telemetry.cpp
    src/features/localization.cpp src/features/ascension.cpp)
  target_link_libraries(fgohook dobby_static log dl)
  ```

  `build.sh` configures with the NDK toolchain for `arm64-v8a` and
  `armeabi-v7a`, `ANDROID_PLATFORM=android-21`, and copies the results to
  `app/src/main/assets/fgo/hook/<abi>/libfgohook.so`.

- [ ] **Step 4: Add the minimal `jni_entry.cpp`**

  `JNI_OnLoad` logs `loaded`, then `pthread_create`s an init thread that:

  1. `dlopen("libil2cpp.so", RTLD_NOLOAD)` in a retry loop.
  2. `dlsym`s the IL2CPP API.
  3. `sleep(3)` (do **not** call IL2CPP before this).
  4. waits for `il2cpp_domain_get()` non-null.
  5. calls the feature installers.

- [ ] **Step 5: Build and verify dependencies**

  Run: `./build.sh`
  Expected: both `.so` files build.

  Run: `llvm-readelf -d out/arm64-v8a/libfgohook.so | grep NEEDED`
  Expected: `liblog.so`, `libdl.so`, `libm.so`, `libc.so` only — **no**
  `libavalon.so`, **no** `libshadowhook.so`, **no** `libc++_shared.so`.

- [ ] **Step 6: On-device load test**

  Inject only `libfgohook.so`, load it via the loader, launch the game, and run:

  ```bash
  adb logcat -d -s FgoHook
  ```

  Expected: `FgoHook: loaded` with no crash.

- [ ] **Step 7: Commit**

  ```bash
  git add fgohook
  git commit -m "feat(native): self-contained fgohook with vendored Dobby"
  ```

### Task 2: IL2CPP helper

**Files:**
- Create: `fgohook/include/il2cpp.h`, `fgohook/src/il2cpp.cpp`
- Modify: `fgohook/src/jni_entry.cpp`

**Interfaces:**
- Produces:
  ```cpp
  namespace zest::il2cpp {
  struct Method { void* info; void* fn; };
  bool wait_ready(int timeout_ms);
  void* image_for(const char* assembly_name);
  Method find(void* image, const char* ns, const char* klass,
              const char* name, int argc);
  void* make_string(const char* utf8);
  std::string read_string(void* il2cpp_string);
  }
  ```

- [ ] **Step 1: Write the failing host test for the name lookup helper**

  `tests/host/test_hooks.cpp` verifies a pure `looks_like_assembly("Assembly-CSharp.dll")`
  helper used by `image_for`; run it with the host compiler and expect failure
  before the helper exists.

- [ ] **Step 2: Implement `il2cpp.cpp`**

  Resolve `il2cpp_domain_get`, `il2cpp_domain_get_assemblies`,
  `il2cpp_assembly_get_image`, `il2cpp_class_from_name`,
  `il2cpp_class_get_method_from_name`, `il2cpp_string_new`,
  `il2cpp_string_chars`. `find()` returns `{nullptr, nullptr}` on any miss.

- [ ] **Step 3: Verify on-device**

  In `jni_entry.cpp` log the resolved pointers for
  `CombineResultEffectComponent.clickNext` (0 args) and
  `UnityEngine.Application.set_targetFrameRate` (1 arg).

  ```bash
  adb logcat -d -s FgoHook | grep resolved
  ```

  Expected: non-null pointers for both.

- [ ] **Step 4: Commit**

  ```bash
  git add fgohook/include/il2cpp.h fgohook/src/il2cpp.cpp fgohook/src/jni_entry.cpp fgohook/tests
  git commit -m "feat(native): IL2CPP name-based method resolution"
  ```

### Task 3: Hook registry

**Files:**
- Create: `fgohook/include/hooks.h`, `fgohook/src/hooks.cpp`
- Create: `fgohook/include/features.h`
- Test: `fgohook/tests/host/test_hooks.cpp`

**Interfaces:**
- Produces:
  ```cpp
  namespace zest::hooks {
  struct Spec {
      const char* assembly; const char* ns; const char* klass;
      const char* method; int argc;
      void* replacement; void** original;
  };
  bool install(const Spec& spec);
  bool install_all(const Spec* specs, size_t count);
  }
  ```

- [ ] **Step 1: Write the failing host test for `valid_spec`**

  A pure `valid_spec(const Spec&)` rejects null names and negative argc.

- [ ] **Step 2: Implement `install()`**

  Resolve via `il2cpp::find`; call `DobbyHook(fn, replacement, original)`;
  log `hooked <klass>::<method>` on success and `failed to hook <klass>::<method>`
  otherwise. Return false on any failure; never throw.

- [ ] **Step 3: Implement `install_all()`**

  Iterate the specs, collect a count, log `N/M hooks installed`.

- [ ] **Step 4: Run the host tests**

  Run: `./tests/run_host.sh`
  Expected: PASS for `valid_spec` cases.

- [ ] **Step 5: Verify a missing method degrades (Review Focus 1)**

  Temporarily add a spec for `DoesNotExist.Method` to `install_all`, launch the
  game, and check:

  ```bash
  adb logcat -d -s FgoHook | grep "failed to hook DoesNotExist::Method"
  adb shell pidof io.zest.betterfgo.en
  ```

  Expected: the failure line appears and the process still reaches the title
  screen. Remove the temporary spec.

- [ ] **Step 6: Commit**

  ```bash
  git add fgohook/include/hooks.h fgohook/src/hooks.cpp fgohook/tests
  git commit -m "feat(native): hook registry with per-hook logging"
  ```

### Task 4: 60 FPS + timeScale

**Files:**
- Create: `fgohook/src/features/display.cpp`
- Modify: `fgohook/src/jni_entry.cpp` (call `install_display()`)

**Interfaces:**
- Produces: `bool zest::features::install_display();`
- Hooks:
  - `UnityEngine.Application.set_targetFrameRate` (1 arg)
  - `UnityEngine.Time.set_timeScale` (1 arg)
  - `UnityEngine.Time.get_timeScale` (0 args)

- [ ] **Step 1: Implement the replacements**

  `set_targetFrameRate(self, int fps, mi)` calls the original with `60` and logs
  `Setting framerate to 60fps.` once. `set_timeScale(self, float v, mi)` logs
  `Blocked timescale change to <v>` and does not forward the change (matches
  the donor). `get_timeScale` forwards unchanged.

- [ ] **Step 2: Build and install**

  Build, patch, install, launch. Run:

  ```bash
  adb logcat -d -s FgoHook | grep -E "framerate|timescale"
  ```

  Expected: `Setting framerate to 60fps.` present; the game renders at 60 fps.

- [ ] **Step 3: Commit**

  ```bash
  git add fgohook/src/features/display.cpp fgohook/src/jni_entry.cpp
  git commit -m "feat(native): 60fps and timeScale blocking"
  ```

### Task 5: Widescreen

**Files:**
- Modify: `fgohook/src/features/display.cpp`

**Interfaces:**
- Hook: `CommonUI.SetForceObi_16_9` (1 arg).

- [ ] **Step 1: Implement the hook**

  Call the original with `true`; log `widescreen forced`. If the method is
  missing, log `failed to hook CommonUI::SetForceObi_16_9` and continue.

- [ ] **Step 2: Verify visually**

  Patch/install; the game must use the full 16:9 width with no side bars.

- [ ] **Step 3: Commit**

  ```bash
  git add fgohook/src/features/display.cpp
  git commit -m "feat(native): force 16:9 widescreen"
  ```

### Task 6: Name fixes

**Files:**
- Create: `fgohook/src/features/localization.cpp`
- Test: `fgohook/tests/host/test_localization.cpp`

**Interfaces:**
- Produces: `bool zest::features::install_localization();`
- Pure helper: `std::string replace_name(const std::string& in);`
- Hook: `EntityScriptUtil.GetStringValue` (3 args).

- [ ] **Step 1: Write the failing host test**

  ```cpp
  assert(replace_name("Altria") == "Artoria");
  assert(replace_name("Tam Lin") == "Faerie Knight");
  assert(replace_name("Mash") == "Mash");
  assert(replace_name("") == "");
  ```

- [ ] **Step 2: Implement `replace_name` + the hook**

  `GetStringValue(self, key, def, mi)` reads the returned string, applies
  `replace_name`, returns a new IL2CPP string; null input → forward the original.

- [ ] **Step 3: Run the host test**

  Run: `./tests/run_host.sh`
  Expected: PASS.

- [ ] **Step 4: Guard null/non-ASCII (Review Focus 3)**

  In the hook, a null IL2CPP string forwards the original; add a host test for
  a UTF-8 string. Launch and open a servant; the game must not crash.

- [ ] **Step 5: Verify in-game**

  Open a servant whose name is affected and confirm `Artoria` / `Faerie Knight`.

- [ ] **Step 6: Commit**

  ```bash
  git add fgohook/src/features/localization.cpp fgohook/tests
  git commit -m "feat(native): Altria/Artoria and Tam Lin/Faerie Knight fixes"
  ```

### Task 7: AppCheck / Firebase blocking

**Files:**
- Create: `fgohook/src/features/telemetry.cpp`

**Interfaces:**
- Produces: `bool zest::features::install_telemetry();`
- Hooks:
  - `FirebaseScript.Initialize`
  - `SetupAppCheckProvider`
  - `get_AppCheckTokenError` / `set_AppCheckTokenError`

- [ ] **Step 1: Implement the hooks**

  `Initialize` / `SetupAppCheckProvider` return immediately and log
  `Blocked FirebaseScript Initialise.` / `Blocked SetupAppCheckProvider.`.
  The token-error accessors are forced to a neutral value.

- [ ] **Step 2: Verify startup**

  Patch/install/launch. Run:

  ```bash
  adb logcat -d -s FgoHook | grep Blocked
  adb shell pidof io.zest.betterfgo.en
  ```

  Expected: the `Blocked …` lines appear and the process stays alive at the
  title screen.

- [ ] **Step 3: Commit**

  ```bash
  git add fgohook/src/features/telemetry.cpp
  git commit -m "feat(native): block Firebase AppCheck"
  ```

### Task 8: Crashlytics / Repro / Backtrace blocking

**Files:**
- Modify: `fgohook/src/features/telemetry.cpp`

- [ ] **Step 1: Add the hooks**

  No-op replacements for `CrashReporter.BacktraceSetAttributes`,
  `BacktraceStartUp`, `BacktraceDisableSampling`, `BacktraceEnableSampling`,
  `BacktraceDisable`, `BacktraceEnable`, `CrashlyticsAddCustomKey`,
  `AdManager.InitializeRepro`, `SetUserId`, `SetFriendCode`, `TrackEvent`,
  `SendPushRegistrationId`, logging `Blocked <name>.`.

- [ ] **Step 2: Verify**

  ```bash
  adb logcat -d -s FgoHook | grep Blocked
  ```

  Expected: all twelve `Blocked …` lines (or explicit `failed to hook` for ones
  absent in this version), game still running.

- [ ] **Step 3: Commit**

  ```bash
  git add fgohook/src/features/telemetry.cpp
  git commit -m "feat(native): block Crashlytics/Repro/Backtrace telemetry"
  ```

### Task 9: Port the ascension tap

**Files:**
- Create: `fgohook/src/features/ascension.cpp` (from `fgohook/fgohook.cpp`)
- Delete: `fgohook/fgohook.cpp`
- Modify: `fgohook/src/jni_entry.cpp`

**Interfaces:**
- Produces: `bool zest::features::install_ascension();`
- Hooks: `CombineResultEffectComponent.SetLimitUpCombineInfo`, `Close`,
  `UnityEngine.Input.get_touchCount`.

- [ ] **Step 1: Move the existing logic**

  Keep the behavior already proven on-device: capture the component, detect the
  touch rising edge, call `stopVoice` → `OnFinishVoice(true)` → `clickNext`.
  Replace the direct `dlsym("libavalon.so")` with the registry/il2cpp helpers.

- [ ] **Step 2: Verify**

  Ascend a servant and tap. Run:

  ```bash
  adb logcat -d -s FgoHook | grep -E "ascension result active|tap ->"
  ```

  Expected: both lines, and the result screen advances.

- [ ] **Step 3: Commit**

  ```bash
  git add fgohook/src/features/ascension.cpp fgohook/fgohook.cpp fgohook/src/jni_entry.cpp
  git commit -m "refactor(native): port ascension tap to the hook registry"
  ```

### Task 10: Patcher integration (Plan 1 milestone)

**Files:**
- Modify: `app/src/main/kotlin/io/zest/fgopatcher/FgoPatch.kt`
- Modify: `loader/src/com/loadLib/libLoader$1.smali`
- Modify: `app/src/main/assets/fgo/hook/<abi>/libfgohook.so`
- Modify: `README.md` (feature list)

**Interfaces:**
- The patcher injects `libfgohook.so`; the loader loads it (still alongside
  `libavalon` until Plan 2/3 reach parity).

- [ ] **Step 1: Rebuild the two `.so` files and copy them into assets**

  ```bash
  ./fgohook/build.sh
  cp fgohook/out/arm64-v8a/libfgohook.so app/src/main/assets/fgo/hook/arm64-v8a/
  cp fgohook/out/armeabi-v7a/libfgohook.so app/src/main/assets/fgo/hook/armeabi-v7a/
  ```

- [ ] **Step 2: Load order**

  Keep `avalon` then `fgohook` in the loader (temporary). `FgoPatch` already
  injects `libfgohook.so`.

- [ ] **Step 3: Full EN/JP test**

  Patch EN 2.94.1 and JP 2.139.5; install; run:

  ```bash
  adb logcat -d -s FgoHook | grep -E "hooked|framerate|Blocked|resolved"
  ```

  Expected: the Plan 1 hooks install and fire on both variants.

- [ ] **Step 4: Verify both ABIs are packaged (Review Focus 6)**

  ```bash
  unzip -l app/build/outputs/apk/debug/app-debug.apk | grep 'lib/.*/libfgohook.so'
  ```

  Expected: both `lib/arm64-v8a/libfgohook.so` and `lib/armeabi-v7a/libfgohook.so`.
  On an armv7 target (emulator or donor device) confirm `FgoHook: loaded`.

- [ ] **Step 5: Commit**

  ```bash
  git add app/src/main/kotlin/io/zest/fgopatcher/FgoPatch.kt loader README.md app/src/main/assets/fgo/hook
  git commit -m "feat: load self-contained fgohook in patched builds"
  ```

### Task 11: Docs + handoff

**Files:**
- Modify: `README.md`, `NOTICE`

- [ ] **Step 1: Document the architecture and the current feature set.**

- [ ] **Step 2: Commit**

  ```bash
  git add README.md NOTICE
  git commit -m "docs: document fgohook architecture and Plan 1 features"
  ```

---

## Plan 2 — NP skip + subtitle + movie (separate plan)

Subsystems (each produces working software on its own):

- `features/np_skip.cpp`: hook `BattlePerformance.startNoblePhantasm` /
  `endNoblePhantasm`; hook `UICamera.Notify`, match `OnDoubleClick`, and force
  the NP end while an NP is active.
- `features/subtitle.cpp`: `PlainSubtitleManager.Play/Stop`,
  `OptionManager.get_DispNPSubtitle`.
- `features/movie.cpp`: `WellFired.USFGOPlayAudioEvent` / `get_IsPlayable`,
  movie commands `PLAY_MOVIE` / `WAIT_FINISH_MOVIE`.
- Test matrix: EN + JP, double-click during an NP, subtitle on/off, story movie
  skip, no crash when the game updates a name.

## Plan 3 — Battle fast-forward (separate plan)

- Reverse the `DataMasterBase<…>` / `battle` / `Replaced` flow.
- Implement script-command skipping (the full command table).
- Verify: no soft-lock at battle end, drops/EXP still granted, EN + JP.

## Plan 4 — Remove the donor (separate plan)

- Drop `libavalon` injection and the donor requirement from `FgoPatch.kt` /
  `MainActivity.kt`.
- Drop the `RestClient` stub and the webview proxy (already absent).
- Update the UI, README, and release flow; ship 1.0.1 without a donor.

---

## Self-Review

- **Spec coverage:** every Goal maps to a task (core Tasks 1–3; 60fps/timeScale
  Task 4; widescreen Task 5; names Task 6; AppCheck Task 7; telemetry Task 8;
  ascension Task 9; patcher Task 10). NP/movie/battle/removal are explicitly
  deferred to Plans 2–4, matching the spec's risk split.
- **Step scan:** steps are one action each; no step hides a multi-feature
  change.
- **Type consistency:** `il2cpp::find`, `hooks::install`, and the feature
  installers use the same signatures across tasks.
- **Review Focus:** each of the six lines has a test in Tasks 1–8/10.
- **Proportion:** the plan describes decisions and tests, not full function
  bodies.
