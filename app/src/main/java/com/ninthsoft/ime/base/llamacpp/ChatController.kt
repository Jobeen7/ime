package com.ninthsoft.ime.base.llamacpp

import com.ninthsoft.ime.base.llamacpp.InferenceEngine.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Manages the full lifecycle of a single LLM chat session.
 *
 * Usage:
 * ```
 * val controller = ChatController(engine, "/path/to/model.gguf", "You are a helpful assistant.")
 * val result = controller.initialize()
 * if (result.isSuccess) {
 *     controller.chat("Hello!").collect { token -> print(token) }
 * }
 * controller.close()
 * ```
 */
class ChatController(
    private val engine: InferenceEngine,
    private val modelPath: String,
    private val systemPrompt: String = "",
) {

    private var isInitialized = false

    /**
     * Loads the model and sets the system prompt.
     * Must be called before [chat].
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(modelPath)
            require(file.exists()) { "Model file not found: $modelPath" }
            require(file.isFile && file.canRead()) { "Cannot read model file: $modelPath" }

            Timber.i("Loading model: %s (%.1f MB)", file.name, file.length() / 1024.0 / 1024.0)
            engine.loadModel(modelPath)

            if (systemPrompt.isNotEmpty()) {
                Timber.i("Setting system prompt: %s", systemPrompt)
                engine.setSystemPrompt(systemPrompt)
            }

            isInitialized = true
            Timber.i("Model loaded and ready")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize chat controller")
            Result.failure(e)
        }
    }

    /**
     * Sends a user message and returns a [Flow] of generated tokens.
     * The model must already be loaded via [initialize].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun chat(message: String, maxTokens: Int = 256): Flow<String> = flow {
        require(isInitialized) { "ChatController not initialized" }
        require(message.isNotEmpty()) { "Message must not be empty" }

        try {
            Timber.i("User: %s", message)
            engine.sendUserPrompt(message, maxTokens).collect { token ->
                emit(token)
            }
        } catch (e: CancellationException) {
            Timber.i("Chat generation cancelled")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Chat generation error")
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Unloads the model and releases resources. The [engine] instance
     * remains usable for loading a new model.
     */
    suspend fun close() {
        if (!isInitialized) return
        withContext(Dispatchers.IO) {
            try {
                engine.cleanUp()
                Timber.i("Chat controller closed")
            } catch (e: Exception) {
                Timber.e(e, "Error closing chat controller")
            }
            isInitialized = false
        }
    }
}
