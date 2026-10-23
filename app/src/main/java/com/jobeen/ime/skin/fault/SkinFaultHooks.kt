package com.jobeen.ime.skin.fault

/**
 * 崩溃注入钩子分发点（详设 §2.9）。主源码里只有这个空分发：release
 * 构建 [handler] 恒为默认空实现，注入点调用零成本、零行为。
 *
 * 真正的注入实现（到达注入点即等待标记文件，供 adb 侧 kill 进程后
 * 冷启验证）只存在于 debug 源码集，由 DebugFaultHooks 在 debug
 * 构建启动时安装。A-5（篡改 active.json）、A-7（删代次目录）是
 * 文件层模拟、无进程内注入点，故不在枚举内。
 */
object SkinFaultHooks {

    /** 注入点编号与详设 §2.9 崩溃点矩阵一一对应。 */
    enum class Point {
        I1, I2, I3, I4, I5,
        A1, A2, A3, A4, A6,
        D1, S1,
    }

    @Volatile
    var handler: (Point) -> Unit = {}

    fun hit(point: Point) = handler(point)
}
