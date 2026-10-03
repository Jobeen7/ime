// SPDX-License-Identifier: Apache-2.0
//
// JNI bridge for the librime "levers" module. Provides schema management
// and user dictionary backup/restore/export/import operations through the
// RimeLeversApi C interface (BSD-3-Clause).

#include <rime_levers_api.h>

#include <rime/dict/db_utils.h>
#include <rime/dict/table_db.h>
#include <rime/dict/tsv.h>
#include <rime/dict/user_db.h>
#include <rime/dict/user_dictionary.h>
#include <rime/registry.h>

#include <memory>
#include <string>
#include <string_view>
#include <unordered_set>
#include <vector>

#include "jni_env.h"
#include "rime_bridge.h"
#include "rime_data.h"

using namespace rime_jni;

namespace {

    // 任一环节缺失（Rime 未初始化、模块未加载）都返回 nullptr，
    // 调用方必须判空：旧实现直接解引用链，异常状态下就是 native 崩溃
    RimeLeversApi *leversApi() {
        auto *api = rime_get_api();
        if (!api || !api->find_module) return nullptr;
        auto *module = api->find_module("levers");
        if (!module || !module->get_api) return nullptr;
        return reinterpret_cast<RimeLeversApi *>(module->get_api());
    }
    class SwitcherSettings {
    public:
        SwitcherSettings()
                : api_(leversApi()),
                  settings_(api_ ? api_->switcher_settings_init() : nullptr) {
            if (api_ && settings_) {
                api_->load_settings(reinterpret_cast<RimeCustomSettings *>(settings_));
            }
        }

        ~SwitcherSettings() {
            if (api_ && settings_)
                api_->custom_settings_destroy(
                        reinterpret_cast<RimeCustomSettings *>(settings_));
        }

        SwitcherSettings(const SwitcherSettings &) = delete;

        SwitcherSettings &operator=(const SwitcherSettings &) = delete;

        std::vector<SchemaEntry> availableSchemas() {
            if (!api_ || !settings_) return {};
            RimeSchemaList list{};
            std::vector<SchemaEntry> out;
            if (api_->get_available_schema_list(settings_, &list)) {
                out = SchemaEntry::fromList(list);
                api_->schema_list_destroy(&list);
            }
            return out;
        }

        std::vector<SchemaEntry> selectedSchemas() {
            if (!api_ || !settings_) return {};
            RimeSchemaList list{};
            std::vector<SchemaEntry> out;
            if (api_->get_selected_schema_list(settings_, &list)) {
                std::unordered_set<std::string> selectedIds;
                for (size_t i = 0; i < list.size; ++i) {
                    if (list.list[i].schema_id) selectedIds.insert(list.list[i].schema_id);
                }
                api_->schema_list_destroy(&list);
                RimeSchemaList available{};
                if (api_->get_available_schema_list(settings_, &available)) {
                    for (size_t i = 0; i < available.size; ++i) {
                        const auto &item = available.list[i];
                        if (item.schema_id && selectedIds.count(item.schema_id)) {
                            out.emplace_back(item);
                        }
                    }
                    api_->schema_list_destroy(&available);
                }
            }
            return out;
        }

        bool selectSchemas(const std::vector<std::string> &ids) {
            if (!api_ || !settings_) return false;
            std::vector<const char *> ptrs;
            ptrs.reserve(ids.size());
            for (const auto &id: ids) ptrs.push_back(id.c_str());
            bool ok = api_->select_schemas(settings_, ptrs.data(),
                                           static_cast<int>(ptrs.size()));
            if (ok) api_->save_settings(reinterpret_cast<RimeCustomSettings *>(settings_));
            return ok;
        }

    private:
        RimeLeversApi *api_;
        RimeSwitcherSettings *settings_;
    };

}  // namespace

