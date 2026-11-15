// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * AI 种子数据（WO52 9.1）：Jime 自写的第一批内置内容，新库建库
 * 或迁移 15→16 时落库。
 *
 * - 服务商 1 条 DeepSeek（baseURL https://api.deepseek.com，apiPath "v1"，
 *   模型 "deepseek-v4-flash"，contextWindow 64000，requestTimeout 60.0；
 *   种子里没有 apiToken——密钥只由使用者自己在设置里填）；
 * - 系统提示词 5 条、用户提示词 3 条、指令 4 条（措辞均为 Jime
 *   自写：翻译／润色／改语气／回复，各配剪贴板或选中文本模板）；
 * - 模型行没有单独的来源数组：服务商的 `modelID` 字段指向模型行
 *   id `...0102`，`requestModelID` 才是调 API 用的模型字符串；
 *   模型行按此补种（displayName 与 modelID 均为 "deepseek-v4-flash"）。
 * - sort 从 Int.MIN_VALUE 起排；canBeDelete 映射到
 *   builtInCanBeDelete，isBuiltIn 恒 true；"无 System Prompt"／
 *   "无 User Prompt"／DeepSeek 为默认（isDefault true），其余 false。
 * - `type` 未指定时 Jime 侧默认 "openai"（OpenAI 兼容）。
 * - 图标名（iconName）是 Jime 自有命名（none／translate／polish／
 *   tone／reply／clipboard／selection），只作字符串存取，不对应
 *   任何系统图标集。
 *
 * 落库：[insertAll] 用绑定参数逐行插（不拼 SQL 串，种子正文含换行与
 * 引号也不怕）；迁移 15→16 与新库 onCreate 共用同一函数。插入顺序：
 * 服务商（defaultModelId 先空）→模型→回填服务商 defaultModelId→
 * 提示词→指令（提示词→指令的外键在事务提交时才校验，建表时已声明
 * 为 DEFERRABLE，所以先插提示词、后插指令才不会触发外键错）。
 */
object AiSeeds {

    const val DEEPSEEK_PROVIDER_ID = "00000000-0000-4000-8000-000000000101"
    const val DEEPSEEK_MODEL_ROW_ID = "00000000-0000-4000-8000-000000000102"

    val providers: List<AIProvider> = listOf(
        AIProvider(
            id = "00000000-0000-4000-8000-000000000101",
            name = "DeepSeek",
            sort = Int.MIN_VALUE,
            isDefault = true,
            isEnabled = true,
            isBuiltIn = true,
            builtInCanBeDelete = true,
            baseURL = "https://api.deepseek.com",
            apiPath = "v1",
            apiToken = "",
            requestTimeout = 60.0,
            contextWindow = 64000,
            defaultModelId = DEEPSEEK_MODEL_ROW_ID,
        ),
    )

    val models: List<AIProviderModel> = listOf(
        AIProviderModel(
            id = DEEPSEEK_MODEL_ROW_ID,
            providerId = DEEPSEEK_PROVIDER_ID,
            displayName = "deepseek-v4-flash",
            modelID = "deepseek-v4-flash",
            sort = Int.MIN_VALUE,
            isEnabled = true,
        ),
    )

