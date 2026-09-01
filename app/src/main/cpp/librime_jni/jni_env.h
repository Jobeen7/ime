// SPDX-License-Identifier: Apache-2.0
//
// JNI environment utilities for the RIME native bridge.
// Provides RAII wrappers for JNI string/array references and a singleton
// that caches class/method IDs at library load time.

#pragma once

#include <jni.h>

#include <string>

namespace jni {

// Throws a Java exception with the given message.
    inline void throwException(JNIEnv *env, const char *message) {
        jclass cls = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }

// RAII wrapper around jstring -> const char* conversion.
    class StringChars {
    public:
        StringChars(JNIEnv *env, jstring str)
                : env_(env), str_(str), chars_(env->GetStringUTFChars(str, nullptr)) {}

        ~StringChars() { env_->ReleaseStringUTFChars(str_, chars_); }

        StringChars(const StringChars &) = delete;

        StringChars &operator=(const StringChars &) = delete;

        operator const char *() const { return chars_; }

        const char *get() const { return chars_; }

        std::string str() const { return chars_ ? std::string(chars_) : std::string(); }

    private:
        JNIEnv *env_;
        jstring str_;
        const char *chars_;
    };

// RAII wrapper for a local reference.
    template<typename T = jobject>
    class LocalRef {
    public:
        LocalRef(JNIEnv *env, jobject ref) : env_(env), ref_(reinterpret_cast<T>(ref)) {}

        ~LocalRef() {
            if (ref_) env_->DeleteLocalRef(ref_);
        }

        LocalRef(const LocalRef &) = delete;

        LocalRef &operator=(const LocalRef &) = delete;

        T get() const { return ref_; }

        operator T() const { return ref_; }

    private:
        JNIEnv *env_;
        T ref_;
    };

// Creates a new UTF-8 jstring and returns it as a local reference.
    inline jstring makeString(JNIEnv *env, const char *chars) {
        return env->NewStringUTF(chars ? chars : "");
    }

    inline jstring makeString(JNIEnv *env, const std::string &s) {
        return env->NewStringUTF(s.c_str());
    }

// Attaches the current native thread to the JVM (if not already attached)
// and releases the attachment on destruction.
    class ScopedEnv {
    public:
        explicit ScopedEnv(JavaVM *vm) : vm_(vm), env_(nullptr), attached_(false) {
            if (vm_->GetEnv(reinterpret_cast<void **>(&env_), JNI_VERSION_1_6) ==
                JNI_EDETACHED) {
                vm_->AttachCurrentThread(&env_, nullptr);
                attached_ = true;
            }
        }

        ~ScopedEnv() {
            if (attached_) vm_->DetachCurrentThread();
        }

        operator JNIEnv *() const { return env_; }

        JNIEnv *operator->() const { return env_; }

    private:
        JavaVM *vm_;
        JNIEnv *env_;
        bool attached_;
    };

// Singleton that caches global references to Java classes and method IDs
// used by the RIME JNI bridge. Initialized once in JNI_OnLoad.
    class GlobalRefs {
    public:
        JavaVM *vm = nullptr;

        jclass Object;
        jclass String;

        jclass Integer;
        jmethodID IntegerCtor;

        jclass Rime;
        jmethodID HandleRimeMessage;

        jclass CandidateProto;
        jmethodID CandidateProtoCtor;

        jclass CommitProto;
        jmethodID CommitProtoCtor;

        jclass ContextProto;
        jmethodID ContextProtoCtor;

        jclass SyllableProto;
        jmethodID SyllableProtoCtor;

        jclass CompositionProto;
        jmethodID CompositionProtoCtor;

        jclass MenuProto;
        jmethodID MenuProtoCtor;

        jclass StatusProto;
        jmethodID StatusProtoCtor;

        jclass SchemaItem;
        jmethodID SchemaItemCtor;

        jclass KeyEvent;
        jmethodID KeyEventCtor;

