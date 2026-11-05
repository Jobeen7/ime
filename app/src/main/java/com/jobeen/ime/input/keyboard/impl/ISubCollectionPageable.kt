// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

/**
 * 二级集合翻页板（P1-5）：符号／表情板当前子类按页翻
 * （页 = 板网格一屏）；页内无更多时返回 false，调用方
 * 空操作，不换父类、不下发引擎。
 */
interface ISubCollectionPageable : IKeyboard {
    fun pageSubCollection(forward: Boolean): Boolean
}
