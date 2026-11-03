#pragma once

#include <android/log.h>

#ifndef FGO_TAG
#define FGO_TAG "FgoHook"
#endif

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, FGO_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, FGO_TAG, __VA_ARGS__)
