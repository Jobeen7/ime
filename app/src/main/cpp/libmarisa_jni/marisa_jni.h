// SPDX-License-Identifier: Apache-2.0

#pragma once

#include <jni.h>

#include <string>

namespace marisa_jni {

inline void throwException(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    // 与 librime_jni 同纪律：类查找失败（实际不可达）时不再拿 null 调
    // ThrowNew，让 FindClass 自身的未决异常显形即可
    if (cls == nullptr) return;
    env->ThrowNew(cls, message);
    env->DeleteLocalRef(cls);
}

// String encoding conversion between Java (UTF-16) and native (UTF-8).
// GetStringUTFChars/NewStringUTF use *modified* UTF-8 (CESU-8): characters
// outside the BMP are mangled. Same fix as librime_jni/jni_env.h: funnel all
// bridge strings through real UTF-16 <-> UTF-8 conversion; malformed input
// degrades to U+FFFD instead of aborting the bridge.
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
    const char *get() const { return utf8_.c_str(); }
    std::string str() const { return utf8_; }

private:
    std::string utf8_;
};

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

class GlobalRefs {
public:
    jclass Object;
    jclass String;

    explicit GlobalRefs(JNIEnv *env) {
        // 逐步查找、逐步判空（与 librime_jni 的 initRefs 同纪律）：任一步
        // 失败立即停手，清掉查找异常后改抛带明确信息的 RuntimeException，
        // 让问题在库加载期显形，而不是把 null 全局引用潜伏到首次使用才崩
        Object = findGlobal(env, "java/lang/Object");
        String = Object ? findGlobal(env, "java/lang/String") : nullptr;
        if (Object == nullptr || String == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            throwException(env,
                           "marisa_jni: required Java class lookup failed");
        }
    }

private:
    static jclass findGlobal(JNIEnv *env, const char *name) {
        jclass cls = env->FindClass(name);
        if (cls == nullptr || env->ExceptionCheck()) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return nullptr;
        }
        return static_cast<jclass>(env->NewGlobalRef(cls));
    }
};

inline GlobalRefs *g_refs = nullptr;

}  // namespace marisa_jni
