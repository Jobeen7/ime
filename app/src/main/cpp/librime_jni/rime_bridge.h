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
        jni::LocalRef<jstring> id(env, jni::makeString(env, entry.id));
        jni::LocalRef<jstring> name(env, jni::makeString(env, entry.name));
        jni::LocalRef<jstring> layout(env, jni::makeString(env, entry.layout));
        jni::LocalRef<jstring> punct(env, jni::makeString(env, entry.punctuation));
        jni::LocalRef<jstring> kind(env, jni::makeString(env, entry.kind));
        return env->NewObject(jni::g_refs->SchemaItem, jni::g_refs->SchemaItemCtor,
                              id.get(), name.get(), layout.get(), punct.get(),
                              kind.get());
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
            // GetObjectArrayElement 每轮产生一个局部引用，必须逐轮释放，
            // 否则长数组会累积撑爆局部引用表
            jni::LocalRef<jstring> elem(
                    env, static_cast<jstring>(env->GetObjectArrayElement(arr, i)));
            jni::StringChars chars(env, elem.get());
            out.emplace_back(chars.get());
        }
        return out;
    }

    // 循环构造 Java 对象时某一步若抛异常（如 OOM），必须立即停下：
    // 带未决异常继续调用 JNI 是未定义行为，Android 上会直接 abort。
    // ExceptionCheck 只读 JVM 的一个标志位，代价可忽略。
    inline bool HasPendingException(JNIEnv *env) {
        return env->ExceptionCheck() == JNI_TRUE;
    }

    inline jobjectArray vectorToJavaStringArray(JNIEnv *env,
                                                const std::vector<std::string> &v) {
        jobjectArray arr = env->NewObjectArray(static_cast<int>(v.size()),
                                               jni::g_refs->String, nullptr);
        for (int i = 0; i < static_cast<int>(v.size()); ++i) {
            jni::LocalRef<jstring> ref(env, jni::makeString(env, v[i]));
            env->SetObjectArrayElement(arr, i, ref.get());
            if (HasPendingException(env)) break;
        }
        return arr;
    }

    inline jobject toJavaCommit(JNIEnv *env, const CommitData &commit) {
        jni::LocalRef<jstring> text(
                env, commit.text ? jni::makeString(env, *commit.text) : nullptr);
        return env->NewObject(jni::g_refs->CommitProto, jni::g_refs->CommitProtoCtor,
                              text.get());
    }

    inline jobject toJavaCandidate(JNIEnv *env, const CandidateData &cand) {
        jni::LocalRef<jstring> text(env, jni::makeString(env, cand.text));
        jni::LocalRef<jstring> comment(env, jni::makeString(env, cand.comment));
        jni::LocalRef<jstring> label(env, jni::makeString(env, cand.label));
        jni::LocalRef<jstring> type(env, jni::makeString(env, cand.type));
        return env->NewObject(
                jni::g_refs->CandidateProto, jni::g_refs->CandidateProtoCtor,
                text.get(), comment.get(), label.get(), type.get());
    }

    inline jobjectArray toJavaCandidateArray(JNIEnv *env,
                                             const std::vector<CandidateData> &list) {
        jobjectArray arr = env->NewObjectArray(static_cast<int>(list.size()),
                                               jni::g_refs->CandidateProto, nullptr);
        for (int i = 0; i < static_cast<int>(list.size()); ++i) {
            jni::LocalRef<> ref(env, toJavaCandidate(env, list[i]));
            env->SetObjectArrayElement(arr, i, ref.get());
            if (HasPendingException(env)) break;
        }
        return arr;
    }

    inline jobject toJavaSyllable(JNIEnv *env, const SyllableData &sd) {
        jni::LocalRef<jstring> raw(env, jni::makeString(env, sd.rawInput));
        jni::LocalRef<jstring> spelling(env, jni::makeString(env, sd.spelling));
        jni::LocalRef<jstring> text(env, jni::makeString(env, sd.text));
        return env->NewObject(jni::g_refs->SyllableProto,
                              jni::g_refs->SyllableProtoCtor,
                              raw.get(), spelling.get(), text.get(),
                              static_cast<jint>(sd.textSyllableStart),
                              static_cast<jint>(sd.textSyllableEnd));
    }

    inline jobject toJavaComposition(JNIEnv *env, const CompositionData &comp) {
        jni::LocalRef<jstring> preedit(
                env, comp.preedit ? jni::makeString(env, *comp.preedit) : nullptr);
        jni::LocalRef<jstring> preview(
                env, comp.commitTextPreview
                     ? jni::makeString(env, *comp.commitTextPreview)
                     : nullptr);
        jni::LocalRef<jobjectArray> syllableArray(
                env, env->NewObjectArray(static_cast<int>(comp.syllables.size()),
                                         jni::g_refs->SyllableProto, nullptr));
        for (int i = 0; i < static_cast<int>(comp.syllables.size()); ++i) {
            jni::LocalRef<> ref(env, toJavaSyllable(env, comp.syllables[i]));
            env->SetObjectArrayElement(syllableArray.get(), i, ref.get());
            if (HasPendingException(env)) break;
        }
        return env->NewObject(jni::g_refs->CompositionProto,
                              jni::g_refs->CompositionProtoCtor, comp.length,
                              comp.cursorPos, comp.selStart, comp.selEnd,
                              preedit.get(), preview.get(), syllableArray.get());
    }

    inline jobject toJavaMenu(JNIEnv *env, const MenuData &menu) {
        jni::LocalRef<jobjectArray> candidates(
                env, toJavaCandidateArray(env, menu.candidates));
        jni::LocalRef<jobjectArray> labels(
                env, vectorToJavaStringArray(env, menu.selectLabels));
        jni::LocalRef<jstring> keys(env, jni::makeString(env, menu.selectKeys));
        return env->NewObject(
                jni::g_refs->MenuProto, jni::g_refs->MenuProtoCtor, menu.pageSize,
                menu.pageNumber, menu.isLastPage, menu.highlightedIndex,
                candidates.get(), keys.get(), labels.get());
    }

    inline jobject toJavaContext(JNIEnv *env, const ContextData &ctx) {
        jni::LocalRef<> composition(env, toJavaComposition(env, ctx.composition));
        jni::LocalRef<> menu(env, toJavaMenu(env, ctx.menu));
        jni::LocalRef<jstring> input(env, jni::makeString(env, ctx.input));
        return env->NewObject(jni::g_refs->ContextProto, jni::g_refs->ContextProtoCtor,
                              composition.get(), menu.get(), input.get(),
                              ctx.caretPos);
    }

    inline jobject toJavaStatus(JNIEnv *env, const StatusData &status) {
        jni::LocalRef<jstring> id(env, jni::makeString(env, status.schemaId));
        jni::LocalRef<jstring> name(env, jni::makeString(env, status.schemaName));
        return env->NewObject(
                jni::g_refs->StatusProto, jni::g_refs->StatusProtoCtor,
                id.get(), name.get(), status.isDisabled,
                status.isComposing, status.isAsciiMode, status.isFullShape,
                status.isSimplified, status.isTraditional, status.isAsciiPunct);
    }

}  // namespace rime_jni
