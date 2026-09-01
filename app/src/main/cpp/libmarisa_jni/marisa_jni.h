// SPDX-License-Identifier: Apache-2.0

#pragma once

#include <jni.h>

#include <string>

namespace marisa_jni {

inline void throwException(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(cls, message);
    env->DeleteLocalRef(cls);
}

class StringChars {
public:
    StringChars(JNIEnv *env, jstring str)
        : env_(env), str_(str), chars_(env->GetStringUTFChars(str, nullptr)) {}
    ~StringChars() { env_->ReleaseStringUTFChars(str_, chars_); }
    StringChars(const StringChars &) = delete;
    StringChars &operator=(const StringChars &) = delete;
    const char *get() const { return chars_; }
    std::string str() const { return chars_ ? std::string(chars_) : std::string(); }

private:
    JNIEnv *env_;
    jstring str_;
    const char *chars_;
};

inline jstring makeString(JNIEnv *env, const char *chars) {
    return env->NewStringUTF(chars ? chars : "");
}

inline jstring makeString(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(s.c_str());
}

class GlobalRefs {
public:
    jclass Object;
    jclass String;

    explicit GlobalRefs(JNIEnv *env) {
        Object = static_cast<jclass>(
            env->NewGlobalRef(env->FindClass("java/lang/Object")));
        String = static_cast<jclass>(
            env->NewGlobalRef(env->FindClass("java/lang/String")));
    }
};

inline GlobalRefs *g_refs = nullptr;

}  // namespace marisa_jni
