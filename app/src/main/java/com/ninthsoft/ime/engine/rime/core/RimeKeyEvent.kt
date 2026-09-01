// SPDX-License-Identifier: Apache-2.0

package com.ninthsoft.ime.engine.rime.core

import com.ninthsoft.ime.engine.event.KeyModifiers

data class RimeKeyEvent(
    val value: Int,
    val modifiers: Int,
    val repr: String,
) {
    val keyVal by lazy { KeyValue(value) }
    val keyModifiers by lazy { KeyModifiers.of(modifiers) }

    override fun toString(): String = repr

    companion object {
        val None = RimeKeyEvent(0, 0, "0x0000")

        @JvmStatic
        external fun parse(repr: String): RimeKeyEvent

        @JvmStatic
        external fun getKeycodeByName(name: String): Int

        @JvmStatic
        external fun getModifierByName(name: String): Int
    }
}
