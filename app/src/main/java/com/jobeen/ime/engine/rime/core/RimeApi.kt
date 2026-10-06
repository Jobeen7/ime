package com.jobeen.ime.engine.rime.core

import com.jobeen.ime.engine.event.KeyModifiers
import kotlinx.coroutines.flow.SharedFlow

interface RimeApi {
    val messageFlow: SharedFlow<RimeMessage<*>>

    val isReady: Boolean

    val schemaCached: RimeSchema
    val statusCached: StatusProto

    val compositionCached: CompositionProto

    val hasMenu: Boolean

    val paging: Boolean

    suspend fun isEmpty(): Boolean

    suspend fun deploy()

    suspend fun updateConfig()

    suspend fun joinMaintenanceThread()

    suspend fun syncUserData(): Boolean

    /** 用户词典名列表 */
    suspend fun getUserDictList(): List<String>

    /**
     * 热导入：将文本词库合并进运行中引擎已打开的用户词典，返回导入条数，失败为 -1。
     * 必须经引擎调度器串行执行——直接在 IO 线程调用 JNI 会与引擎查词/学词
     * 并发访问同一个 LevelDB，有损坏词库的风险。
     */
    suspend fun importUserDictLive(dictName: String, textFile: String): Int

    /** 热导出：从运行中引擎已打开的用户词典导出文本，返回条数，失败为 -1。 */
    suspend fun exportUserDictLive(dictName: String, textFile: String): Int

    suspend fun processKey(
        value: Int,
        modifiers: UInt = 0u,
        isVirtual: Boolean = true,
    ): Boolean

    suspend fun processKey(
        value: KeyValue,
        modifiers: KeyModifiers,
        isVirtual: Boolean = true,
    ): Boolean

    suspend fun simulateKeySequence(
        sequence: String,
    ): Boolean

    suspend fun selectCandidate(idx: Int, global: Boolean): Boolean

    suspend fun deleteCandidate(idx: Int, global: Boolean): Boolean

    suspend fun changeCandidatePage(backward: Boolean): Boolean

    suspend fun moveCursorPos(position: Int)

    suspend fun availableSchemata(): Array<SchemaItem>

    suspend fun enabledSchemata(): Array<SchemaItem>

    suspend fun setEnabledSchemata(schemaIds: Array<String>): Boolean

    suspend fun selectedSchemata(): Array<SchemaItem>

    suspend fun selectedSchemaId(): String

    suspend fun selectSchema(schemaId: String): Boolean

    suspend fun currentSchema(): RimeSchema

    suspend fun commitComposition(): Boolean

    suspend fun commitCurrentSelection(append: String = ""): Boolean

    suspend fun clearComposition()

    suspend fun setInput(input: String, emit: Boolean = true): Boolean

    suspend fun appendInput(input: String, emit: Boolean = true): Boolean

    suspend fun freeContext()

    suspend fun getRawInput(): String

    suspend fun getInputConfirmedPosition(): Int

    suspend fun setRuntimeOption(
        option: String,
        value: Boolean,
    )

    suspend fun getRuntimeOption(option: String): Boolean

    suspend fun getCandidates(
        startIndex: Int,
        limit: Int,
    ): Array<CandidateProto>
}
