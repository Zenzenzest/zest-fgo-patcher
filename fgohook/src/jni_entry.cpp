// FGO native hook library.
//
// Loaded by libLoader. Hooks IL2CPP's init, resolves the game's classes and
// methods by name once the runtime is ready, and installs inline hooks with
// the bundled Dobby.

#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <time.h>
#include <stdint.h>
#include <string.h>

#include <dobby.h>

#include "log.h"

namespace {

typedef void* Il2CppDomain;
typedef void* Il2CppAssembly;
typedef void* Il2CppImage;
typedef void* Il2CppClass;
typedef void* MethodInfo;

typedef Il2CppDomain (*fn_domain_get_t)();
typedef const Il2CppAssembly** (*fn_domain_get_assemblies_t)(Il2CppDomain, size_t*);
typedef Il2CppImage* (*fn_assembly_get_image_t)(const Il2CppAssembly*);
typedef Il2CppClass* (*fn_class_from_name_t)(const Il2CppImage*, const char*, const char*);
typedef MethodInfo* (*fn_class_get_method_from_name_t)(Il2CppClass*, const char*, int);
typedef void* (*fn_init_t)(const char* domain_name);

fn_domain_get_t p_domain_get = nullptr;
fn_domain_get_assemblies_t p_domain_get_assemblies = nullptr;
fn_assembly_get_image_t p_assembly_get_image = nullptr;
fn_class_from_name_t p_class_from_name = nullptr;
fn_class_get_method_from_name_t p_class_get_method_from_name = nullptr;

fn_init_t orig_il2cpp_init = nullptr;
volatile int g_resolved = 0;
volatile int g_init_done = 0;

// The active ascension result component.
void* g_instance = nullptr;
MethodInfo* g_click_next = nullptr;
void* g_click_next_fn = nullptr;
MethodInfo* g_stop_voice = nullptr;
void* g_stop_voice_fn = nullptr;
MethodInfo* g_on_finish_voice = nullptr;
void* g_on_finish_voice_fn = nullptr;
volatile int g_active = 0;
volatile int g_touch_down = 0;
uint64_t g_last_click_ms = 0;

uint64_t now_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000u + static_cast<uint64_t>(ts.tv_nsec) / 1000000u;
}

void* method_ptr(MethodInfo* method) {
    if (method == nullptr) return nullptr;
    return *reinterpret_cast<void**>(method);
}

// --- CombineResultEffectComponent.SetLimitUpCombineInfo ---------------------
typedef void (*SetLimitUp_t)(void* self, int kind, void* base_data, int base_limit,
                             void* callback, void* servant_change, int auto_fadeout,
                             int display_type, int gender_type, void* play_voice_label,
                             void* reward_gift_data, void* method);
SetLimitUp_t orig_set_limit_up = nullptr;

void hook_set_limit_up(void* self, int kind, void* base_data, int base_limit,
                       void* callback, void* servant_change, int auto_fadeout,
                       int display_type, int gender_type, void* play_voice_label,
                       void* reward_gift_data, void* method) {
    g_instance = self;
    g_active = 1;
    g_touch_down = 0;
    LOGI("ascension result active: %p", self);
    if (orig_set_limit_up != nullptr) {
        orig_set_limit_up(self, kind, base_data, base_limit, callback, servant_change,
                          auto_fadeout, display_type, gender_type, play_voice_label,
                          reward_gift_data, method);
    }
}

// --- CombineResultEffectComponent.Close -------------------------------------
typedef void (*Close_t)(void* self, void* method);
Close_t orig_close = nullptr;

void hook_close(void* self, void* method) {
    if (self == g_instance) {
        g_active = 0;
        g_instance = nullptr;
        LOGI("ascension result closed");
    }
    if (orig_close != nullptr) orig_close(self, method);
}

// --- CombineResultEffectComponent.clickNext ---------------------------------
typedef void (*ClickNext_t)(void* self, void* method);

