package com.jobeen.ime.skin.model

import com.jobeen.ime.input.keyboard.key.KeyEffectPainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO38 按键特效参数（对端 Mie v3.3.9 KeyEffects 口径）：
 * 内阴影／渐变／3D 底边厚度／按压下沉／涟漪。
 * 解析口径测试已随 §5.2 死代码删除；只留 KeyEffectPainter
 * 纯函数回归锁定。
 */
class Wo38KeyEffectTest {

    // ---------- painter 纯函数 ----------

    @Test
    fun innerStrokeWidthIsTwiceBlurWithOnePxFloor() {
        assertEquals(8f, KeyEffectPainter.innerStrokeWidthPx(4f))
        assertEquals(1f, KeyEffectPainter.innerStrokeWidthPx(0f))
        assertEquals(1f, KeyEffectPainter.innerStrokeWidthPx(0.2f))
    }

    @Test
    fun innerBlurThresholdMatchesMie() {
        assertFalse(KeyEffectPainter.innerNeedsBlur(0.5f))
        assertTrue(KeyEffectPainter.innerNeedsBlur(0.6f))
    }
}
