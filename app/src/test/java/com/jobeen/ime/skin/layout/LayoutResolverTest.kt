package com.jobeen.ime.skin.layout

import com.jobeen.ime.skin.model.LayoutResolution
import com.jobeen.ime.skin.model.ResolvedLayout
import com.jobeen.ime.skin.model.SkinSnapshotBuilder
import com.jobeen.ime.skin.model.SkinValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 布局引用解析 + 快照透传（B3 详设 §3.4/§3.5）。 */
class LayoutResolverTest {

    private fun layoutTree(id: String = "lay-1"): JsonObject = buildJsonObject {
        put("formatVersion", 3)
        put("meta", buildJsonObject {
            put("id", id)
            put("name", "我的布局")
        })
        put("rows", buildJsonArray {
            add(buildJsonObject {
                put("keys", buildJsonArray {
                    add(buildJsonObject {
                        put("stableId", "1")
                        put("label", "q")
                        put("tap", buildJsonObject {
                            put("type", "text")
                            put("value", "q")
                        })
                    })
                    add(buildJsonObject {
                        put("stableId", "2")
                        put("label", "w")
                        put("widthWeight", 2.0)
                        put("tap", buildJsonObject {
                            put("type", "text")
                            put("value", "w")
                        })
                    })
                })
            })
        })
    }

    @Test
    fun validTreeResolvesToBuiltLayout() {
        val outcome = LayoutResolver.resolve(layoutTree(), "lay-1", 3, emptyMap())
        assertTrue(outcome is LayoutResolver.Outcome.Resolved)
        val resolved = (outcome as LayoutResolver.Outcome.Resolved).resolved
        assertEquals("lay-1", resolved.layoutId)
        assertEquals(3, resolved.generation)
        assertEquals("我的布局", resolved.name)
        assertEquals(listOf("lay-1:K-1", "lay-1:K-2"), resolved.baseLayout[0].map { it.id })
        // 权重 1:2 → 比例 1/3 : 2/3
        assertEquals(
            1f / 3f, resolved.baseLayout[0][0].appearance.percentWidth, 1e-6f,
        )
    }

    @Test
    fun invalidTreeYieldsIssuesNotCrash() {
        val outcome = LayoutResolver.resolve(null, "lay-1", 1, emptyMap())
        assertTrue(outcome is LayoutResolver.Outcome.Invalid)
        assertEquals(
            "unparseable",
            (outcome as LayoutResolver.Outcome.Invalid).issues.single().reason,
        )
        val mismatched = LayoutResolver.resolve(layoutTree(id = "other"), "lay-1", 1, emptyMap())
        assertTrue(mismatched is LayoutResolver.Outcome.Invalid)
    }

    @Test
    fun fieldFallbacksStillResolve() {
        val tree = buildJsonObject {
            put("formatVersion", 3)
            put("meta", buildJsonObject { put("id", "lay-1") })
            put("rows", buildJsonArray {
                add(buildJsonObject {
                    put("keys", buildJsonArray {
                        add(buildJsonObject {
                            put("stableId", "1")
                            put("tap", buildJsonObject {
                                put("type", "text")
                                put("value", "q")
                            })
                        })
                    })
                })
            })
        }
        val outcome = LayoutResolver.resolve(tree, "lay-1", 1, emptyMap())
        assertTrue(outcome is LayoutResolver.Outcome.Resolved)
        val resolved = outcome as LayoutResolver.Outcome.Resolved
        assertTrue(resolved.issues.isNotEmpty())
        assertEquals("lay-1", resolved.resolved.name) // meta.name 缺失回退为 id
    }

