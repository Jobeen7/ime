package com.ninthsoft.ime.base.speech

enum class ModelProvider { QNN, CPU }

object SpeechUiBridge {
    @Volatile
    var onRecordingStarted: (() -> Unit)? = null

    @Volatile
    var onAmplitude: ((Float) -> Unit)? = null

    @Volatile
    var onDone: (() -> Unit)? = null

    // 模型缺失时回调（下载询问入口）。为 null 时由 SherpaSpeechClient 自行 Toast 提示。
    @Volatile
    var onModelMissing: ((ModelProvider) -> Unit)? = null

    // 会话启动失败（如本地组件不可用）时回调。用于无条件收起语音 UI，避免卡在动画中。
    @Volatile
    var onFailed: (() -> Unit)? = null

    fun clear() {
        onRecordingStarted = null
        onAmplitude = null
        onDone = null
        onFailed = null
    }
}
