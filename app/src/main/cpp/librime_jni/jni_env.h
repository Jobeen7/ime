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

// String encoding conversion between Java (UTF-16) and librime (UTF-8).
//
// GetStringUTFChars/NewStringUTF use *modified* UTF-8 (CESU-8): characters
// outside the BMP are encoded as a surrogate pair of 3-byte sequences, which
// is not valid UTF-8 — librime (and any real UTF-8 consumer) mangles emoji
// and rare CJK characters that cross the bridge. All bridge strings are
// therefore funneled through the real conversions below:
//   Java -> native: GetStringChars (UTF-16) + utf16ToUtf8
//   native -> Java: utf8ToUtf16 + NewString
// Malformed input degrades to U+FFFD instead of aborting the bridge.
    inline void appendUtf8(std::string &out, char32_t cp) {
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }

    inline std::string utf16ToUtf8(const jchar *chars, jsize len) {
        std::string out;
        out.reserve(static_cast<size_t>(len));
        for (jsize i = 0; i < len; ++i) {
            char32_t cp = chars[i];
            if (cp >= 0xD800 && cp <= 0xDBFF) {
                if (i + 1 < len && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
                    ++i;
                } else {
                    cp = 0xFFFD;  // lone high surrogate
                }
            } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
                cp = 0xFFFD;  // lone low surrogate
            }
            appendUtf8(out, cp);
        }
        return out;
    }

    inline std::u16string utf8ToUtf16(const char *data, size_t len) {
        std::u16string out;
        out.reserve(len);
        size_t i = 0;
        while (i < len) {
            const auto c = static_cast<unsigned char>(data[i]);
            char32_t cp;
            size_t n;
            if (c < 0x80) {
                cp = c;
                n = 1;
            } else if ((c & 0xE0) == 0xC0) {
                cp = c & 0x1F;
                n = 2;
            } else if ((c & 0xF0) == 0xE0) {
                cp = c & 0x0F;
                n = 3;
            } else if ((c & 0xF8) == 0xF0) {
                cp = c & 0x07;
                n = 4;
            } else {
                cp = 0xFFFD;
                n = 1;
            }
            if (n > 1) {
                bool ok = i + n <= len;
                if (ok) {
                    for (size_t k = 1; k < n; ++k) {
                        const auto cc = static_cast<unsigned char>(data[i + k]);
                        if ((cc & 0xC0) != 0x80) {
                            ok = false;
                            break;
                        }
                        cp = (cp << 6) | (cc & 0x3F);
                    }
                }
                // Reject truncated/overlong/out-of-range sequences.
                if (!ok || cp > 0x10FFFF || (n == 2 && cp < 0x80) ||
                    (n == 3 && cp < 0x800) || (n == 4 && cp < 0x10000)) {
                    cp = 0xFFFD;
                    n = 1;
                }
            }
            i += n;
            if (cp < 0x10000) {
                out += static_cast<char16_t>(cp);
            } else {
                cp -= 0x10000;
                out += static_cast<char16_t>(0xD800 + (cp >> 10));
                out += static_cast<char16_t>(0xDC00 + (cp & 0x3FF));
            }
        }
        return out;
    }

