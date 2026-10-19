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

    /**
     * 把一段文本作为引擎自产 Commit 消息送入通知管线：与 native 按键
     * 产生的 Commit 走同一队列（notificationQueue）、同一单线程消费
     * 者分发，入队顺序即 rime-main 上任务的执行顺序，全局严格 FIFO。
     * 供组字为空时直通上屏空格/回车用——这类上屏若绕过管线直送 UI
     * 层，会超车上一键尚在管线中的 native Commit（其转换/分发含补取
     * 等待），造成上屏文字顺序错乱。所产消息带直送标记：下游只参与
     * 排序与落屏，不按「打字上屏」记账（不失效候选/前文缓存、不触发
     * 搭配学习与联想），与旧直送路径的下游行为等价。
     */
    suspend fun emitCommit(text: String)

    /**
     * 把一个按键作为引擎自产 Key 消息送入通知管线：与 [emitCommit]
     * 同口径，在 rime-main 上经同一队列入队、与 native 按键响应严格
     * FIFO。供引擎侧合成的「未处理按键」借道 KeyMessage 透传路径
     * （RimeEngine.forwardUnhandledKey）落到宿主用，如空组字退格——
     * 不经 native processKey，避免被方案处理器吞掉后宿主收不到键；
     * [isVirtual] 恒传 true，否则透传点会按内部合成键丢弃。
     */
    suspend fun emitKey(value: Int, modifiers: Int, isVirtual: Boolean)

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
