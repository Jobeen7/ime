// SPDX-License-Identifier: Apache-2.0
//
// JNI bridge for RimeKeyEvent. Uses rime::KeyEvent (BSD-3-Clause) to parse
// key representations and rime/key_table.h for name-to-code lookups.

#include <rime/key_event.h>

#include "jni_env.h"

extern "C" {

JNIEXPORT jobject JNICALL
Java_com_jobeen_ime_engine_rime_core_RimeKeyEvent_parse(
        JNIEnv *env, jclass, jstring repr) {
    jni::StringChars chars(env, repr);
    rime::KeyEvent event;
    event.Parse(chars.str());
    return env->NewObject(jni::g_refs->KeyEvent, jni::g_refs->KeyEventCtor,
                          event.keycode(), event.modifier(),
                          jni::makeString(env, event.repr()));
}

}  // extern "C"
