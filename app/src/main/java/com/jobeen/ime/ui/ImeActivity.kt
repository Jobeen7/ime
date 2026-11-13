package com.jobeen.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.theme.AppAppearance
import com.jobeen.ime.ui.theme.AppAppearanceMode
import com.jobeen.ime.ui.theme.ImeTheme

/**
 * Activity 基类（WO42 §3-1）：21 个 Activity 复制的同一段模板只留一份——
 * 创建时记下外观模式，onResume 比对、变了才重建（返回即刷新）。
 * 只搬运，零行为变化：
 * - 各子类原 onCreate 的其余工作（状态恢复、启动器、偏好读取等）在
 *   [onCreateBeforeContent] 里做（记外观模式、调 edge-to-edge 之后、
 *   setContent 之前，与旧顺序一致）；WordCreation 的事后工作
 *   （loadDictList）在子类 onCreate 里、super.onCreate 之后做。
 * - 旧代码不调 enableEdgeToEdge 的 5 页覆写 [useEdgeToEdge] 为 false。
 * - onResume 有额外工作的 3 页覆写 [onResumeExtra]（顺序与旧代码一致：
 *   额外工作先、刷新判定后）。
 * - 主题字号一律取 [AppAppearance.themeMode] 现值；旧代码里
 *   `remember { mutableIntStateOf(...) }` 只是只读捕获（从无写入方），
 *   与 onCreate 时读一次等价，重建时同样重读。
 */
abstract class ImeActivity : ComponentActivity() {

    // 返回即刷新：创建时记下的外观模式；onResume 比对，变了才重建。
    private var createdAppearanceMode: AppAppearanceMode? = null

    /** 是否调用 enableEdgeToEdge：缺省 true；旧代码不调的 5 页覆写为 false。 */
    protected open val useEdgeToEdge: Boolean = true

    /**
     * onResume 额外工作（缺省无）：MainActivity（刷新 imeConfiguredState）、
     * SetupActivity（checkImeStatus）、UserDictActivity（字典重载＋同步显示刷新）
     * 在此做；执行顺序与旧代码一致（额外工作先、刷新判定后）。
     */
    protected open fun onResumeExtra() {}

    /**
     * onCreate 预备工作（缺省无）：在基类记外观模式、调 edge-to-edge
     * 之后、setContent 之前执行；顺序与旧代码一致。BackupActivity
     * （待办恢复＋观察者）、MainActivity（主题迁移＋启动器）、
     * SetupActivity（checkImeStatus）、UserDictActivity（偏好＋调度）
     * 在此做。
     */
    protected open fun onCreateBeforeContent(savedInstanceState: Bundle?) {}

    @Composable
    protected abstract fun Content()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAppearanceMode = AppAppearance.getMode(this)
        if (useEdgeToEdge) enableEdgeToEdge()
        onCreateBeforeContent(savedInstanceState)
        setContent {
            ImeTheme(themeMode = AppAppearance.themeMode(this), dynamicColor = AppAppearance.useDynamicColor(this)) {
                Content()
            }
        }
    }

    /**
     * 返回即刷新：从外观页返回时，若应用外观模式已变则重建本页，
     * 主题立即生效；模式未变不重建；重建后新模式即记录值，不循环。
     */
    override fun onResume() {
        super.onResume()
        onResumeExtra()
        val recordedAppearanceMode = createdAppearanceMode
        if (recordedAppearanceMode != null &&
            AppAppearance.appearanceModeChanged(recordedAppearanceMode, AppAppearance.getMode(this))
        ) {
            recreate()
        }
    }
}
