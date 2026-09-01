package com.ninthsoft.ime.base.llamacpp

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * InferenceEngine 的手动启动入口。
 *
 * 不再依赖 androidx.startup，需要使用时显式调用 [initialize]。
 */
object InferenceEngineInitializer {

    const val MODELS_DIR = "model"

    @Volatile
    private var _instance: InferenceEngine? = null

    val instance: InferenceEngine
        get() = _instance ?: throw IllegalStateException(
            "InferenceEngine not initialized! Call InferenceEngineInitializer.initialize first."
        )

    fun initialize(context: Context): InferenceEngine {
        File(context.getExternalFilesDir(null), MODELS_DIR).also { it.mkdirs() }
        val engine = AiChat.getInferenceEngine(context)
        _instance = engine
        Timber.i("llama.cpp InferenceEngine created")
        return engine
    }
}