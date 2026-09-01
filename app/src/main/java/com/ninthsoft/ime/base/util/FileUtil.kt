package com.ninthsoft.ime.base.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import java.io.File
import java.io.IOException

object FileUtil {
    fun rename(
        src: File,
        newName: String,
    ): Result<File> {
        if (!src.exists()) {
            return Result.failure(NoSuchFileException(src))
        }
        if (newName.isBlank()) {
            return Result.failure(IllegalArgumentException("New name is blank"))
        }
        if (newName == src.name) return Result.success(src)
        val newFile = src.resolveSibling(newName)
        return if (!newFile.exists() && src.renameTo(newFile)) {
            Result.success(newFile)
        } else {
            Result.failure(IllegalStateException("Rename file '${src.name}' to $newName failed"))
        }
    }

    fun delete(file: File) = runCatching {
        if (!file.exists()) return@runCatching
        val res = if (file.isDirectory) {
            file.walkBottomUp().fold(true) { acc, file ->
                if (file.exists()) file.delete() else acc
            }
        } else {
            file.delete()
        }
        if (!res) {
            throw IOException("Cannot delete ${file.path}")
        }
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun Context.isStorageAvailable(): Boolean {
    return Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()
}

fun Context.requestExternalStoragePermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = "package:$packageName".toUri()
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            startActivity(intent)
        }
    } else {
        Toast.makeText(this, "该版本无需申请此特殊权限", Toast.LENGTH_SHORT).show()
    }
}