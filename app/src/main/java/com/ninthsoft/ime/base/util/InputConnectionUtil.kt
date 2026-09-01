package com.ninthsoft.ime.base.util

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_CTRL_LEFT
import android.view.KeyEvent.KEYCODE_SHIFT_LEFT
import android.view.KeyEvent.META_CTRL_LEFT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_SHIFT_LEFT_ON
import android.view.KeyEvent.META_SHIFT_ON

class InputConnectionUtil {

    companion object {
        fun sendCombinationKeyEvent(
            service: InputMethodService, keyCode: Int, ctrl: Boolean = false, shift: Boolean = false
        ) {
            sendCombinationKeyEvent(service.currentInputConnection, keyCode, ctrl, shift)
        }

        fun sendCombinationKeyEvent(
            ic: InputConnection?, keyCode: Int, ctrl: Boolean = false, shift: Boolean = false
        ) {
            ic ?: return
            val now = SystemClock.uptimeMillis()
            var meta = 0
            if (ctrl) meta = meta or META_CTRL_ON or META_CTRL_LEFT_ON
            if (shift) meta = meta or META_SHIFT_ON or META_SHIFT_LEFT_ON
            if (ctrl) ic.sendKeyEvent(
                KeyEvent(
                    now, now, ACTION_DOWN, KEYCODE_CTRL_LEFT, 0, 0
                )
            )
            if (shift) ic.sendKeyEvent(
                KeyEvent(
                    now, now, ACTION_DOWN, KEYCODE_SHIFT_LEFT, 0, 0
                )
            )
            ic.sendKeyEvent(KeyEvent(now, now, ACTION_DOWN, keyCode, 0, meta))
            ic.sendKeyEvent(KeyEvent(now, now, ACTION_UP, keyCode, 0, meta))
            if (shift) ic.sendKeyEvent(
                KeyEvent(
                    now, now, ACTION_UP, KEYCODE_SHIFT_LEFT, 0, 0
                )
            )
            if (ctrl) ic.sendKeyEvent(
                KeyEvent(
                    now, now, ACTION_UP, KEYCODE_CTRL_LEFT, 0, 0
                )
            )
        }
    }

}