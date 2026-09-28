package com.jobeen.ime.data

import com.jobeen.ime.base.util.appContext
import java.io.File

object App {
    private fun extDir(name: String): File =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, name).also { it.mkdirs() }

    var themesDir = extDir("themes")
    var logDir = extDir("log")
    var downloadDir = extDir("download")
    val modelDir = extDir("model")
    val speechModelDir = File(modelDir, "speech").also { it.mkdirs() }
}