    /** 手工默认方案（与 SkinSnapshotBuilderTest 同口径：KeyboardTheme.DEFAULT 依赖 Android 环境，JVM 单测不可用）。 */
    private val defaultScheme = com.jobeen.ime.data.keyboard.theme.KeyboardColors.ColorScheme(
        keyBackground = 100, keyPressed = 101, keyBorderStroke = 102,
        specialKeyBackground = 200, specialKeyPressed = 201, specialKeyBorderStroke = 202,
        accentKeyBackground = 300, accentKeyPressed = 301, accentKeyBorderStroke = 302,
        keyText = 400, specialKeyText = 401, accentKeyText = 402, altText = 403,
        background = 500, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
        panel = com.jobeen.ime.data.keyboard.theme.KeyboardColors.ColorScheme.PanelColors(
            background = 600, toolbarText = 601, toolbarActived = 602, toolbarIcon = 603,
            candidateBackground = 604, candidateText = 605, candidateIndex = 606,
            candidateDivider = 607, toolbarPressed = 608,
        ),
        pinner = com.jobeen.ime.data.keyboard.theme.KeyboardColors.ColorScheme.PinnerColors(
            background = 700, textColor = 701, secondaryTextColor = 702,
        ),
        toastBackground = 999, toastText = 998,
    )

    @Test
    fun snapshotCarriesResolution() {
        val resolvedLayout = ResolvedLayout(
            layoutId = "lay-1", generation = 2, name = "L", baseLayout = emptyList(),
        )
        val tree = buildJsonObject {
            put("formatVersion", 3)
            put("meta", buildJsonObject {
                put("id", "skin-1")
                put("name", "S")
            })
            put("layoutId", "lay-1")
            // 无配色拒绝（2026-10-27 硬化）：带一色，否则树被拒；
            // 双模式硬要求（2026-10-29）：深色段缺失同样拒包，故补一色深色板
            put("styles", buildJsonObject {
                put("keyBackground", "#FFFFFFFF")
            })
            put("stylesDark", buildJsonObject {
                put("keyBackground", "#FF222222")
            })
        }
        val validated = when (
            val result = SkinValidator.validate(
                tree, 143,
                com.jobeen.ime.skin.model.GeometryFallbacks(1f, 1f, 1f, 1f),
                assetKind = { null },
            )
        ) {
            is SkinValidator.Result.Valid -> result.skin
            is SkinValidator.Result.Rejected -> error("unexpected rejection")
        }
        val snapshot = SkinSnapshotBuilder.build(
            validated = validated,
            ref = com.jobeen.ime.skin.store.SkinGenRef("skin-1", "h", 1),
            defaultScheme = defaultScheme,
            tier = com.jobeen.ime.skin.model.MemoryTier.NORMAL,
            assetSha256 = { null },
            targetWidthPx = 100,
            targetHeightPx = 50,
            resolvedLayout = resolvedLayout,
        )
        assertEquals(resolvedLayout, snapshot.resolvedLayout)
        assertEquals(LayoutResolution.RESOLVED, snapshot.layoutResolution)
        // 未注入解析结果时默认 NOT_SET、resolvedLayout 为空
        val plain = SkinSnapshotBuilder.build(
            validated = validated,
            ref = com.jobeen.ime.skin.store.SkinGenRef("skin-1", "h", 1),
            defaultScheme = defaultScheme,
            tier = com.jobeen.ime.skin.model.MemoryTier.NORMAL,
            assetSha256 = { null },
            targetWidthPx = 100,
            targetHeightPx = 50,
        )
        assertNull(plain.resolvedLayout)
        assertEquals(LayoutResolution.NOT_SET, plain.layoutResolution)
        // 失败结论可显式携带（工厂层 MISSING/INVALID 口径）
        val failed = SkinSnapshotBuilder.build(
            validated = validated,
            ref = com.jobeen.ime.skin.store.SkinGenRef("skin-1", "h", 1),
            defaultScheme = defaultScheme,
            tier = com.jobeen.ime.skin.model.MemoryTier.NORMAL,
            assetSha256 = { null },
            targetWidthPx = 100,
            targetHeightPx = 50,
            layoutResolution = LayoutResolution.MISSING,
        )
        assertEquals(LayoutResolution.MISSING, failed.layoutResolution)
        assertTrue(failed.layoutId == "lay-1")
    }
}
