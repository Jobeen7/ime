package com.ninthsoft.ime.data

import com.ninthsoft.ime.base.util.appContext
import java.io.File

object App {
    var themesDir =  File(appContext.getExternalFilesDir(null), "themes").also { it.mkdirs() }
    var logDir =  File(appContext.getExternalFilesDir(null), "log").also { it.mkdirs() }
    var downloadDir =  File(appContext.getExternalFilesDir(null), "download").also { it.mkdirs() }
    val modelDir = File(appContext.getExternalFilesDir(null), "model").also { it.mkdirs() }
    val speechModelDir = File(appContext.getExternalFilesDir(null), "model/speech").also { it.mkdirs() }
}
