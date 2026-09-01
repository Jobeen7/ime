package com.ninthsoft.ime.base.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import androidx.core.content.edit
import timber.log.Timber
import java.io.File

class AssetExtractionProviderUtil : ContentProvider() {

    private val prefs by lazy {
        context?.getSharedPreferences("asset_extract_prefs", android.content.Context.MODE_PRIVATE)
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        val p = prefs ?: return false
        if (p.getBoolean("extracted", false)) return false

        val destRoot = ctx.getExternalFilesDir(null) ?: run {
            Timber.e("Cannot get external files dir")
            return false
        }
        val rootEntries = ctx.assets.list("") ?: return false

        Timber.d("First launch: extracting %d assets to %s", rootEntries.size, destRoot.absolutePath)
        for (entry in rootEntries) {
            val destPath = File(destRoot, entry).absolutePath
            copyAsset(ctx, entry, destPath)
        }
        p.edit { putBoolean("extracted", true) }
        Timber.d("Asset extraction completed")
        return false
    }

    private fun copyAsset(ctx: android.content.Context, path: String, dest: String) {
        val subAssets = ctx.assets.list(path)
        if (!subAssets.isNullOrEmpty()) {
            for (sub in subAssets) {
                copyAsset(ctx, "$path/$sub", "$dest/$sub")
            }
        } else {
            ctx.assets.open(path).use { input ->
                File(dest).also { it.parentFile?.mkdirs() }.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
