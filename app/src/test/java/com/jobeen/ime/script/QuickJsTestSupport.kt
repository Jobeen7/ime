// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.script

import java.io.File
import org.junit.Assume

/**
 * C-L-06：主机 QuickJS .so 缺失时跳过（非 Linux 下 buildHostQuickJs
 * 不编，系统属性指到的文件不存在；没有 .so 时 native 调用只会抛
 * UnsatisfiedLinkError，不是被测行为——Assume 跳过而非失败）。
 */
internal fun assumeHostQuickJsAvailable() {
    val hostLib = System.getProperty("jime.quickjs.hostLib")
    Assume.assumeTrue(
        "主机 QuickJS .so 缺失（非 Linux 或未编 buildHostQuickJs），跳过",
        !hostLib.isNullOrBlank() && File(hostLib).exists(),
    )
}
