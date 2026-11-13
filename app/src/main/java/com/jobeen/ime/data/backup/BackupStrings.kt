// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.backup

import androidx.annotation.StringRes
import com.jobeen.ime.base.util.appContext

/**
 * data/backup 内用户可见文案的唯一取串口（WO36 第 6 项，WO33 扫尾
 * “备份构造侧”）：BackupManager／RoomSnapshotReader 抛出的
 * BackupException 消息经 BackupActivity 以 Toast 原样示人，旧实现
 * 是硬编码中文；现一律走 strings.xml 的 backup_* 串，文案与旧字
 * 面逐字节一致（DeepErrorMapper 按旧前缀做尾巴消毒，见
 * DeepErrorMapperTest 的 sanitizeTail 用例）。
 *
 * 只能在真机路径上调用：取串要真 Application Context。JVM 单测
 * 从不跑通这些路径（parsePayload／decryptPayload／restoreBackup／
 * applyPayload／RoomSnapshotReader.read 均无 JVM 用例，见各测试
 * 文件的 KDoc），故此处直接用 [appContext]，不另 thread resolver
 * 参数。仍须保持无 Context 的两个例外（见 BackupManager 的注释）：
 * [BackupManager.BackupDataChangedException]（JVM 断言逐字消息）与
 * [readBackupBytes] 的“备份文件过大”（JVM 断言抛错类型）——两者
 * 仍用字面量，文案不变。
 */
internal fun backupString(@StringRes resId: Int, vararg args: Any?): String =
    appContext.getString(resId, *args)
