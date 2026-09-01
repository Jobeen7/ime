package com.ninthsoft.ime.base.llamacpp

import android.content.Context
import com.ninthsoft.ime.base.llamacpp.internal.InferenceEngineImpl

/**
 * Main entry point for the llama.cpp AI Chat integration.
 */
object AiChat {
    /**
     * Get the inference engine single instance.
     */
    fun getInferenceEngine(context: Context) = InferenceEngineImpl.getInstance(context)
}