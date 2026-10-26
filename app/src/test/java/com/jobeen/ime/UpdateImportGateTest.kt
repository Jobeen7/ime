package com.jobeen.ime

import com.jobeen.ime.base.ngram.UpdateImportGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新/导入交叉闸的凭证语义（残余 1）：部署侧超时/取消/丢弃三路
 * 都可能在等待方之后放闸，裸 Boolean 的重复释放会在新持有者刚
 * 抢到闸的瞬间把它误清；凭证 CAS 只清自己名下的持有。
 * 本类是交叉闸凭证语义的唯一测试使用者，抢放成对。
 */
class UpdateImportGateTest {

    @Test
    fun token_acquireReturnsDistinctTokens() {
        val first = UpdateImportGate.tryAcquire()
        assertNotNull(first)
        try {
            assertNull(UpdateImportGate.tryAcquire())
        } finally {
            UpdateImportGate.release(first)
        }
        val second = UpdateImportGate.tryAcquire()
        assertNotNull(second)
        try {
            // 两次抢占的凭证不是同一个对象（否则误放无法区分持有者）
            assertTrue(first !== second)
        } finally {
            UpdateImportGate.release(second)
        }
        assertFalse(UpdateImportGate.isHeld)
    }

    @Test
    fun token_nullReleaseIsNoop() {
        val token = UpdateImportGate.tryAcquire()
        assertNotNull(token)
        try {
            UpdateImportGate.release(null)
            assertTrue(UpdateImportGate.isHeld)
            assertNull(UpdateImportGate.tryAcquire())
        } finally {
            UpdateImportGate.release(token)
        }
        assertFalse(UpdateImportGate.isHeld)
    }

    @Test
    fun token_doubleReleaseIsSafe() {
        val token = UpdateImportGate.tryAcquire()
        assertNotNull(token)
        UpdateImportGate.release(token)
        assertFalse(UpdateImportGate.isHeld)
        // 重复释放不复活、不误清后来者
        UpdateImportGate.release(token)
        val next = UpdateImportGate.tryAcquire()
        assertNotNull(next)
        try {
            UpdateImportGate.release(token)
            assertTrue(UpdateImportGate.isHeld)
        } finally {
            UpdateImportGate.release(next)
        }
        assertFalse(UpdateImportGate.isHeld)
    }
}
