package com.jobeen.ime.skin.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 键盘性能报告纯函数（PerfReport）：分区、差值格式、发送文本与显示一致。 */
class PerfReportTest {

private fun snapshot(
openedAtMs: Long = 1_000_000L,
totalPssKb: Long = 204_800L,
availMemKb: Long = 3_145_728L,
cpuPercent: Double? = 12.5,
viewCount: Int = 120,
graphicsKb: Long? = 51_200L,
skinCacheCount: Int = 3,
skinCacheBytes: Long = 3L * 1024 * 1024,
nativeHeapKb: Long? = 30_720L,
stackKb: Long? = 1024L,
codeKb: Long? = 20_480L,
otherKb: Long? = 10_240L,
mallocAllocatedKb: Long = 40_960L,
userdbCount: Int = 2,
userdbBytes: Long = 5L * 1024 * 1024,
leveldbNames: List<String> = listOf("wanxiang.userdb", "luna_pinyin.userdb"),
uptimeMs: Long = 2L * 3600 * 1000 + 15L * 60 * 1000,
reportPath: String = "/files/Logs/performance/memory-20261106-003000-123-command.log",
) = PerfSnapshot(
openedAtMs = openedAtMs,
totalPssKb = totalPssKb,
availMemKb = availMemKb,
cpuPercent = cpuPercent,
viewCount = viewCount,
graphicsKb = graphicsKb,
skinCacheCount = skinCacheCount,
skinCacheBytes = skinCacheBytes,
nativeHeapKb = nativeHeapKb,
stackKb = stackKb,
codeKb = codeKb,
otherKb = otherKb,
mallocAllocatedKb = mallocAllocatedKb,
userdbCount = userdbCount,
userdbBytes = userdbBytes,
leveldbNames = leveldbNames,
uptimeMs = uptimeMs,
reportPath = reportPath,
)

private val timingRows = listOf(
"冷启动" to "1 次，均值 300.0 ms，最大 300 ms",
"内存" to "2 次，均值 100.0 KB，最大 120 KB",
)

@Test
fun sections_orderAndTitles() {
val sections = buildPerfSections(snapshot(), null, timingRows)
assertEquals(
listOf("耗时", "总览", "按归属（估算）", "按内存类型", "malloc", "进程", "存储位置"),
sections.map { it.title},
)
// 耗时分区在内存分区之前，只加不减。
assertEquals(
listOf("冷启动", "内存"),
sections[0].rows.map { it.label},
)
}

@Test
fun sections_emptyTimingShowsPlaceholder() {
val sections = buildPerfSections(snapshot(), null, emptyList())
assertEquals(listOf("暂无数据"), sections[0].rows.map { it.label})
}

@Test
fun deltaFormat_plusAndMinusMb() {
val prev = snapshot(openedAtMs = 990_000L, totalPssKb = 200_704L, availMemKb = 3_150_000L)
val cur = snapshot(openedAtMs = 1_000_000L, totalPssKb = 204_800L, availMemKb = 3_145_728L)
val sections = buildPerfSections(cur, prev, timingRows)
val overview = sections[1].rows.associateBy { it.label}
assertEquals("200.0 MB", overview.getValue("总内存").value)
assertEquals("+4.0 MB", overview.getValue("总内存").delta)
assertEquals("-4.2 MB", overview.getValue("距上限剩余").delta)
// 距上次采集 = 两次打开的时间差（10 秒）。
assertEquals("10.0 秒", overview.getValue("距上次采集").value)
}

@Test
fun firstSample_hasNoDeltas() {
val sections = buildPerfSections(snapshot(), null, timingRows)
assertTrue(sections.flatMap { it.rows}.none { it.delta!= null})
assertEquals("首次打开", sections[1].rows.first { it.label == "距上次采集"}.value)
val report = formatPerfReport(sections, firstSample = true)
assertTrue(report.contains("首次采集（无差值）"))
assertFalse(report.contains("（+"))
assertFalse(report.contains("（-"))
}

@Test
fun attribution_scriptNotMeasurableAndRemainderCanBeNegative() {
// 脚本行不编数字；其余 = 总内存 − 图形 − 皮肤缓存，可为负，照实显示。
val small = snapshot(totalPssKb = 10_240L, graphicsKb = 51_200L, skinCacheBytes = 0L)
val sections = buildPerfSections(small, null, timingRows)
val attribution = sections[2].rows.associateBy { it.label}
val script = attribution.getValue("脚本")
assertTrue(script.value.contains("不可计量"))
assertEquals(null, script.delta)
assertEquals("-40.0 MB", attribution.getValue("其余").value)
assertTrue(attribution.getValue("键盘界面").value.contains("120 个视图"))
assertTrue(attribution.getValue("皮肤图片缓存（估算）").value.contains("3 项"))
assertEquals("120", attribution.getValue("视图数量").value)
}

@Test
fun memoryTypes_noImageIoRow() {
val sections = buildPerfSections(snapshot(), null, timingRows)
val labels = sections[3].rows.map { it.label}
assertEquals(listOf("malloc 堆", "图形", "栈", "代码与映射文件", "其他"), labels)
assertFalse(labels.any { it.contains("ImageIO", ignoreCase = true)})
}

@Test
fun malloc_onlyAllocatedRowWithScriptNote() {
val sections = buildPerfSections(snapshot(), null, timingRows)
assertEquals(1, sections[4].rows.size)
val row = sections[4].rows[0]
assertEquals("已分配", row.label)
assertTrue(row.value.contains("40.0 MB"))
assertTrue(row.value.contains("脚本部分不可计量"))
}

@Test
fun processSection_userdbIsDirectoryStatsAndLeveldbNames() {
val sections = buildPerfSections(snapshot(), null, timingRows)
val process = sections[5].rows.associateBy { it.label}
val rime = process.getValue("RIME 打开的数据文件")
assertTrue(rime.value.contains("2 个"))
assertTrue(rime.value.contains("5.0 MB"))
assertTrue(rime.value.contains("目录统计口径，非 fd 口径"))
assertTrue(
process.getValue("打开的 LevelDB").value
.contains("2 个：wanxiang.userdb、luna_pinyin.userdb"),
)
assertEquals("2小时15分", process.getValue("运行时长").value)
}

@Test
fun reportText_matchesDisplayedRows() {
val prev = snapshot(openedAtMs = 990_000L, totalPssKb = 200_704L)
val cur = snapshot(openedAtMs = 1_000_000L)
val sections = buildPerfSections(cur, prev, timingRows)
val report = formatPerfReport(sections, firstSample = false)
assertTrue(report.startsWith("键盘性能\n"))
for (section in sections) {
assertTrue(report.contains(""))
for (row in section.rows) {
val expected = if (row.delta!= null) {
"${row.label} ${row.value}（${row.delta}）"
} else {
"${row.label} ${row.value}"
}
assertTrue("报告缺行：$expected", report.contains(expected))
}
}
// 存储位置末行是报告落盘路径。
assertTrue(report.contains("/files/Logs/performance/memory-20261106-003000-123-command.log"))
}

@Test
fun formatDuration_cases() {
assertEquals("0秒", formatDuration(500L))
assertEquals("45分32秒", formatDuration((45L * 60 + 32) * 1000))
assertEquals("2小时15分", formatDuration((2L * 3600 + 15 * 60) * 1000))
}

@Test
fun formatDeltaMb_zeroShowsPlus() {
assertEquals("+0.0 MB", formatDeltaMb(0L))
assertEquals("+1.0 MB", formatDeltaMb(1024L))
assertEquals("-1.0 MB", formatDeltaMb(-1024L))
}
}
