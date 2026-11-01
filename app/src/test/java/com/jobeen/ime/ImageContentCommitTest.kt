package com.jobeen.ime

import android.view.inputmethod.InputConnection
import com.jobeen.ime.input.ImageContentCommit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片内容提交的纯函数与入口守卫测试。
 *
 * Android 绑定路径（暂存、FileProvider 授权、InputContentInfo 构造、目标
 * commitContent 的真值）无法在 JVM 单元测试里跑：本模块无 Robolectric，
 * 且 ImeInputConnection 的构造本身依赖主线程 Handler；那部分只做静态
 * 核验，留待真机验证。以下覆盖不依赖框架的部分：类型白名单、扩展名、
 * 内容寻址文件名、授权位合成、入口守卫的 false 路径。
 */
class ImageContentCommitTest {

    @Test
    fun supportedMimeTypesAreConcreteImageTypesOnly() {
        assertTrue(ImageContentCommit.isSupportedImageMimeType("image/png"))
        assertTrue(ImageContentCommit.isSupportedImageMimeType("image/gif"))
        assertTrue(ImageContentCommit.isSupportedImageMimeType("image/jpeg"))
        assertTrue(ImageContentCommit.isSupportedImageMimeType("image/webp"))
        // 通配类型不发：编辑器侧按具体类型匹配，通配无法保证被接受
        assertFalse(ImageContentCommit.isSupportedImageMimeType("image/*"))
        assertFalse(ImageContentCommit.isSupportedImageMimeType("text/plain"))
        assertFalse(ImageContentCommit.isSupportedImageMimeType(null))
        assertFalse(ImageContentCommit.isSupportedImageMimeType(""))
    }

    @Test
    fun mimeTypeMatchingIgnoresCaseAndWhitespace() {
        assertTrue(ImageContentCommit.isSupportedImageMimeType("IMAGE/PNG"))
        assertTrue(ImageContentCommit.isSupportedImageMimeType("  image/jpeg "))
        assertEquals("png", ImageContentCommit.mimeToExtension("IMAGE/PNG"))
    }

    @Test
    fun mimeToExtensionMapsEachSupportedType() {
        assertEquals("png", ImageContentCommit.mimeToExtension("image/png"))
        assertEquals("gif", ImageContentCommit.mimeToExtension("image/gif"))
        assertEquals("jpg", ImageContentCommit.mimeToExtension("image/jpeg"))
        assertEquals("webp", ImageContentCommit.mimeToExtension("image/webp"))
        assertNull(ImageContentCommit.mimeToExtension("image/bmp"))
        assertNull(ImageContentCommit.mimeToExtension(null))
    }

    @Test
    fun stagedFileNameIsSha256HexPlusExtension() {
        // "abc" 的 SHA-256 是公开已知向量：同字节恒同名是内容寻址的根基
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad.png",
            ImageContentCommit.stagedFileName("abc".toByteArray(), "image/png"),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad.jpg",
            ImageContentCommit.stagedFileName("abc".toByteArray(), "image/jpeg"),
        )
        assertNull(ImageContentCommit.stagedFileName("abc".toByteArray(), "image/bmp"))
    }

    @Test
    fun stagedFileNameIsDeterministicAndContentSensitive() {
        val first = ImageContentCommit.stagedFileName("abc".toByteArray(), "image/png")
        val again = ImageContentCommit.stagedFileName("abc".toByteArray(), "image/png")
        val other = ImageContentCommit.stagedFileName("abd".toByteArray(), "image/png")
        assertEquals(first, again)
        assertTrue(first != null && other != null && first != other)
        // 文件名只含十六进制与扩展名，无路径穿越面
        assertTrue(first!!.matches(Regex("[0-9a-f]+\\.png")))
    }

    @Test
    fun commitFlagsAddsGrantBitAndKeepsCallerBits() {
        assertEquals(
            InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            ImageContentCommit.commitFlags(0),
        )
        assertEquals(
            0x2 or InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            ImageContentCommit.commitFlags(0x2),
        )
    }

    @Test
    fun commitImageRejectsNullTargetAndEmptyBytesWithoutThrowing() {
        assertFalse(
            ImageContentCommit.commitImage(null, null, byteArrayOf(1), "image/png"),
        )
        assertFalse(
            ImageContentCommit.commitImage(null, null, byteArrayOf(), "image/png"),
        )
    }

    @Test
    fun commitImageRejectsUnsupportedMimeWithoutThrowing() {
        assertFalse(
            ImageContentCommit.commitImage(null, null, byteArrayOf(1), "image/bmp"),
        )
        assertFalse(
            ImageContentCommit.commitImageUri(null, null, "image/bmp"),
        )
    }

    @Test
    fun commitImageUriRejectsNullTargetAndNullUriWithoutThrowing() {
        assertFalse(ImageContentCommit.commitImageUri(null, null, "image/png"))
    }
}
