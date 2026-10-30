package com.jobeen.ime.skin.runtime

import android.content.Context
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.returnKeyVariantForSkin

/**
 * 回车变体运行期解析（Lee 2026-10-28 决定：自定义皮肤生效时
 * 回车跟皮肤，判断点 9 的“跟内置主题 id”作废）：
 * 皮肤快照存在 → Accent（皮肤色板的 accent 槽；单键样式覆写
 * 的颜色槽已优先于变体，见 KeyView 取色链）；
 * 无快照 → 内置主题口径（returnKeyVariantForTheme）。
 * 本解析不读颜色模式与跟随开关：开关只决定皮肤取浅/深色板，
 * 不再间接改变回车变体（此前开关经 themeFor 的主题 id 间接
 * 带走变体，现回车已不读内置主题 id，此链路不复存在）。
 * 快照存在性与 KeyboardColors.resolve / 各键盘 effectiveLayout
 * 用同一口径（SkinSnapshotProvider.current）。
 */
object EnterKeyVariant {
    fun resolve(context: Context): Variant = returnKeyVariantForSkin(
        SkinSnapshotProvider.current(context) != null,
        KeyboardColors.themeFor(context).id,
    )
}
