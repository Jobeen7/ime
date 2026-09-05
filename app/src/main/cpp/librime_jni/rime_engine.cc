// SPDX-License-Identifier: Apache-2.0
//
// Main JNI entry point and RIME engine bridge. Exposes the librime C API
// (rime_api.h, BSD-3-Clause) to the Kotlin layer through a set of static
// JNI methods on the com.ninthsoft.ime.engine.rime.core.Rime class.

#include <rime_api.h>

#include <cstdlib>
#include <memory>
#include <string>
#include <tuple>
#include <vector>

#include "jni_env.h"
#include "rime_bridge.h"
#include "rime_data.h"

using namespace rime_jni;

// Forward declarations for librime plugin module dependency registration.
// These are defined in the plugin shared object files and must have external
// linkage so the linker can resolve them.
extern void rime_require_module_lua();

extern void rime_require_module_octagram();

extern void rime_require_module_predict();

namespace {

    constexpr int kMaxSchemaIdLen = 2048;
    constexpr int kBulkCandidateLimit = 100;

    void requireModules() {
        rime_require_module_lua();
        rime_require_module_octagram();
        rime_require_module_predict();
    }

    class RimeSession {
    public:
        RimeSession() : id_(rime_get_api()->create_session()) {
            if (!id_) throw std::runtime_error("create_session failed");
        }

        ~RimeSession() {
            if (id_) rime_get_api()->destroy_session(id_);
        }

        RimeSession(const RimeSession &) = delete;

        RimeSession &operator=(const RimeSession &) = delete;

        RimeSessionId id() const { return id_; }

    private:
        RimeSessionId id_ = 0;
    };

    class RimeEngine {
    public:
        static RimeEngine &instance() {
            static RimeEngine eng;
            return eng;
        }

        RimeEngine() : api_(rime_get_api()) {}

        RimeEngine(const RimeEngine &) = delete;

        RimeEngine &operator=(const RimeEngine &) = delete;

        void startup(bool fullCheck, RimeNotificationHandler handler) {
            if (!api_) return;

            RIME_STRUCT(RimeTraits, traits)
            traits.shared_data_dir = std::getenv("RIME_SHARED_DATA_DIR");
            traits.user_data_dir = std::getenv("RIME_USER_DATA_DIR");
            traits.log_dir = "";
            traits.app_name = "rime.cpp";
            traits.distribution_name = "Jime";
            traits.distribution_code_name = "Jime";
            traits.distribution_version = std::getenv("RIME_DISTRIBUTION_VERSION");

            api_->setup(&traits);
            api_->initialize(&traits);
            api_->set_notification_handler(handler, jni::g_refs->vm);
            api_->start_maintenance(fullCheck);
        }

        void shutdown() {
            session_.reset();
            api_->finalize();
        }

        void joinMaintenanceThread() {
            api_->join_maintenance_thread();
        }

        bool syncUserData() {
            session_.reset();
            return api_->sync_user_data();
        }

        bool deploySchema(const std::string &file) {
            return api_->deploy_schema(file.c_str());
        }

        bool deployConfig(const std::string &file, const std::string &versionKey) {
            return api_->deploy_config_file(file.c_str(), versionKey.c_str());
        }

        bool processKey(int keycode, int mask) {
            return api_->process_key(sessionId(), keycode, mask);
        }

        bool simulateKeySequence(const std::string &seq) {
            return api_->simulate_key_sequence(sessionId(), seq.c_str());
        }

        bool setInput(const std::string &input) {
            return api_->set_input(sessionId(), input.c_str());
        }

        bool appendInput(const std::string &input) {
            return api_->append_input(sessionId(), input.c_str());
        }

        size_t getInputConfirmedPosition() {
            return api_->get_input_confirmed_pos(sessionId());
        }

        bool commitComposition() {
            return api_->commit_composition(sessionId());
        }

        bool commitCurrentSelection(const std::string &append) {
            return api_->commit_current_selection(sessionId(), append.empty() ? nullptr : append.c_str());
        }

