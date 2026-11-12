// SPDX-License-Identifier: Apache-2.0

#include <marisa/trie.h>

#include <android/log.h>

#include <cstdint>
#include <string>
#include <vector>

#include "marisa_jni.h"

using namespace marisa_jni;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    // N-L3：旧实现不检查 AttachCurrentThread 返回值，失败时把未初始
    // 化的 env 交给 GlobalRefs。失败即加载失败（JNI_ERR），不在
    // 空 env 上继续建全局引用。
    JNIEnv *env = nullptr;
    if (vm == nullptr ||
        vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }
    g_refs = new GlobalRefs(env);
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_jobeen_ime_base_marisa_MarisaJNI_create(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new marisa::Trie());
}

JNIEXPORT void JNICALL
Java_com_jobeen_ime_base_marisa_MarisaJNI_destroy(JNIEnv *, jclass,
                                                             jlong ptr) {
    delete reinterpret_cast<marisa::Trie *>(ptr);
}

JNIEXPORT void JNICALL
Java_com_jobeen_ime_base_marisa_MarisaJNI_mmap(JNIEnv *env, jclass,
                                                          jlong ptr,
                                                          jstring filename) {
    // N-L5：空 ptr 即 native 崩溃；Kotlin 侧虽有 require 守卫，
    // JNI 边界本身必须设防。
    if (ptr == 0) {
        throwException(env, "MarisaJNI: null native ptr in mmap");
        return;
    }
    StringChars path(env, filename);
    try {
        reinterpret_cast<marisa::Trie *>(ptr)->mmap(path.get());
    } catch (const std::exception &e) {
        throwException(env, e.what());
    }
}

JNIEXPORT jboolean JNICALL
Java_com_jobeen_ime_base_marisa_MarisaJNI_empty(JNIEnv *env, jclass,
                                                           jlong ptr) {
    // N-L5＋N-S2：空 ptr 返回 true（无键可查即空）；未加载的 Trie
    // 调 empty() 抛 std::logic_error，接住后同样按空处理——
    // Prediction.isLoaded() 据此判未加载，不让异常穿过 JNI。
    if (ptr == 0) return JNI_TRUE;
    try {
        return reinterpret_cast<marisa::Trie *>(ptr)->empty();
    } catch (const std::exception &) {
        return JNI_TRUE;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_jobeen_ime_base_marisa_MarisaJNI_predictiveSearchBytes(
    JNIEnv *env, jclass, jlong ptr, jbyteArray query, jint limit) {
    // N-L5：空 ptr 或空 query 直接返回 null（本函数失败约定），
    // 不解引用。
    if (ptr == 0 || query == nullptr) return nullptr;
    auto *trie = reinterpret_cast<marisa::Trie *>(ptr);
    jsize queryLen = env->GetArrayLength(query);
    jbyte *queryData = env->GetByteArrayElements(query, nullptr);
    if (!queryData) return nullptr;
    marisa::Agent agent;
    agent.set_query(reinterpret_cast<const char *>(queryData),
                    static_cast<std::size_t>(queryLen));

    std::vector<std::string> results;
    try {
        while (trie->predictive_search(agent)) {
            if (limit > 0 && static_cast<jint>(results.size()) >= limit) break;
            const auto &key = agent.key();
            results.emplace_back(std::string(key.ptr(), key.length()));
        }
    } catch (const std::exception &) {
        env->ReleaseByteArrayElements(query, queryData, JNI_ABORT);
        return nullptr;
    }
    env->ReleaseByteArrayElements(query, queryData, JNI_ABORT);

    jclass byteArrayClass = env->FindClass("[B");
    if (!byteArrayClass) {
        __android_log_print(ANDROID_LOG_ERROR, "marisa_jni",
                            "FindClass(\"[B\") failed in predictiveSearchBytes, returning null");
        // 查找失败已留未决异常：清掉并按本函数失败约定返回 null，
        // 不能拿空 class 继续 NewObjectArray
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jobjectArray arr = env->NewObjectArray(
        static_cast<jsize>(results.size()), byteArrayClass, nullptr);
    env->DeleteLocalRef(byteArrayClass);
    if (!arr) return nullptr;
    for (size_t i = 0; i < results.size(); ++i) {
        jbyteArray keyBytes = env->NewByteArray(
            static_cast<jsize>(results[i].size()));
        // N-L2：OOM 时 NewByteArray 返回 null，继续
        // SetByteArrayRegion 即崩溃；当场清异常、返回 null。
        if (keyBytes == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return nullptr;
        }
        env->SetByteArrayRegion(keyBytes, 0,
                                static_cast<jsize>(results[i].size()),
                                reinterpret_cast<const jbyte *>(results[i].data()));
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), keyBytes);
        env->DeleteLocalRef(keyBytes);
    }
    return arr;
}

}  // extern "C"
