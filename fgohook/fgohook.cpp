// FGO ascension tap-to-advance hook.
//
// Loaded by libLoader right after libavalon. Waits for IL2CPP, resolves the
// game's own CombineResultEffectComponent by name, and turns a touch-down
// during an ascension result into a call to its clickNext() method.
//
// No game offsets are hardcoded: everything is looked up through the IL2CPP
// API, so it survives game updates as long as the class/method names stay.

#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <time.h>
#include <stdint.h>
#include <string.h>
#include <android/log.h>

#define TAG "FgoHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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
typedef void* (*fn_thread_attach_t)(Il2CppDomain);

// Exported by the donor's libavalon.so (Dobby is linked statically there).
typedef int (*DobbyHook_t)(void* address, void* fake_func, void** out_origin_func);

fn_domain_get_t p_domain_get = nullptr;
fn_domain_get_assemblies_t p_domain_get_assemblies = nullptr;
fn_assembly_get_image_t p_assembly_get_image = nullptr;
fn_class_from_name_t p_class_from_name = nullptr;
fn_class_get_method_from_name_t p_class_get_method_from_name = nullptr;
fn_thread_attach_t p_thread_attach = nullptr;
DobbyHook_t p_dobby_hook = nullptr;

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
// void (Kind kind, UserServantEntity baseData, int baseCollectionLimitCnt,
//       ClickDelegate callback, ServantChangeEntity servantChange,
//       bool autoFadeout, Type displayType, int genderType,
//       string playVoiceLabel, List<int[]> rewardGiftDataList)
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

    // Cut the current voice and subtitle, then take the game's normal
    // "tap to continue" path.
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
    p_thread_attach = reinterpret_cast<fn_thread_attach_t>(dlsym(lib, "il2cpp_thread_attach"));

    if (p_domain_get == nullptr || p_domain_get_assemblies == nullptr ||
        p_assembly_get_image == nullptr || p_class_from_name == nullptr ||
        p_class_get_method_from_name == nullptr) {
        LOGE("missing IL2CPP exports");
        return nullptr;
    }

    // libavalon.so is loaded first by libLoader and exports DobbyHook.
    void* avalon = dlopen("libavalon.so", RTLD_NOLOAD | RTLD_NOW);
    if (avalon == nullptr) avalon = dlopen("libavalon.so", RTLD_NOW);
    if (avalon == nullptr) {
        LOGE("libavalon.so not loaded");
        return nullptr;
    }
    p_dobby_hook = reinterpret_cast<DobbyHook_t>(dlsym(avalon, "DobbyHook"));
    if (p_dobby_hook == nullptr) {
        LOGE("DobbyHook not exported by libavalon");
        return nullptr;
    }
    LOGI("DobbyHook from libavalon");

    // IL2CPP publishes its domain before it has finished initialising. Calling
    // into it (attach/metadata) at that point dereferences null internal state,
    // so wait for Unity to finish bringing the runtime up before touching it.
    LOGI("waiting for IL2CPP");
    sleep(3);

    Il2CppDomain domain = nullptr;
    while ((domain = p_domain_get()) == nullptr) usleep(100 * 1000);
    LOGI("IL2CPP ready");

    Il2CppClass* combine = nullptr;
    Il2CppClass* input = nullptr;
    while (combine == nullptr || input == nullptr) {
        if (combine == nullptr) {
            combine = find_class(domain, "", "CombineResultEffectComponent");
        }
        if (input == nullptr) {
            input = find_class(domain, "UnityEngine", "Input");
        }
        if (combine == nullptr || input == nullptr) usleep(200 * 1000);
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
        if (p_dobby_hook(set_limit_up_ptr, reinterpret_cast<void*>(hook_set_limit_up),
                         reinterpret_cast<void**>(&orig_set_limit_up)) == 0) {
            LOGI("hooked SetLimitUpCombineInfo");
        } else {
            LOGE("failed to hook SetLimitUpCombineInfo");
        }
    }
    if (close_ptr != nullptr) {
        if (p_dobby_hook(close_ptr, reinterpret_cast<void*>(hook_close),
                         reinterpret_cast<void**>(&orig_close)) == 0) {
            LOGI("hooked Close");
        } else {
            LOGE("failed to hook Close");
        }
    }
    if (touch_count_ptr != nullptr) {
        if (p_dobby_hook(touch_count_ptr, reinterpret_cast<void*>(hook_get_touch_count),
                         reinterpret_cast<void**>(&orig_get_touch_count)) == 0) {
            LOGI("hooked get_touchCount");
        } else {
            LOGE("failed to hook get_touchCount");
        }
    }

    g_click_next = click_next;
    g_click_next_fn = click_next_ptr;
    g_stop_voice = stop_voice;
    g_stop_voice_fn = stop_voice_ptr;
    g_on_finish_voice = on_finish_voice;
    g_on_finish_voice_fn = on_finish_voice_ptr;

    LOGI("ready");
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