    val systemPrompts: List<AISystemPrompt> = listOf(
        AISystemPrompt(
            id = "00000000-0000-4000-8000-000000000001",
            name = "无 System Prompt",
            iconName = "none",
            purpose = "",
            body = "",
            sort = -2147483648,
            isDefault = true,
            isEnabled = true,
            isBuiltIn = true,
            builtInCanBeDelete = false,
        ),
        AISystemPrompt(
            id = "00000000-0000-4000-8000-000000000003",
            name = "翻译",
            iconName = "translate",
            purpose = "在中英文之间互译，译文即答案",
            body = "你来负责翻译。用户发来一段文字，你把它译成另一种语言：中文原文译成英文，其他语言的原文译成简体中文。\n\n翻译时注意：\n1. 意思要译准，措辞按目标语言的习惯来写，读起来自然；\n2. 原文里的换行、列表、代码块、网址、@ 提到的人和表情符号，原样保留；\n3. 代码里的名字和不适合翻译的专有名词不动；\n4. 回答里只有译文，别加解释、别加引号、也别把原文贴回来。",
            sort = -2147483647,
            isDefault = false,
            isEnabled = true,
            isBuiltIn = true,
            builtInCanBeDelete = true,
        ),
        AISystemPrompt(
            id = "00000000-0000-4000-8000-000000000004",
            name = "润色",
            iconName = "polish",
            purpose = "把文字改得更顺，不动原意和语种",
            body = "你来负责润色。用户发来的文字，你帮它改得更清楚、更顺、更像正式写出来的样子。\n\n注意：\n1. 语言、意思和信息量都不变，别添事实、别删事实、别改立场；\n2. 错字、标点和语病都修掉，句子不顺就调顺序，多余的话删掉；\n3. 原文的换行、列表、代码块和专有名词留着；\n4. 回答里只有改完的文字，别解释改了哪里、别加引号、别一次给几个版本。",
            sort = -2147483646,
            isDefault = false,
            isEnabled = true,
            isBuiltIn = true,
            builtInCanBeDelete = true,
        ),
        AISystemPrompt(
            id = "00000000-0000-4000-8000-000000000005",
            name = "改语气",
            iconName = "tone",
            purpose = "照点名要的语气重写；没点名就改得客气得体",
            body = "你来负责改语气。用户发来的文字，按点名要的语气重写一遍。\n\n注意：\n1. 文字里写了目标语气（比如「正式一点」「委婉些」「轻松点」），就按它改，那句要求本身别写进结果；\n2. 没写要什么语气，就改得客气、得体、像正式场合说的话；\n3. 语言、核心意思和要办的事不变，别添事实也别删事实；\n4. 回答里只有改完的文字，别解释、别加引号、别一次给几个版本。",
            sort = -2147483645,
            isDefault = false,
            isEnabled = true,
            isBuiltIn = true,
            builtInCanBeDelete = true,
        ),
        AISystemPrompt(
            id = "00000000-0000-4000-8000-000000000006",
            name = "回复",
            iconName = "reply",
            purpose = "照着收到的消息，替用户拟一条回复",
            body = "用户发来的是别人写给他的消息或者一段对话，你替他拟一条回复。\n\n注意：\n1. 回复用和来信一样的语言；正式场合就正式、日常场合就随意；\n2. 对方问的事、要的东西要正面接住；信息不够时给一句稳妥的话，别编事实、时间和承诺；\n3. 长短和来信差不多，通常一到三句；除非来信是正式邮件，否则别写称呼和落款；\n4. 回答里只有回复本身，别解释、别加引号、别一次给几个版本。",
            sort = -2147483644,
            isDefault = false,
            isEnabled = true,
            isBuiltIn = true,
            builtInCanBeDelete = true,
        ),
    )

    val userPrompts: List<AIUserPrompt> = listOf(
        AIUserPrompt(
            id = "00000000-0000-4000-8000-000000000002",
            name = "无 User Prompt",
            iconName = "none",
            purpose = "",
            templateBody = "{{input}}",
            sort = -2147483648,
            isDefault = true,
            isEnabled = true,
            isBuiltIn = true,
            builtInCanBeDelete = false,
        ),
        AIUserPrompt(
            id = "00000000-0000-4000-8000-000000000007",
            name = "系统剪贴板",
            iconName = "clipboard",
            purpose = "把输入框的文字和剪贴板里的文字合在一起发出去",
            templateBody = "{{input}}\n\n{{pasteboardContent}}",
            sort = -2147483647,
            isDefault = false,
            isEnabled = true,
            isBuiltIn = true,
            builtInCanBeDelete = true,
        ),
        AIUserPrompt(
            id = "00000000-0000-4000-8000-000000000008",
            name = "选中文本",
            iconName = "selection",
            purpose = "把输入框的文字和宿主应用里选中的文字合在一起发出去",
            templateBody = "{{input}}\n\n{{selectedText}}",
            sort = -2147483646,
            isDefault = false,
            isEnabled = true,
            isBuiltIn = true,
            builtInCanBeDelete = true,
        ),
    )

    val instructions: List<AIInstruction> = listOf(
        AIInstruction(
            id = "00000000-0000-4000-8000-000000000201",
            name = "翻译",
            detail = "翻译剪贴板里的文字",
            systemPromptID = "00000000-0000-4000-8000-000000000003",
            userPromptID = "00000000-0000-4000-8000-000000000007",
            providerID = "00000000-0000-4000-8000-000000000101",
            sort = -2147483648,
        ),
        AIInstruction(
            id = "00000000-0000-4000-8000-000000000202",
            name = "润色",
            detail = "润色剪贴板里的文字",
            systemPromptID = "00000000-0000-4000-8000-000000000004",
            userPromptID = "00000000-0000-4000-8000-000000000007",
            providerID = "00000000-0000-4000-8000-000000000101",
            sort = -2147483647,
        ),
        AIInstruction(
            id = "00000000-0000-4000-8000-000000000203",
            name = "改写语气",
            detail = "按点名的语气改写剪贴板里的文字",
            systemPromptID = "00000000-0000-4000-8000-000000000005",
            userPromptID = "00000000-0000-4000-8000-000000000007",
            providerID = "00000000-0000-4000-8000-000000000101",
            sort = -2147483646,
        ),
        AIInstruction(
            id = "00000000-0000-4000-8000-000000000204",
            name = "回复",
            detail = "替剪贴板里的消息拟一条回复",
            systemPromptID = "00000000-0000-4000-8000-000000000006",
            userPromptID = "00000000-0000-4000-8000-000000000007",
            providerID = "00000000-0000-4000-8000-000000000101",
            sort = -2147483645,
        ),
    )

