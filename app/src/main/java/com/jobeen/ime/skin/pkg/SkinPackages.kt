package com.jobeen.ime.skin.pkg

import android.content.Context
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.skin.image.AndroidBackgroundPreparer
import com.jobeen.ime.skin.media.AndroidAudioInfoProbe
import com.jobeen.ime.skin.media.AndroidImageInfoProbe
import com.jobeen.ime.skin.runtime.SkinSnapshotFactory
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinLibrary

/**
 * 导入管线的生产装配（Android 探针/预处理/默认方案注入点集中于此，
 * UI 与同步只经本门面取用，避免各入口各装一份口径漂移）。
 */
object SkinPackages {

    fun importer(context: Context): JimeImporter {
        val appContext = context.applicationContext
        return JimeImporter(
            layout = SkinLibrary.layout,
            store = SkinLibrary.store,
            importTransaction = ImportTransaction(SkinLibrary.layout, SkinLibrary.store),
            imageProbe = AndroidImageInfoProbe,
            audioProbe = AndroidAudioInfoProbe,
            backgroundPreparer = AndroidBackgroundPreparer(
                appContext.resources.displayMetrics.density,
            ),
            defaultScheme = KeyboardTheme.DEFAULT.colors,
        )
    }

    fun importContext(context: Context): JimeImporter.Context {
        val (targetW, targetH) = SkinSnapshotFactory.backgroundTargetPx(context)
        return JimeImporter.Context(
            currentVersionCode = SkinSnapshotFactory.currentVersionCode(context),
            fallbacks = SkinSnapshotFactory.geometryFallbacks(context),
            tier = SkinSnapshotFactory.evaluateTier(context),
            targetWidthPx = targetW,
            targetHeightPx = targetH,
        )
    }
}
