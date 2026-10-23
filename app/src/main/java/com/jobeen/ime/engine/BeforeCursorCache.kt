package com.jobeen.ime.engine

/**
 * 光标前文缓存的存储（纯逻辑、无 Android 依赖，可单测）：只存最
 * 近一次读取到的前文整体，经单个 @Volatile 引用发布——读者要么
 * 看到完整的旧值、要么看到完整的新值。
 *
 * 失效与防串框不在这里：换框 / 上屏 / 删字 / 光标移动由
 * RimeEngine 主动 [invalidate]；异步读者以输入框序号整数比对整
 * 轮作废（见 RimeEngine.peekTextBeforeCursor）。此处不带读取时
 * 刻与代次标签。
 */
internal class BeforeCursorCache {

    @Volatile
    private var text: String? = null

    /** 当前缓存内容；未缓存或已失效时为 null，调用方重新读取。 */
    fun cachedText(): String? = text

    /** 发布一次读取结果（整体替换）。 */
    fun publish(text: String) {
        this.text = text
    }

    /** 主动失效：整体置空，其后任何读取必然未命中，直至下一次成功发布。 */
    fun invalidate() {
        text = null
    }
}
