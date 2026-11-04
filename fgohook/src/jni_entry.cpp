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
#include "localization.h"

namespace {

typedef void* Il2CppDomain;
typedef void* Il2CppAssembly;
typedef void* Il2CppImage;
typedef void* Il2CppClass;
typedef void* MethodInfo;
typedef void Il2CppType;

typedef Il2CppDomain (*fn_domain_get_t)();
typedef const Il2CppAssembly** (*fn_domain_get_assemblies_t)(Il2CppDomain, size_t*);
typedef Il2CppImage* (*fn_assembly_get_image_t)(const Il2CppAssembly*);
typedef Il2CppClass* (*fn_class_from_name_t)(const Il2CppImage*, const char*, const char*);
typedef MethodInfo* (*fn_class_get_method_from_name_t)(Il2CppClass*, const char*, int);
typedef uint32_t (*fn_method_get_flags_t)(const MethodInfo*, uint32_t*);
typedef void* (*fn_string_new_t)(const char*);
typedef uint16_t* (*fn_string_chars_t)(void*);
typedef int32_t (*fn_string_length_t)(void*);
typedef size_t (*fn_image_get_class_count_t)(const Il2CppImage*);
typedef Il2CppClass* (*fn_image_get_class_t)(const Il2CppImage*, size_t);
typedef void* (*fn_class_get_methods_t)(Il2CppClass*, void**);
typedef const char* (*fn_method_get_name_t)(void*);
typedef const char* (*fn_class_get_name_t)(Il2CppClass*);
typedef uint32_t (*fn_method_get_param_count_t)(const MethodInfo*);
typedef const Il2CppType* (*fn_method_get_param_t)(const MethodInfo*, uint32_t);
typedef const Il2CppType* (*fn_method_get_return_type_t)(const MethodInfo*);
typedef char* (*fn_type_get_name_t)(const Il2CppType*);
typedef void* (*fn_init_t)(const char* domain_name);

fn_domain_get_t p_domain_get = nullptr;
fn_domain_get_assemblies_t p_domain_get_assemblies = nullptr;
fn_assembly_get_image_t p_assembly_get_image = nullptr;
fn_class_from_name_t p_class_from_name = nullptr;
fn_class_get_method_from_name_t p_class_get_method_from_name = nullptr;
fn_method_get_flags_t p_method_get_flags = nullptr;
fn_string_new_t p_string_new = nullptr;
fn_string_chars_t p_string_chars = nullptr;
fn_string_length_t p_string_length = nullptr;
fn_image_get_class_count_t p_image_get_class_count = nullptr;
fn_image_get_class_t p_image_get_class = nullptr;
fn_class_get_methods_t p_class_get_methods = nullptr;
fn_method_get_name_t p_method_get_name = nullptr;
fn_class_get_name_t p_class_get_name = nullptr;
fn_method_get_param_count_t p_method_get_param_count = nullptr;
fn_method_get_param_t p_method_get_param = nullptr;
fn_method_get_return_type_t p_method_get_return_type = nullptr;
fn_type_get_name_t p_type_get_name = nullptr;

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

// NP skip: the donor holds Time.timeScale at 8.0 until the NP ends.
volatile int g_np_skip_active = 0;
float g_saved_time_scale = 1.0f;
MethodInfo* g_set_time_scale_method = nullptr;
MethodInfo* g_get_time_scale_method = nullptr;

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
    // While an NP skip is active, hold the 8x fast-forward: ignore every
    // timescale change the game tries to make. Outside a skip, forward
    // everything (slow-mo, normal speed, fast-forward).
    if (g_np_skip_active) {
        LOGI("Blocked timescale change to %.2f", value);
        return;
    }
    if (orig_set_time_scale != nullptr) {
        orig_set_time_scale(value, method);
    }
}

typedef float (*GetTimeScale_t)(void* method);
GetTimeScale_t orig_get_time_scale = nullptr;

float hook_get_time_scale(void* method) {
    return orig_get_time_scale != nullptr ? orig_get_time_scale(method) : 1.0f;
}

