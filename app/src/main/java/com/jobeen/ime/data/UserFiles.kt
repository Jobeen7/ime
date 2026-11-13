// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data

import com.jobeen.ime.data.manager.SchemeDirs
import java.io.File

/**
 * 用户文件统一命名空间（WO39 E2-8，第一步：只收路径常量）。
 *
 * 背景：对标 Mie N80（全部用户可编辑文件收进 rime/.mie/）。Jime 现状
 * 是两锚点散落：外部存储根（getExternalFilesDir(null)，Rime 数据、
 * 方案目录、皮肤库、模型）＋应用私有 filesDir（崩溃、统计、脚本、
 * 插件、工具栏等），另有 SharedPreferences 与 Room 库不在文件命名
 * 空间内。位置清单即以下常量本身（审计方法：grep filesDir /
 * getExternalFilesDir / cacheDir 全量，逐项核对调用点；完整审计表
 * 见 WO39 汇报）。
 *
 * 本批只做命名空间本身，不搬磁盘文件、不改引用语义：
 * - 本对象只含相对名常量＋以锚点根为入参的纯解析函数（可 JVM 单测），
 *   不读 Context、不建目录、不移动、不删除任何文件；
 * - 调用点只做“字面量 → 常量”的等值替换（DataManager、App、
 *   SkinScriptStore 三处未被他人改动的文件），值逐字节相同，
 *   其余调用点保持原字面量，由 [UserFilesTest] 交叉断言两者一致，
 *   防止命名空间与实际调用点漂移；
 * - 磁盘旧位置迁移本批为零：任一路径都未证明可安全移动（移动即改
 *   Rime/备份/恢复/文档提供方等多处读取口径，且 filesDir 与外部根
 *   的备份、清理、可见性语义不同），全部记为后续项，见 WO39 汇报
 *   中的审计清单“迁移”列。
 *
 * 锚点说明：
 * - 外部根：getExternalFilesDir(null)，卸载即删、用户经文档提供方
 *   （AppFilesDocumentsProvider）可见；Rime 引擎只认此根下的
 *   shared/ 与 user/。
 * - filesDir：应用私有内部存储，卸载即删、用户不可见；Room 库默认
 *   落 filesDir/databases/（库名见 [IME_DATABASE_NAME] 等）。
 * - cacheDir：纯临时文件（更新 .part、诊断 zip、分享暂存、语音/词典
 *   同步临时文件），系统可随时清理，不是用户文件，不进本命名空间。
 */
object UserFiles {

    // ---- 外部根（getExternalFilesDir(null)）下的顶层名 ----

    /** Rime 共享数据（内置方案、词库、opencc；DataManager.sharedDataDir）。 */
    const val SHARED_DIR_NAME = "shared"

    /** 方案目录根（多目录模型；DataManager.schemeDirsRoot）。 */
    const val SCHEME_DIRS_ROOT_NAME = "user"

    /** 资源版本哨兵（AppStartup.releaseResourcesIfNeeded 写 version.txt）。 */
    const val VERSION_FILE_NAME = "version.txt"

    /** Rime 编译输出目录名（shared/build 预编译、user/<dir>/build 部署期）。 */
    const val BUILD_DIR_NAME = "build"

    /** 旧主题目录（App.themesDir）。 */
    const val THEMES_DIR_NAME = "themes"

    /** 旧日志目录（App.logDir，历史遗留；现用 [LOGS_DIR_NAME]）。 */
    const val LOG_DIR_NAME = "log"

    /** 诊断日志目录（App.logsDir；与 filesDir 下的 "logs" 回落位是两个位置，见审计）。 */
    const val LOGS_DIR_NAME = "logs"

    /** 皮肤 v3 库根（App.skinLibraryDir；子目录见 [SKIN_LIBRARY_CHILD_DIRS]）。 */
    const val SKIN_LIBRARY_DIR_NAME = "skin_library"

    /** 下载目录（App.downloadDir）。 */
    const val DOWNLOAD_DIR_NAME = "download"

    /** 模型根（App.modelDir；内置预测模型 model/predict.marisa 即解压于此）。 */
    const val MODEL_DIR_NAME = "model"

    /** 本地语音模型子目录（App.speechModelDir；model/speech/）。 */
    const val SPEECH_MODEL_DIR_NAME = "speech"

    /** 皮肤库子目录（SkinLibraryLayout；根为 [SKIN_LIBRARY_DIR_NAME]）。 */
    val SKIN_LIBRARY_CHILD_DIRS: Set<String> =
        setOf("skins", "layouts", "assets", "staging", "drafts")

    // ---- filesDir 下的顶层名 ----

    /** 崩溃文件目录（CrashTrap.crashDir；filesDir/crash）。 */
    const val CRASH_DIR_NAME = "crash"

    /** 性能埋点目录（PerfMonitor.configure；filesDir/Logs/performance）。 */
    const val PERF_DIR_NAME = "Logs/performance"

    /** 输入统计目录（InputStats.configure；filesDir/input_stats/stats.json）。 */
    const val INPUT_STATS_DIR_NAME = "input_stats"

