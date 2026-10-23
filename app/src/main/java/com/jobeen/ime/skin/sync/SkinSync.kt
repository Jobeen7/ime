package com.jobeen.ime.skin.sync

import android.content.Context
import com.jobeen.ime.skin.pkg.SkinPackages
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinLibrary

/** 皮肤同步的生产装配（设置页入口经此取用，传输与状态口径集中）。 */
object SkinSync {

    val stateStore: SkinSyncStateStore by lazy { SkinSyncStateStore(SkinLibrary.layout) }

    fun engine(context: Context): SkinLibrarySync = SkinLibrarySync(
        layout = SkinLibrary.layout,
        store = SkinLibrary.store,
        stateStore = stateStore,
        transport = WebDavSkinTransport(),
        importTransaction = ImportTransaction(SkinLibrary.layout, SkinLibrary.store),
        importerProvider = {
            SkinPackages.importer(context) to SkinPackages.importContext(context)
        },
    )
}