void maybe_click_next() {
    if (!g_active || g_instance == nullptr || g_click_next_fn == nullptr) return;
    const uint64_t now = now_ms();
    if (now - g_last_click_ms < 200) return;
    g_last_click_ms = now;
    LOGI("tap -> stop voice + advance");

    if (g_stop_voice_fn != nullptr) {
        reinterpret_cast<void (*)(void*, void*)>(g_stop_voice_fn)(g_instance, g_stop_voice);
    }
    if (g_on_finish_voice_fn != nullptr) {
        reinterpret_cast<void (*)(void*, int, void*)>(g_on_finish_voice_fn)(
            g_instance, 1, g_on_finish_voice);
    }
    reinterpret_cast<ClickNext_t>(g_click_next_fn)(g_instance, g_click_next);
}

// --- UnityEngine.Input.get_touchCount ---------------------------------------
typedef int (*GetTouchCount_t)(void* method);
GetTouchCount_t orig_get_touch_count = nullptr;

int hook_get_touch_count(void* method) {
    const int count = orig_get_touch_count != nullptr ? orig_get_touch_count(method) : 0;
    if (count > 0) {
        if (!g_touch_down) {
            g_touch_down = 1;
            maybe_click_next();
        }
    } else {
        g_touch_down = 0;
    }
    return count;
}

// --- UnityEngine.Application.set_targetFrameRate (static) -------------------
typedef void (*SetTargetFrameRate_t)(int fps, void* method);
SetTargetFrameRate_t orig_set_target_frame_rate = nullptr;

void hook_set_target_frame_rate(int fps, void* method) {
    (void)fps;
    static bool logged = false;
    if (!logged) {
        LOGI("Setting framerate to 60fps.");
        logged = true;
    }
    if (orig_set_target_frame_rate != nullptr) {
        orig_set_target_frame_rate(60, method);
    }
}

// --- UnityEngine.Time.set_timeScale / get_timeScale (static) ----------------
typedef void (*SetTimeScale_t)(float value, void* method);
SetTimeScale_t orig_set_time_scale = nullptr;

void hook_set_time_scale(float value, void* method) {
    (void)orig_set_time_scale;
    (void)method;
    // Matches the donor: keep the game at normal speed even when a scene tries
    // to slow it down (NP animations, cutscenes).
    LOGI("Blocked timescale change to %.2f", value);
}

typedef float (*GetTimeScale_t)(void* method);
GetTimeScale_t orig_get_time_scale = nullptr;

float hook_get_time_scale(void* method) {
    return orig_get_time_scale != nullptr ? orig_get_time_scale(method) : 1.0f;
}

Il2CppClass* find_class(Il2CppDomain domain, const char* name_space, const char* name) {
    size_t count = 0;
    const Il2CppAssembly** assemblies = p_domain_get_assemblies(domain, &count);
    if (assemblies == nullptr) return nullptr;
    for (size_t i = 0; i < count; ++i) {
        Il2CppImage* image = p_assembly_get_image(assemblies[i]);
        if (image == nullptr) continue;
        Il2CppClass* klass = p_class_from_name(image, name_space, name);
        if (klass != nullptr) return klass;
    }
    return nullptr;
}

/**
 * Runs once, after IL2CPP has finished initializing. Safe to call from the
 * thread that returned from il2cpp_init (it is attached).
 */