    /** 皮肤脚本库（SkinScriptStore.SCRIPTS_DIR；filesDir/skin_scripts）。 */
    const val SKIN_SCRIPTS_DIR_NAME = "skin_scripts"

    /** 还原 journal 目录（RestoreJournal.JOURNAL_DIR_NAME；filesDir/restore_journal）。 */
    const val RESTORE_JOURNAL_DIR_NAME = "restore_journal"

    /** 全局字体目录（GlobalFonts；filesDir/fonts）。 */
    const val FONTS_DIR_NAME = "fonts"

    /** 云语音插件目录（PluginStore；filesDir/cloud_plugins/<id>/）。 */
    const val CLOUD_PLUGINS_DIR_NAME = "cloud_plugins"

    /** 工具栏用户配置（ToolbarUserStore.FILE_NAME；filesDir/toolbar_user.json）。 */
    const val TOOLBAR_USER_FILE_NAME = "toolbar_user.json"

    /** 工具栏自定义图片目录（ToolbarUserStore.IMAGE_DIR；filesDir/toolbar_images）。 */
    const val TOOLBAR_IMAGES_DIR_NAME = "toolbar_images"

    /** 自定义符号栏配置（SymbolBarConfigLoader.FILE_NAME；filesDir/symbol-bar.yaml）。 */
    const val SYMBOL_BAR_FILE_NAME = "symbol-bar.yaml"

    /** 语音输入开关（VoiceInputSwitchStore.FILE_NAME；filesDir/voice_input_enabled）。 */
    const val VOICE_INPUT_SWITCH_FILE_NAME = "voice_input_enabled"

    /** 用户搭配学习表（PredictionManager；filesDir/user_collocations.tsv）。 */
    const val USER_COLLOCATIONS_FILE_NAME = "user_collocations.tsv"

    /** 语音纠错词对（ImeInputMethodService 清理路径；filesDir/voice_corrections.tsv）。 */
    const val VOICE_CORRECTIONS_FILE_NAME = "voice_corrections.tsv"

    /** 自定义按键音文件名前缀（KeyboardSettingsScreen；filesDir/custom_key_sound.<ext>）。 */
    const val CUSTOM_KEY_SOUND_PREFIX = "custom_key_sound."

    /** QNN 时代残留清理目录（ModelDownloader；filesDir/cdsp，仅清理目标）。 */
    const val CDSP_DIR_NAME = "cdsp"

    /** LogScreen 回落日志目录（filesDir/logs；与外部根 [LOGS_DIR_NAME] 不是同一位置）。 */
    const val LOGS_FALLBACK_DIR_NAME = "logs"

    /** Room 库目录名（Room 默认 filesDir/databases/）。 */
    const val DATABASES_DIR_NAME = "databases"

    /** 主库名（AppDatabase.getInstance）。 */
    const val IME_DATABASE_NAME = "ime_database"

    /** 剪贴板库名（ClipboardDatabase.getInstance）。 */
    const val CLIPBOARD_DATABASE_NAME = "clipboard_database"

    // ---- 分类：用户可编辑 vs 应用管理（迁移口径） ----

    /**
     * 用户可编辑条目（相对名，锚点见各解析函数）：Mie N80 意义上的
     * 迁移候选集。本批只登记、不移动；逐项迁移须另证明读取口径、
     * 备份口径与回落口径全部跟随，否则丢数据。
     */
    val userEditableRelativeNames: Set<String> = setOf(
        SHARED_DIR_NAME,
        SCHEME_DIRS_ROOT_NAME,
        THEMES_DIR_NAME,
        SKIN_LIBRARY_DIR_NAME,
        FONTS_DIR_NAME,
        CLOUD_PLUGINS_DIR_NAME,
        SKIN_SCRIPTS_DIR_NAME,
        TOOLBAR_USER_FILE_NAME,
        TOOLBAR_IMAGES_DIR_NAME,
        SYMBOL_BAR_FILE_NAME,
        VOICE_INPUT_SWITCH_FILE_NAME,
        USER_COLLOCATIONS_FILE_NAME,
        VOICE_CORRECTIONS_FILE_NAME,
        // 注意：CUSTOM_KEY_SOUND_PREFIX 是文件名前缀（custom_key_sound.<ext>），
        // 不是完整相对名，故不进本集合；前缀常量另见 [CUSTOM_KEY_SOUND_PREFIX]。
    )

    /**
     * 应用管理条目（相对名）：崩溃、埋点、统计、还原 journal、Room 库、
     * 日志与历史残留。只登记供备份排除与清理口径引用，不是迁移候选，
     * 不得随用户文件一起搬。
     */
    val appManagedRelativeNames: Set<String> = setOf(
        CRASH_DIR_NAME,
        PERF_DIR_NAME,
        INPUT_STATS_DIR_NAME,
        RESTORE_JOURNAL_DIR_NAME,
        DATABASES_DIR_NAME,
        LOGS_DIR_NAME,
        LOGS_FALLBACK_DIR_NAME,
        CDSP_DIR_NAME,
        LOG_DIR_NAME,
        DOWNLOAD_DIR_NAME,
        MODEL_DIR_NAME,
        VERSION_FILE_NAME,
        BUILD_DIR_NAME,
    )

