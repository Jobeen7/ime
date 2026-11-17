package com.jobeen.ime.skin.batch5

import com.jobeen.ime.skin.runtime.NightModePolicy
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第 5 批定向单测：纯 JVM 护栏（§2 图片／§3 切换／§4 组开关／§6 三态与宽度）。§1 可移植归档随皮肤导入删除而删除；§5 节流门（FabBackdropRefreshGate）随玻璃基础设施删除而删除。 */
class Batch5SkinGuardsTest {



    // ---- §2 图片护栏 ----



    // ---- §3 切换缓存 ----


    @Test
    fun nightModePolicy_explicitWinsOverReported() {
        assertTrue(NightModePolicy.resolve(NightModePolicy.MODE_NIGHT_YES, false))
        assertFalse(NightModePolicy.resolve(NightModePolicy.MODE_NIGHT_NO, true))
        assertTrue(NightModePolicy.resolve(NightModePolicy.MODE_NIGHT_AUTO, true))
        assertFalse(NightModePolicy.resolve(null, false))
        assertTrue(NightModePolicy.resolve(NightModePolicy.MODE_NIGHT_UNSPECIFIED, true))
    }


    // ---- §6 三态／数值／宽度／图标槽／覆盖层语义 ----




}
