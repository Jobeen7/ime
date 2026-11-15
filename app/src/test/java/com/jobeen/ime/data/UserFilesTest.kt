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
        assertEquals(File(files, "fonts"), UserFiles.fontsDir(files))
        assertEquals(File(files, "databases"), UserFiles.databasesDir(files))
        assertEquals(
            File(files, "databases/clipboard_database"),
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


}
