package com.jobeen.ime.engine

/**
 * 仅一个布尔标志，不是状态机。
 * 仅由部署等待函数置位与复位；
 * 导入守门与键盘切换守门只读它。
 */
object SchemeDeployState {
    @Volatile
    var isDeploying: Boolean = false
}
