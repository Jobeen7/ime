package com.jobeen.ime.input.panel.toolbar

import android.content.Context
import android.graphics.drawable.Drawable
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.SkinToolbarSnapshot
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider

/**
 * 皮肤工具栏接线点（StateRenders 与 KawaiiPanelView 共用，口径唯一）。
 *
 * - 只读 [SkinSnapshotProvider.peek]，不触发启动加载（快照由键盘
 *   窗口/应用事务在面板创建前加载并安装；peek 为空 = 无皮肤生效，
 *   用出厂默认）。
 * - 快照无 toolbar 定义（present=false 且三项全空）时返回 null，
 *   调用方用出厂默认；有任一定义即返回快照（中央区未定义时
 *   抉择函数仍会取默认中央区，只应用边缘图标）。
 * - 解析/解码异常整体兜底：记 ERROR（含异常类名）后返回 null，
 *   工具栏回落出厂默认，绝不崩溃。
 */
object SkinToolbarApplier {

    data class Applied(
        val toolbar: SkinToolbarSnapshot,
        val icons: Map<String, Drawable>,
    )

    fun resolveFor(context: Context): Applied? {
        return runCatching {
            val snapshot = SkinSnapshotProvider.peek() ?: return null
            val toolbar = snapshot.toolbar
            val hasAny = toolbar.present && (
                toolbar.buttons != null ||
                    toolbar.expandIcon != null ||
                    toolbar.collapseIcon != null ||
                    toolbar.expandSymbol != null ||
                    toolbar.collapseSymbol != null
            )
            if (!hasAny) return null
            Applied(
                toolbar = toolbar,
                icons = SkinToolbarIconResolver.resolve(
                    context, snapshot.ref.skinId, toolbar,
                ),
            )
        }.onFailure { error ->
            DiagLog.e(
                "toolbar", "toolbar_skin_apply_failed",
                mapOf("errorClass" to error.javaClass.simpleName),
                throwable = error,
            )
        }.getOrNull()
    }

    /**
     * 长驻渲染器刷新用（KawaiiPanelView.refreshTheme）：有皮肤构成
     * 则应用皮肤，否则用出厂默认（updateCenterActions 会清皮肤
     * 态，见 ToolbarRenderer）。
     */
    fun applyTo(renderer: ToolbarRenderer, context: Context) {
        val applied = resolveFor(context)
        if (applied != null) {
            renderer.updateSkinToolbar(applied.toolbar, applied.icons)
        } else {
            renderer.updateCenterActions(
                ToolbarConfig.DEFAULT_ACTIONS,
                emptyMap(),
            )
        }
    }
}
