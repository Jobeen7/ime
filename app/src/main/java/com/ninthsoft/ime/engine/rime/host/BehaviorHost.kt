package com.ninthsoft.ime.engine.rime.host

import com.ninthsoft.ime.base.util.PinYinUtil
import com.ninthsoft.ime.engine.IBehaviorHost
import com.ninthsoft.ime.engine.behavior.IBehavior
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.UserSegmentSymbol
import com.ninthsoft.ime.engine.rime.behavior.Backspace
import com.ninthsoft.ime.engine.rime.behavior.InputKey
import com.ninthsoft.ime.engine.rime.behavior.InputString
import com.ninthsoft.ime.engine.rime.behavior.Reset
import com.ninthsoft.ime.engine.rime.behavior.RimeBehavior
import com.ninthsoft.ime.engine.rime.behavior.Segmentation
import com.ninthsoft.ime.engine.rime.behavior.SelectPinYin
import com.ninthsoft.ime.engine.rime.behavior.Selection
import com.ninthsoft.ime.engine.rime.core.IRimeJob
import timber.log.Timber

class BehaviorHost(val rimeJob: IRimeJob) : IBehaviorHost {
    private val emptyInput = ""
    private val symbols = listOf(".", ",", ";")

    // 已选择的拼音队列
    private val selectedPinYinQueue = ArrayDeque<CandidatePinYin>()

    // 输入内容的队列
    private val inputStringQueue = ArrayDeque<String>()

    // 键盘核心行为队列
    private val behaviorQueue = ArrayDeque<IBehavior>()

    /**
     * 等同于 fcitx5-android SidePanelKeyboard#updateRimeInput。
     * 将 buildRimeInput 构造的输入投递给 Rime 引擎。
     */
    private fun updateRimeInput(): Boolean {
        rimeJob.sendJob {
            val input = build()
            Timber.d("rimeJob.sendJob setInput: %s", input)
            if (input.startsWith("/")) {
                setInput(input)
                return@sendJob
            }
            val parts = inputParts(input)
            parts.forEachIndexed { index, item ->
                val emit = index + 1 == parts.size
                when (true) {
                    (item in symbols) -> {
                        if (index == 0) setInput(emptyInput, false)
                        simulateKeySequence(sequence = item)
                    }

                    (index == 0) -> setInput(input = item, emit)
                    else -> appendInput(input = item, emit)
                }
            }
        }
        return true
    }

    private fun inputParts(input: String): List<String> {
        if (input.isEmpty()) return listOf(input)
        val parts = mutableListOf<String>()
        val text = StringBuilder()
        for (char in input) {
            val value = char.toString()
            if (value in symbols) {
                if (text.isNotEmpty()) {
                    parts += text.toString()
                    text.clear()
                }
                parts += value
            } else {
                text.append(char)
            }
        }
        if (text.isNotEmpty()) parts += text.toString()
        return parts
    }

    override fun flowed(behavior: IBehavior): Boolean {
        if (behavior is RimeBehavior) {
            behavior.withRimeJob(rimeJob)
        }
        when (behavior) {
            // 文本字符输入，对应 fcitx5 FcitxKeyAction(NORMAL)：
            // 登记到 inputStringQueue 与 behaviorQueue，并把原按键交给 Rime 处理。
            is InputString -> {
                inputStringQueue.add(behavior.sequence)
                behaviorQueue.add(behavior)
            }

            // 非字符按键（功能键等），直接交给 Rime 处理，不需要刷新队列状态。
            is InputKey -> {
                behavior.invoke()
                return true
            }

            // 分隔符输入，对应 fcitx5 SEGMENT：
            // recontrolSegment 返回 true 时表示无需插入分隔符，直接结束；
            // 否则登记到队列，并通过 updateRimeInput 把分隔符推给 Rime。
            // 注：Segmentation.invoke() 为空实现，因此必须显式 updateRimeInput。
            is Segmentation -> {
                if (recontrolSegment()) return true
                inputStringQueue.add(segmentKey)
                behaviorQueue.add(behavior)
                return updateRimeInput()
            }

            // 选择候选拼音，对应 fcitx5 SELECT_PINYIN：
            // 只登记到队列并 updateRimeInput；SelectPinYin.invoke() 为空。
            is SelectPinYin -> {
                selectedPinYinQueue.add(behavior.pinYin)
                behaviorQueue.add(behavior)
                return updateRimeInput()
            }

            // 退格键，对应 fcitx5 SymAction(BackSpace)：
            // recontrolBackspace 返回 true 时表示在队列内消化掉 BackSpace，
            // 需 updateRimeInput；
            // 否则把 BackSpace 真正发给 Rime 处理（不刷新队列状态）。
            is Backspace -> {
                if (recontrolBackspace()) {
                    return updateRimeInput()
                }
                behavior.invoke()
                return true
            }

            // 重置输入：让 Rime clearComposition，并清空所有队列。
            is Reset -> {
                behavior.invoke()
                resetState()
                return true
            }

            // 选择候选词，对应 fcitx5 SELECT_CANDIDATE：登记到队列并交给 Rime 选择。
            is Selection -> {
                behaviorQueue.add(behavior)
                behavior.invoke()
                return true
            }
        }
        return updateRimeInput()
    }

