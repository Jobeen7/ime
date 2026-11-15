package com.jobeen.ime.engine.rime.core

import android.view.KeyEvent

object KeyMapping {
    const val Key_space: Int = 0x0020

    const val Key_numbersign: Int = 0x0023

    const val Key_apostrophe: Int = 0x0027

    const val Key_asterisk: Int = 0x002a

    const val Key_plus: Int = 0x002b

    const val Key_comma: Int = 0x002c

    const val Key_minus: Int = 0x002d

    const val Key_period: Int = 0x002e

    const val Key_slash: Int = 0x002f

    const val Key_0: Int = 0x0030

    const val Key_1: Int = 0x0031

    const val Key_2: Int = 0x0032

    const val Key_3: Int = 0x0033

    const val Key_4: Int = 0x0034

    const val Key_5: Int = 0x0035

    const val Key_6: Int = 0x0036

    const val Key_7: Int = 0x0037

    const val Key_8: Int = 0x0038

    const val Key_9: Int = 0x0039

    const val Key_semicolon: Int = 0x003b

    const val Key_equal: Int = 0x003d

    const val Key_at: Int = 0x0040

    const val Key_A: Int = 0x0041

    const val Key_B: Int = 0x0042

    const val Key_C: Int = 0x0043

    const val Key_D: Int = 0x0044

    const val Key_E: Int = 0x0045

    const val Key_F: Int = 0x0046

    const val Key_G: Int = 0x0047

    const val Key_H: Int = 0x0048

    const val Key_I: Int = 0x0049

    const val Key_J: Int = 0x004a

    const val Key_K: Int = 0x004b

    const val Key_L: Int = 0x004c

    const val Key_M: Int = 0x004d

    const val Key_N: Int = 0x004e

    const val Key_O: Int = 0x004f

    const val Key_P: Int = 0x0050

    const val Key_Q: Int = 0x0051

    const val Key_R: Int = 0x0052

    const val Key_S: Int = 0x0053

    const val Key_T: Int = 0x0054

    const val Key_U: Int = 0x0055

    const val Key_V: Int = 0x0056

    const val Key_W: Int = 0x0057

    const val Key_X: Int = 0x0058

    const val Key_Y: Int = 0x0059

    const val Key_Z: Int = 0x005a

    const val Key_bracketleft: Int = 0x005b

    const val Key_backslash: Int = 0x005c

    const val Key_bracketright: Int = 0x005d

    const val Key_grave: Int = 0x0060

    const val Key_a: Int = 0x0061

    const val Key_b: Int = 0x0062

    const val Key_c: Int = 0x0063

    const val Key_d: Int = 0x0064

    const val Key_e: Int = 0x0065

    const val Key_f: Int = 0x0066

    const val Key_g: Int = 0x0067

    const val Key_h: Int = 0x0068

    const val Key_i: Int = 0x0069

    const val Key_j: Int = 0x006a

    const val Key_k: Int = 0x006b

    const val Key_l: Int = 0x006c

    const val Key_m: Int = 0x006d

    const val Key_n: Int = 0x006e

    const val Key_o: Int = 0x006f

    const val Key_p: Int = 0x0070

    const val Key_q: Int = 0x0071

    const val Key_r: Int = 0x0072

    const val Key_s: Int = 0x0073

    const val Key_t: Int = 0x0074

    const val Key_u: Int = 0x0075

    const val Key_v: Int = 0x0076

    const val Key_w: Int = 0x0077

    const val Key_x: Int = 0x0078

    const val Key_y: Int = 0x0079

    const val Key_z: Int = 0x007a

    const val Key_F1: Int = 0xffbe

    const val Key_F2: Int = 0xffbf

    const val Key_F3: Int = 0xffc0

    const val Key_F4: Int = 0xffc1

    const val Key_F5: Int = 0xffc2

    const val Key_F6: Int = 0xffc3

    const val Key_F7: Int = 0xffc4

    const val Key_F8: Int = 0xffc5