void resolve_and_hook() {
    if (g_resolved) return;
    g_resolved = 1;

    Il2CppDomain domain = p_domain_get();
    if (domain == nullptr) {
        LOGE("IL2CPP domain not available");
        g_resolved = 0;
        return;
    }
    LOGI("IL2CPP ready");

    Il2CppClass* combine = nullptr;
    Il2CppClass* input = nullptr;
    // il2cpp_init returns before the game assemblies are fully loaded, so give
    // them time to appear instead of resolving once and failing.
    for (int attempt = 0; attempt < 40; ++attempt) {
        combine = find_class(domain, "", "CombineResultEffectComponent");
        input = find_class(domain, "UnityEngine", "Input");
        if (combine != nullptr && input != nullptr) break;
        usleep(250 * 1000);
    }
    if (combine == nullptr || input == nullptr) {
        LOGE("failed to find game classes (combine=%p input=%p)", combine, input);
        g_resolved = 0;
        return;
    }

    Il2CppClass* application = find_class(domain, "UnityEngine", "Application");
    Il2CppClass* time = find_class(domain, "UnityEngine", "Time");

    MethodInfo* set_limit_up =
        p_class_get_method_from_name(combine, "SetLimitUpCombineInfo", 10);
    MethodInfo* click_next = p_class_get_method_from_name(combine, "clickNext", 0);
    MethodInfo* close = p_class_get_method_from_name(combine, "Close", 0);
    MethodInfo* stop_voice = p_class_get_method_from_name(combine, "stopVoice", 0);
    MethodInfo* on_finish_voice = p_class_get_method_from_name(combine, "OnFinishVoice", 1);
    MethodInfo* touch_count = p_class_get_method_from_name(input, "get_touchCount", 0);
    MethodInfo* set_target_frame_rate = application != nullptr
        ? p_class_get_method_from_name(application, "set_targetFrameRate", 1)
        : nullptr;
    MethodInfo* set_time_scale = time != nullptr
        ? p_class_get_method_from_name(time, "set_timeScale", 1)
        : nullptr;
    MethodInfo* get_time_scale = time != nullptr
        ? p_class_get_method_from_name(time, "get_timeScale", 0)
        : nullptr;

    void* set_limit_up_ptr = method_ptr(set_limit_up);
    void* click_next_ptr = method_ptr(click_next);
    void* close_ptr = method_ptr(close);
    void* stop_voice_ptr = method_ptr(stop_voice);
    void* on_finish_voice_ptr = method_ptr(on_finish_voice);
    void* touch_count_ptr = method_ptr(touch_count);
    void* set_target_frame_rate_ptr = method_ptr(set_target_frame_rate);
    void* set_time_scale_ptr = method_ptr(set_time_scale);
    void* get_time_scale_ptr = method_ptr(get_time_scale);

    LOGI("resolved: setLimitUp=%p clickNext=%p close=%p stopVoice=%p finishVoice=%p touchCount=%p",
         set_limit_up_ptr, click_next_ptr, close_ptr, stop_voice_ptr, on_finish_voice_ptr,
         touch_count_ptr);
    LOGI("resolved: framerate=%p timeScale=%p getTimeScale=%p",
         set_target_frame_rate_ptr, set_time_scale_ptr, get_time_scale_ptr);

    if (set_limit_up_ptr != nullptr) {
        if (DobbyHook(set_limit_up_ptr, reinterpret_cast<void*>(hook_set_limit_up),
                      reinterpret_cast<void**>(&orig_set_limit_up)) == 0) {
            LOGI("hooked SetLimitUpCombineInfo");
        } else {
            LOGE("failed to hook SetLimitUpCombineInfo");
        }
    }
    if (close_ptr != nullptr) {
        if (DobbyHook(close_ptr, reinterpret_cast<void*>(hook_close),
                      reinterpret_cast<void**>(&orig_close)) == 0) {
            LOGI("hooked Close");
        } else {
            LOGE("failed to hook Close");
        }
    }
    if (touch_count_ptr != nullptr) {
        if (DobbyHook(touch_count_ptr, reinterpret_cast<void*>(hook_get_touch_count),
                      reinterpret_cast<void**>(&orig_get_touch_count)) == 0) {
            LOGI("hooked get_touchCount");
        } else {
            LOGE("failed to hook get_touchCount");
        }
    }
    if (set_target_frame_rate_ptr != nullptr) {
        if (DobbyHook(set_target_frame_rate_ptr,
                      reinterpret_cast<void*>(hook_set_target_frame_rate),
                      reinterpret_cast<void**>(&orig_set_target_frame_rate)) == 0) {
            LOGI("hooked Application::set_targetFrameRate");
        } else {
            LOGE("failed to hook Application::set_targetFrameRate");
        }
    } else {
        LOGE("failed to resolve Application::set_targetFrameRate");
    }
    if (set_time_scale_ptr != nullptr) {
        if (DobbyHook(set_time_scale_ptr, reinterpret_cast<void*>(hook_set_time_scale),
                      reinterpret_cast<void**>(&orig_set_time_scale)) == 0) {
            LOGI("hooked Time::set_timeScale");
        } else {
            LOGE("failed to hook Time::set_timeScale");
        }
    } else {
        LOGE("failed to resolve Time::set_timeScale");
    }
    if (get_time_scale_ptr != nullptr) {
        if (DobbyHook(get_time_scale_ptr, reinterpret_cast<void*>(hook_get_time_scale),
                      reinterpret_cast<void**>(&orig_get_time_scale)) == 0) {
            LOGI("hooked Time::get_timeScale");
        } else {
            LOGE("failed to hook Time::get_timeScale");
        }
    } else {
        LOGE("failed to resolve Time::get_timeScale");
    }

    g_click_next = click_next;
    g_click_next_fn = click_next_ptr;
    g_stop_voice = stop_voice;
    g_stop_voice_fn = stop_voice_ptr;
    g_on_finish_voice = on_finish_voice;
    g_on_finish_voice_fn = on_finish_voice_ptr;

    LOGI("hooks installed");
}

