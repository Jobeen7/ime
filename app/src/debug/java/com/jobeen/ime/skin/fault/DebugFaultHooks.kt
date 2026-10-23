package com.jobeen.ime.skin.fault

import java.io.File

/**
 * 故障注入的 debug 实现（详设 §2.9，仅 debug 源码集编译）：
 * 注入点存在标记文件时阻塞等待标记消失——测试侧在此窗口内
 * `am kill` / `kill -9` 杀进程，再冷启验证预期恢复动作。
 *
 * 标记目录由 [install] 注入（DebugImeApplication 传
 * `<外部文件目录>/fault_hooks`），标记文件名 = 注入点名
 * （如 `I1`、`A2`）。无标记文件时 hit 立即返回，正常调试不受影响。
 */
object DebugFaultHooks {
    private const val POLL_MS = 50L

    @Volatile
    private var markerDir: File? = null

    fun install(dir: File) {
        markerDir = dir
        SkinFaultHooks.handler = { point -> awaitMarker(point) }
    }

    internal fun awaitMarker(point: SkinFaultHooks.Point) {
        val dir = markerDir ?: return
        val marker = File(dir, point.name)
        if (!marker.exists()) return
        while (marker.exists()) {
            Thread.sleep(POLL_MS)
        }
    }
}