    const val Key_F9: Int = 0xffc6

    const val Key_F10: Int = 0xffc7

    const val Key_F11: Int = 0xffc8

    const val Key_F12: Int = 0xffc9

    const val Key_Shift_L: Int = 0xffe1

    const val Key_Shift_R: Int = 0xffe2

    const val Key_Control_L: Int = 0xffe3

    const val Key_Control_R: Int = 0xffe4

    const val Key_Caps_Lock: Int = 0xffe5

    const val Key_Meta_L: Int = 0xffe7

    const val Key_Meta_R: Int = 0xffe8

    const val Key_Alt_L: Int = 0xffe9

    const val Key_Alt_R: Int = 0xffea

    const val Key_Insert: Int = 0xff63

    const val Key_Delete: Int = 0xffff

    const val Key_Home: Int = 0xff50

    const val Key_End: Int = 0xff57

    const val Key_Page_Down: Int = 0xff56

    const val Key_Page_Up: Int = 0xff55

    const val Key_Tab: Int = 0xff09

    const val Key_BackSpace: Int = 0xff08

    const val Key_Return: Int = 0xff0d

    const val Key_Escape: Int = 0xff1b

    const val Key_Up: Int = 0xff52

    const val Key_Down: Int = 0xff54

    const val Key_Left: Int = 0xff51

    const val Key_Right: Int = 0xff53

    const val Key_KP_Divide: Int = 0xffaf

    const val Key_KP_Multiply: Int = 0xffaa

    const val Key_KP_Subtract: Int = 0xffad

    const val Key_KP_7: Int = 0xffb7

    const val Key_KP_8: Int = 0xffb8

    const val Key_KP_9: Int = 0xffb9

    const val Key_KP_Add: Int = 0xffab

    const val Key_KP_4: Int = 0xffb4

    const val Key_KP_5: Int = 0xffb5

    const val Key_KP_6: Int = 0xffb6

    const val Key_KP_1: Int = 0xffb1

    const val Key_KP_2: Int = 0xffb2

    const val Key_KP_3: Int = 0xffb3

    const val Key_KP_Enter: Int = 0xff8d

    const val Key_KP_0: Int = 0xffb0

    const val Key_KP_Decimal: Int = 0xffae

    const val Key_Eisu_toggle: Int = 0xff30

    const val Key_Kana_Lock: Int = 0xff2d

    const val Key_Hiragana_Katakana: Int = 0xff27

    const val Key_Zenkaku_Hankaku: Int = 0xff2a

    const val Key_VoidSymbol: Int = 0xffffff