// --- EntityScriptUtil.GetStringValue (static or instance) -------------------
std::string utf16_to_utf8(const uint16_t* chars, int32_t length) {
    std::string out;
    out.reserve(static_cast<size_t>(length));
    for (int32_t i = 0; i < length; ++i) {
        uint32_t code = chars[i];
        if (code >= 0xD800 && code <= 0xDBFF && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        if (code <= 0x7F) {
            out.push_back(static_cast<char>(code));
        } else if (code <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (code >> 6)));
            out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
        } else if (code <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (code >> 12)));
            out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (code >> 18)));
            out.push_back(static_cast<char>(0x80 | ((code >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
        }
    }
    return out;
}

std::string il2cpp_string_to_utf8(void* value) {
    if (value == nullptr || p_string_length == nullptr || p_string_chars == nullptr) {
        return {};
    }
    const int32_t length = p_string_length(value);
    const uint16_t* chars = p_string_chars(value);
    if (chars == nullptr || length <= 0) return {};
    return utf16_to_utf8(chars, length);
}

void* replace_il2cpp_string(void* value) {
    if (value == nullptr) return nullptr;
    if (p_string_chars == nullptr || p_string_length == nullptr || p_string_new == nullptr) {
        return value;
    }
    const int32_t length = p_string_length(value);
    const uint16_t* chars = p_string_chars(value);
    if (chars == nullptr || length <= 0) return value;

    const std::string original = utf16_to_utf8(chars, length);
    const std::string fixed = zest::replace_name(original);
    if (fixed == original) return value;

    LOGI("name fix: %s -> %s", original.c_str(), fixed.c_str());
    return p_string_new(fixed.c_str());
}

typedef void* (*GetStringValueStatic_t)(void*, void*, void*, void*);
typedef void* (*GetStringValueInstance_t)(void*, void*, void*, void*, void*);
GetStringValueStatic_t orig_get_string_value_static = nullptr;
GetStringValueInstance_t orig_get_string_value_instance = nullptr;

void* hook_get_string_value_static(void* a1, void* a2, void* a3, void* method) {
    void* result = orig_get_string_value_static != nullptr
        ? orig_get_string_value_static(a1, a2, a3, method)
        : nullptr;
    return replace_il2cpp_string(result);
}

void* hook_get_string_value_instance(void* self, void* a1, void* a2, void* a3, void* method) {
    void* result = orig_get_string_value_instance != nullptr
        ? orig_get_string_value_instance(self, a1, a2, a3, method)
        : nullptr;
    return replace_il2cpp_string(result);
}

// --- Telemetry blockers -----------------------------------------------------
// Each replacement ignores its arguments and returns null. The hooked methods
// are void/bool/string-returning; null maps to "nothing happened"/false.
#define DEFINE_BLOCK_HOOK(fn, label)      \
    void* fn() {                          \
        static bool logged = false;       \
        if (!logged) {                    \
            LOGI("Blocked %s.", label);   \
            logged = true;                \
        }                                 \
        return nullptr;                   \
    }

DEFINE_BLOCK_HOOK(block_firebase_initialize, "FirebaseScript Initialise")
DEFINE_BLOCK_HOOK(block_setup_appcheck, "SetupAppCheckProvider")
DEFINE_BLOCK_HOOK(block_get_appcheck_error, "get_AppCheckTokenError")
DEFINE_BLOCK_HOOK(block_set_appcheck_error, "set_AppCheckTokenError")
DEFINE_BLOCK_HOOK(block_bt_set_attributes, "backtrace SetAttributes")
DEFINE_BLOCK_HOOK(block_bt_startup, "backtrace startup")
DEFINE_BLOCK_HOOK(block_bt_disable_sampling, "backtrace DisableSampling")
DEFINE_BLOCK_HOOK(block_bt_enable_sampling, "backtrace EnableSampling")
DEFINE_BLOCK_HOOK(block_bt_disable, "backtrace Disable")
DEFINE_BLOCK_HOOK(block_bt_enable, "backtrace Enable")
DEFINE_BLOCK_HOOK(block_crashlytics_key, "Crashlytics AddCustomKey")
DEFINE_BLOCK_HOOK(block_repro_init, "ReproNotification initialize")
DEFINE_BLOCK_HOOK(block_repro_user_id, "ReproNotification SetUserId")
DEFINE_BLOCK_HOOK(block_repro_friend_code, "ReproNotification SetFriendCode")
DEFINE_BLOCK_HOOK(block_repro_track, "ReproNotification TrackEvent")
DEFINE_BLOCK_HOOK(block_repro_push, "ReproNotification SetPushRegistrationId")

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

MethodInfo* find_method_anywhere(Il2CppDomain domain, const char* method_name, Il2CppClass** owner) {
    if (owner != nullptr) *owner = nullptr;
    if (p_image_get_class_count == nullptr || p_image_get_class == nullptr ||
        p_class_get_methods == nullptr || p_method_get_name == nullptr) {
        return nullptr;
    }

    size_t assembly_count = 0;
    const Il2CppAssembly** assemblies = p_domain_get_assemblies(domain, &assembly_count);
    if (assemblies == nullptr) return nullptr;

    for (size_t a = 0; a < assembly_count; ++a) {
        Il2CppImage* image = p_assembly_get_image(assemblies[a]);
        if (image == nullptr) continue;
        const size_t class_count = p_image_get_class_count(image);
        for (size_t i = 0; i < class_count; ++i) {
            Il2CppClass* klass = p_image_get_class(image, i);
            if (klass == nullptr) continue;
            void* iter = nullptr;
            void* method = nullptr;
            while ((method = p_class_get_methods(klass, &iter)) != nullptr) {
                const char* name = p_method_get_name(method);
                if (name != nullptr && strcmp(name, method_name) == 0) {
                    if (owner != nullptr) *owner = klass;
                    return reinterpret_cast<MethodInfo*>(method);
                }
            }
        }
    }
    return nullptr;
}

const char* class_name_of(Il2CppClass* klass) {
    if (klass == nullptr || p_class_get_name == nullptr) return "?";
    const char* name = p_class_get_name(klass);
    return name != nullptr ? name : "?";
}

Il2CppClass* find_class_anywhere(Il2CppDomain domain, const char* class_name) {
    if (p_image_get_class_count == nullptr || p_image_get_class == nullptr ||
        p_class_get_name == nullptr) {
        return nullptr;
    }
    size_t assembly_count = 0;
    const Il2CppAssembly** assemblies = p_domain_get_assemblies(domain, &assembly_count);
    if (assemblies == nullptr) return nullptr;
    for (size_t a = 0; a < assembly_count; ++a) {
        Il2CppImage* image = p_assembly_get_image(assemblies[a]);
        if (image == nullptr) continue;
        const size_t class_count = p_image_get_class_count(image);
        for (size_t i = 0; i < class_count; ++i) {
            Il2CppClass* klass = p_image_get_class(image, i);
            if (klass == nullptr) continue;
            const char* name = p_class_get_name(klass);
            if (name != nullptr && strcmp(name, class_name) == 0) return klass;
        }
    }
    return nullptr;
}

void log_method_signature(const char* label, void* method) {
    if (method == nullptr) {
        LOGE("%s: not found", label);
        return;
    }
    uint32_t iflags = 0;
    const uint32_t flags = p_method_get_flags != nullptr
        ? p_method_get_flags(reinterpret_cast<MethodInfo*>(method), &iflags)
        : 0;
    const uint32_t argc = p_method_get_param_count != nullptr
        ? p_method_get_param_count(reinterpret_cast<MethodInfo*>(method))
        : 0;
    const Il2CppType* ret = p_method_get_return_type != nullptr
        ? p_method_get_return_type(reinterpret_cast<MethodInfo*>(method))
        : nullptr;
    const char* ret_name = (ret != nullptr && p_type_get_name != nullptr)
        ? p_type_get_name(ret)
        : "?";
    LOGI("%s: static=%d argc=%u ret=%s", label, (flags & 0x0010u) != 0, argc,
         ret_name != nullptr ? ret_name : "?");
    for (uint32_t i = 0; i < argc; ++i) {
        const Il2CppType* param = p_method_get_param != nullptr
            ? p_method_get_param(reinterpret_cast<MethodInfo*>(method), i)
            : nullptr;
        const char* param_name = (param != nullptr && p_type_get_name != nullptr)
            ? p_type_get_name(param)
            : "?";
        LOGI("%s param %u: %s", label, i, param_name != nullptr ? param_name : "?");
    }
}

void log_methods_named(Il2CppClass* klass, const char* method_name) {
    if (klass == nullptr || p_class_get_methods == nullptr || p_method_get_name == nullptr) {
        return;
    }
    void* iter = nullptr;
    void* method = nullptr;
    while ((method = p_class_get_methods(klass, &iter)) != nullptr) {
        const char* name = p_method_get_name(method);
        if (name != nullptr && strcmp(name, method_name) == 0) {
            log_method_signature(method_name, method);
        }
    }
}

void log_np_signatures(Il2CppDomain domain) {
    Il2CppClass* battle = find_class_anywhere(domain, "BattlePerformance");
    Il2CppClass* camera = find_class_anywhere(domain, "UICamera");
    LOGI("np signatures: BattlePerformance=%p UICamera=%p", battle, camera);
    if (battle != nullptr) {
        log_methods_named(battle, "startNoblePhantasm");
        log_methods_named(battle, "endNoblePhantasm");
    }
    if (camera != nullptr) {
        log_methods_named(camera, "Notify");
    }
}

// --- NP skip ---------------------------------------------------------------
volatile int g_np_active = 0;
void* g_np_self = nullptr;

typedef void (*StartNoble_t)(void* self, void* method);
StartNoble_t orig_start_noble = nullptr;

void hook_start_noble(void* self, void* method) {
    g_np_self = self;
    g_np_active = 1;
    g_np_skip_active = 0;
    LOGI("NP started");
    if (orig_start_noble != nullptr) orig_start_noble(self, method);
}

typedef void (*EndNoble_t)(void* self, int reason, void* method);
EndNoble_t orig_end_noble = nullptr;

void hook_end_noble(void* self, int reason, void* method) {
    if (g_np_skip_active) {
        if (orig_set_time_scale != nullptr && g_set_time_scale_method != nullptr) {
            orig_set_time_scale(g_saved_time_scale, g_set_time_scale_method);
        }
        g_np_skip_active = 0;
    }
    g_np_active = 0;
    g_np_self = nullptr;
    LOGI("NP ended (reason=%d)", reason);
    if (orig_end_noble != nullptr) orig_end_noble(self, reason, method);
}

typedef void (*Notify_t)(void* go, void* message, void* arg, void* method);
Notify_t orig_notify = nullptr;

void hook_notify(void* go, void* message, void* arg, void* method) {
    bool double_click = false;
    if (g_np_active && !g_np_skip_active) {
        const std::string text = il2cpp_string_to_utf8(message);
        if (!text.empty()) {
            static int logged = 0;
            if (logged < 40) {
                LOGI("np notify: %s", text.c_str());
                ++logged;
            }
        }
        double_click = (text == "OnDoubleClick");
    }

    if (double_click) {
        LOGI("NP skip: 8x fast-forward");
        g_saved_time_scale = orig_get_time_scale != nullptr
            ? orig_get_time_scale(g_get_time_scale_method)
            : 1.0f;
        g_np_skip_active = 1;
        if (orig_set_time_scale != nullptr && g_set_time_scale_method != nullptr) {
            orig_set_time_scale(8.0f, g_set_time_scale_method);
        }
    }

    if (orig_notify != nullptr) orig_notify(go, message, arg, method);
}

void install_np_skip(Il2CppDomain domain) {
    Il2CppClass* battle = find_class_anywhere(domain, "BattlePerformance");
    Il2CppClass* camera = find_class_anywhere(domain, "UICamera");

    if (battle != nullptr) {
        MethodInfo* start = p_class_get_method_from_name(battle, "startNoblePhantasm", 0);
        MethodInfo* end = p_class_get_method_from_name(battle, "endNoblePhantasm", 1);
        void* start_fn = method_ptr(start);
        void* end_fn = method_ptr(end);
        if (start_fn != nullptr &&
            DobbyHook(start_fn, reinterpret_cast<void*>(hook_start_noble),
                      reinterpret_cast<void**>(&orig_start_noble)) == 0) {
            LOGI("hooked startNoblePhantasm");
        } else {
            LOGE("failed to hook startNoblePhantasm");
        }
        if (end_fn != nullptr &&
            DobbyHook(end_fn, reinterpret_cast<void*>(hook_end_noble),
                      reinterpret_cast<void**>(&orig_end_noble)) == 0) {
            LOGI("hooked endNoblePhantasm");
        } else {
            LOGE("failed to hook endNoblePhantasm");
        }
    } else {
        LOGE("failed to resolve BattlePerformance");
    }

    if (camera != nullptr) {
        MethodInfo* notify = p_class_get_method_from_name(camera, "Notify", 3);
        void* notify_fn = method_ptr(notify);
        if (notify_fn != nullptr &&
            DobbyHook(notify_fn, reinterpret_cast<void*>(hook_notify),
                      reinterpret_cast<void**>(&orig_notify)) == 0) {
            LOGI("hooked UICamera::Notify");
        } else {
            LOGE("failed to hook UICamera::Notify");
        }
    } else {
        LOGE("failed to resolve UICamera");
    }
}

struct BlockSpec {
    const char* klass;
    const char* method;
    void* replacement;
};

const BlockSpec kBlockSpecs[] = {
    {"FirebaseScript", "Initialize", reinterpret_cast<void*>(block_firebase_initialize)},
    {"FirebaseScript", "SetupAppCheckProvider", reinterpret_cast<void*>(block_setup_appcheck)},
    {"FirebaseScript", "get_AppCheckTokenError", reinterpret_cast<void*>(block_get_appcheck_error)},
    {"FirebaseScript", "set_AppCheckTokenError", reinterpret_cast<void*>(block_set_appcheck_error)},
    {"CrashReporter", "BacktraceSetAttributes", reinterpret_cast<void*>(block_bt_set_attributes)},
    {"CrashReporter", "BacktraceStartUp", reinterpret_cast<void*>(block_bt_startup)},
    {"CrashReporter", "BacktraceDisableSampling", reinterpret_cast<void*>(block_bt_disable_sampling)},
    {"CrashReporter", "BacktraceEnableSampling", reinterpret_cast<void*>(block_bt_enable_sampling)},
    {"CrashReporter", "BacktraceDisable", reinterpret_cast<void*>(block_bt_disable)},
    {"CrashReporter", "BacktraceEnable", reinterpret_cast<void*>(block_bt_enable)},
    {"CrashReporter", "CrashlyticsAddCustomKey", reinterpret_cast<void*>(block_crashlytics_key)},
    {"AdManager", "InitializeRepro", reinterpret_cast<void*>(block_repro_init)},
    {"AdManager", "SetUserId", reinterpret_cast<void*>(block_repro_user_id)},
    {"AdManager", "SetFriendCode", reinterpret_cast<void*>(block_repro_friend_code)},
    {"AdManager", "TrackEvent", reinterpret_cast<void*>(block_repro_track)},
    {"AdManager", "SendPushRegistrationId", reinterpret_cast<void*>(block_repro_push)},
};

void install_telemetry(Il2CppDomain domain) {
    for (const BlockSpec& spec : kBlockSpecs) {
        Il2CppClass* klass = find_class(domain, "", spec.klass);
        MethodInfo* method = nullptr;
        if (klass != nullptr) {
            for (int argc = 0; argc <= 6 && method == nullptr; ++argc) {
                method = p_class_get_method_from_name(klass, spec.method, argc);
            }
        }
        if (method == nullptr) {
            Il2CppClass* owner = nullptr;
            method = find_method_anywhere(domain, spec.method, &owner);
            if (method != nullptr) {
                LOGI("resolved %s::%s via metadata scan", class_name_of(owner), spec.method);
            }
        }
        if (method == nullptr) {
            LOGE("failed to resolve %s::%s", spec.klass, spec.method);
            continue;
        }
        void* fn = method_ptr(method);
        if (fn == nullptr) {
            LOGE("failed to resolve %s::%s", spec.klass, spec.method);
            continue;
        }
        void* original = nullptr;
        if (DobbyHook(fn, spec.replacement, &original) == 0) {
            LOGI("hooked %s::%s", spec.klass, spec.method);
        } else {
            LOGE("failed to hook %s::%s", spec.klass, spec.method);
        }
    }
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

    // Unity's core classes are available as soon as il2cpp_init returns. Hook
    // the display methods first: the game applies its startup frame rate and
    // timeScale before the game assemblies are loaded, and a later install
    // would miss those calls.
    Il2CppClass* application = nullptr;
    Il2CppClass* time = nullptr;
    for (int attempt = 0; attempt < 20; ++attempt) {
        application = find_class(domain, "UnityEngine", "Application");
        time = find_class(domain, "UnityEngine", "Time");
        if (application != nullptr && time != nullptr) break;
        usleep(50 * 1000);
    }
    MethodInfo* set_target_frame_rate = application != nullptr
        ? p_class_get_method_from_name(application, "set_targetFrameRate", 1)
        : nullptr;
    MethodInfo* set_time_scale = time != nullptr
        ? p_class_get_method_from_name(time, "set_timeScale", 1)
        : nullptr;
    MethodInfo* get_time_scale = time != nullptr
        ? p_class_get_method_from_name(time, "get_timeScale", 0)
        : nullptr;

    void* set_target_frame_rate_ptr = method_ptr(set_target_frame_rate);
    void* set_time_scale_ptr = method_ptr(set_time_scale);
    void* get_time_scale_ptr = method_ptr(get_time_scale);

    g_set_time_scale_method = set_time_scale;
    g_get_time_scale_method = get_time_scale;

    LOGI("resolved: framerate=%p timeScale=%p getTimeScale=%p",
         set_target_frame_rate_ptr, set_time_scale_ptr, get_time_scale_ptr);

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

    // The game assemblies load after the first scene, so wait for them before
    // resolving the ascension component. Display hooks stay installed either way.
    Il2CppClass* combine = nullptr;
    Il2CppClass* input = nullptr;
    for (int attempt = 0; attempt < 40; ++attempt) {
        combine = find_class(domain, "", "CombineResultEffectComponent");
        input = find_class(domain, "UnityEngine", "Input");
        if (combine != nullptr && input != nullptr) break;
        usleep(250 * 1000);
    }
    if (combine == nullptr || input == nullptr) {
        LOGE("failed to find game classes (combine=%p input=%p)", combine, input);
        return;
    }

    MethodInfo* set_limit_up =
        p_class_get_method_from_name(combine, "SetLimitUpCombineInfo", 10);
    MethodInfo* click_next = p_class_get_method_from_name(combine, "clickNext", 0);
    MethodInfo* close = p_class_get_method_from_name(combine, "Close", 0);
    MethodInfo* stop_voice = p_class_get_method_from_name(combine, "stopVoice", 0);
    MethodInfo* on_finish_voice = p_class_get_method_from_name(combine, "OnFinishVoice", 1);
    MethodInfo* touch_count = p_class_get_method_from_name(input, "get_touchCount", 0);

    void* set_limit_up_ptr = method_ptr(set_limit_up);
    void* click_next_ptr = method_ptr(click_next);
    void* close_ptr = method_ptr(close);
    void* stop_voice_ptr = method_ptr(stop_voice);
    void* on_finish_voice_ptr = method_ptr(on_finish_voice);
    void* touch_count_ptr = method_ptr(touch_count);

    LOGI("resolved: setLimitUp=%p clickNext=%p close=%p stopVoice=%p finishVoice=%p touchCount=%p",
         set_limit_up_ptr, click_next_ptr, close_ptr, stop_voice_ptr, on_finish_voice_ptr,
         touch_count_ptr);

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

    Il2CppClass* entity_util = find_class(domain, "", "EntityScriptUtil");
    MethodInfo* get_string_value = entity_util != nullptr
        ? p_class_get_method_from_name(entity_util, "GetStringValue", 3)
        : nullptr;
    void* get_string_value_ptr = method_ptr(get_string_value);
    LOGI("resolved: getStringValue=%p", get_string_value_ptr);

    if (get_string_value_ptr != nullptr) {
        if (p_method_get_flags == nullptr || p_string_new == nullptr ||
            p_string_chars == nullptr || p_string_length == nullptr) {
            LOGE("failed to resolve IL2CPP string API for EntityScriptUtil::GetStringValue");
        } else {
            uint32_t iflags = 0;
            const uint32_t flags = p_method_get_flags(get_string_value, &iflags);
            if ((flags & 0x0010u) != 0) {  // MethodAttributes.Static
                if (DobbyHook(get_string_value_ptr,
                              reinterpret_cast<void*>(hook_get_string_value_static),
                              reinterpret_cast<void**>(&orig_get_string_value_static)) == 0) {
                    LOGI("hooked EntityScriptUtil::GetStringValue (static)");
                } else {
                    LOGE("failed to hook EntityScriptUtil::GetStringValue");
                }
            } else {
                if (DobbyHook(get_string_value_ptr,
                              reinterpret_cast<void*>(hook_get_string_value_instance),
                              reinterpret_cast<void**>(&orig_get_string_value_instance)) == 0) {
                    LOGI("hooked EntityScriptUtil::GetStringValue (instance)");
                } else {
                    LOGE("failed to hook EntityScriptUtil::GetStringValue");
                }
            }
        }
    } else {
        LOGE("failed to resolve EntityScriptUtil::GetStringValue");
    }

    g_click_next = click_next;
    g_click_next_fn = click_next_ptr;
    g_stop_voice = stop_voice;
    g_stop_voice_fn = stop_voice_ptr;
    g_on_finish_voice = on_finish_voice;
    g_on_finish_voice_fn = on_finish_voice_ptr;

    log_np_signatures(domain);
    install_np_skip(domain);
    install_telemetry(domain);

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
    p_method_get_flags = reinterpret_cast<fn_method_get_flags_t>(
        dlsym(lib, "il2cpp_method_get_flags"));
    p_string_new = reinterpret_cast<fn_string_new_t>(dlsym(lib, "il2cpp_string_new"));
    p_string_chars = reinterpret_cast<fn_string_chars_t>(dlsym(lib, "il2cpp_string_chars"));
    p_string_length = reinterpret_cast<fn_string_length_t>(dlsym(lib, "il2cpp_string_length"));
    p_image_get_class_count = reinterpret_cast<fn_image_get_class_count_t>(
        dlsym(lib, "il2cpp_image_get_class_count"));
    p_image_get_class = reinterpret_cast<fn_image_get_class_t>(
        dlsym(lib, "il2cpp_image_get_class"));
    p_class_get_methods = reinterpret_cast<fn_class_get_methods_t>(
        dlsym(lib, "il2cpp_class_get_methods"));
    p_method_get_name = reinterpret_cast<fn_method_get_name_t>(
        dlsym(lib, "il2cpp_method_get_name"));
    p_class_get_name = reinterpret_cast<fn_class_get_name_t>(
        dlsym(lib, "il2cpp_class_get_name"));
    p_method_get_param_count = reinterpret_cast<fn_method_get_param_count_t>(
        dlsym(lib, "il2cpp_method_get_param_count"));
    p_method_get_param = reinterpret_cast<fn_method_get_param_t>(
        dlsym(lib, "il2cpp_method_get_param"));
    p_method_get_return_type = reinterpret_cast<fn_method_get_return_type_t>(
        dlsym(lib, "il2cpp_method_get_return_type"));
    p_type_get_name = reinterpret_cast<fn_type_get_name_t>(
        dlsym(lib, "il2cpp_type_get_name"));

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
    // Resolve immediately: the display hooks must be in place before the first
    // scene applies its frame rate. resolve_and_hook waits for the game
    // assemblies itself.
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
