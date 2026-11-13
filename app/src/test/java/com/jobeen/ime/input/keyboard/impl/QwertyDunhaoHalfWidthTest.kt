package com.jobeen.ime.input.keyboard.impl

import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * V-低2 缺省解钉住测试：WO33 补表后，半角模式下文案为「、」的键，
 * 提交与键面一致地出半角形「､」——这是有意为之的半角口径，
 * 不是偶然如此；由他一票否决（若只想要符号栏变、打字不变，
 * 须把符号栏改走自有映射、两表回退，本测试随之改）。
 *
 * 两条链（均只读核实，未改行为）：
 * 提交链：QwertyKeyboard.onAction → transformAction(CommitAction,
 * punctuationState == FullWidth) → PunctuationUtil.convert(:129)，
 * 半角时 FULL_TO_HALF['、']='､'。
 * 键面链：BaseKeyboard.updatePunctuationMode(:1277-1284) 逐键
 * updateMode → TextKeyView:1440／AltTextKeyView:1553/1558/1563／
 * ImageTextKeyView:1924 → CharacterSet.halfWidth（full2half 由
 * half2full 反推，'、'→'､'）。
 * （复查报告引的 KeyView:1453 实为 TextKeyView init 内 setTextSize
 * 行，不是转换行；转换行以 grep 实测为准，见上。）
 *
 * CharacterSet.convert 是整串精确匹配（多字符标签不逐字转），
 * 故本文件只钉单字符顿号键的实际情形，不把多字符行为写成断言。
 */
class QwertyDunhaoHalfWidthTest {

    private fun transform(
        action: KeyboardAction,
        caps: QwertyKeyboard.CapsState = QwertyKeyboard.CapsState.None,
        fullWidth: Boolean = true,
    ) = QwertyKeyboard.transformAction(action, caps, fullWidth)

    @Test
    fun dunhaoCommit_halfWidthSubmitsHalfForm() {
        val (forwarded, capsTarget) = transform(
            KeyboardAction.CommitAction("、"), fullWidth = false,
        )
        assertEquals(KeyboardAction.CommitAction("､"), forwarded)
        assertNull(capsTarget)
    }

    @Test
    fun dunhaoCommit_fullWidthSubmitsFullForm() {
        val (forwarded, capsTarget) = transform(
            KeyboardAction.CommitAction("、"), fullWidth = true,
        )
        assertEquals(KeyboardAction.CommitAction("、"), forwarded)
        assertNull(capsTarget)
    }

    @Test
    fun dunhaoFace_halfWidthShowsHalfForm() {
        // 键面：半角显「､」，全角显「、」；已是目标形态原样。
        assertEquals("､", PunctuationMode.CharacterSet.halfWidth("、"))
        assertEquals("、", PunctuationMode.CharacterSet.fullWidth("､"))
        assertEquals("、", PunctuationMode.CharacterSet.fullWidth("、"))
        assertEquals("､", PunctuationMode.CharacterSet.halfWidth("､"))
    }

    @Test
    fun dunhaoCommitAndFaceAgreeInHalfWidth() {
        // 同一键在半角模式下“提交什么，键面就显示什么”。
        val (forwarded, _) = transform(
            KeyboardAction.CommitAction("、"), fullWidth = false,
        )
        val committed = (forwarded as KeyboardAction.CommitAction).text
        assertEquals(PunctuationMode.CharacterSet.halfWidth("、"), committed)
        assertEquals("､", committed)
    }

    @Test
    fun dunhaoCommitAndFaceAgreeInFullWidth() {
        val (forwarded, _) = transform(
            KeyboardAction.CommitAction("、"), fullWidth = true,
        )
        val committed = (forwarded as KeyboardAction.CommitAction).text
        assertEquals(PunctuationMode.CharacterSet.fullWidth("、"), committed)
        assertEquals("、", committed)
    }

    @Test
    fun dunhaoLiteralChannelUnaffected() {
        // 字面通道（裁定 B2）绕行标点转换：补表不得漏进 LiteralCommit。
        val (half, _) = transform(
            KeyboardAction.LiteralCommitAction("、"), fullWidth = false,
        )
        assertEquals(KeyboardAction.LiteralCommitAction("、"), half)
        val (full, _) = transform(
            KeyboardAction.LiteralCommitAction("、"), fullWidth = true,
        )
        assertEquals(KeyboardAction.LiteralCommitAction("、"), full)
    }

    @Test
    fun dunhaoCommit_ignoresCapsState() {
        // CommitAction 不消费 Caps：大写态下半角顿号仍出「､」。
        val (forwarded, capsTarget) = transform(
            KeyboardAction.CommitAction("、"),
            caps = QwertyKeyboard.CapsState.Once,
            fullWidth = false,
        )
        assertEquals(KeyboardAction.CommitAction("､"), forwarded)
        assertNull(capsTarget)
    }

    @Test
    fun dunhaoMixedString_halfWidthConvertsPerChar() {
        // PunctuationUtil 是逐字转换：串内顿号随模式换形，其余字符不动。
        val (half, _) = transform(
            KeyboardAction.CommitAction("甲、乙"), fullWidth = false,
        )
        assertEquals(KeyboardAction.CommitAction("甲､乙"), half)
        val (full, _) = transform(
            KeyboardAction.CommitAction("甲、乙"), fullWidth = true,
        )
        assertEquals(KeyboardAction.CommitAction("甲、乙"), full)
    }

    @Test
    fun neighborsUnchangedByDunhaoTable() {
        // 邻键口径未动：逗号／句号两模式行为与补表前一致。
        val (commaHalf, _) = transform(KeyboardAction.CommitAction(","), fullWidth = false)
        assertEquals(KeyboardAction.CommitAction(","), commaHalf)
        val (commaFull, _) = transform(KeyboardAction.CommitAction(","), fullWidth = true)
        assertEquals(KeyboardAction.CommitAction("，"), commaFull)
        val (periodHalf, _) = transform(KeyboardAction.CommitAction("。"), fullWidth = false)
        assertEquals(KeyboardAction.CommitAction("."), periodHalf)
        val (periodFull, _) = transform(KeyboardAction.CommitAction("。"), fullWidth = true)
        assertEquals(KeyboardAction.CommitAction("。"), periodFull)
    }
}
