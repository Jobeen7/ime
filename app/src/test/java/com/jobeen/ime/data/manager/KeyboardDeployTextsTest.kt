// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 键盘内部署提示文案（接线轮 R7，纯函数）：只说部署，绝不声称同步。 */
class KeyboardDeployTextsTest {

    @Test
    fun deployingAndSuccess() {
        assertEquals(
            "正在部署…",
            KeyboardDeployTexts.toastFor(
                KeyboardDeployProgress(KeyboardDeployPhase.DEPLOYING, null, null),
            ),
        )
        assertEquals(
            "部署完成",
            KeyboardDeployTexts.toastFor(
                KeyboardDeployProgress(KeyboardDeployPhase.SUCCESS, null, null),
            ),
        )
    }

    @Test
    fun failedReasons() {
        assertEquals(
            "部署失败",
            KeyboardDeployTexts.toastFor(
                KeyboardDeployProgress(KeyboardDeployPhase.FAILED, null, "deploy_failed"),
            ),
        )
        assertEquals(
            "部署超时（后台部署继续）",
            KeyboardDeployTexts.toastFor(
                KeyboardDeployProgress(
                    KeyboardDeployPhase.FAILED, null, "deploy_timeout_running",
                ),
            ),
        )
        assertEquals(
            "部署失败：IllegalStateException",
            KeyboardDeployTexts.toastFor(
                KeyboardDeployProgress(
                    KeyboardDeployPhase.FAILED, null, "IllegalStateException",
                ),
            ),
        )
        assertEquals(
            "部署失败",
            KeyboardDeployTexts.toastFor(
                KeyboardDeployProgress(KeyboardDeployPhase.FAILED, null, null),
            ),
        )
    }

    @Test
    fun silentPhases() {
        assertNull(
            KeyboardDeployTexts.toastFor(
                KeyboardDeployProgress(KeyboardDeployPhase.IDLE, null, null),
            ),
        )
        assertNull(
            KeyboardDeployTexts.toastFor(
                KeyboardDeployProgress(KeyboardDeployPhase.NEEDS_CONFIRM, null, null),
            ),
        )
        assertNull(
            KeyboardDeployTexts.toastFor(
                KeyboardDeployProgress(KeyboardDeployPhase.SYNCING, null, null),
            ),
        )
    }
}
