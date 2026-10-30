package com.jobeen.ime.skin.store

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 原子文件写（定稿八.2/八.3 口径）：临时文件 → 写满 → 文件 fsync →
 * rename 替换 → 目录 fsync。进程在任一步被杀，目标文件要么是旧版
 * 完整内容、要么是新版完整内容，绝不出现截断的半截文件（崩溃点
 * I-4/A-5 的防线）。沿用 ThemeStore 的 tmp+rename 先例并补 fsync。
 */
object AtomicFiles {
    fun writeAtomically(dir: File, name: String, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.channel.force(true)
        }
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            // 少数文件系统上 rename 覆盖已存在文件会失败：退化为先删后改名
            target.delete()
            check(tmp.renameTo(target)) { "atomic rename failed for $name in ${dir.path}" }
        }
        fsyncDir(dir)
    }

    fun writeAtomically(dir: File, name: String, text: String) =
        writeAtomically(dir, name, text.toByteArray(Charsets.UTF_8))

    /** 目录 fsync：保证 rename 本身落盘；失败不致命（尽力而为）。 */
    private fun fsyncDir(dir: File) {
        runCatching {
            RandomAccessFile(dir, "r").use { it.channel.force(true) }
        }
    }
}