    /**
     * 种子落库（迁移与新库 onCreate 共用）：已存在同 id 行时跳过
     * （INSERT OR IGNORE），可重复调、不会把用户改过的种子再盖回去。
     */
    fun insertAll(db: SupportSQLiteDatabase) {
        for (p in providers) {
            db.execSQL(
                "INSERT OR IGNORE INTO `AIProvider` (`id`,`name`,`type`,`sort`," +
                    "`isDefault`,`isEnabled`,`isBuiltIn`,`builtInCanBeDelete`," +
                    "`baseURL`,`apiPath`,`apiToken`,`requestTimeout`,`maxOutputTokens`," +
                    "`contextWindow`,`temperature`,`presencePenalty`,`frequencyPenalty`," +
                    "`customParametersJSON`,`defaultModelId`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    p.id, p.name, p.type, p.sort,
                    if (p.isDefault) 1 else 0, if (p.isEnabled) 1 else 0,
                    if (p.isBuiltIn) 1 else 0, if (p.builtInCanBeDelete) 1 else 0,
                    p.baseURL, p.apiPath, p.apiToken, p.requestTimeout, p.maxOutputTokens,
                    p.contextWindow, p.temperature, p.presencePenalty, p.frequencyPenalty,
                    p.customParametersJSON, null, // defaultModelId 先空，模型行落库后再回填
                ),
            )
        }
        for (m in models) {
            db.execSQL(
                "INSERT OR IGNORE INTO `AIProviderModel` (`id`,`providerId`,`displayName`," +
                    "`modelID`,`sort`,`isEnabled`) VALUES (?,?,?,?,?,?)",
                arrayOf<Any?>(m.id, m.providerId, m.displayName, m.modelID, m.sort, if (m.isEnabled) 1 else 0),
            )
        }
        db.execSQL(
            "UPDATE `AIProvider` SET `defaultModelId` = ? WHERE `id` = ?",
            arrayOf<Any?>(DEEPSEEK_MODEL_ROW_ID, DEEPSEEK_PROVIDER_ID),
        )
        for (sp in systemPrompts) {
            db.execSQL(
                "INSERT OR IGNORE INTO `AISystemPrompt` (`id`,`name`,`iconName`,`purpose`," +
                    "`body`,`generationOverridesJSON`,`sort`,`isDefault`,`isEnabled`," +
                    "`isBuiltIn`,`builtInCanBeDelete`) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    sp.id, sp.name, sp.iconName, sp.purpose, sp.body,
                    sp.generationOverridesJSON, sp.sort,
                    if (sp.isDefault) 1 else 0, if (sp.isEnabled) 1 else 0,
                    if (sp.isBuiltIn) 1 else 0, if (sp.builtInCanBeDelete) 1 else 0,
                ),
            )
        }
        for (up in userPrompts) {
            db.execSQL(
                "INSERT OR IGNORE INTO `AIUserPrompt` (`id`,`name`,`iconName`,`purpose`," +
                    "`templateBody`,`sort`,`isDefault`,`isEnabled`,`isBuiltIn`," +
                    "`builtInCanBeDelete`) VALUES (?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    up.id, up.name, up.iconName, up.purpose, up.templateBody, up.sort,
                    if (up.isDefault) 1 else 0, if (up.isEnabled) 1 else 0,
                    if (up.isBuiltIn) 1 else 0, if (up.builtInCanBeDelete) 1 else 0,
                ),
            )
        }
        for (ins in instructions) {
            db.execSQL(
                "INSERT OR IGNORE INTO `AIInstruction` (`id`,`name`,`detail`," +
                    "`systemPromptID`,`userPromptID`,`providerID`,`sort`) VALUES (?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    ins.id, ins.name, ins.detail, ins.systemPromptID, ins.userPromptID,
                    ins.providerID, ins.sort,
                ),
            )
        }
    }
}
