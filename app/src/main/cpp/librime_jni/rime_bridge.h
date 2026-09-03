// SPDX-License-Identifier: Apache-2.0
//
// Conversion helpers that turn the intermediate C++ data structures
// defined in rime_data.h into the corresponding Java objects expected
// by the Kotlin side of the JNI bridge.

#pragma once

#include <rime_api.h>

#include "jni_env.h"
#include "rime_data.h"

namespace rime_jni {

    inline jobject toJavaSchemaItem(JNIEnv *env, const SchemaEntry &entry) {
        return env->NewObject(jni::g_refs->SchemaItem, jni::g_refs->SchemaItemCtor,
                              jni::makeString(env, entry.id),
                              jni::makeString(env, entry.name),
                              jni::makeString(env, entry.layout),
                              jni::makeString(env, entry.punctuation));
    }

    inline jobjectArray toJavaSchemaArray(JNIEnv *env,
                                          const std::vector<SchemaEntry> &items) {
        jobjectArray arr = env->NewObjectArray(static_cast<int>(items.size()),
                                               jni::g_refs->SchemaItem, nullptr);
        for (int i = 0; i < static_cast<int>(items.size()); ++i) {
            jni::LocalRef<> ref(env, toJavaSchemaItem(env, items[i]));
            env->SetObjectArrayElement(arr, i, ref.get());
        }
        return arr;
    }

    inline std::vector<std::string> javaStringArrayToVector(JNIEnv *env,
                                                            jobjectArray arr) {
        int len = env->GetArrayLength(arr);
        std::vector<std::string> out;
        out.reserve(len);
        for (int i = 0; i < len; ++i) {
            jni::StringChars chars(
                    env, static_cast<jstring>(env->GetObjectArrayElement(arr, i)));
            out.emplace_back(chars.get());
        }
        return out;
    }

    inline jobjectArray vectorToJavaStringArray(JNIEnv *env,
                                                const std::vector<std::string> &v) {
        jobjectArray arr = env->NewObjectArray(static_cast<int>(v.size()),
                                               jni::g_refs->String, nullptr);
        for (int i = 0; i < static_cast<int>(v.size()); ++i) {
            jni::LocalRef<jstring> ref(env, jni::makeString(env, v[i]));
            env->SetObjectArrayElement(arr, i, ref.get());
        }
        return arr;
    }

    inline jobject toJavaCommit(JNIEnv *env, const CommitData &commit) {
        jstring text = commit.text ? jni::makeString(env, *commit.text) : nullptr;
        return env->NewObject(jni::g_refs->CommitProto, jni::g_refs->CommitProtoCtor,
                              text);
    }

    inline jobject toJavaCandidate(JNIEnv *env, const CandidateData &cand) {
        return env->NewObject(
                jni::g_refs->CandidateProto, jni::g_refs->CandidateProtoCtor,
                jni::makeString(env, cand.text), jni::makeString(env, cand.comment),
                jni::makeString(env, cand.label), jni::makeString(env, cand.type));
    }

    inline jobjectArray toJavaCandidateArray(JNIEnv *env,
                                             const std::vector<CandidateData> &list) {
        jobjectArray arr = env->NewObjectArray(static_cast<int>(list.size()),
                                               jni::g_refs->CandidateProto, nullptr);
        for (int i = 0; i < static_cast<int>(list.size()); ++i) {
            jni::LocalRef<> ref(env, toJavaCandidate(env, list[i]));
            env->SetObjectArrayElement(arr, i, ref.get());
        }
        return arr;
    }

    inline jobject toJavaSyllable(JNIEnv *env, const SyllableData &sd) {
        return env->NewObject(jni::g_refs->SyllableProto,
                              jni::g_refs->SyllableProtoCtor,
                              jni::makeString(env, sd.rawInput),
                              jni::makeString(env, sd.spelling),
                              jni::makeString(env, sd.text),
                              static_cast<jint>(sd.textSyllableStart),
                              static_cast<jint>(sd.textSyllableEnd));
    }

    inline jobject toJavaComposition(JNIEnv *env, const CompositionData &comp) {
        jstring preedit = comp.preedit ? jni::makeString(env, *comp.preedit) : nullptr;
        jstring preview = comp.commitTextPreview
                          ? jni::makeString(env, *comp.commitTextPreview)
                          : nullptr;
        jobjectArray syllableArray = env->NewObjectArray(
                static_cast<int>(comp.syllables.size()),
                jni::g_refs->SyllableProto, nullptr);
        for (int i = 0; i < static_cast<int>(comp.syllables.size()); ++i) {
            jni::LocalRef<> ref(env, toJavaSyllable(env, comp.syllables[i]));
            env->SetObjectArrayElement(syllableArray, i, ref.get());
        }
        return env->NewObject(jni::g_refs->CompositionProto,
                              jni::g_refs->CompositionProtoCtor, comp.length,
                              comp.cursorPos, comp.selStart, comp.selEnd, preedit,
                              preview, syllableArray);
    }

    inline jobject toJavaMenu(JNIEnv *env, const MenuData &menu) {
        jobjectArray candidates = toJavaCandidateArray(env, menu.candidates);
        jobjectArray labels = vectorToJavaStringArray(env, menu.selectLabels);
        return env->NewObject(
                jni::g_refs->MenuProto, jni::g_refs->MenuProtoCtor, menu.pageSize,
                menu.pageNumber, menu.isLastPage, menu.highlightedIndex, candidates,
                jni::makeString(env, menu.selectKeys), labels);
    }

    inline jobject toJavaContext(JNIEnv *env, const ContextData &ctx) {
        jobject composition = toJavaComposition(env, ctx.composition);
        jobject menu = toJavaMenu(env, ctx.menu);
        return env->NewObject(jni::g_refs->ContextProto, jni::g_refs->ContextProtoCtor,
                              composition, menu, jni::makeString(env, ctx.input),
                              ctx.caretPos);
    }

    inline jobject toJavaStatus(JNIEnv *env, const StatusData &status) {
        return env->NewObject(
                jni::g_refs->StatusProto, jni::g_refs->StatusProtoCtor,
                jni::makeString(env, status.schemaId),
                jni::makeString(env, status.schemaName), status.isDisabled,
                status.isComposing, status.isAsciiMode, status.isFullShape,
                status.isSimplified, status.isTraditional, status.isAsciiPunct);
    }

}  // namespace rime_jni
