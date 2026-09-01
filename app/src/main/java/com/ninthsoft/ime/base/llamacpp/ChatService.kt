package com.ninthsoft.ime.base.llamacpp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Singleton chat service wrapping a single [ChatController] instance.
 *
 * Usage:
 * ```
 * ChatService.loadModel("/path/to/model.gguf", "You are a helpful IME assistant.")
 * ChatService.chat("你好")?.collect { token -> ... }
 * ChatService.close()
 * ```
 */
object ChatService {

    private var controller: ChatController? = null

    val isLoaded: Boolean get() = controller != null

    /**
     * Loads a model asynchronously on IO. Safe to call multiple times —
     * subsequent calls are ignored if already loaded.
     */
    suspend fun loadModel(
        modelPath: String,
        systemPrompt: String = "",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (controller != null) {
            Timber.w("Model already loaded, ignoring")
            return@withContext Result.success(Unit)
        }

        val engine = InferenceEngineInitializer.instance
        engine.state.first { it is InferenceEngine.State.Initialized }

        val ctrl = ChatController(engine, modelPath, systemPrompt)
        val result = ctrl.initialize()
        if (result.isSuccess) {
            controller = ctrl
        }
        result
    }

    /**
     * Sends a message and returns a [Flow] of generated tokens,
     * or `null` if no model is loaded.
     */
    fun chat(message: String, maxTokens: Int = 256): Flow<String>? {
        val ctrl = controller ?: run {
            Timber.w("No model loaded")
            return null
        }
        return ctrl.chat(message, maxTokens)
    }

    /** Unloads the model. */
    suspend fun close() {
        val ctrl = controller ?: return
        controller = null
        ctrl.close()
    }

    /**
     * Sends a message, collects all tokens, and returns the complete response
     * as a single [String]. Logs the full conversation round via Timber.
     *
     * Returns `null` if no model is loaded.
     */
    suspend fun chatOnce(message: String, maxTokens: Int = 256): String? {
        val flow = chat(message, maxTokens) ?: return null
        val result = StringBuilder()
        try {
            flow.collect { token -> result.append(token) }
        } catch (e: CancellationException) {
            Timber.w("Chat cancelled")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Chat error: %s", message)
            return null
        }
        Timber.i("Chat result:\n%s", result.toString())
        return result.toString()
    }
}