        void clearComposition() { api_->clear_composition(sessionId()); }

        void freeContext() {
            RIME_STRUCT(RimeContext, data)
            auto sid = sessionId();
            if (api_->get_context(sid, &data)) {
                api_->free_context(&data);
            }
        }

        std::unique_ptr<CommitData> commit() {
            RIME_STRUCT(RimeCommit, data)
            if (api_->get_commit(sessionId(), &data)) {
                auto p = std::make_unique<CommitData>(&data);
                api_->free_commit(&data);
                return p;
            }
            return std::make_unique<CommitData>();
        }

        std::unique_ptr<ContextData> context() {
            RIME_STRUCT(RimeContext, data)
            auto sid = sessionId();
            if (api_->get_context(sid, &data)) {
                const char *input = api_->get_input(sid);
                size_t caret = api_->get_caret_pos(sid);
                auto p = std::make_unique<ContextData>(
                        &data, input ? input : "", static_cast<int>(caret));
                api_->free_context(&data);
                return p;
            }
            return std::make_unique<ContextData>();
        }

        std::unique_ptr<StatusData> status() {
            RIME_STRUCT(RimeStatus, data)
            if (api_->get_status(sessionId(), &data)) {
                auto p = std::make_unique<StatusData>(&data);
                api_->free_status(&data);
                return p;
            }
            return std::make_unique<StatusData>();
        }

        void setOption(const std::string &key, bool value) {
            api_->set_option(sessionId(), key.c_str(), value);
        }

        bool getOption(const std::string &key) {
            return api_->get_option(sessionId(), key.c_str());
        }

        std::string currentSchemaId() {
            char buf[kMaxSchemaIdLen];
            return api_->get_current_schema(sessionId(), buf, kMaxSchemaIdLen) ? buf
                                                                               : "";
        }

        std::vector<SchemaEntry> schemaList() {
            RimeSchemaList list{};
            std::vector<SchemaEntry> out;
            if (api_->get_schema_list(&list)) {
                out = SchemaEntry::fromList(list);
                api_->free_schema_list(&list);
            }
            return out;
        }

        bool selectSchema(const std::string &id) {
            return api_->select_schema(sessionId(), id.c_str());
        }

        std::string rawInput() {
            const char *s = api_->get_input(sessionId());
            return s ? s : "";
        }

        size_t caretPos() { return api_->get_caret_pos(sessionId()); }

        void setCaretPos(size_t pos) { api_->set_caret_pos(sessionId(), pos); }

        bool selectCandidate(size_t index, bool global) {
            return global ? api_->select_candidate(sessionId(), index)
                          : api_->select_candidate_on_current_page(sessionId(), index);
        }

        bool deleteCandidate(size_t index, bool global) {
            return global ? api_->delete_candidate(sessionId(), index)
                          : api_->delete_candidate_on_current_page(sessionId(), index);
        }

        bool changePage(bool backward) {
            return api_->change_page(sessionId(), backward);
        }

        std::vector<CandidateData> candidates(int start, int limit) {
            std::vector<CandidateData> out;
            out.reserve(limit);
            RimeCandidateListIterator iter{};
            if (api_->candidate_list_from_index(sessionId(), &iter, start)) {
                int count = 0;
                while (api_->candidate_list_next(&iter)) {
                    if (count >= limit) break;
                    out.emplace_back(iter.candidate, std::to_string((count + 1) % 10));
                    ++count;
                }
                api_->candidate_list_end(&iter);
            }
            return out;
        }

        std::tuple<int, int, std::vector<CandidateData>> bulkCandidates() {
            auto list = candidates(0, kBulkCandidateLimit);
            int size = static_cast<int>(list.size()) < kBulkCandidateLimit
                       ? static_cast<int>(list.size())
                       : -1;
            int highlighted = 0;
            RIME_STRUCT(RimeContext, ctx)
            if (api_->get_context(sessionId(), &ctx)) {
                highlighted = ctx.menu.highlighted_candidate_index;
                api_->free_context(&ctx);
            }
            return std::make_tuple(size, highlighted, std::move(list));
        }