    /**
     * 等同于 fcitx5-android SidePanelKeyboard#recontrolBackspace。
     * 返回 true 表示已在队列内消化掉 BackSpace（不转交 Rime）；false 表示转交 Rime 处理。
     */
    private fun recontrolBackspace(): Boolean {
        if (behaviorQueue.isEmpty()) return false
        return when (behaviorQueue.removeLast()) {
            is SelectPinYin -> {
                if (selectedPinYinQueue.isNotEmpty()) {
                    selectedPinYinQueue.removeLast()
                    true
                } else {
                    false
                }
            }

            is Selection -> false

            else -> {
                inputStringQueue.removeLast()
                false
            }
        }
    }

    /**
     * 等同于 fcitx5-android SidePanelKeyboard#recontrolSegment。
     * 返回 true 表示拒绝该 Segmentation；false 表示允许插入分隔符。
     */
    private fun recontrolSegment(): Boolean {
        if (inputStringQueue.isEmpty()) return true
        if (behaviorQueue.last() is Segmentation) return true
        var selectedSize = 0
        selectedPinYinQueue.forEach { selectedSize += it.pinYin.length }
        return selectedSize == inputStringQueue.size
    }

    override fun resetState() {
        selectedPinYinQueue.clear()
        inputStringQueue.clear()
        behaviorQueue.clear()
    }

    /**
     * 等同于 fcitx5-android SidePanelKeyboard#buildPossibleCombinations。
     * 依据 Rime 当前输入（currentInput，对应 fcitx getRimeInput）和已确认位置
     * （confirmedLen，对应 fcitx getRimeInputConfirmPosition === RimeApi.getInputConfirmedPosition）
     * 构造可能候选拼音。手动选择拼音时插入的分隔符会修正 confirmedLen。
     */
    fun possiblePinYin(
        candidatePinYinType: String, currentInput: String, confirmedLen: Int
    ): List<CandidatePinYin> {
        if (inputStringQueue.isEmpty()) return emptyList()
        // 因为手动选择拼音插入分隔符的缘故，此处需要先修正已确认的内容长度
        var len = confirmedLen
        var index = 0
        if (len > 0) {
            currentInput.forEachIndexed { i, char ->
                if (i >= confirmedLen) return@forEachIndexed
                val raw = inputStringQueue.getOrNull(index).toString()
                if (char == segmentKeyChar && segmentKey != raw) {
                    len -= 1
                    index += 1
                }
                index += 1
            }
        }
        val position = nextSequencePosition(len)
        if (position < 0) return emptyList()
        val sequence = inputStringQueue.joinToString("").substring(position)
        return PinYinUtil.possibleCombinations(candidatePinYinType, sequence).map { pinYin ->
            var raw = sequence.substring(0, pinYin.length)
            // 如果候选拼音以分词标记结束，必须把分词标记加入 raw
            if (segmentKeyChar == sequence.getOrNull(pinYin.length)) {
                raw += segmentKey
            }
            CandidatePinYin(pinYin, raw, position)
        }
    }

    /**
     * 等同于 fcitx5-android SidePanelKeyboard#nextSequencePosition。
     * 从修正后的 confirmedLen 开始跳过所有已选中的拼音区间，找到下一个空闲输入位置。
     */
    private fun nextSequencePosition(confirmedLen: Int): Int {
        val inputSize = inputStringQueue.size
        val ranges = selectedPinYinQueue.map { it.position until (it.position + it.raw.length) }
            .sortedBy { it.first }
        // 合法性校验：confirmedLen 不能落在任何已选中的拼音区间内部
        for (r in ranges) {
            if (confirmedLen > r.first && confirmedLen < r.last + 1) return -1
        }
        // 从 confirmedLen 开始寻找 next free position
        var pos = confirmedLen.coerceIn(0, inputSize)
        while (pos < inputSize) {
            var jumped = false
            for (r in ranges) {
                if (pos in r) {
                    pos = r.last + 1
                    jumped = true
                    break
                }
            }
            if (!jumped) return pos
        }
        return pos
    }

    /**
     * 等同于 fcitx5-android SidePanelKeyboard#buildRimeInput。
     * 根据 inputStringQueue 与 selectedPinYinQueue 构造要投递给 Rime 的输入串。
     * 每个选中的拼音会替换原 raw，并在其后追加 segmentKeyChar 作为分隔符。
     */
    fun build(): String {
        val input = inputStringQueue.joinToString("")
        if (selectedPinYinQueue.isEmpty()) return input
        val first = selectedPinYinQueue.first()
        val last = selectedPinYinQueue.last()
        val start = first.position
        val end = last.position + last.raw.length
        if (start < 0 || end > input.length) return input
        val result = StringBuilder().append(input.substring(0, start))
        var cursor = start
        for (entry in selectedPinYinQueue) {
            if (entry.position > cursor) {
                result.append(input.substring(cursor, entry.position))
            }
            val rawEnd = entry.position + entry.raw.length
            if (rawEnd <= input.length && input.regionMatches(
                    entry.position, entry.raw, 0, entry.raw.length
                )
            ) {
                result.append(entry.pinYin)
                result.append(segmentKeyChar)
            } else {
                result.append(input.substring(entry.position, rawEnd))
            }
            cursor = rawEnd
        }
        return result.append(input.substring(end)).toString()
    }

    companion object {
        // 分隔符（选择拼音时插入的分隔符），字符串形式
        const val segmentKey = UserSegmentSymbol.toString()

        // 与 segmentKey 同义，字符形式，便于逐字符比对
        var segmentKeyChar: Char = segmentKey.toCharArray().first()
    }
}
