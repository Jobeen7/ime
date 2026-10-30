// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.input.keyboard.window.SchemaTargetResolver
import com.jobeen.ime.input.keyboard.window.SchemaTargetResolver.SchemaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 命名方案直切换目标解析（重点候选 8-3）：ID 优先于名，无命中不切换。 */
class SchemaTargetResolverTest {

    private val schemas = listOf(
        SchemaRef("wanxiang", "万象拼音"),
        SchemaRef("luna", "朙月拼音"),
        SchemaRef("double", "万象拼音"),
    )

    @Test
    fun idMatchWinsOverName() {
        assertEquals("wanxiang", SchemaTargetResolver.resolve("wanxiang", schemas))
    }

    @Test
    fun nameFallsBackWhenNoIdMatch() {
        assertEquals("luna", SchemaTargetResolver.resolve("朙月拼音", schemas))
    }

    @Test
    fun duplicateNameResolvesToFirst() {
        assertEquals("wanxiang", SchemaTargetResolver.resolve("万象拼音", schemas))
    }

    @Test
    fun unknownTargetResolvesToNull() {
        assertNull(SchemaTargetResolver.resolve("no_such_schema", schemas))
        assertNull(SchemaTargetResolver.resolve("", schemas))
        assertNull(SchemaTargetResolver.resolve("   ", schemas))
        assertNull(SchemaTargetResolver.resolve("wanxiang", emptyList()))
    }
}
