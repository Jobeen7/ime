package com.jobeen.ime.base.speech

import com.jobeen.ime.base.util.WeakProperty

enum class ModelProvider { QNN, CPU }

object SpeechUiBridge {
    // 弱引用后备：注册方（KeyboardWindowView）销毁后槽位自动失效，
    // 避免单例强持键盘视图及其视图树；注册方须用字段强持有这些回调实例。
    var onRecordingStarted: (() -> Unit)? by WeakProperty()

    var onAmplitude: ((Float) -> Unit)? by WeakProperty()

    var onDone: (() -> Unit)? by WeakProperty()

    // 模型缺失时回调（下载询问入口）。为 null 时由 SherpaSpeechClient 自行 Toast 提示。
    var onModelMissing: ((ModelProvider) -> Unit)? by WeakProperty()

    // 会话启动失败（如本地组件不可用）时回调。用于无条件收起语音 UI，避免卡在动画中。
    var onFailed: (() -> Unit)? by WeakProperty()

    fun clear() {
        onRecordingStarted = null
        onAmplitude = null
        onDone = null
        onModelMissing = null
        onFailed = null
    }
}
