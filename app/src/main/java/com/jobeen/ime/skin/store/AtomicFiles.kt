package com.jobeen.ime.skin.store

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 原子文件写（定稿八.2/八.3 口径）：临时文件 → 写满 → 文件 fsync →
 * rename 替换 → 目录 fsync。进程在任一步被杀，目标文件要么是旧版
 * 完整内容、要么是新版完整内容，绝不出现截断的半截文件（崩溃点
 * I-4/A-5 的防线）。沿用 ThemeStore 的 tmp+rename 先例并补 fsync。
 *
 * 共用实现 [write]（目标文件口径）：SchemeEnabled、SchemePackageImport
 * 的 writeJournal、SkinScriptStore 此前各写一遍 tmp＋rename（且皆
 * 无 fsync、tmp 命名与失败口径各异），现统一收成此处，不再另造。
 * 旧三处与本实现的结果语义一致（旧内容或新内容完整二选一），
 * 差别只在耐久性补强（fsync）与并发 tmp 隔离（nanoTime 后缀，
 * 沿用 SchemeEnabled 的旧口径：固定 .tmp 名在并发写同一文件时
 * 会互踩）。
 */
object AtomicFiles {
    /**
     * 原子写目标文件（共用实现）：父目录按需创建；tmp 名带
     * nanoTime 后缀防并发互踩；写满＋文件 fsync 后 rename 覆盖；
     * rename 失败删 tmp 并抛 IOException（旧文件原样保留，调用
     * 方按原失败口径处置——SchemeEnabled/writeJournal 原即抛
     * IOException，SkinScriptStore 原即以 error 转写失败）。
     */
    fun write(target: File, bytes: ByteArray) {
        val dir = target.parentFile
        dir?.mkdirs()
        val tmp = if (dir != null) {
            File(dir, target.name + ".tmp-${System.nanoTime()}")
        } else {
            File(target.name + ".tmp-${System.nanoTime()}")
        }
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.channel.force(true)
            }
            if (!tmp.renameTo(target)) {
                // rename 覆盖失败即抛错、旧文件原样保留：绝不先删
                // 目标再改名——删后改名再失败会丢旧文件，违背
                // “旧版完整或新版完整二选一”的承诺（旧 writeAtomically
                // 的先删后改名回退即此问题，本实现一并收紧；Linux
                // 上 rename 覆盖本就成功，回退分支极少触发）。
                throw java.io.IOException(
                    "atomic rename failed: ${tmp.path} -> ${target.path}",
                )
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw e
        }
        if (dir != null) fsyncDir(dir)
    }

    /** 目标文件口径的文本版。 */
    fun write(target: File, text: String) =
        write(target, text.toByteArray(Charsets.UTF_8))

    /** 目录＋文件名口径：唯一实现即 [write]，不另存第二份逻辑。 */
    fun writeAtomically(dir: File, name: String, bytes: ByteArray) {
        write(File(dir, name), bytes)
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
