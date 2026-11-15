// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.core


data class RimeKeyEvent(
    val value: Int,
    val modifiers: Int,
    val repr: String,
) {

    override fun toString(): String = repr

    companion object {
        val None = RimeKeyEvent(0, 0, "0x0000")

        @JvmStatic
        external fun parse(repr: String): RimeKeyEvent
    }
}
