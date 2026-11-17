package com.jobeen.ime.input.keyboard.key

import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回车变体（无皮肤口径）：皮肤 v3 死壳删除后快照恒为 null，
 * 生产接线 EnterKeyVariant.resolve 恒走无皮肤分支；本测试只覆盖
 * 无皮肤时的内置主题口径原样不动（纯逻辑，不碰 Android 框架）。
 */
class EnterKeyVariantTest {

    // ---------- 无皮肤：内置行为原样不动 ----------

    @Test
    fun skinInactiveKeepsBuiltinBehavior() {
        assertEquals(Variant.Normal, returnKeyVariantForSkin(false, "light"))
        assertEquals(Variant.Normal, returnKeyVariantForSkin(false, "amoled"))
        assertEquals(Variant.Accent, returnKeyVariantForSkin(false, "sunset"))
        assertEquals(Variant.Accent, returnKeyVariantForSkin(false, "monet"))
        assertEquals(Variant.Accent, returnKeyVariantForSkin(false, "unknown"))
        assertEquals(Variant.Accent, returnKeyVariantForSkin(false, ""))
    }
}