    private:
        RimeApi *api_;
        std::shared_ptr<RimeSession> session_;

        RimeSessionId sessionId() {
            if (!session_) {
                try {
                    session_ = std::make_shared<RimeSession>();
                } catch (...) {
                    session_.reset();
                }
            }
            return session_ ? session_->id() : 0;
        }
    };

}  // namespace

jni::GlobalRefs *jni::g_refs = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    jni::g_refs = new jni::GlobalRefs(vm);
    requireModules();
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_bootstrap(
        JNIEnv *env, jclass, jstring shared_dir, jstring user_dir,
        jstring version_name, jboolean full_check) {
    jni::StringChars shared(env, shared_dir);
    jni::StringChars user(env, user_dir);
    jni::StringChars version(env, version_name);

    setenv("RIME_SHARED_DATA_DIR", shared.get(), 1);
    setenv("RIME_USER_DATA_DIR", user.get(), 1);
    setenv("RIME_DISTRIBUTION_VERSION", version.get(), 1);

    auto handler = [](void * /*ctx*/, RimeSessionId, const char *type,
                      const char *value) {
        auto env = jni::g_refs->attach();
        int code = 0;
        if (std::strcmp(type, "schema") == 0) {
            code = 1;
        } else if (std::strcmp(type, "option") == 0) {
            code = 2;
        } else if (std::strcmp(type, "deploy") == 0) {
            code = 3;
        }
        jobjectArray args =
                env->NewObjectArray(1, jni::g_refs->Object, nullptr);
        jni::LocalRef<jstring> ref(env, jni::makeString(env, value));
        env->SetObjectArrayElement(args, 0, ref.get());
        env->CallStaticVoidMethod(jni::g_refs->Rime, jni::g_refs->HandleRimeMessage,
                                  code, args);
        env->DeleteLocalRef(args);
    };

    RimeEngine::instance().startup(full_check, handler);
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_shutdown(JNIEnv *, jclass) {
    RimeEngine::instance().shutdown();
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_joinMaintenanceThread(JNIEnv *, jclass) {
    RimeEngine::instance().joinMaintenanceThread();
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_deploySchemaFile(
        JNIEnv *env, jclass, jstring schema_file) {
    jni::StringChars path(env, schema_file);
    return RimeEngine::instance().deploySchema(path.str());
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_deployConfigFile(
        JNIEnv *env, jclass, jstring file_name, jstring version_key) {
    jni::StringChars file(env, file_name);
    jni::StringChars key(env, version_key);
    return RimeEngine::instance().deployConfig(file.str(), key.str());
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_syncUserData(JNIEnv *, jclass) {
    return RimeEngine::instance().syncUserData();
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_processKey(
        JNIEnv *, jclass, jint keycode, jint mask) {
    return RimeEngine::instance().processKey(keycode, mask);
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_commitComposition(
        JNIEnv *, jclass) {
    return RimeEngine::instance().commitComposition();
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_commitCurrentSelection(
        JNIEnv *env, jclass, jstring append) {
    jni::StringChars suffix(env, append);
    return RimeEngine::instance().commitCurrentSelection(suffix.str());
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_clearComposition(
        JNIEnv *, jclass) {
    RimeEngine::instance().clearComposition();
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_freeContext(
        JNIEnv *, jclass) {
    RimeEngine::instance().freeContext();
}

JNIEXPORT jobject JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_getCommit(JNIEnv *env,
                                                       jclass) {
    auto c = RimeEngine::instance().commit();
    return toJavaCommit(env, *c);
}

JNIEXPORT jobject JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_getContext(JNIEnv *env,
                                                        jclass) {
    auto ctx = RimeEngine::instance().context();
    return toJavaContext(env, *ctx);
}

JNIEXPORT jobject JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_getStatus(JNIEnv *env,
                                                       jclass) {
    auto s = RimeEngine::instance().status();
    return toJavaStatus(env, *s);
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_setOption(
        JNIEnv *env, jclass, jstring option, jboolean value) {
    jni::StringChars key(env, option);
    RimeEngine::instance().setOption(key.str(), value);
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_getOption(JNIEnv *env,
                                                       jclass,
                                                       jstring option) {
    jni::StringChars key(env, option);
    return RimeEngine::instance().getOption(key.str());
}

JNIEXPORT jobjectArray JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_getSchemaList(JNIEnv *env,
                                                           jclass) {
    return toJavaSchemaArray(env, RimeEngine::instance().schemaList());
}

JNIEXPORT jstring JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_getCurrentSchema(
        JNIEnv *env, jclass) {
    return jni::makeString(env, RimeEngine::instance().currentSchemaId());
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_selectSchema(
        JNIEnv *env, jclass, jstring schema_id) {
    jni::StringChars id(env, schema_id);
    return RimeEngine::instance().selectSchema(id.str());
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_simulateKeySequence(
        JNIEnv *env, jclass, jstring key_sequence) {
    jni::StringChars seq(env, key_sequence);
    return RimeEngine::instance().simulateKeySequence(seq.str());
}

JNIEXPORT jstring JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_getRawInput(JNIEnv *env,
                                                         jclass) {
    return jni::makeString(env, RimeEngine::instance().rawInput());
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_setInput(JNIEnv *env,
                                                       jclass, jstring input) {
    jni::StringChars raw(env, input);
    return RimeEngine::instance().setInput(raw.str());
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_appendInput(JNIEnv *env,
                                                         jclass, jstring input) {
    jni::StringChars raw(env, input);
    return RimeEngine::instance().appendInput(raw.str());
}


JNIEXPORT jint JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_getInputConfirmedPosition(JNIEnv *env,
                                                      jclass) {
    return static_cast<jint>(RimeEngine::instance().getInputConfirmedPosition());
}

JNIEXPORT jint JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_getCaretPos(JNIEnv *, jclass) {
    return static_cast<jint>(RimeEngine::instance().caretPos());
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_setCaretPos(JNIEnv *, jclass,
                                                         jint caret_pos) {
    RimeEngine::instance().setCaretPos(static_cast<size_t>(caret_pos));
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_selectCandidate(
        JNIEnv *, jclass, jint index, jboolean global) {
    return RimeEngine::instance().selectCandidate(
            static_cast<size_t>(index), global);
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_deleteCandidate(
        JNIEnv *, jclass, jint index, jboolean global) {
    return RimeEngine::instance().deleteCandidate(
            static_cast<size_t>(index), global);
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_changeCandidatePage(
        JNIEnv *, jclass, jboolean backward) {
    return RimeEngine::instance().changePage(backward);
}

JNIEXPORT jobjectArray JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_getCandidates(
        JNIEnv *env, jclass, jint start_index, jint limit) {
    auto list = RimeEngine::instance().candidates(start_index, limit);
    return toJavaCandidateArray(env, list);
}

JNIEXPORT jobjectArray JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_getBulkCandidates(
        JNIEnv *env, jclass) {
    auto [size, highlighted, list] = RimeEngine::instance().bulkCandidates();

    jni::LocalRef<> sizeObj(
            env, env->NewObject(jni::g_refs->Integer, jni::g_refs->IntegerCtor, size));
    jni::LocalRef<> hlObj(env, env->NewObject(jni::g_refs->Integer,
                                              jni::g_refs->IntegerCtor, highlighted));
    jni::LocalRef<jobjectArray> listObj(env, toJavaCandidateArray(env, list));

    jobjectArray result =
            env->NewObjectArray(3, jni::g_refs->Object, nullptr);
    env->SetObjectArrayElement(result, 0, sizeObj.get());
    env->SetObjectArrayElement(result, 1, hlObj.get());
    env->SetObjectArrayElement(result, 2, listObj.get());
    return result;
}

}  // extern "C"