    // ---- 外部根解析（纯函数；锚点由调用方传入） ----

    fun sharedDir(extRoot: File): File = File(extRoot, SHARED_DIR_NAME)
    fun schemeDirsRoot(extRoot: File): File = File(extRoot, SCHEME_DIRS_ROOT_NAME)
    fun versionFile(extRoot: File): File = File(extRoot, VERSION_FILE_NAME)
    fun themesDir(extRoot: File): File = File(extRoot, THEMES_DIR_NAME)
    fun logDir(extRoot: File): File = File(extRoot, LOG_DIR_NAME)
    fun logsDir(extRoot: File): File = File(extRoot, LOGS_DIR_NAME)
    fun skinLibraryDir(extRoot: File): File = File(extRoot, SKIN_LIBRARY_DIR_NAME)
    fun downloadDir(extRoot: File): File = File(extRoot, DOWNLOAD_DIR_NAME)
    fun modelDir(extRoot: File): File = File(extRoot, MODEL_DIR_NAME)
    fun speechModelDir(extRoot: File): File = File(modelDir(extRoot), SPEECH_MODEL_DIR_NAME)
    fun prebuiltDataDir(extRoot: File): File = File(sharedDir(extRoot), BUILD_DIR_NAME)

    /** 引擎 user 目录 = 方案根 / 当前目录名（缺省初始目录；与 DataManager.userDataDir 同口径）。 */
    fun userDataDir(extRoot: File, dirName: String = SchemeDirs.INITIAL_DIR): File =
        File(schemeDirsRoot(extRoot), dirName)

    /** 方案目录部署期 build 目录（与 DataManager.stagingDir 同口径）。 */
    fun stagingDir(extRoot: File, dirName: String = SchemeDirs.INITIAL_DIR): File =
        File(userDataDir(extRoot, dirName), BUILD_DIR_NAME)

    // ---- 皮肤库子目录解析（纯函数；根由调用方传入） ----

    fun skinLibrarySkinsDir(skinLibraryRoot: File): File = File(skinLibraryRoot, "skins")
    fun skinLibraryLayoutsDir(skinLibraryRoot: File): File = File(skinLibraryRoot, "layouts")
    fun skinLibraryAssetsDir(skinLibraryRoot: File): File = File(skinLibraryRoot, "assets")
    fun skinLibraryStagingDir(skinLibraryRoot: File): File = File(skinLibraryRoot, "staging")
    fun skinLibraryDraftsDir(skinLibraryRoot: File): File = File(skinLibraryRoot, "drafts")

    // ---- filesDir 解析（纯函数；锚点由调用方传入） ----

    fun crashDir(filesDir: File): File = File(filesDir, CRASH_DIR_NAME)
    fun perfDir(filesDir: File): File = File(filesDir, PERF_DIR_NAME)
    fun inputStatsDir(filesDir: File): File = File(filesDir, INPUT_STATS_DIR_NAME)
    fun skinScriptsDir(filesDir: File): File = File(filesDir, SKIN_SCRIPTS_DIR_NAME)
    fun restoreJournalDir(filesDir: File): File = File(filesDir, RESTORE_JOURNAL_DIR_NAME)
    fun fontsDir(filesDir: File): File = File(filesDir, FONTS_DIR_NAME)
    fun cloudPluginsDir(filesDir: File): File = File(filesDir, CLOUD_PLUGINS_DIR_NAME)
    fun toolbarUserFile(filesDir: File): File = File(filesDir, TOOLBAR_USER_FILE_NAME)
    fun toolbarImagesDir(filesDir: File): File = File(filesDir, TOOLBAR_IMAGES_DIR_NAME)
    fun symbolBarFile(filesDir: File): File = File(filesDir, SYMBOL_BAR_FILE_NAME)
    fun voiceInputSwitchFile(filesDir: File): File = File(filesDir, VOICE_INPUT_SWITCH_FILE_NAME)
    fun userCollocationsFile(filesDir: File): File = File(filesDir, USER_COLLOCATIONS_FILE_NAME)
    fun voiceCorrectionsFile(filesDir: File): File = File(filesDir, VOICE_CORRECTIONS_FILE_NAME)
    fun cdspDir(filesDir: File): File = File(filesDir, CDSP_DIR_NAME)
    fun logsFallbackDir(filesDir: File): File = File(filesDir, LOGS_FALLBACK_DIR_NAME)
    fun databasesDir(filesDir: File): File = File(filesDir, DATABASES_DIR_NAME)
    fun imeDatabaseFile(filesDir: File): File = File(databasesDir(filesDir), IME_DATABASE_NAME)
    fun clipboardDatabaseFile(filesDir: File): File =
        File(databasesDir(filesDir), CLIPBOARD_DATABASE_NAME)
}
