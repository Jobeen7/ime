package com.jobeen.ime.skin.fault

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import java.io.File

/**
 * debug 构建的钩子安装点（仅 debug 清单声明本 Provider，release
 * 不存在）：ContentProvider.onCreate 先于一切业务注入点执行，
 * 在此把 [DebugFaultHooks] 装到 [SkinFaultHooks] 分发点上。
 * 不做任何数据操作，query/insert 等一律不支持。
 */
class DebugFaultHooksInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        DebugFaultHooks.install(File(base, "fault_hooks"))
        return true
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
