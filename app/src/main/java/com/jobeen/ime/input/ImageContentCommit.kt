package com.jobeen.ime.input

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/**
 * 图片内容提交：把图片字节或已有内容 URI 经目标 [InputConnection] 发给当前编辑器。
 *
 * 方向说明（不要颠倒）：
 * - 发送方（输入法）只做：构造 [InputContentInfo]、在 flags 里置
 *   [InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION]、调用
 *   [InputConnection.commitContent]、把目标的返回值原样返回。
 * - [InputContentInfo.requestPermission]/releasePermission 由接收方（编辑器应用）在
 *   读内容前后调用，发送方绝不调用：发送方调用只会给自己授权，于事无补。
 * - 目标是否接受图片取决于编辑器应用本身：它须声明支持该图片类型并实现
 *   commitContent；返回 false 即应用不支持或拒绝，调用方不得 reinterpret 为成功。
 *
 * URI 设计（无新增依赖、无需改动清单与 FileProvider 路径配置）：
 * - 字节路径：内容寻址的皮肤图片文件并不在 APK assets 里，无法用 asset URI
 *   直发；改为把字节暂存到 cacheDir/shared/images/（FileProvider 的 cache-path
 *   已覆盖 shared/，与皮肤分享共用同一授权树），再经既有 FileProvider 取 URI。
 *   暂存文件名取字节的 SHA-256 十六进制＋按类型定的扩展名：同内容恒同名
 *   （去重、无路径穿越面——文件名里只有十六进制与扩展名），已存在且长度
 *   一致时直接复用，不重写。
 * - URI 路径：调用方自带的可授权内容 URI（如 FileProvider URI）直接包装发送，
 *   调用方负责该 URI 本身可被授权；file URI 与私有文件路径不在此列。
 * - 暂存文件不主动删除：编辑器在 commitContent 返回后才异步读内容，提交时
 *   删文件等于把刚发出的 URI 变成死链；cacheDir 由系统在空间紧张时回收。
 *
 * 约束：
 * - 只发具体图片类型（png/gif/jpeg/webp）；通配类型不发，因为编辑器侧按
 *   具体类型匹配，通配类型无法保证被接受。
 * - opts 原样透传，输入法不解读、不注入：它是编辑器应用自定义的附加数据。
 * - flags 在调用方 flags 基础上或上授权位，不丢弃调用方传入的其他位。
 * - 桥接模式下的虚拟连接（文本 Editable，无图片位）对 commitContent 恒返回
 *   false，把它当目标传进来会如实得到 false，不会把图片偷偷写进真实编辑器。
 * - [InputContentInfo] 自 API 25 起可用，低于该版本直接返回 false，绝不在
 *   旧版本上触碰该类。
 */
object ImageContentCommit {

    /** 发送方支持的图片类型（小写归一化后比对）。 */
    val supportedMimeTypes: Set<String> =
        setOf("image/png", "image/gif", "image/jpeg", "image/webp")

    /** [mimeType] 是否为受支持的具体图片类型（大小写与首尾空白不敏感）。 */
    fun isSupportedImageMimeType(mimeType: String?): Boolean =
        mimeType?.trim()?.lowercase() in supportedMimeTypes

    /** [mimeType] 对应的暂存文件扩展名，不支持的类型返回 null。 */
    fun mimeToExtension(mimeType: String?): String? =
        when (mimeType?.trim()?.lowercase()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> null
        }

    /**
     * [bytes] 的暂存文件名：SHA-256 十六进制＋扩展名；不支持的类型返回 null。
     * 纯函数：同字节恒同名，与提交路径的空字节拒绝无关（空字节也有哈希值，
     * 但提交入口会先拒空字节，见 [commitImage]）。
     */
    fun stagedFileName(bytes: ByteArray, mimeType: String?): String? {
        val extension = mimeToExtension(mimeType) ?: return null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return "$hash.$extension"
    }

    /** 调用方 flags 或上图片授权位后的 flags（调用方其他位原样保留）。 */
    fun commitFlags(callerFlags: Int): Int =
        callerFlags or InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION

    /**
     * 提交图片字节：暂存→FileProvider URI→目标 commitContent，返回目标的原值。
     * 入口守卫失败（目标/上下文为空、空字节、不支持的类型、低于 API 25、
     * 暂存或授权异常、目标抛异常）一律返回 false，不抛异常。
     */
    fun commitImage(
        target: InputConnection?,
        context: Context?,
        bytes: ByteArray,
        mimeType: String,
        flags: Int = 0,
        opts: Bundle? = null,
    ): Boolean {
        if (target == null || context == null) return false
        if (bytes.isEmpty() || !isSupportedImageMimeType(mimeType)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return false
        val staged = runCatching { stageBytes(context, bytes, mimeType) }.getOrNull()
            ?: return false
        val uri = runCatching {
            FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", staged,
            )
        }.getOrNull() ?: return false
        return commitUri(target, uri, mimeType, flags, opts)
    }

    /**
     * 提交调用方自带的内容 URI：不暂存、不改写 URI，只包装并以授权位提交，
     * 返回目标的原值。守卫失败一律返回 false，不抛异常。
     */
    fun commitImageUri(
        target: InputConnection?,
        uri: Uri?,
        mimeType: String,
        flags: Int = 0,
        opts: Bundle? = null,
    ): Boolean {
        if (target == null || uri == null || uri == Uri.EMPTY) return false
        if (!isSupportedImageMimeType(mimeType)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return false
        return commitUri(target, uri, mimeType, flags, opts)
    }

    private fun stageBytes(context: Context, bytes: ByteArray, mimeType: String): File {
        val name = stagedFileName(bytes, mimeType)
            ?: throw IllegalArgumentException("unsupported image mime type")
        val dir = File(context.cacheDir, "shared/images").apply { mkdirs() }
        require(dir.isDirectory) { "image staging dir unavailable" }
        val file = File(dir, name)
        if (!file.isFile || file.length() != bytes.size.toLong()) {
            file.outputStream().use { it.write(bytes) }
        }
        return file
    }

    private fun commitUri(
        target: InputConnection,
        uri: Uri,
        mimeType: String,
        flags: Int,
        opts: Bundle?,
    ): Boolean {
        val description = ClipDescription("image", arrayOf(mimeType.trim().lowercase()))
        val info = InputContentInfo(uri, description, null)
        return runCatching {
            target.commitContent(info, commitFlags(flags), opts)
        }.getOrDefault(false)
    }
}
