// SPDX-License-Identifier: Apache-2.0
//
// Intermediate C++ data structures that mirror the Kotlin data classes
// exchanged across the JNI boundary. These types are populated from the
// librime C API structs and later converted to Java objects.

#pragma once

#include <rime_api.h>
#include <utf8.h>

#include <cstring>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace rime_jni {

// Counts the number of Unicode code points between two UTF-8 pointers.
    inline int utf8Distance(const char *begin, const char *end) {
        return static_cast<int>(utf8::unchecked::distance(begin, end));
    }

    struct SchemaEntry {
        std::string id;
        std::string name;
        std::string layout;
        std::string punctuation;

        SchemaEntry() = default;

        SchemaEntry(const RimeSchemaListItem &item)
                : id(item.schema_id ? item.schema_id : ""),
                  name(item.name ? item.name : ""),
                  layout(item.layout ? item.layout : ""),
                  punctuation(item.punctuation ? item.punctuation : "") {}

        static std::vector<SchemaEntry> fromList(const RimeSchemaList &list) {
            std::vector<SchemaEntry> out;
            out.reserve(list.size);
            for (size_t i = 0; i < list.size; ++i) {
                out.emplace_back(list.list[i]);
            }
            return out;
        }
    };

    struct CommitData {
        std::optional<std::string> text;

        CommitData() = default;

        explicit CommitData(const RimeCommit *commit) {
            if (commit && commit->text) text = commit->text;
        }
    };

    struct CandidateData {
        std::string text;
        std::string comment;
        std::string label;

        CandidateData() = default;

        CandidateData(const RimeCandidate &c, std::string_view lbl)
                : text(c.text ? c.text : ""),
                  comment(c.comment ? c.comment : ""),
                  label(lbl) {}
    };

    struct SyllableData {
        std::string rawInput;
        std::string spelling;
        std::string text;
        int textSyllableStart = -1;
        int textSyllableEnd = -1;
    };

    struct CompositionData {
        int length = 0;
        int cursorPos = 0;
        int selStart = 0;
        int selEnd = 0;
        std::optional<std::string> preedit;
        std::optional<std::string> commitTextPreview;
        std::vector<SyllableData> syllables;
    };

    struct MenuData {
        int pageSize = 0;
        int pageNumber = 0;
        bool isLastPage = false;
        int highlightedIndex = 0;
        std::vector<CandidateData> candidates;
        std::string selectKeys;
        std::vector<std::string> selectLabels;
    };

    struct ContextData {
        CompositionData composition;
        MenuData menu;
        std::string input;
        int caretPos = 0;

        ContextData() = default;

        ContextData(const RimeContext *ctx, std::string_view in, int caret) {
            input = in;
            caretPos = caret;

            if (ctx->composition.length > 0) {
                const auto &c = ctx->composition;
                const char *preeditStr = c.preedit;
                composition.length = utf8Distance(preeditStr, preeditStr + c.length);
                composition.cursorPos = utf8Distance(preeditStr, preeditStr + c.cursor_pos);
                composition.selStart = utf8Distance(preeditStr, preeditStr + c.sel_start);
                composition.selEnd = utf8Distance(preeditStr, preeditStr + c.sel_end);
                composition.preedit = preeditStr;
                if (ctx->commit_text_preview) {
                    composition.commitTextPreview = ctx->commit_text_preview;
                }
                if (c.num_syllables > 0 && c.syllables) {
                    composition.syllables.reserve(c.num_syllables);
                    for (int i = 0; i < c.num_syllables; ++i) {
                        SyllableData sd;
                        sd.rawInput = c.syllables[i].raw_input ? c.syllables[i].raw_input : "";
                        sd.spelling = c.syllables[i].spelling ? c.syllables[i].spelling : "";
                        sd.text = c.syllables[i].text ? c.syllables[i].text : "";
                        sd.textSyllableStart = c.syllables[i].text_syllable_start;
                        sd.textSyllableEnd = c.syllables[i].text_syllable_end;
                        composition.syllables.push_back(std::move(sd));
                    }
                }
            }

            if (ctx->menu.num_candidates > 0) {
                const auto &m = ctx->menu;
                menu.pageSize = m.page_size;
                menu.pageNumber = m.page_no;
                menu.isLastPage = m.is_last_page;
                menu.highlightedIndex = m.highlighted_candidate_index;
                menu.selectKeys = m.select_keys ? m.select_keys : "";

                const auto keysLen = m.select_keys ? std::strlen(m.select_keys) : 0;
                menu.candidates.reserve(m.num_candidates);
                for (int i = 0; i < m.num_candidates; ++i) {
                    std::string label;
                    if (i < m.page_size && RIME_PROVIDED(ctx, select_labels)) {
                        label = ctx->select_labels[i];
                    } else if (i < static_cast<int>(keysLen)) {
                        label = std::string(1, m.select_keys[i]);
                    } else {
                        label = std::to_string((i + 1) % 10);
                    }
                    menu.selectLabels.push_back(label);
                    menu.candidates.emplace_back(m.candidates[i], label);
                }
            }
        }
    };

    struct StatusData {
        std::string schemaId;
        std::string schemaName;
        bool isDisabled = false;
        bool isComposing = false;
        bool isAsciiMode = false;
        bool isFullShape = false;
        bool isSimplified = false;
        bool isTraditional = false;
        bool isAsciiPunct = false;

        StatusData() = default;

        explicit StatusData(const RimeStatus *s)
                : schemaId(s->schema_id ? s->schema_id : ""),
                  schemaName(s->schema_name ? s->schema_name : ""),
                  isDisabled(s->is_disabled),
                  isComposing(s->is_composing),
                  isAsciiMode(s->is_ascii_mode),
                  isFullShape(s->is_full_shape),
                  isSimplified(s->is_simplified),
                  isTraditional(s->is_traditional),
                  isAsciiPunct(s->is_ascii_punct) {}
    };

}  // namespace rime_jni
