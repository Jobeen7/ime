package com.jobeen.ime

import com.jobeen.ime.base.speech.selectSpeechModelAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 语音模型选包纯函数测试：只认 int8 家族 + 文件名日期，取最新一份。
 * 选包规则写死在 selectSpeechModelAsset，上游改命名时跟着改这里。
 */
class SpeechModelAssetSelectionTest {

    private val prefix =
        "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-"

    @Test
    fun emptyList_returnsNull() {
        assertNull(selectSpeechModelAsset(emptyList()))
    }

    @Test
    fun noFamilyMatch_returnsNull() {
        assertNull(
            selectSpeechModelAsset(
                listOf(
                    "sherpa-onnx-streaming-zipformer-zh-2023-02-23.tar.bz2",
                    "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-2026-06-05.tar.bz2",
                    "something-else.tar.bz2",
                )
            )
        )
    }

    @Test
    fun fp32Only_returnsNull() {
        // 同家族的 fp32 版不带 -int8- 段，不能被误选
        assertNull(
            selectSpeechModelAsset(
                listOf(
                    "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-2026-06-05.tar.bz2",
                )
            )
        )
    }

    @Test
    fun singleMatch_returned() {
        val name = prefix + "2026-06-05.tar.bz2"
        assertEquals(name, selectSpeechModelAsset(listOf(name)))
    }

    @Test
    fun multipleDates_picksNewest() {
        val older = prefix + "2025-12-01.tar.bz2"
        val newest = prefix + "2026-06-05.tar.bz2"
        val middle = prefix + "2026-01-20.tar.bz2"
        assertEquals(newest, selectSpeechModelAsset(listOf(older, newest, middle)))
        // 顺序无关
        assertEquals(newest, selectSpeechModelAsset(listOf(middle, older, newest)))
    }

    @Test
    fun otherFamiliesWithNewerDates_ignored() {
        val mine = prefix + "2026-06-05.tar.bz2"
        assertEquals(
            mine,
            selectSpeechModelAsset(
                listOf(
                    "sherpa-onnx-qnn-SM8650-binary-x-asr-streaming-zipformer-transducer-zh-en-punct-2099-01-01-chunk-size-160ms.tar.bz2",
                    "sherpa-onnx-sense-voice-zh-2099-12-31.tar.bz2",
                    mine,
                )
            ),
        )
    }

    @Test
    fun sameDate_picksLexicographicallyLarger() {
        val plain = prefix + "2026-06-05.tar.bz2"
        val suffixed = prefix + "x-2026-06-05.tar.bz2"
        assertEquals(suffixed, selectSpeechModelAsset(listOf(plain, suffixed)))
        assertEquals(suffixed, selectSpeechModelAsset(listOf(suffixed, plain)))
    }

    @Test
    fun invalidDates_skipped() {
        assertNull(selectSpeechModelAsset(listOf(prefix + "2026-13-05.tar.bz2")))
        assertNull(selectSpeechModelAsset(listOf(prefix + "2026-06-00.tar.bz2")))
        assertNull(selectSpeechModelAsset(listOf(prefix + "2026-06-32.tar.bz2")))
        // 非法日期不挡合法候选（哪怕合法的更旧）
        val valid = prefix + "2025-01-01.tar.bz2"
        assertEquals(
            valid,
            selectSpeechModelAsset(listOf(prefix + "2026-13-05.tar.bz2", valid)),
        )
    }

    @Test
    fun noDateOrWrongSuffix_skipped() {
        assertNull(selectSpeechModelAsset(listOf(prefix + "latest.tar.bz2")))
        assertNull(selectSpeechModelAsset(listOf(prefix + "2026-06-05.zip")))
        // 日期不在文件名末尾（后面还有段）不算
        assertNull(selectSpeechModelAsset(listOf(prefix + "2026-06-05-backup.tar.bz2")))
    }
}
