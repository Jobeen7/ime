package com.jobeen.ime.skin.image

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** assetImage 内置图注册表（工具项 9）：名 → drawable 的接线口径。 */
class BuiltinImageRegistryTest {

    private class FakeDrawable : Drawable() {
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        override fun getOpacity(): Int = 0
    }

    private val fake = FakeDrawable()

    private fun provider(
        lookupId: (String) -> Int = { 0 },
        drawableForId: (Int) -> Drawable? = { null },
    ) = BuiltinImageRegistry.provider(lookupId, drawableForId)

    @Test
    fun blankNameReturnsNull() {
        val resolve = provider(lookupId = { 7 }, drawableForId = { fake })
        assertNull(resolve(""))
        assertNull(resolve("   "))
    }

    @Test
    fun unknownNameReturnsNull() {
        val resolve = provider(lookupId = { 0 }, drawableForId = { fake })
        assertNull(resolve("no_such_drawable"))
    }

    @Test
    fun knownNameReturnsDrawable() {
        val resolve = provider(lookupId = { 7 }, drawableForId = { fake })
        assertSame(fake, resolve("ic_keyboard_language"))
    }

    @Test
    fun lookupFailureReturnsNull() {
        val resolve = provider(
            lookupId = { throw IllegalStateException("boom") },
            drawableForId = { fake },
        )
        assertNull(resolve("ic_keyboard_language"))
    }

    @Test
    fun drawableFailureReturnsNull() {
        val resolve = provider(
            lookupId = { 7 },
            drawableForId = { throw IllegalStateException("boom") },
        )
        assertNull(resolve("ic_keyboard_language"))
    }

    @Test
    fun drawableNullReturnsNull() {
        val resolve = provider(lookupId = { 7 }, drawableForId = { null })
        assertNull(resolve("ic_keyboard_language"))
    }
}
