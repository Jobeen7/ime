// SPDX-License-Identifier: Apache-2.0
//
// JNI bridge for OpenCC dictionary and text conversion. Uses the OpenCC
// C++ API directly (Apache-2.0 licensed).

#include <opencc/Common.hpp>
#include <opencc/DictConverter.hpp>
#include <opencc/Exception.hpp>
#include <opencc/SimpleConverter.hpp>

#include <string>

#include "jni_env.h"

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_ninthsoft_ime_engine_rime_data_opencc_OpenCCDictManager_openCCLineConv(
        JNIEnv *env, jclass, jstring input, jstring config_file_name) {
    jni::StringChars in(env, input);
    jni::StringChars config(env, config_file_name);
    try {
        opencc::SimpleConverter converter(config.str());
        return jni::makeString(env, converter.Convert(in.str()));
    } catch (const opencc::Exception &e) {
        jni::throwException(env, e.what());
        return jni::makeString(env, "");
    }
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_engine_rime_data_opencc_OpenCCDictManager_openCCDictConv(
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
    }
}

}  // extern "C"
