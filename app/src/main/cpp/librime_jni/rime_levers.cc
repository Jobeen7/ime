// SPDX-License-Identifier: Apache-2.0
//
// JNI bridge for the librime "levers" module. Provides schema management
// and user dictionary backup/restore/export/import operations through the
// RimeLeversApi C interface (BSD-3-Clause).

#include <rime_levers_api.h>

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

    RimeLeversApi *leversApi() {
        return reinterpret_cast<RimeLeversApi *>(
                rime_get_api()->find_module("levers")->get_api());
    }

    class SwitcherSettings {
    public:
        SwitcherSettings()
                : api_(leversApi()), settings_(api_->switcher_settings_init()) {
            api_->load_settings(reinterpret_cast<RimeCustomSettings *>(settings_));
        }

        ~SwitcherSettings() {
            if (settings_)
                api_->custom_settings_destroy(
                        reinterpret_cast<RimeCustomSettings *>(settings_));
        }

        SwitcherSettings(const SwitcherSettings &) = delete;

        SwitcherSettings &operator=(const SwitcherSettings &) = delete;

        std::vector<SchemaEntry> availableSchemas() {
            RimeSchemaList list{};
            std::vector<SchemaEntry> out;
            if (api_->get_available_schema_list(settings_, &list)) {
                out = SchemaEntry::fromList(list);
                api_->schema_list_destroy(&list);
            }
            return out;
        }

        std::vector<SchemaEntry> selectedSchemas() {
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

extern "C" {

JNIEXPORT jobjectArray JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_getAvailableSchemaList(
        JNIEnv *env, jclass) {
    SwitcherSettings sw;
    return toJavaSchemaArray(env, sw.availableSchemas());
}

JNIEXPORT jobjectArray JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_getSelectedSchemaList(
        JNIEnv *env, jclass) {
    SwitcherSettings sw;
    return toJavaSchemaArray(env, sw.selectedSchemas());
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_core_Rime_selectSchemas(
        JNIEnv *env, jclass, jobjectArray array) {
    SwitcherSettings sw;
    return sw.selectSchemas(javaStringArrayToVector(env, array));
}

JNIEXPORT jobjectArray JNICALL
Java_com_ninthsoft_ime_engine_rime_data_userdict_UserDictManager_getUserDictList(
        JNIEnv *env, jclass) {
    auto *api = leversApi();
    std::vector<std::string> dicts;
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
Java_com_ninthsoft_ime_engine_rime_data_userdict_UserDictManager_backupUserDict(
        JNIEnv *env, jclass, jstring dict_name) {
    jni::StringChars name(env, dict_name);
    return leversApi()->backup_user_dict(name.get());
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_engine_rime_data_userdict_UserDictManager_restoreUserDict(
        JNIEnv *env, jclass, jstring snapshot_file) {
    jni::StringChars path(env, snapshot_file);
    return leversApi()->restore_user_dict(path.get());
}

JNIEXPORT jint JNICALL
Java_com_ninthsoft_ime_engine_rime_data_userdict_UserDictManager_exportUserDict(
        JNIEnv *env, jclass, jstring dict_name, jstring text_file) {
    jni::StringChars name(env, dict_name);
    jni::StringChars file(env, text_file);
    return leversApi()->export_user_dict(name.get(), file.get());
}

JNIEXPORT jint JNICALL
Java_com_ninthsoft_ime_engine_rime_data_userdict_UserDictManager_importUserDict(
        JNIEnv *env, jclass, jstring dict_name, jstring text_file) {
    jni::StringChars name(env, dict_name);
    jni::StringChars file(env, text_file);
    return leversApi()->import_user_dict(name.get(), file.get());
}

}  // extern "C"
