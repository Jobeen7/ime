// SPDX-License-Identifier: Apache-2.0

#include <marisa/trie.h>

#include <cstdint>
#include <string>
#include <vector>

#include "marisa_jni.h"

using namespace marisa_jni;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env;
    vm->AttachCurrentThread(&env, nullptr);
    g_refs = new GlobalRefs(env);
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_create(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new marisa::Trie());
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_destroy(JNIEnv *, jclass,
                                                             jlong ptr) {
    delete reinterpret_cast<marisa::Trie *>(ptr);
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_clear(JNIEnv *, jclass,
                                                           jlong ptr) {
    reinterpret_cast<marisa::Trie *>(ptr)->clear();
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_build(
    JNIEnv *env, jclass, jlong ptr, jobjectArray keys, jfloatArray weights,
    jint config_flags) {
    auto *trie = reinterpret_cast<marisa::Trie *>(ptr);
    jint keyCount = env->GetArrayLength(keys);

    jfloat *weightData = nullptr;
    if (weights != nullptr) {
        weightData = env->GetFloatArrayElements(weights, nullptr);
    }

    marisa::Keyset keyset;
    for (jint i = 0; i < keyCount; ++i) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(keys, i));
        StringChars chars(env, js);
        float w = weightData ? weightData[i] : 1.0f;
        keyset.push_back(std::string_view(chars.get()), w);
    }

    if (weightData) {
        env->ReleaseFloatArrayElements(weights, weightData, JNI_ABORT);
    }

    try {
        trie->build(keyset, config_flags);
    } catch (const std::exception &e) {
        throwException(env, e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_mmap(JNIEnv *env, jclass,
                                                          jlong ptr,
                                                          jstring filename) {
    StringChars path(env, filename);
    try {
        reinterpret_cast<marisa::Trie *>(ptr)->mmap(path.get());
    } catch (const std::exception &e) {
        throwException(env, e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_load(JNIEnv *env, jclass,
                                                          jlong ptr,
                                                          jstring filename) {
    StringChars path(env, filename);
    try {
        reinterpret_cast<marisa::Trie *>(ptr)->load(path.get());
    } catch (const std::exception &e) {
        throwException(env, e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_save(JNIEnv *env, jclass,
                                                          jlong ptr,
                                                          jstring filename) {
    StringChars path(env, filename);
    try {
        reinterpret_cast<marisa::Trie *>(ptr)->save(path.get());
    } catch (const std::exception &e) {
        throwException(env, e.what());
    }
}

JNIEXPORT jint JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_lookup(
    JNIEnv *env, jclass, jlong ptr, jstring query) {
    StringChars q(env, query);
    auto *trie = reinterpret_cast<marisa::Trie *>(ptr);
    marisa::Agent agent;
    agent.set_query(q.get());
    try {
        if (trie->lookup(agent)) {
            return static_cast<jint>(agent.key().id());
        }
    } catch (const std::exception &) {
    }
    return -1;
}

JNIEXPORT jobjectArray JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_commonPrefixSearch(
    JNIEnv *env, jclass, jlong ptr, jstring query) {
    StringChars q(env, query);
    auto *trie = reinterpret_cast<marisa::Trie *>(ptr);
    marisa::Agent agent;
    agent.set_query(q.get());

    std::vector<std::pair<std::string, int>> results;
    try {
        while (trie->common_prefix_search(agent)) {
            const auto &key = agent.key();
            results.emplace_back(std::string(key.ptr(), key.length()),
                                 static_cast<int>(key.id()));
        }
    } catch (const std::exception &) {
        return nullptr;
    }

    jobjectArray arr = env->NewObjectArray(
        static_cast<jsize>(results.size() * 2), g_refs->Object, nullptr);
    for (size_t i = 0; i < results.size(); ++i) {
        jstring keyStr = makeString(env, results[i].first);
        env->SetObjectArrayElement(arr, static_cast<jsize>(i * 2), keyStr);
        env->DeleteLocalRef(keyStr);

        jstring idStr = makeString(
            env, std::to_string(results[i].second));
        env->SetObjectArrayElement(arr, static_cast<jsize>(i * 2 + 1), idStr);
        env->DeleteLocalRef(idStr);
    }
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_predictiveSearch(
    JNIEnv *env, jclass, jlong ptr, jstring query, jint limit) {
    StringChars q(env, query);
    auto *trie = reinterpret_cast<marisa::Trie *>(ptr);
    marisa::Agent agent;
    agent.set_query(q.get());

    std::vector<std::pair<std::string, int>> results;
    try {
        while (trie->predictive_search(agent)) {
            if (limit > 0 && static_cast<jint>(results.size()) >= limit) break;
            const auto &key = agent.key();
            results.emplace_back(std::string(key.ptr(), key.length()),
                                 static_cast<int>(key.id()));
        }
    } catch (const std::exception &) {
        return nullptr;
    }

    jobjectArray arr = env->NewObjectArray(
        static_cast<jsize>(results.size() * 2), g_refs->Object, nullptr);
    for (size_t i = 0; i < results.size(); ++i) {
        jstring keyStr = makeString(env, results[i].first);
        env->SetObjectArrayElement(arr, static_cast<jsize>(i * 2), keyStr);
        env->DeleteLocalRef(keyStr);

        jstring idStr = makeString(
            env, std::to_string(results[i].second));
        env->SetObjectArrayElement(arr, static_cast<jsize>(i * 2 + 1), idStr);
        env->DeleteLocalRef(idStr);
    }
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_numKeys(JNIEnv *, jclass,
                                                             jlong ptr) {
    return static_cast<jint>(
        reinterpret_cast<marisa::Trie *>(ptr)->num_keys());
}

JNIEXPORT jint JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_numTries(JNIEnv *, jclass,
                                                              jlong ptr) {
    return static_cast<jint>(
        reinterpret_cast<marisa::Trie *>(ptr)->num_tries());
}

JNIEXPORT jint JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_numNodes(JNIEnv *, jclass,
                                                              jlong ptr) {
    return static_cast<jint>(
        reinterpret_cast<marisa::Trie *>(ptr)->num_nodes());
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_empty(JNIEnv *, jclass,
                                                           jlong ptr) {
    return reinterpret_cast<marisa::Trie *>(ptr)->empty();
}

JNIEXPORT jlong JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_size(JNIEnv *, jclass,
                                                          jlong ptr) {
    return static_cast<jlong>(
        reinterpret_cast<marisa::Trie *>(ptr)->size());
}

JNIEXPORT jlong JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_totalSize(JNIEnv *, jclass,
                                                               jlong ptr) {
    return static_cast<jlong>(
        reinterpret_cast<marisa::Trie *>(ptr)->total_size());
}

JNIEXPORT jlong JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_ioSize(JNIEnv *, jclass,
                                                            jlong ptr) {
    return static_cast<jlong>(
        reinterpret_cast<marisa::Trie *>(ptr)->io_size());
}

JNIEXPORT jint JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_reverseLookup(
    JNIEnv *env, jclass, jlong ptr, jint keyId) {
    auto *trie = reinterpret_cast<marisa::Trie *>(ptr);
    marisa::Agent agent;
    agent.set_query(static_cast<std::size_t>(keyId));
    try {
        trie->reverse_lookup(agent);
        return static_cast<jint>(agent.key().id());
    } catch (const std::exception &) {
        return -1;
    }
}

JNIEXPORT jstring JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_reverseLookupKey(
    JNIEnv *env, jclass, jlong ptr, jint keyId) {
    auto *trie = reinterpret_cast<marisa::Trie *>(ptr);
    marisa::Agent agent;
    agent.set_query(static_cast<std::size_t>(keyId));
    try {
        trie->reverse_lookup(agent);
        const auto &key = agent.key();
        return makeString(env, std::string(key.ptr(), key.length()));
    } catch (const std::exception &) {
        return nullptr;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_commonPrefixSearchBytes(
    JNIEnv *env, jclass, jlong ptr, jbyteArray query) {
    auto *trie = reinterpret_cast<marisa::Trie *>(ptr);
    jsize queryLen = env->GetArrayLength(query);
    jbyte *queryData = env->GetByteArrayElements(query, nullptr);
    marisa::Agent agent;
    agent.set_query(reinterpret_cast<const char *>(queryData),
                    static_cast<std::size_t>(queryLen));

    std::vector<std::pair<std::string, int>> results;
    try {
        while (trie->common_prefix_search(agent)) {
            const auto &key = agent.key();
            results.emplace_back(std::string(key.ptr(), key.length()),
                                 static_cast<int>(key.id()));
        }
    } catch (const std::exception &) {
        env->ReleaseByteArrayElements(query, queryData, JNI_ABORT);
        return nullptr;
    }
    env->ReleaseByteArrayElements(query, queryData, JNI_ABORT);

    jclass byteArrayClass = env->FindClass("[B");
    jobjectArray arr = env->NewObjectArray(
        static_cast<jsize>(results.size()), byteArrayClass, nullptr);
    env->DeleteLocalRef(byteArrayClass);
    for (size_t i = 0; i < results.size(); ++i) {
        jbyteArray keyBytes = env->NewByteArray(
            static_cast<jsize>(results[i].first.size()));
        env->SetByteArrayRegion(keyBytes, 0,
                                static_cast<jsize>(results[i].first.size()),
                                reinterpret_cast<const jbyte *>(results[i].first.data()));
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), keyBytes);
        env->DeleteLocalRef(keyBytes);
    }
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_predictiveSearchBytes(
    JNIEnv *env, jclass, jlong ptr, jbyteArray query, jint limit) {
    auto *trie = reinterpret_cast<marisa::Trie *>(ptr);
    jsize queryLen = env->GetArrayLength(query);
    jbyte *queryData = env->GetByteArrayElements(query, nullptr);
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
    jobjectArray arr = env->NewObjectArray(
        static_cast<jsize>(results.size()), byteArrayClass, nullptr);
    env->DeleteLocalRef(byteArrayClass);
    for (size_t i = 0; i < results.size(); ++i) {
        jbyteArray keyBytes = env->NewByteArray(
            static_cast<jsize>(results[i].size()));
        env->SetByteArrayRegion(keyBytes, 0,
                                static_cast<jsize>(results[i].size()),
                                reinterpret_cast<const jbyte *>(results[i].data()));
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), keyBytes);
        env->DeleteLocalRef(keyBytes);
    }
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_dumpKeys(
    JNIEnv *env, jclass, jlong ptr, jint limit) {
    auto *trie = reinterpret_cast<marisa::Trie *>(ptr);
    int count = static_cast<int>(trie->num_keys());
    if (limit > 0 && limit < count) count = limit;

    jclass byteArrayClass = env->FindClass("[B");
    jobjectArray arr = env->NewObjectArray(
        static_cast<jsize>(count), byteArrayClass, nullptr);
    env->DeleteLocalRef(byteArrayClass);

    for (int i = 0; i < count; ++i) {
        marisa::Agent agent;
        agent.set_query(static_cast<std::size_t>(i));
        try {
            trie->reverse_lookup(agent);
            const auto &key = agent.key();
            jbyteArray keyBytes = env->NewByteArray(static_cast<jsize>(key.length()));
            env->SetByteArrayRegion(keyBytes, 0, static_cast<jsize>(key.length()),
                                    reinterpret_cast<const jbyte *>(key.ptr()));
            env->SetObjectArrayElement(arr, static_cast<jsize>(i), keyBytes);
            env->DeleteLocalRef(keyBytes);
        } catch (const std::exception &) {
        }
    }
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_ninthsoft_ime_base_marisa_MarisaJNI_lookupBytes(
    JNIEnv *env, jclass, jlong ptr, jbyteArray query) {
    auto *trie = reinterpret_cast<marisa::Trie *>(ptr);
    jsize queryLen = env->GetArrayLength(query);
    jbyte *queryData = env->GetByteArrayElements(query, nullptr);
    marisa::Agent agent;
    agent.set_query(reinterpret_cast<const char *>(queryData),
                    static_cast<std::size_t>(queryLen));

    jbyteArray result = nullptr;
    try {
        if (trie->common_prefix_search(agent)) {
            const auto &key = agent.key();
            result = env->NewByteArray(static_cast<jsize>(key.length()));
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(key.length()),
                                    reinterpret_cast<const jbyte *>(key.ptr()));
        }
    } catch (const std::exception &) {
    }
    env->ReleaseByteArrayElements(query, queryData, JNI_ABORT);
    return result;
}

}  // extern "C"