    @JvmStatic
    fun valToKeyCode(v: Int): Int {
        return when (v) {
            Key_space -> KeyEvent.KEYCODE_SPACE
            Key_numbersign -> KeyEvent.KEYCODE_POUND
            Key_apostrophe -> KeyEvent.KEYCODE_APOSTROPHE
            Key_asterisk -> KeyEvent.KEYCODE_STAR
            Key_plus -> KeyEvent.KEYCODE_PLUS
            Key_comma -> KeyEvent.KEYCODE_COMMA
            Key_minus -> KeyEvent.KEYCODE_MINUS
            Key_period -> KeyEvent.KEYCODE_PERIOD
            Key_slash -> KeyEvent.KEYCODE_SLASH
            Key_0 -> KeyEvent.KEYCODE_0
            Key_1 -> KeyEvent.KEYCODE_1
            Key_2 -> KeyEvent.KEYCODE_2
            Key_3 -> KeyEvent.KEYCODE_3
            Key_4 -> KeyEvent.KEYCODE_4
            Key_5 -> KeyEvent.KEYCODE_5
            Key_6 -> KeyEvent.KEYCODE_6
            Key_7 -> KeyEvent.KEYCODE_7
            Key_8 -> KeyEvent.KEYCODE_8
            Key_9 -> KeyEvent.KEYCODE_9
            Key_semicolon -> KeyEvent.KEYCODE_SEMICOLON
            Key_equal -> KeyEvent.KEYCODE_EQUALS
            Key_at -> KeyEvent.KEYCODE_AT
            Key_A -> KeyEvent.KEYCODE_A
            Key_B -> KeyEvent.KEYCODE_B
            Key_C -> KeyEvent.KEYCODE_C
            Key_D -> KeyEvent.KEYCODE_D
            Key_E -> KeyEvent.KEYCODE_E
            Key_F -> KeyEvent.KEYCODE_F
            Key_G -> KeyEvent.KEYCODE_G
            Key_H -> KeyEvent.KEYCODE_H
            Key_I -> KeyEvent.KEYCODE_I
            Key_J -> KeyEvent.KEYCODE_J
            Key_K -> KeyEvent.KEYCODE_K
            Key_L -> KeyEvent.KEYCODE_L
            Key_M -> KeyEvent.KEYCODE_M
            Key_N -> KeyEvent.KEYCODE_N
            Key_O -> KeyEvent.KEYCODE_O
            Key_P -> KeyEvent.KEYCODE_P
            Key_Q -> KeyEvent.KEYCODE_Q
            Key_R -> KeyEvent.KEYCODE_R
            Key_S -> KeyEvent.KEYCODE_S
            Key_T -> KeyEvent.KEYCODE_T
            Key_U -> KeyEvent.KEYCODE_U
            Key_V -> KeyEvent.KEYCODE_V
            Key_W -> KeyEvent.KEYCODE_W
            Key_X -> KeyEvent.KEYCODE_X
            Key_Y -> KeyEvent.KEYCODE_Y
            Key_Z -> KeyEvent.KEYCODE_Z
            Key_bracketleft -> KeyEvent.KEYCODE_LEFT_BRACKET
            Key_backslash -> KeyEvent.KEYCODE_BACKSLASH
            Key_bracketright -> KeyEvent.KEYCODE_RIGHT_BRACKET
            Key_grave -> KeyEvent.KEYCODE_GRAVE
            Key_a -> KeyEvent.KEYCODE_A
            Key_b -> KeyEvent.KEYCODE_B
            Key_c -> KeyEvent.KEYCODE_C
            Key_d -> KeyEvent.KEYCODE_D
            Key_e -> KeyEvent.KEYCODE_E
            Key_f -> KeyEvent.KEYCODE_F
            Key_g -> KeyEvent.KEYCODE_G
            Key_h -> KeyEvent.KEYCODE_H
            Key_i -> KeyEvent.KEYCODE_I
            Key_j -> KeyEvent.KEYCODE_J
            Key_k -> KeyEvent.KEYCODE_K
            Key_l -> KeyEvent.KEYCODE_L
            Key_m -> KeyEvent.KEYCODE_M
            Key_n -> KeyEvent.KEYCODE_N
            Key_o -> KeyEvent.KEYCODE_O
            Key_p -> KeyEvent.KEYCODE_P
            Key_q -> KeyEvent.KEYCODE_Q
            Key_r -> KeyEvent.KEYCODE_R
            Key_s -> KeyEvent.KEYCODE_S
            Key_t -> KeyEvent.KEYCODE_T
            Key_u -> KeyEvent.KEYCODE_U
            Key_v -> KeyEvent.KEYCODE_V
            Key_w -> KeyEvent.KEYCODE_W
            Key_x -> KeyEvent.KEYCODE_X
            Key_y -> KeyEvent.KEYCODE_Y
            Key_z -> KeyEvent.KEYCODE_Z
            Key_F1 -> KeyEvent.KEYCODE_F1
            Key_F2 -> KeyEvent.KEYCODE_F2
            Key_F3 -> KeyEvent.KEYCODE_F3
            Key_F4 -> KeyEvent.KEYCODE_F4
            Key_F5 -> KeyEvent.KEYCODE_F5
            Key_F6 -> KeyEvent.KEYCODE_F6
            Key_F7 -> KeyEvent.KEYCODE_F7
            Key_F8 -> KeyEvent.KEYCODE_F8
            Key_F9 -> KeyEvent.KEYCODE_F9
            Key_F10 -> KeyEvent.KEYCODE_F10
            Key_F11 -> KeyEvent.KEYCODE_F11
            Key_F12 -> KeyEvent.KEYCODE_F12
            Key_Shift_L -> KeyEvent.KEYCODE_SHIFT_LEFT
            Key_Shift_R -> KeyEvent.KEYCODE_SHIFT_RIGHT
            Key_Control_L -> KeyEvent.KEYCODE_CTRL_LEFT
            Key_Control_R -> KeyEvent.KEYCODE_CTRL_RIGHT
            Key_Caps_Lock -> KeyEvent.KEYCODE_CAPS_LOCK
            Key_Meta_L -> KeyEvent.KEYCODE_META_LEFT
            Key_Meta_R -> KeyEvent.KEYCODE_META_RIGHT
            Key_Alt_L -> KeyEvent.KEYCODE_ALT_LEFT
            Key_Alt_R -> KeyEvent.KEYCODE_ALT_RIGHT
            Key_Insert -> KeyEvent.KEYCODE_INSERT
            Key_Delete -> KeyEvent.KEYCODE_FORWARD_DEL
            Key_Home -> KeyEvent.KEYCODE_MOVE_HOME
            Key_End -> KeyEvent.KEYCODE_MOVE_END
            Key_Page_Down -> KeyEvent.KEYCODE_PAGE_DOWN
            Key_Page_Up -> KeyEvent.KEYCODE_PAGE_UP
            Key_Tab -> KeyEvent.KEYCODE_TAB
            Key_BackSpace -> KeyEvent.KEYCODE_DEL
            Key_Return -> KeyEvent.KEYCODE_ENTER
            Key_Escape -> KeyEvent.KEYCODE_ESCAPE
            Key_Up -> KeyEvent.KEYCODE_DPAD_UP
            Key_Down -> KeyEvent.KEYCODE_DPAD_DOWN
            Key_Left -> KeyEvent.KEYCODE_DPAD_LEFT
            Key_Right -> KeyEvent.KEYCODE_DPAD_RIGHT
            Key_KP_Divide -> KeyEvent.KEYCODE_NUMPAD_DIVIDE
            Key_KP_Multiply -> KeyEvent.KEYCODE_NUMPAD_MULTIPLY
            Key_KP_Subtract -> KeyEvent.KEYCODE_NUMPAD_SUBTRACT
            Key_KP_7 -> KeyEvent.KEYCODE_NUMPAD_7
            Key_KP_8 -> KeyEvent.KEYCODE_NUMPAD_8
            Key_KP_9 -> KeyEvent.KEYCODE_NUMPAD_9
            Key_KP_Add -> KeyEvent.KEYCODE_NUMPAD_ADD
            Key_KP_4 -> KeyEvent.KEYCODE_NUMPAD_4
            Key_KP_5 -> KeyEvent.KEYCODE_NUMPAD_5
            Key_KP_6 -> KeyEvent.KEYCODE_NUMPAD_6
            Key_KP_1 -> KeyEvent.KEYCODE_NUMPAD_1
            Key_KP_2 -> KeyEvent.KEYCODE_NUMPAD_2
            Key_KP_3 -> KeyEvent.KEYCODE_NUMPAD_3
            Key_KP_Enter -> KeyEvent.KEYCODE_NUMPAD_ENTER
            Key_KP_0 -> KeyEvent.KEYCODE_NUMPAD_0
            Key_KP_Decimal -> KeyEvent.KEYCODE_NUMPAD_DOT
            Key_Eisu_toggle -> KeyEvent.KEYCODE_EISU
            Key_Kana_Lock -> KeyEvent.KEYCODE_KANA
            Key_Hiragana_Katakana -> KeyEvent.KEYCODE_KATAKANA_HIRAGANA
            Key_Zenkaku_Hankaku -> KeyEvent.KEYCODE_ZENKAKU_HANKAKU
            Key_VoidSymbol -> KeyEvent.KEYCODE_UNKNOWN
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

    /**
     * Duplicate labels are expected, as the mapping is not one-to-one
     */
    @JvmStatic
    fun keyCodeToVal(code: Int): Int {
        return when (code) {
            KeyEvent.KEYCODE_SPACE -> Key_space
            KeyEvent.KEYCODE_POUND -> Key_numbersign
            KeyEvent.KEYCODE_APOSTROPHE -> Key_apostrophe
            KeyEvent.KEYCODE_STAR -> Key_asterisk
            KeyEvent.KEYCODE_PLUS -> Key_plus
            KeyEvent.KEYCODE_COMMA -> Key_comma
            KeyEvent.KEYCODE_MINUS -> Key_minus
            KeyEvent.KEYCODE_PERIOD -> Key_period
            KeyEvent.KEYCODE_SLASH -> Key_slash
            KeyEvent.KEYCODE_0 -> Key_0
            KeyEvent.KEYCODE_1 -> Key_1
            KeyEvent.KEYCODE_2 -> Key_2
            KeyEvent.KEYCODE_3 -> Key_3
            KeyEvent.KEYCODE_4 -> Key_4
            KeyEvent.KEYCODE_5 -> Key_5
            KeyEvent.KEYCODE_6 -> Key_6
            KeyEvent.KEYCODE_7 -> Key_7
            KeyEvent.KEYCODE_8 -> Key_8
            KeyEvent.KEYCODE_9 -> Key_9
            KeyEvent.KEYCODE_SEMICOLON -> Key_semicolon
            KeyEvent.KEYCODE_EQUALS -> Key_equal
            KeyEvent.KEYCODE_AT -> Key_at
            KeyEvent.KEYCODE_LEFT_BRACKET -> Key_bracketleft
            KeyEvent.KEYCODE_BACKSLASH -> Key_backslash
            KeyEvent.KEYCODE_RIGHT_BRACKET -> Key_bracketright
            KeyEvent.KEYCODE_GRAVE -> Key_grave
            KeyEvent.KEYCODE_A -> Key_a
            KeyEvent.KEYCODE_B -> Key_b
            KeyEvent.KEYCODE_C -> Key_c
            KeyEvent.KEYCODE_D -> Key_d
            KeyEvent.KEYCODE_E -> Key_e
            KeyEvent.KEYCODE_F -> Key_f
            KeyEvent.KEYCODE_G -> Key_g
            KeyEvent.KEYCODE_H -> Key_h
            KeyEvent.KEYCODE_I -> Key_i
            KeyEvent.KEYCODE_J -> Key_j
            KeyEvent.KEYCODE_K -> Key_k
            KeyEvent.KEYCODE_L -> Key_l
            KeyEvent.KEYCODE_M -> Key_m
            KeyEvent.KEYCODE_N -> Key_n
            KeyEvent.KEYCODE_O -> Key_o
            KeyEvent.KEYCODE_P -> Key_p
            KeyEvent.KEYCODE_Q -> Key_q
            KeyEvent.KEYCODE_R -> Key_r
            KeyEvent.KEYCODE_S -> Key_s
            KeyEvent.KEYCODE_T -> Key_t
            KeyEvent.KEYCODE_U -> Key_u
            KeyEvent.KEYCODE_V -> Key_v
            KeyEvent.KEYCODE_W -> Key_w
            KeyEvent.KEYCODE_X -> Key_x
            KeyEvent.KEYCODE_Y -> Key_y
            KeyEvent.KEYCODE_Z -> Key_z
            KeyEvent.KEYCODE_F1 -> Key_F1
            KeyEvent.KEYCODE_F2 -> Key_F2
            KeyEvent.KEYCODE_F3 -> Key_F3
            KeyEvent.KEYCODE_F4 -> Key_F4
            KeyEvent.KEYCODE_F5 -> Key_F5
            KeyEvent.KEYCODE_F6 -> Key_F6
            KeyEvent.KEYCODE_F7 -> Key_F7
            KeyEvent.KEYCODE_F8 -> Key_F8
            KeyEvent.KEYCODE_F9 -> Key_F9
            KeyEvent.KEYCODE_F10 -> Key_F10
            KeyEvent.KEYCODE_F11 -> Key_F11
            KeyEvent.KEYCODE_F12 -> Key_F12
            KeyEvent.KEYCODE_SHIFT_LEFT -> Key_Shift_L
            KeyEvent.KEYCODE_SHIFT_RIGHT -> Key_Shift_R
            KeyEvent.KEYCODE_CTRL_LEFT -> Key_Control_L
            KeyEvent.KEYCODE_CTRL_RIGHT -> Key_Control_R
            KeyEvent.KEYCODE_CAPS_LOCK -> Key_Caps_Lock
            KeyEvent.KEYCODE_META_LEFT -> Key_Meta_L
            KeyEvent.KEYCODE_META_RIGHT -> Key_Meta_R
            KeyEvent.KEYCODE_ALT_LEFT -> Key_Alt_L
            KeyEvent.KEYCODE_ALT_RIGHT -> Key_Alt_R
            KeyEvent.KEYCODE_INSERT -> Key_Insert
            KeyEvent.KEYCODE_FORWARD_DEL -> Key_Delete
            KeyEvent.KEYCODE_MOVE_HOME -> Key_Home
            KeyEvent.KEYCODE_MOVE_END -> Key_End
            KeyEvent.KEYCODE_PAGE_DOWN -> Key_Page_Down
            KeyEvent.KEYCODE_PAGE_UP -> Key_Page_Up
            KeyEvent.KEYCODE_TAB -> Key_Tab
            KeyEvent.KEYCODE_DEL -> Key_BackSpace
            KeyEvent.KEYCODE_ENTER -> Key_Return
            KeyEvent.KEYCODE_ESCAPE -> Key_Escape
            KeyEvent.KEYCODE_DPAD_UP -> Key_Up
            KeyEvent.KEYCODE_DPAD_DOWN -> Key_Down
            KeyEvent.KEYCODE_DPAD_LEFT -> Key_Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> Key_Right
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> Key_KP_Divide
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> Key_KP_Multiply
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> Key_KP_Subtract
            KeyEvent.KEYCODE_NUMPAD_7 -> Key_KP_7
            KeyEvent.KEYCODE_NUMPAD_8 -> Key_KP_8
            KeyEvent.KEYCODE_NUMPAD_9 -> Key_KP_9
            KeyEvent.KEYCODE_NUMPAD_ADD -> Key_KP_Add
            KeyEvent.KEYCODE_NUMPAD_4 -> Key_KP_4
            KeyEvent.KEYCODE_NUMPAD_5 -> Key_KP_5
            KeyEvent.KEYCODE_NUMPAD_6 -> Key_KP_6
            KeyEvent.KEYCODE_NUMPAD_1 -> Key_KP_1
            KeyEvent.KEYCODE_NUMPAD_2 -> Key_KP_2
            KeyEvent.KEYCODE_NUMPAD_3 -> Key_KP_3
            KeyEvent.KEYCODE_NUMPAD_ENTER -> Key_KP_Enter
            KeyEvent.KEYCODE_NUMPAD_0 -> Key_KP_0
            KeyEvent.KEYCODE_NUMPAD_DOT -> Key_KP_Decimal
            KeyEvent.KEYCODE_EISU -> Key_Eisu_toggle
            KeyEvent.KEYCODE_KANA -> Key_Kana_Lock
            KeyEvent.KEYCODE_KATAKANA_HIRAGANA -> Key_Hiragana_Katakana
            KeyEvent.KEYCODE_ZENKAKU_HANKAKU -> Key_Zenkaku_Hankaku
            KeyEvent.KEYCODE_UNKNOWN -> Key_VoidSymbol
            else -> Key_VoidSymbol
        }
    }

}