package com.jobeen.ime.data

import com.jobeen.ime.base.util.appContext
import java.io.File

object App {
    private fun extDir(name: String): File =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, name).also { it.mkdirs() }

    var themesDir = extDir("themes")
    var logDir = extDir("log")
    // 诊断日志目录（详设十三.4：应用私有 logs/，滚动保留；与旧 log/ 区分）
    val logsDir = extDir("logs")
    // 皮肤 v3 库根目录（详设 §2.1，裁定根目录名 skin_library）
    val skinLibraryDir = extDir("skin_library")
    var downloadDir = extDir("download")
    val modelDir = extDir("model")
    val speechModelDir = File(modelDir, "speech").also { it.mkdirs() }
}