        explicit GlobalRefs(JavaVM *vm_) : vm(vm_) {
            JNIEnv *env;
            vm->AttachCurrentThread(&env, nullptr);

            Object = static_cast<jclass>(
                    env->NewGlobalRef(env->FindClass("java/lang/Object")));
            String = static_cast<jclass>(
                    env->NewGlobalRef(env->FindClass("java/lang/String")));

            Integer = static_cast<jclass>(
                    env->NewGlobalRef(env->FindClass("java/lang/Integer")));
            IntegerCtor = env->GetMethodID(Integer, "<init>", "(I)V");

            Rime = static_cast<jclass>(env->NewGlobalRef(
                    env->FindClass("com/ninthsoft/ime/engine/rime/core/Rime")));
            HandleRimeMessage = env->GetStaticMethodID(
                    Rime, "handleMessage", "(I[Ljava/lang/Object;)V");

            CandidateProto = static_cast<jclass>(env->NewGlobalRef(
                    env->FindClass("com/ninthsoft/ime/engine/rime/core/CandidateProto")));
            CandidateProtoCtor = env->GetMethodID(
                    CandidateProto, "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");

            CommitProto = static_cast<jclass>(env->NewGlobalRef(
                    env->FindClass("com/ninthsoft/ime/engine/rime/core/CommitProto")));
            CommitProtoCtor =
                    env->GetMethodID(CommitProto, "<init>", "(Ljava/lang/String;)V");

            ContextProto = static_cast<jclass>(env->NewGlobalRef(
                    env->FindClass("com/ninthsoft/ime/engine/rime/core/ContextProto")));
            ContextProtoCtor = env->GetMethodID(
                    ContextProto, "<init>",
                    "(Lcom/ninthsoft/ime/engine/rime/core/CompositionProto;"
                    "Lcom/ninthsoft/ime/engine/rime/core/MenuProto;Ljava/lang/String;I)V");

            SyllableProto = static_cast<jclass>(env->NewGlobalRef(
                    env->FindClass("com/ninthsoft/ime/engine/rime/core/SyllableProto")));
            SyllableProtoCtor = env->GetMethodID(
                    SyllableProto, "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)V");

            CompositionProto = static_cast<jclass>(env->NewGlobalRef(
                    env->FindClass("com/ninthsoft/ime/engine/rime/core/CompositionProto")));
            CompositionProtoCtor = env->GetMethodID(
                    CompositionProto, "<init>",
                    "(IIIILjava/lang/String;Ljava/lang/String;[Lcom/ninthsoft/ime/engine/rime/core/SyllableProto;)V");

            MenuProto = static_cast<jclass>(env->NewGlobalRef(
                    env->FindClass("com/ninthsoft/ime/engine/rime/core/MenuProto")));
            MenuProtoCtor = env->GetMethodID(
                    MenuProto, "<init>",
                    "(IIZI[Lcom/ninthsoft/ime/engine/rime/core/CandidateProto;"
                    "Ljava/lang/String;[Ljava/lang/String;)V");

            StatusProto = static_cast<jclass>(env->NewGlobalRef(
                    env->FindClass("com/ninthsoft/ime/engine/rime/core/StatusProto")));
            StatusProtoCtor = env->GetMethodID(
                    StatusProto, "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;ZZZZZZZ)V");

            SchemaItem = static_cast<jclass>(env->NewGlobalRef(
                    env->FindClass("com/ninthsoft/ime/engine/rime/core/SchemaItem")));
            SchemaItemCtor = env->GetMethodID(SchemaItem, "<init>",
                                              "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");

            KeyEvent = static_cast<jclass>(env->NewGlobalRef(
                    env->FindClass("com/ninthsoft/ime/engine/rime/core/RimeKeyEvent")));
            KeyEventCtor =
                    env->GetMethodID(KeyEvent, "<init>", "(IILjava/lang/String;)V");
        }

        ScopedEnv attach() const { return ScopedEnv(vm); }
    };

    extern GlobalRefs *g_refs;

}  // namespace jni
