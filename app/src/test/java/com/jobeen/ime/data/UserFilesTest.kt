// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data

import com.jobeen.ime.base.speech.VoiceInputSwitchStore
import com.jobeen.ime.data.backup.RestoreJournal
import com.jobeen.ime.data.manager.SchemeDirs
import com.jobeen.ime.input.panel.toolbar.ToolbarUserStore
import com.jobeen.ime.skin.script.SkinScriptStore
import com.jobeen.ime.skin.symbolbar.SymbolBarConfigLoader
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WO39 E2-8：UserFiles 命名空间——解析口径、与既有调用点常量一致、分类无遗漏。 */
class UserFilesTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    @Test
    fun extRootResolvers_matchDataManagerAndAppLayout() {
        val ext = tempDir("userfiles-ext")
        assertEquals(File(ext, "shared"), UserFiles.sharedDir(ext))
        assertEquals(File(ext, "user"), UserFiles.schemeDirsRoot(ext))
        assertEquals(File(ext, "version.txt"), UserFiles.versionFile(ext))
        assertEquals(File(ext, "themes"), UserFiles.themesDir(ext))
        assertEquals(File(ext, "log"), UserFiles.logDir(ext))
        assertEquals(File(ext, "logs"), UserFiles.logsDir(ext))
        assertEquals(File(ext, "skin_library"), UserFiles.skinLibraryDir(ext))
        assertEquals(File(ext, "download"), UserFiles.downloadDir(ext))
        assertEquals(File(ext, "model"), UserFiles.modelDir(ext))
        assertEquals(File(ext, "model/speech"), UserFiles.speechModelDir(ext))
        assertEquals(File(ext, "shared/build"), UserFiles.prebuiltDataDir(ext))
        // 引擎 user 目录：缺省初始目录，与 DataManager.userDataDir 同口径
        assertEquals(File(ext, "user/default"), UserFiles.userDataDir(ext))
        assertEquals(File(ext, "user/rime-wanxiang"), UserFiles.userDataDir(ext, "rime-wanxiang"))
        assertEquals(File(ext, "user/default/build"), UserFiles.stagingDir(ext))
    }

    @Test
    fun filesDirResolvers_matchKnownCallSites() {
        val files = tempDir("userfiles-files")
        assertEquals(File(files, "crash"), UserFiles.crashDir(files))
        assertEquals(File(files, "Logs/performance"), UserFiles.perfDir(files))
        assertEquals(File(files, "input_stats"), UserFiles.inputStatsDir(files))
        assertEquals(File(files, "skin_scripts"), UserFiles.skinScriptsDir(files))
        assertEquals(File(files, "restore_journal"), UserFiles.restoreJournalDir(files))
        assertEquals(File(files, "fonts"), UserFiles.fontsDir(files))
        assertEquals(File(files, "cloud_plugins"), UserFiles.cloudPluginsDir(files))
        assertEquals(File(files, "toolbar_user.json"), UserFiles.toolbarUserFile(files))
        assertEquals(File(files, "toolbar_images"), UserFiles.toolbarImagesDir(files))
        assertEquals(File(files, "symbol-bar.yaml"), UserFiles.symbolBarFile(files))
        assertEquals(File(files, "voice_input_enabled"), UserFiles.voiceInputSwitchFile(files))
        assertEquals(File(files, "user_collocations.tsv"), UserFiles.userCollocationsFile(files))
        assertEquals(File(files, "voice_corrections.tsv"), UserFiles.voiceCorrectionsFile(files))
        assertEquals(File(files, "cdsp"), UserFiles.cdspDir(files))
        assertEquals(File(files, "logs"), UserFiles.logsFallbackDir(files))
        assertEquals(File(files, "databases"), UserFiles.databasesDir(files))
        assertEquals(File(files, "databases/ime_database"), UserFiles.imeDatabaseFile(files))
        assertEquals(
            File(files, "databases/clipboard_database"),
            UserFiles.clipboardDatabaseFile(files),
        )
    }

    @Test
    fun skinLibraryChildren_matchLayout() {
        val root = tempDir("userfiles-skinlib")
        assertEquals(File(root, "skins"), UserFiles.skinLibrarySkinsDir(root))
        assertEquals(File(root, "layouts"), UserFiles.skinLibraryLayoutsDir(root))
        assertEquals(File(root, "assets"), UserFiles.skinLibraryAssetsDir(root))
        assertEquals(File(root, "staging"), UserFiles.skinLibraryStagingDir(root))
        assertEquals(File(root, "drafts"), UserFiles.skinLibraryDraftsDir(root))
        assertEquals(
            UserFiles.SKIN_LIBRARY_CHILD_DIRS,
            setOf("skins", "layouts", "assets", "staging", "drafts"),
        )
    }

    @Test
    fun constants_matchExistingCallSiteConstants_noDrift() {
        // 命名空间与实际调用点是同一值：任一处改名/改值都会在这里失败。
        assertEquals(SkinScriptStore.SCRIPTS_DIR, UserFiles.SKIN_SCRIPTS_DIR_NAME)
        assertEquals(RestoreJournal.JOURNAL_DIR_NAME, UserFiles.RESTORE_JOURNAL_DIR_NAME)
        assertEquals(ToolbarUserStore.FILE_NAME, UserFiles.TOOLBAR_USER_FILE_NAME)
        assertEquals(ToolbarUserStore.IMAGE_DIR, UserFiles.TOOLBAR_IMAGES_DIR_NAME)
        assertEquals(SymbolBarConfigLoader.FILE_NAME, UserFiles.SYMBOL_BAR_FILE_NAME)
        assertEquals(VoiceInputSwitchStore.FILE_NAME, UserFiles.VOICE_INPUT_SWITCH_FILE_NAME)
        assertEquals(SchemeDirs.INITIAL_DIR, "default")
        assertEquals(SchemeDirs.MIGRATION_BACKUP_DIR, ".migration-backup")
    }

    @Test
    fun namesContainNoTraversalOrAbsolutePath() {
        val all = UserFiles.userEditableRelativeNames + UserFiles.appManagedRelativeNames +
            UserFiles.SKIN_LIBRARY_CHILD_DIRS
        assertTrue(all.isNotEmpty())
        for (name in all) {
            assertFalse("traversal in $name", ".." in name)
            assertFalse("absolute in $name", name.startsWith("/"))
            assertFalse("trailing slash in $name", name.endsWith("/"))
            assertFalse("leading slash in $name", name.startsWith("/"))
        }
        // 单段名（除 Logs/performance 这一有意分段外）不含分隔符
        for (name in all) {
            if (name != UserFiles.PERF_DIR_NAME) {
                assertFalse("unexpected separator in $name", "/" in name)
            }
        }
    }

    @Test
    fun classification_userEditableAndAppManagedAreDisjointAndCoverKnownNames() {
        assertTrue(
            UserFiles.userEditableRelativeNames.intersect(UserFiles.appManagedRelativeNames).isEmpty(),
        )
        // 用户可编辑：方案、皮肤库、字体、插件、脚本、工具栏、符号栏、语音开关、搭配、纠错词对
        for (name in listOf(
            UserFiles.SCHEME_DIRS_ROOT_NAME,
            UserFiles.SKIN_LIBRARY_DIR_NAME,
            UserFiles.FONTS_DIR_NAME,
            UserFiles.CLOUD_PLUGINS_DIR_NAME,
            UserFiles.SKIN_SCRIPTS_DIR_NAME,
            UserFiles.TOOLBAR_USER_FILE_NAME,
            UserFiles.SYMBOL_BAR_FILE_NAME,
            UserFiles.VOICE_INPUT_SWITCH_FILE_NAME,
            UserFiles.USER_COLLOCATIONS_FILE_NAME,
        )) {
            assertTrue("$name should be user-editable", name in UserFiles.userEditableRelativeNames)
        }
        // 应用管理：崩溃、埋点、统计、还原 journal、数据库——不得随用户文件搬
        for (name in listOf(
            UserFiles.CRASH_DIR_NAME,
            UserFiles.PERF_DIR_NAME,
            UserFiles.INPUT_STATS_DIR_NAME,
            UserFiles.RESTORE_JOURNAL_DIR_NAME,
            UserFiles.DATABASES_DIR_NAME,
        )) {
            assertTrue("$name should be app-managed", name in UserFiles.appManagedRelativeNames)
            assertFalse("$name must not be user-editable", name in UserFiles.userEditableRelativeNames)
        }
    }

    @Test
    fun twoLogsLocationsStayDistinct_externalVsFilesDirFallback() {
        // 审计发现：外部根 logs/（App.logsDir）与 filesDir/logs（LogScreen
        // 回落位）是两个不同位置；命名空间必须同时登记、不得合并。
        val ext = tempDir("userfiles-ext-logs")
        val files = tempDir("userfiles-files-logs")
        assertEquals(File(ext, "logs"), UserFiles.logsDir(ext))
        assertEquals(File(files, "logs"), UserFiles.logsFallbackDir(files))
        assertEquals(UserFiles.LOGS_DIR_NAME, UserFiles.LOGS_FALLBACK_DIR_NAME)
    }
}
