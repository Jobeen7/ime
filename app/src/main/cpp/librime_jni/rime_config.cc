// SPDX-License-Identifier: Apache-2.0
//
// JNI bridge for RimeConfig. Exposes config open/close/read/write operations
// through the librime C API (rime_api.h, BSD-3-Clause).

#include <rime_api.h>

#include "jni_env.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ninthsoft_ime_engine_rime_core_RimeConfig_openRimeConfig(
        JNIEnv *env, jclass, jstring config_id) {
    auto *api = rime_get_api();
    jni::StringChars id(env, config_id);
    auto *config = new RimeConfig;
    if (!api->config_open(id.get(), config)) {
        delete config;
        return 0;
    }
    return reinterpret_cast<jlong>(config);
}

JNIEXPORT jlong JNICALL
Java_com_ninthsoft_ime_engine_rime_core_RimeConfig_openRimeUserConfig(
        JNIEnv *env, jclass, jstring config_id) {
    auto *api = rime_get_api();
    jni::StringChars id(env, config_id);
    auto *config = new RimeConfig;
    if (!api->user_config_open(id.get(), config)) {
        delete config;
        return 0;
    }
    return reinterpret_cast<jlong>(config);
}

JNIEXPORT jlong JNICALL
Java_com_ninthsoft_ime_engine_rime_core_RimeConfig_openRimeSchema(
        JNIEnv *env, jclass, jstring schema_id) {
    auto *api = rime_get_api();
    jni::StringChars id(env, schema_id);
    auto *config = new RimeConfig;
    if (!api->schema_open(id.get(), config)) {
        delete config;
        return 0;
    }
    return reinterpret_cast<jlong>(config);
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_engine_rime_core_RimeConfig_closeRimeConfig(
        JNIEnv *, jclass, jlong peer) {
    auto *api = rime_get_api();
    auto *config = reinterpret_cast<RimeConfig *>(peer);
    api->config_close(config);
    delete config;
}

JNIEXPORT jobject JNICALL
Java_com_ninthsoft_ime_engine_rime_core_RimeConfig_getRimeConfigInt(
        JNIEnv *env, jclass, jlong peer, jstring key) {
    auto *api = rime_get_api();
    jni::StringChars k(env, key);
    int value = 0;
    if (!api->config_get_int(reinterpret_cast<RimeConfig *>(peer), k.get(),
                             &value)) {
        return nullptr;
    }
    return env->NewObject(jni::g_refs->Integer, jni::g_refs->IntegerCtor, value);
}

JNIEXPORT jstring JNICALL
Java_com_ninthsoft_ime_engine_rime_core_RimeConfig_getRimeConfigString(
        JNIEnv *env, jclass, jlong peer, jstring key) {
    auto *api = rime_get_api();
    jni::StringChars k(env, key);
    const char *value = api->config_get_cstring(
            reinterpret_cast<RimeConfig *>(peer), k.get());
    if (!value) return nullptr;
    return jni::makeString(env, value);
}

JNIEXPORT jobjectArray JNICALL
Java_com_ninthsoft_ime_engine_rime_core_RimeConfig_getRimeConfigListItemPath(
        JNIEnv *env, jclass, jlong peer, jstring key) {
    auto *api = rime_get_api();
    auto *config = reinterpret_cast<RimeConfig *>(peer);
    jni::StringChars k(env, key);
    int size = static_cast<int>(api->config_list_size(config, k.get()));
    jobjectArray arr =
            env->NewObjectArray(size, jni::g_refs->String, nullptr);
    RimeConfigIterator iter;
    int i = 0;
    if (!api->config_begin_list(&iter, config, k.get())) return arr;
    while (api->config_next(&iter)) {
        jni::LocalRef<jstring> ref(env, jni::makeString(env, iter.path));
        env->SetObjectArrayElement(arr, i++, ref.get());
    }
    api->config_end(&iter);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_engine_rime_core_RimeConfig_setRimeConfigBool(
        JNIEnv *env, jclass, jlong peer, jstring key, jboolean value) {
    auto *api = rime_get_api();
    jni::StringChars k(env, key);
    api->config_set_bool(reinterpret_cast<RimeConfig *>(peer), k.get(), value);
}

}  // extern "C"