namespace {

// Hot import/export (Jime user dict sync): merge into / read from the
// already-open shared user db while the engine is running, without shutting
// Rime down.
//
// Background: the classic levers import/export opens a second LevelDB handle,
// which fails while the engine holds the db open; stopping Rime first is not
// an option either, because native finalize clears the component registry and
// unloads modules, crashing any later levers call (and it blocks on the
// maintenance thread). So instead we reuse the shared Db instance from the
// user_dictionary component's pool and run the exact same TsvReader ->
// UserDbImporter / DbSource -> TsvWriter pipeline on it. When the db isn't
// currently open (engine idle or dict not in use), fall back to the classic
// levers API, which can safely open it itself.
rime::an<rime::Db> GetOpenUserDb(const std::string &dict_name) {
    auto *udc = dynamic_cast<rime::UserDictionaryComponent *>(
            rime::Registry::instance().Find("user_dictionary"));
    if (!udc) {
        return nullptr;
    }
    rime::the<rime::UserDictionary> dict(udc->Create(dict_name, "userdb"));
    if (!dict) {
        return nullptr;
    }
    rime::an<rime::Db> db = dict->db();
    if (!db) {
        return nullptr;
    }
    // The component pool shares one Db per dict with the running engine, but
    // the handle may not be opened yet (the engine loads it lazily). Open it
    // here so the import lands in the same store the translator reads from.
    // Open() is a no-op when already loaded.
    if (!db->loaded()) {
        db->Open();
    }
    return db->loaded() ? db : nullptr;
}

jint HotImportUserDict(const char *dict_name, const char *text_file) {
    if (rime::an<rime::Db> db = GetOpenUserDb(dict_name)) {
        if (!rime::UserDbHelper(db).IsUserDb())
            return -1;
        rime::TsvReader reader(rime::path(text_file), rime::TableDb::format.parser);
        rime::UserDbImporter importer(db.get());
        int num_entries = 0;
        try {
            num_entries = reader >> importer;
        } catch (std::exception &) {
            return -1;
        }
        return num_entries;
    }
    auto *api = leversApi();
    if (!api) return -1;
    return api->import_user_dict(dict_name, text_file);
}

jint HotExportUserDict(const char *dict_name, const char *text_file) {
    if (rime::an<rime::Db> db = GetOpenUserDb(dict_name)) {
        if (!rime::UserDbHelper(db).IsUserDb())
            return -1;
        rime::TsvWriter writer(rime::path(text_file), rime::TableDb::format.formatter);
        writer.file_description = "Rime user dictionary export";
        rime::DbSource source(db.get());
        int num_entries = 0;
        try {
            num_entries = writer << source;
        } catch (std::exception &) {
            return -1;
        }
        return num_entries;
    }
    auto *api = leversApi();
    if (!api) return -1;
    return api->export_user_dict(dict_name, text_file);
}

}  // namespace

extern "C" {

JNIEXPORT jobjectArray JNICALL
Java_com_jobeen_ime_engine_rime_core_Rime_getAvailableSchemaList(
        JNIEnv *env, jclass) {
    SwitcherSettings sw;
    return toJavaSchemaArray(env, sw.availableSchemas());
}

JNIEXPORT jobjectArray JNICALL
Java_com_jobeen_ime_engine_rime_core_Rime_getSelectedSchemaList(
        JNIEnv *env, jclass) {
    SwitcherSettings sw;
    return toJavaSchemaArray(env, sw.selectedSchemas());
}

JNIEXPORT jboolean JNICALL
Java_com_jobeen_ime_engine_rime_core_Rime_selectSchemas(
        JNIEnv *env, jclass, jobjectArray array) {
    SwitcherSettings sw;
    return sw.selectSchemas(javaStringArrayToVector(env, array));
}

JNIEXPORT jobjectArray JNICALL
Java_com_jobeen_ime_engine_rime_data_userdict_UserDictManager_getUserDictList(
        JNIEnv *env, jclass) {
    auto *api = leversApi();
    std::vector<std::string> dicts;
    if (!api) return vectorToJavaStringArray(env, dicts);
    RimeUserDictIterator iter{};
    if (api->user_dict_iterator_init(&iter)) {
        while (true) {
            const char *name = api->next_user_dict(&iter);
            if (!name) break;
            dicts.emplace_back(name);
        }
        api->user_dict_iterator_destroy(&iter);
    }
    return vectorToJavaStringArray(env, dicts);
}

JNIEXPORT jboolean JNICALL
Java_com_jobeen_ime_engine_rime_data_userdict_UserDictManager_backupUserDict(
        JNIEnv *env, jclass, jstring dict_name) {
    jni::StringChars name(env, dict_name);
    auto *api = leversApi();
    return api ? api->backup_user_dict(name.get()) : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_jobeen_ime_engine_rime_data_userdict_UserDictManager_restoreUserDict(
        JNIEnv *env, jclass, jstring snapshot_file) {
    jni::StringChars path(env, snapshot_file);
    auto *api = leversApi();
    return api ? api->restore_user_dict(path.get()) : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_jobeen_ime_engine_rime_data_userdict_UserDictManager_exportUserDict(
        JNIEnv *env, jclass, jstring dict_name, jstring text_file) {
    jni::StringChars name(env, dict_name);
    jni::StringChars file(env, text_file);
    auto *api = leversApi();
    return api ? api->export_user_dict(name.get(), file.get()) : -1;
}

JNIEXPORT jint JNICALL
Java_com_jobeen_ime_engine_rime_data_userdict_UserDictManager_importUserDict(
        JNIEnv *env, jclass, jstring dict_name, jstring text_file) {
    jni::StringChars name(env, dict_name);
    jni::StringChars file(env, text_file);
    auto *api = leversApi();
    return api ? api->import_user_dict(name.get(), file.get()) : -1;
}


JNIEXPORT jint JNICALL
Java_com_jobeen_ime_engine_rime_data_userdict_UserDictManager_importUserDictLive(
        JNIEnv *env, jclass, jstring dict_name, jstring text_file) {
    jni::StringChars name(env, dict_name);
    jni::StringChars file(env, text_file);
    return HotImportUserDict(name.get(), file.get());
}

JNIEXPORT jint JNICALL
Java_com_jobeen_ime_engine_rime_data_userdict_UserDictManager_exportUserDictLive(
        JNIEnv *env, jclass, jstring dict_name, jstring text_file) {
    jni::StringChars name(env, dict_name);
    jni::StringChars file(env, text_file);
    return HotExportUserDict(name.get(), file.get());
}

}  // extern "C"
