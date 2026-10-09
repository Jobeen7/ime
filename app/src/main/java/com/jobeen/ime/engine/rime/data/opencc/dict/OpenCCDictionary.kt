package com.jobeen.ime.engine.rime.data.opencc.dict

import com.jobeen.ime.engine.rime.data.opencc.OpenCCDictManager
import java.io.File

class OpenCCDictionary(
    file: File,
) : Dictionary() {
    override var file: File = file
        private set

    override val type: Type =
        if (file.extension == NEW_FORMAT) {
            Type.OCD2
        } else {
            Type.OCD
        }

    init {
        ensureFileExists()
        if (file.extension != type.ext) {
            throw IllegalArgumentException("Not a OpenCC dict ${file.name}")
        }
    }

    override fun toTextDictionary(dest: File): TextDictionary {
        // 不走 ensureTxt：它会预删 dest，与下面的原子替换冲突，这里只做扩展名校验
        if (dest.extension != Type.Text.ext) {
            throw IllegalArgumentException("Dest file name must end with .${Type.Text.ext}")
        }
        // 原子替换：JNI 先写同目录临时文件，成功后 rename 到最终路径；
        // 转换或 rename 失败时删临时文件、保留旧词典不动（范式同 TextDictionary）
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.delete()
        OpenCCDictManager.openCCDictConv(
            file.absolutePath,
            tmp.absolutePath,
            OpenCCDictManager.MODE_BIN_TO_TXT,
        )
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IllegalStateException("Failed to move ${tmp.absolutePath} to ${dest.absolutePath}")
        }
        return TextDictionary(dest)
    }

    override fun toOpenCCDictionary(dest: File): OpenCCDictionary {
        ensureBin(dest)
        // 自拷贝（源与目标是同一文件）直接返回：继续拷贝会把词典自身清空/截断
        if (file.canonicalFile == dest.canonicalFile) {
            return OpenCCDictionary(dest)
        }
        // 原子替换：先拷贝到同目录临时文件，成功后 rename 覆盖 dest；
        // 拷贝或 rename 失败时删临时文件、保留旧词典不动（范式同 TextDictionary）
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.delete()
        file.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IllegalStateException("Failed to move ${tmp.absolutePath} to ${dest.absolutePath}")
        }
        return OpenCCDictionary(dest)
    }

    companion object {
        const val NEW_FORMAT = "ocd2"
        const val OLD_FORMAT = "ocd"
    }
}
