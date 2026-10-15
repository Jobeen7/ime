// SPDX-License-Identifier: Apache-2.0
//
// JNI bridge for OpenCC dictionary and text conversion. Uses the OpenCC
// C++ API directly (Apache-2.0 licensed).

#include <opencc/Common.hpp>
#include <opencc/DictConverter.hpp>
#include <opencc/Exception.hpp>
#include <opencc/SimpleConverter.hpp>

#include <exception>
#include <string>

#include "jni_env.h"

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_jobeen_ime_engine_rime_data_opencc_OpenCCDictManager_openCCLineConv(
        JNIEnv *env, jclass, jstring input, jstring config_file_name) {
    jni::StringChars in(env, input);
    jni::StringChars config(env, config_file_name);
    try {
        opencc::SimpleConverter converter(config.str());
        return jni::makeString(env, converter.Convert(in.str()));
    } catch (const opencc::Exception &e) {
        jni::throwException(env, e.what());
        // 异常已挂起：不能再调 makeString（内部 NewString 不在异常期
        // 允许的 JNI 调用之列，CheckJNI 下 abort）。调用方在异常时会
        // 丢弃返回值，直接回 nullptr
        return nullptr;
    } catch (const std::exception &e) {
        // 非 OpenCC 的标准异常（如 bad_alloc）穿过 JNI 边界会直接
        // terminate，兜底转成 Java 异常、按失败语义返回
        jni::throwException(env, e.what());
        return nullptr;
    } catch (...) {
        jni::throwException(env, "opencc: unknown native exception");
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_jobeen_ime_engine_rime_data_opencc_OpenCCDictManager_openCCDictConv(
        JNIEnv *env, jclass, jstring src, jstring dest, jboolean mode) {
    jni::StringChars src_path(env, src);
    jni::StringChars dest_path(env, dest);
    try {
        if (mode) {
            opencc::ConvertDictionary(src_path.get(), dest_path.get(), "ocd2", "text");
        } else {
            opencc::ConvertDictionary(src_path.get(), dest_path.get(), "text", "ocd2");
        }
    } catch (const opencc::Exception &e) {
        jni::throwException(env, e.what());
    } catch (const std::exception &e) {
        // 同上：标准异常兜底转 Java 异常，不让它穿过 JNI 边界
        jni::throwException(env, e.what());
    } catch (...) {
        jni::throwException(env, "opencc: unknown native exception");
    }
}

}  // extern "C"