void* hook_il2cpp_init(const char* domain_name) {
    void* result = orig_il2cpp_init != nullptr ? orig_il2cpp_init(domain_name) : nullptr;
    LOGI("il2cpp_init returned");
    // The runtime is only safe to touch after the original returns. Signal the
    // init thread instead of resolving here (this runs on Unity's main thread).
    g_init_done = 1;
    return result;
}

void* init_thread(void*) {
    void* lib = nullptr;
    while (lib == nullptr) {
        lib = dlopen("libil2cpp.so", RTLD_NOLOAD | RTLD_NOW);
        if (lib == nullptr) usleep(100 * 1000);
    }

    p_domain_get = reinterpret_cast<fn_domain_get_t>(dlsym(lib, "il2cpp_domain_get"));
    p_domain_get_assemblies = reinterpret_cast<fn_domain_get_assemblies_t>(
        dlsym(lib, "il2cpp_domain_get_assemblies"));
    p_assembly_get_image = reinterpret_cast<fn_assembly_get_image_t>(
        dlsym(lib, "il2cpp_assembly_get_image"));
    p_class_from_name = reinterpret_cast<fn_class_from_name_t>(
        dlsym(lib, "il2cpp_class_from_name"));
    p_class_get_method_from_name = reinterpret_cast<fn_class_get_method_from_name_t>(
        dlsym(lib, "il2cpp_class_get_method_from_name"));

    if (p_domain_get == nullptr || p_domain_get_assemblies == nullptr ||
        p_assembly_get_image == nullptr || p_class_from_name == nullptr ||
        p_class_get_method_from_name == nullptr) {
        LOGE("missing IL2CPP exports");
        return nullptr;
    }

    // il2cpp_init is the reliable "runtime is ready" signal. A fixed timer is
    // not: on a cold start the runtime is still initializing after 3s, and
    // touching it then segfaults. Never call into IL2CPP before this returns.
    void* init_addr = dlsym(lib, "il2cpp_init");
    bool init_hooked = false;
    if (init_addr != nullptr) {
        if (DobbyHook(init_addr, reinterpret_cast<void*>(hook_il2cpp_init),
                      reinterpret_cast<void**>(&orig_il2cpp_init)) == 0) {
            LOGI("hooked il2cpp_init");
            init_hooked = true;
        } else {
            LOGE("failed to hook il2cpp_init");
        }
    } else {
        LOGE("il2cpp_init not exported");
    }

    if (!init_hooked) {
        LOGE("hooks disabled: il2cpp_init hook unavailable");
        return nullptr;
    }

    // Wait for the runtime's own ready signal, then give Unity a moment to load
    // the game assemblies before resolving.
    for (int i = 0; i < 200 && !g_init_done; ++i) usleep(100 * 1000);
    if (!g_init_done) {
        LOGE("il2cpp_init was never called");
        return nullptr;
    }
    sleep(2);
    resolve_and_hook();
    return nullptr;
}

__attribute__((constructor)) void fgohook_init() {
    LOGI("loaded");
    pthread_t thread;
    if (pthread_create(&thread, nullptr, init_thread, nullptr) == 0) {
        pthread_detach(thread);
    } else {
        LOGE("failed to start init thread");
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm;
    (void)reserved;
    return JNI_VERSION_1_6;
}
