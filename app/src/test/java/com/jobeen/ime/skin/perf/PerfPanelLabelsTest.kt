// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.perf

import com.jobeen.ime.R
import com.jobeen.ime.input.panel.component.PerformancePanelView
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO33 第 4(a) 项：性能面板标签抽串的钉住测试。
 *
 * 本文件不断言硬编码中文标签——期望值一律取自 [PerfPanelLabels]
 *（与生产代码同一来源）；strings.xml 里的实际串值由
 * [stringsXml_mirrorsDefaultLabels] 逐项比对源码钉住。
 */
class PerfPanelLabelsTest {

    @Test
    fun defaultLabels_coverAllEvents() {
        assertEquals(PerfMonitor.Event.entries.toSet(), PerfPanelLabels.DEFAULT_LABELS.keys)
        assertTrue(PerfPanelLabels.DEFAULT_LABELS.values.all { it.isNotBlank() })
    }

    @Test
    fun labelResIds_matchPerfEventStrings() {
        assertEquals(R.string.perf_event_cold_start, PerfPanelLabels.labelResId(PerfMonitor.Event.COLD_START))
        assertEquals(R.string.perf_event_key_to_commit, PerfPanelLabels.labelResId(PerfMonitor.Event.KEY_TO_COMMIT))
        assertEquals(R.string.perf_event_skin_apply, PerfPanelLabels.labelResId(PerfMonitor.Event.SKIN_APPLY))
        assertEquals(R.string.perf_event_slow_frame, PerfPanelLabels.labelResId(PerfMonitor.Event.SLOW_FRAME))
        assertEquals(R.string.perf_event_memory, PerfPanelLabels.labelResId(PerfMonitor.Event.MEMORY))
        assertEquals(R.string.perf_event_startup_step, PerfPanelLabels.labelResId(PerfMonitor.Event.STARTUP_STEP))
        assertEquals(R.string.perf_event_key_dispatch, PerfPanelLabels.labelResId(PerfMonitor.Event.KEY_DISPATCH))
        assertEquals(R.string.perf_event_key_engine, PerfPanelLabels.labelResId(PerfMonitor.Event.KEY_ENGINE))
        assertEquals(R.string.perf_event_key_commit, PerfPanelLabels.labelResId(PerfMonitor.Event.KEY_COMMIT))
        assertEquals(R.string.perf_event_main_lag, PerfPanelLabels.labelResId(PerfMonitor.Event.MAIN_LAG))
    }

    @Test
    fun stringsXml_mirrorsDefaultLabels() {
        // 与 BuiltinPluginsTest 同款做法：单测工作目录即模块目录，
        // 直接读 strings.xml 源码比对，防止资源与缺省值静默漂移。
        val stringsXml = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml not found at ${stringsXml.absolutePath}", stringsXml.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsXml)
        val values = HashMap<String, String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
            values[name] = node.textContent
        }
        for (event in PerfMonitor.Event.entries) {
            val resName = "perf_event_" + event.name.lowercase()
            assertEquals(
                "strings.xml $resName 与 PerfPanelLabels.DEFAULT_LABELS 不一致",
                PerfPanelLabels.DEFAULT_LABELS.getValue(event),
                values[resName],
            )
        }
        assertEquals(
            "strings.xml perf_aggregate_format 与 DEFAULT_AGGREGATE_TEMPLATE 不一致",
            PerfPanelLabels.DEFAULT_AGGREGATE_TEMPLATE,
            values["perf_aggregate_format"],
        )
    }

    @Test
    fun rowsFor_usesInjectedLabels() {
        // 注入路径（运行时走资源解析的同一条路）：自定义标签即显示。
        val rows = PerformancePanelView.rowsFor(
            mapOf(PerfMonitor.Event.COLD_START to PerfMonitor.Aggregate(1L, 1.0, 1L)),
            mapOf(PerfMonitor.Event.COLD_START to "X"),
        )
        assertEquals(listOf("X"), rows.map { it.first })
    }

    @Test
    fun rowsFor_missingLabelFallsBackToEventName() {
        val rows = PerformancePanelView.rowsFor(
            mapOf(PerfMonitor.Event.MEMORY to PerfMonitor.Aggregate(1L, 1.0, 1L)),
            emptyMap(),
        )
        assertEquals(listOf(PerfMonitor.Event.MEMORY.name), rows.map { it.first })
    }

    @Test
    fun formatAggregate_unitsAndTemplateWiring() {
        val mem = PerformancePanelView.formatAggregate(
            PerfMonitor.Event.MEMORY,
            PerfMonitor.Aggregate(3L, 100.0, 7L),
        )
        assertEquals(
            String.format(PerfPanelLabels.DEFAULT_AGGREGATE_TEMPLATE, 3L, 100.0, "KB", 7L),
            mem,
        )
        assertTrue(mem.contains("KB"))
        assertTrue(mem.contains("100.0"))
        val ms = PerformancePanelView.formatAggregate(
            PerfMonitor.Event.COLD_START,
            PerfMonitor.Aggregate(1L, 300.0, 300L),
        )
        assertEquals(
            String.format(PerfPanelLabels.DEFAULT_AGGREGATE_TEMPLATE, 1L, 300.0, "ms", 300L),
            ms,
        )
        assertTrue(ms.contains("ms"))
        assertTrue(ms.contains("300.0"))
    }
}
