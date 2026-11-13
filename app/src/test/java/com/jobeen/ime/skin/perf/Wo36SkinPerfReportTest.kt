// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.perf

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO36 性能报告抽串：全部用户可见串走 [PerfReportLabels]，
 * 运行时从 strings.xml（`perf_report_*`）解析注入，纯函数缺省值
 * [PerfReportLabels.DEFAULT] 与资源同值。
 *
 * 本文件不断言硬编码中文——期望值一律取自 DEFAULT（与生产代码
 * 同一来源）；strings.xml 里的实际串值由
 * [stringsXml_mirrorsDefaultLabels] 逐项比对源码钉住。
 * 注入路径（运行时走资源解析的同一条路）由
 * [injectedLabelsAreUsed] 钉住：自定义标签即显示。
 */
class Wo36SkinPerfReportTest {

    private fun snapshot() = PerfSnapshot(
        openedAtMs = 1_000_000L,
        totalPssKb = 204_800L,
        availMemKb = 3_145_728L,
        cpuPercent = 12.5,
        viewCount = 120,
        graphicsKb = 51_200L,
        skinCacheCount = 3,
        skinCacheBytes = 3L * 1024 * 1024,
        nativeHeapKb = 30_720L,
        stackKb = 1024L,
        codeKb = 20_480L,
        otherKb = 10_240L,
        mallocAllocatedKb = 40_960L,
        userdbCount = 2,
        userdbBytes = 5L * 1024 * 1024,
        leveldbNames = listOf("wanxiang.userdb", "luna_pinyin.userdb"),
        uptimeMs = 2L * 3600 * 1000 + 15L * 60 * 1000,
        reportPath = "/files/Logs/performance/memory-20261106-003000-123-command.log",
    )

    @Test
    fun stringsXml_mirrorsDefaultLabels() {
        // 与 PerfPanelLabelsTest 同款做法：单测工作目录即模块目录，
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
        val d = PerfReportLabels.DEFAULT
        val pairs = listOf(
            "perf_report_section_timing" to d.sectionTiming,
            "perf_report_section_overview" to d.sectionOverview,
            "perf_report_section_attribution" to d.sectionAttribution,
            "perf_report_section_memtype" to d.sectionMemtype,
            "perf_report_section_malloc" to d.sectionMalloc,
            "perf_report_section_process" to d.sectionProcess,
            "perf_report_section_storage" to d.sectionStorage,
            "perf_report_row_no_data" to d.rowNoData,
            "perf_report_row_total_memory" to d.rowTotalMemory,
            "perf_report_row_avail_memory" to d.rowAvailMemory,
            "perf_report_row_cpu" to d.rowCpu,
            "perf_report_row_since_last" to d.rowSinceLast,
            "perf_report_row_script" to d.rowScript,
            "perf_report_row_keyboard_ui" to d.rowKeyboardUi,
            "perf_report_row_skin_cache" to d.rowSkinCache,
            "perf_report_row_view_count" to d.rowViewCount,
            "perf_report_row_other" to d.rowOther,
            "perf_report_row_malloc_heap" to d.rowMallocHeap,
            "perf_report_row_graphics" to d.rowGraphics,
            "perf_report_row_stack" to d.rowStack,
            "perf_report_row_code" to d.rowCode,
            "perf_report_row_other_mem" to d.rowOtherMem,
            "perf_report_row_malloc_allocated" to d.rowMallocAllocated,
            "perf_report_row_rime_files" to d.rowRimeFiles,
            "perf_report_row_leveldb" to d.rowLeveldb,
            "perf_report_row_uptime" to d.rowUptime,
            "perf_report_row_report_file" to d.rowReportFile,
            "perf_report_no_data_value" to d.noDataValue,
            "perf_report_cpu_sampled" to d.cpuSampled,
            "perf_report_first_collection" to d.firstCollection,
            "perf_report_script_value" to d.scriptValue,
            "perf_report_keyboard_ui_value" to d.keyboardUiValue,
            "perf_report_skin_cache_value" to d.skinCacheValue,
            "perf_report_malloc_allocated_value" to d.mallocAllocatedValue,
            "perf_report_rime_files_value" to d.rimeFilesValue,
            "perf_report_leveldb_empty" to d.leveldbEmpty,
            "perf_report_leveldb_value" to d.leveldbValue,
            "perf_report_leveldb_separator" to d.leveldbSeparator,
            "perf_report_duration_hours_minutes" to d.durationHoursMinutes,
            "perf_report_duration_minutes_seconds" to d.durationMinutesSeconds,
            "perf_report_duration_seconds" to d.durationSeconds,
            "perf_report_seconds" to d.seconds,
            "perf_report_title" to d.reportTitle,
            "perf_report_first_sample_note" to d.firstSampleNote,
        )
        assertEquals("perf_report_* 串数", 44, pairs.size)
        for ((resName, expected) in pairs) {
            assertEquals(
                "strings.xml $resName 与 PerfReportLabels.DEFAULT 不一致",
                expected,
                values[resName],
            )
        }
    }

    @Test
    fun injectedLabelsAreUsed() {
        val custom = PerfReportLabels.DEFAULT.copy(
            sectionTiming = "X-TIMING",
            sectionOverview = "X-OVERVIEW",
            rowTotalMemory = "X-MEM",
            rowScript = "X-SCRIPT",
            scriptValue = "X-SCRIPT-VALUE",
            reportTitle = "X-TITLE",
            firstSampleNote = "X-FIRST",
        )
        val sections = buildPerfSections(snapshot(), null, emptyList(), custom)
        assertEquals("X-TIMING", sections[0].title)
        assertEquals("X-OVERVIEW", sections[1].title)
        assertEquals("X-MEM", sections[1].rows.first { it.label == "X-MEM" }.label)
        val attribution = sections[2].rows.associateBy { it.label }
        assertEquals("X-SCRIPT-VALUE", attribution.getValue("X-SCRIPT").value)
        val report = formatPerfReport(sections, firstSample = true, custom)
        assertTrue(report.startsWith("X-TITLE\n"))
        assertTrue(report.contains("X-FIRST"))
    }

    @Test
    fun formatDuration_usesInjectedLabels() {
        val custom = PerfReportLabels.DEFAULT.copy(durationSeconds = "%1\$d sec")
        assertEquals("0 sec", formatDuration(500L, custom))
        // 缺省值与旧行为逐字节一致（既有 PerfReportTest 另行钉住）。
        assertEquals("0秒", formatDuration(500L))
        assertEquals("2小时15分", formatDuration((2L * 3600 + 15 * 60) * 1000))
    }

    @Test
    fun formatSeconds_usesInjectedLabels() {
        val custom = PerfReportLabels.DEFAULT.copy(seconds = "%1\$.1f s")
        assertEquals("10.0 s", formatSeconds(10.0, custom))
        assertEquals("10.0 秒", formatSeconds(10.0))
    }
}