// jstring -> UTF-8 std::string conversion (see encoding note above).
// The converted bytes are owned by this wrapper; get()/operator const char*
// expose them in the UTF-8 form librime expects.
    class StringChars {
    public:
        StringChars(JNIEnv *env, jstring str) {
            if (env && str) {
                const jchar *chars = env->GetStringChars(str, nullptr);
                if (chars) {
                    utf8_ = utf16ToUtf8(chars, env->GetStringLength(str));
                    env->ReleaseStringChars(str, chars);
                }
            }
        }

        StringChars(const StringChars &) = delete;

        StringChars &operator=(const StringChars &) = delete;

        operator const char *() const { return utf8_.c_str(); }

        const char *get() const { return utf8_.c_str(); }

        std::string str() const { return utf8_; }

    private:
        std::string utf8_;
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

// Creates a new jstring from UTF-8 input (see encoding note above) and
// returns it as a local reference.
    inline jstring makeString(JNIEnv *env, const char *chars) {
        if (!chars) chars = "";
        const std::u16string u16 = utf8ToUtf16(chars, std::char_traits<char>::length(chars));
        return env->NewString(reinterpret_cast<const jchar *>(u16.data()),
                              static_cast<jsize>(u16.size()));
    }

    inline jstring makeString(JNIEnv *env, const std::string &s) {
        const std::u16string u16 = utf8ToUtf16(s.data(), s.size());
        return env->NewString(reinterpret_cast<const jchar *>(u16.data()),
                              static_cast<jsize>(u16.size()));
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

        jclass Boolean;
        jmethodID BooleanCtor;

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

            // 逐个查找并即时清异常：FindClass 失败会留下未决异常，
            // 带着它继续调 JNI 是未定义行为（ART 可能直接 abort）。
            // 清掉后返回 null，最终由构造尾部的统一校验抛错显形。
            auto findClass = [env](const char *name) -> jclass {
                jclass cls = env->FindClass(name);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    return nullptr;
                }
                return cls;
            };

            Object = static_cast<jclass>(
                    env->NewGlobalRef(findClass("java/lang/Object")));
            String = static_cast<jclass>(
                    env->NewGlobalRef(findClass("java/lang/String")));

            Integer = static_cast<jclass>(
                    env->NewGlobalRef(findClass("java/lang/Integer")));
            IntegerCtor = env->GetMethodID(Integer, "<init>", "(I)V");

            Boolean = static_cast<jclass>(
                    env->NewGlobalRef(findClass("java/lang/Boolean")));
            BooleanCtor = env->GetMethodID(Boolean, "<init>", "(Z)V");

            Rime = static_cast<jclass>(env->NewGlobalRef(
                    findClass("com/jobeen/ime/engine/rime/core/Rime")));
            // librime 通知回调入口：Kotlin 侧只入队立即返回，不在 native 调用栈内分发
            HandleRimeMessage = env->GetStaticMethodID(
                    Rime, "handleNativeNotification", "(I[Ljava/lang/Object;)V");

            CandidateProto = static_cast<jclass>(env->NewGlobalRef(
                    findClass("com/jobeen/ime/engine/rime/core/CandidateProto")));
            CandidateProtoCtor = env->GetMethodID(
                    CandidateProto, "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");

            CommitProto = static_cast<jclass>(env->NewGlobalRef(
                    findClass("com/jobeen/ime/engine/rime/core/CommitProto")));
            CommitProtoCtor =
                    env->GetMethodID(CommitProto, "<init>", "(Ljava/lang/String;)V");

            ContextProto = static_cast<jclass>(env->NewGlobalRef(
                    findClass("com/jobeen/ime/engine/rime/core/ContextProto")));
            ContextProtoCtor = env->GetMethodID(
                    ContextProto, "<init>",
                    "(Lcom/jobeen/ime/engine/rime/core/CompositionProto;"
                    "Lcom/jobeen/ime/engine/rime/core/MenuProto;Ljava/lang/String;I)V");

            SyllableProto = static_cast<jclass>(env->NewGlobalRef(
                    findClass("com/jobeen/ime/engine/rime/core/SyllableProto")));
            SyllableProtoCtor = env->GetMethodID(
                    SyllableProto, "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)V");

            CompositionProto = static_cast<jclass>(env->NewGlobalRef(
                    findClass("com/jobeen/ime/engine/rime/core/CompositionProto")));
            CompositionProtoCtor = env->GetMethodID(
                    CompositionProto, "<init>",
                    "(IIIILjava/lang/String;Ljava/lang/String;[Lcom/jobeen/ime/engine/rime/core/SyllableProto;)V");

            MenuProto = static_cast<jclass>(env->NewGlobalRef(
                    findClass("com/jobeen/ime/engine/rime/core/MenuProto")));
            MenuProtoCtor = env->GetMethodID(
                    MenuProto, "<init>",
                    "(IIZI[Lcom/jobeen/ime/engine/rime/core/CandidateProto;"
                    "Ljava/lang/String;[Ljava/lang/String;)V");

            StatusProto = static_cast<jclass>(env->NewGlobalRef(
                    findClass("com/jobeen/ime/engine/rime/core/StatusProto")));
            StatusProtoCtor = env->GetMethodID(
                    StatusProto, "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;ZZZZZZZ)V");

            SchemaItem = static_cast<jclass>(env->NewGlobalRef(
                    findClass("com/jobeen/ime/engine/rime/core/SchemaItem")));
            SchemaItemCtor = env->GetMethodID(SchemaItem, "<init>",
                                              "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");

            KeyEvent = static_cast<jclass>(env->NewGlobalRef(
                    findClass("com/jobeen/ime/engine/rime/core/RimeKeyEvent")));
            KeyEventCtor =
                    env->GetMethodID(KeyEvent, "<init>", "(IILjava/lang/String;)V");

            // 统一校验：任一按名查找失败（类/方法被改名或被 R8 删除——986 事故
            // 正是此类）都会留下未决异常或 null ID。旧实现不查，未决异常会让
            // 后续 JNI 调用在 ART 上直接 abort、null ID 潜伏到首次回调才崩，
            // 日志里都看不到真正原因。这里清掉查找自身的异常，改抛带明确
            // 信息的 RuntimeException，让问题在库加载期就以可读形式显形。
            const bool missing =
                    env->ExceptionCheck() ||
                    !Object || !String || !Integer || !IntegerCtor ||
                    !Boolean || !BooleanCtor || !Rime || !HandleRimeMessage ||
                    !CandidateProto || !CandidateProtoCtor ||
                    !CommitProto || !CommitProtoCtor ||
                    !ContextProto || !ContextProtoCtor ||
                    !SyllableProto || !SyllableProtoCtor ||
                    !CompositionProto || !CompositionProtoCtor ||
                    !MenuProto || !MenuProtoCtor ||
                    !StatusProto || !StatusProtoCtor ||
                    !SchemaItem || !SchemaItemCtor ||
                    !KeyEvent || !KeyEventCtor;
            if (missing) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                throwException(env,
                               "rime_jni: required Java class/method lookup failed "
                               "(renamed or removed by R8? check proguard keep rules)");
            }
        }

        ScopedEnv attach() const { return ScopedEnv(vm); }
    };

    extern GlobalRefs *g_refs;

}  // namespace jni
