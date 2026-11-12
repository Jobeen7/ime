package com.jobeen.ime.skin.runtime

import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.input.keyboard.key.alphabetKey
import com.jobeen.ime.input.keyboard.key.backspaceKey
import com.jobeen.ime.input.keyboard.key.capsLockKey
import com.jobeen.ime.input.keyboard.key.layoutSwitchKey
import com.jobeen.ime.input.keyboard.key.peroidKey
import com.jobeen.ime.input.keyboard.key.returnKey
import com.jobeen.ime.input.keyboard.key.schemaSwitchKey
import com.jobeen.ime.input.keyboard.key.spaceKey

/**
 * 内置最后一层布局（K-M4，fcitx 式 builtin）：
 * 出厂键盘构建失败时——默认布局本身缺失／无效，如皮肤覆写装配、
 * 偏好读取抛错——键盘工厂回退到这份硬编码布局，保证键盘仍可用。
 *
 * 纯数据：只用 KeyDef 构造器，不读 Context、不读偏好、不碰快照、
 * 不碰资源文件，调用它本身永不失败。形态与出厂 Qwerty 同款
 * （四行 10/9/9/5 键，行宽和与出厂一致），回车取 Normal 变体
 * （无皮肤可跟时无 Accent 色板可取，跟普通键）。
 *
 * 与出厂 Qwerty 的关系：键位／动作／id 逐项相同（由单测
 * BuiltinFallbackLayoutTest 锁死），出厂布局改键时此处同步改；
 * 本文件不引用 QwertyKeyboard，免得兜底路径依赖被兜底的对象。
 */
object BuiltinFallbackLayout {

    /** 兜底键盘的运行时名（与各出厂键盘 NAME 互不相同，不污染注册表）。 */
    const val NAME = "BuiltinFallback"

    /** 硬编码内置布局：每次调用新构建，无共享可变状态。 */
    fun layout(): List<List<KeyDef>> = listOf(
        listOf(
            alphabetKey("q", "1", id = KeyIds.Qwerty.KEY_Q),
            alphabetKey("w", "2", id = KeyIds.Qwerty.KEY_W),
            alphabetKey("e", "3", id = KeyIds.Qwerty.KEY_E),
            alphabetKey("r", "4", id = KeyIds.Qwerty.KEY_R),
            alphabetKey("t", "5", id = KeyIds.Qwerty.KEY_T),
            alphabetKey("y", "6", id = KeyIds.Qwerty.KEY_Y),
            alphabetKey("u", "7", id = KeyIds.Qwerty.KEY_U),
            alphabetKey("i", "8", id = KeyIds.Qwerty.KEY_I),
            alphabetKey("o", "9", id = KeyIds.Qwerty.KEY_O),
            alphabetKey("p", "0", id = KeyIds.Qwerty.KEY_P),
        ),
        listOf(
            alphabetKey("a", "~", id = KeyIds.Qwerty.KEY_A),
            alphabetKey("s", "!", id = KeyIds.Qwerty.KEY_S),
            alphabetKey("d", "@", id = KeyIds.Qwerty.KEY_D),
            alphabetKey("f", "#", id = KeyIds.Qwerty.KEY_F),
            alphabetKey("g", "$", id = KeyIds.Qwerty.KEY_G, mainTextTranslationY = -2),
            alphabetKey("h", "%", id = KeyIds.Qwerty.KEY_H),
            alphabetKey("j", "^", id = KeyIds.Qwerty.KEY_J, altTextTranslationY = 4),
            alphabetKey("k", "&", id = KeyIds.Qwerty.KEY_K),
            alphabetKey("l", "*", id = KeyIds.Qwerty.KEY_L, altTextTranslationY = 4),
        ),
        listOf(
            capsLockKey(id = KeyIds.Qwerty.KEY_CAPS),
            alphabetKey("z", "(", id = KeyIds.Qwerty.KEY_Z),
            alphabetKey("x", ")", id = KeyIds.Qwerty.KEY_X),
            alphabetKey("c", ":", id = KeyIds.Qwerty.KEY_C),
            alphabetKey("v", ";", id = KeyIds.Qwerty.KEY_V),
            alphabetKey("b", ",", id = KeyIds.Qwerty.KEY_B),
            alphabetKey("n", "?", id = KeyIds.Qwerty.KEY_N),
            alphabetKey("m", "/", id = KeyIds.Qwerty.KEY_M),
            backspaceKey(id = KeyIds.Qwerty.KEY_DELETE),
        ),
        listOf(
            layoutSwitchKey(
                "?123", NumberKeyboard.NAME,
                percentWidth = 0.15f, id = KeyIds.Qwerty.KEY_LAYOUT_NUMBER,
            ),
            peroidKey(percentWidth = 0.13f, id = KeyIds.Qwerty.KEY_PERIOD),
            spaceKey(percentWidth = 0.44f, id = KeyIds.Qwerty.KEY_SPACE),
            schemaSwitchKey(0.13f, id = KeyIds.Qwerty.KEY_SCHEMA),
            returnKey(
                percentWidth = 0.15f,
                variant = Variant.Normal,
                id = KeyIds.Qwerty.KEY_ENTER,
            ),
        ),
    )
}
