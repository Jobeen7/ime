package com.jobeen.ime.engine.rime.data.opencc.dict

import com.jobeen.ime.engine.rime.data.opencc.OpenCCDictManager
import java.io.File

class TextDictionary(
    file: File,
) : Dictionary() {
    override var file: File = file
        private set

    override val type: Type = Type.Text

    init {
        ensureFileExists()
        if (file.extension != type.ext) {
            throw IllegalArgumentException("Not a text dict ${file.name}")
        }
    }

    override fun toTextDictionary(dest: File): TextDictionary {
        ensureTxt(dest)
        file.copyTo(dest)
        return TextDictionary(dest)
    }

    override fun toOpenCCDictionary(dest: File): OpenCCDictionary {
        ensureBin(dest)
        // 原子替换：JNI 先写同目录临时文件，成功后 rename 到最终路径；
        // 构建或 rename 失败时删临时文件、保留旧 .ocd2 不动
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.delete()
        OpenCCDictManager.openCCDictConv(
            file.absolutePath,
            tmp.absolutePath,
            OpenCCDictManager.MODE_TXT_TO_BIN,
        )
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IllegalStateException("Failed to move ${tmp.absolutePath} to ${dest.absolutePath}")
        }
        return OpenCCDictionary(dest)
    }
}
