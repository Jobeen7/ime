package com.jobeen.ime.data

import com.jobeen.ime.base.util.appContext
import java.io.File

object App {
    private fun extDir(name: String): File =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, name).also { it.mkdirs() }

    var themesDir = extDir(UserFiles.THEMES_DIR_NAME)
    var logDir = extDir(UserFiles.LOG_DIR_NAME)
    // 诊断日志目录（详设十三.4：应用私有 logs/，滚动保留；与旧 log/ 区分）
    val logsDir = extDir(UserFiles.LOGS_DIR_NAME)
    // 皮肤 v3 库根目录（详设 §2.1，裁定根目录名 skin_library）
    val skinLibraryDir = extDir(UserFiles.SKIN_LIBRARY_DIR_NAME)
    var downloadDir = extDir(UserFiles.DOWNLOAD_DIR_NAME)
    val modelDir = extDir(UserFiles.MODEL_DIR_NAME)
    val speechModelDir = File(modelDir, UserFiles.SPEECH_MODEL_DIR_NAME).also { it.mkdirs() }
}
