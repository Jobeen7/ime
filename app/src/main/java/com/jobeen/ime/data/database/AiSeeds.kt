// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * AI 种子数据（WO52 9.1）：逐字取自对端包内 `assets/seeds/ai.json`
 * （`apktool-out/assets/seeds/ai.json`，由 iOS 源码生成）。
 *
 * - providers 1 条 DeepSeek（baseURL https://api.deepseek.com，apiPath "v1"，
 *   requestModelID "deepseek-v4-flash"，contextWindow 64000，requestTimeout 60.0；
 *   种子中无 apiToken 字段，无内置密钥——apiToken 只由使用者自填）；
 * - systemPrompts 5 条、userPrompts 3 条、instructions 4 条（正文／模板／
 *   详情一字不改，含换行）；
 * - 模型行 ai.json 里没有单独的 models 数组：provider 的 `modelID` 字段
 *   指向模型行 id `...0102`，`requestModelID` 才是 API 模型字符串；
 *   模型行按此补种（displayName 与 modelID 均为 "deepseek-v4-flash"）。
 * - sort 取种子原文（Int.MIN_VALUE 起）；canBeDelete 原文映射到
 *   builtInCanBeDelete，isBuiltIn 恒 true；"无 System Prompt"／
 *   "无 User Prompt"／DeepSeek 为默认（isDefault true），其余 false。
 * - `type` 种子未给：Jime 侧默认 "openai"（OpenAI 兼容）。
 *
 * 落库：[insertAll] 用绑定参数逐行插（不拼 SQL 串，种子正文含换行与
 * 引号也不怕）；迁移 15→16 与新库 onCreate 共用同一函数。插入顺序：
 * 服务商（defaultModelId 先空）→模型→回填服务商 defaultModelId→
 * 提示词→指令（外键在提交时才校验，与 kd3 的 DEFERRABLE 口径一致）。
 */
object AiSeeds {

    const val DEEPSEEK_PROVIDER_ID = "00000000-0000-4000-8000-000000000101"
    const val DEEPSEEK_MODEL_ROW_ID = "00000000-0000-4000-8000-000000000102"
    const val DEEPSEEK_MODEL_ID = "deepseek-v4-flash"

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
            iconName = "nosign",
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
            iconName = "character.bubble",
            purpose = "中英互译，只输出译文",
            body = "你是一名专业翻译。把用户发来的文本翻译成目标语言：原文为中文时译成英文，原文为其他语言时译成简体中文。\n\n要求：\n1. 忠实传达原意，用词自然地道，不要逐字直译；\n2. 保留原文的换行、列表、代码块、网址、@ 提及与表情符号；\n3. 代码标识符与不宜翻译的专有名词保持原样；\n4. 只输出译文，不要解释、不要加引号、不要附上原文。",
            sort = -2147483647,
            isDefault = false,
            isEnabled = true,
            isBuiltIn = true,
            builtInCanBeDelete = true,
        ),
        AISystemPrompt(
            id = "00000000-0000-4000-8000-000000000004",
            name = "润色",
            iconName = "wand.and.stars",
            purpose = "改进表达，保持原意与语种",
            body = "你是一名写作编辑。润色用户发来的文本，让它更清晰、通顺、专业。\n\n要求：\n1. 保持原文的语种、含义与信息量，不增删事实，不改变立场；\n2. 修正错别字、标点与语法错误，理顺语序，删除冗余表达；\n3. 保留原文的换行、列表、代码块与专有名词；\n4. 只输出润色后的文本，不要解释改了什么、不要加引号、不要给多个备选。",
            sort = -2147483646,
            isDefault = false,
            isEnabled = true,
            isBuiltIn = true,
            builtInCanBeDelete = true,
        ),
        AISystemPrompt(
            id = "00000000-0000-4000-8000-000000000005",
            name = "改语气",
            iconName = "theatermasks",
            purpose = "按指定语气重写，未指定时改得礼貌得体",
            body = "你是一名文案改写助手。按要求的语气重写用户发来的文本。\n\n要求：\n1. 文本中若写明了目标语气（如「正式一点」「委婉些」「轻松点」），按该语气改写，并且不要把这句要求本身写进结果；\n2. 没有写明语气时，默认改写得礼貌、得体、专业；\n3. 保持原文的语种、核心信息与诉求，不增删事实；\n4. 只输出改写后的文本，不要解释、不要加引号、不要给多个备选。",
            sort = -2147483645,
            isDefault = false,
            isEnabled = true,
            isBuiltIn = true,
            builtInCanBeDelete = true,
        ),
        AISystemPrompt(
            id = "00000000-0000-4000-8000-000000000006",
            name = "回复",
            iconName = "arrowshape.turn.up.left",
            purpose = "根据收到的消息起草一条回复",
            body = "用户发来的是别人的消息或一段对话，请以用户的身份起草一条回复。\n\n要求：\n1. 使用与来信相同的语种，语气贴合场合：工作场景专业得体，日常场景自然亲切；\n2. 正面回应对方的问题与诉求；信息不足时给出稳妥的回应，不要编造事实、时间或承诺；\n3. 篇幅与来信相当，一般 1-3 句；除非来信明显是正式邮件，否则不要写称呼抬头与落款；\n4. 只输出回复正文，不要解释、不要加引号、不要给多个备选。",
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
            iconName = "nosign",
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
            iconName = "doc.on.clipboard",
            purpose = "把输入框内容与系统剪贴板中的文本一起发送",
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
            iconName = "selection.pin.in.out",
            purpose = "把输入框内容与宿主应用中选中的文本一起发送",
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
            detail = "中英互译剪贴板中的文本",
            systemPromptID = "00000000-0000-4000-8000-000000000003",
            userPromptID = "00000000-0000-4000-8000-000000000007",
            providerID = "00000000-0000-4000-8000-000000000101",
            sort = -2147483648,
        ),
        AIInstruction(
            id = "00000000-0000-4000-8000-000000000202",
            name = "润色",
            detail = "润色剪贴板中的文本",
            systemPromptID = "00000000-0000-4000-8000-000000000004",
            userPromptID = "00000000-0000-4000-8000-000000000007",
            providerID = "00000000-0000-4000-8000-000000000101",
            sort = -2147483647,
        ),
        AIInstruction(
            id = "00000000-0000-4000-8000-000000000203",
            name = "改写语气",
            detail = "按指定语气改写剪贴板中的文本",
            systemPromptID = "00000000-0000-4000-8000-000000000005",
            userPromptID = "00000000-0000-4000-8000-000000000007",
            providerID = "00000000-0000-4000-8000-000000000101",
            sort = -2147483646,
        ),
        AIInstruction(
            id = "00000000-0000-4000-8000-000000000204",
            name = "回复",
            detail = "为剪贴板中的消息起草一条回复",
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
