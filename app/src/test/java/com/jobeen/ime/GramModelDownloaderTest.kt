package com.jobeen.ime

import com.jobeen.ime.base.ngram.DownloadGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨下载器共用下载闸（4.3/N3）：语法模型单独下载与方案更新共用
 * 一把闸，任一路持有期间另一路必须被拒（BUSY），释放后可再抢。
 * DownloadGate 是进程级单例，本类是其唯一测试使用者，抢放成对。
 */
class GramModelDownloaderTest {

    @Test
    fun downloadGate_secondAcquireRejectedUntilRelease() {
        assertTrue(DownloadGate.tryAcquire())
        try {
            assertTrue(DownloadGate.isBusy)
            // 已被持有：第二路（无论同路重复调用还是跨路调用）被拒
            assertFalse(DownloadGate.tryAcquire())
        } finally {
            DownloadGate.release()
        }
        assertFalse(DownloadGate.isBusy)
        assertTrue(DownloadGate.tryAcquire())
        DownloadGate.release()
    }
}
