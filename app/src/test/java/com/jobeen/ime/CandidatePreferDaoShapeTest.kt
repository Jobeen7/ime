package com.jobeen.ime

import com.jobeen.ime.data.database.CandidatePreferDao
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CandidatePreferDao.upsert 的语句形态规格（第十二批 F5）。
 *
 * 规格：公开的 upsert 是 @Transaction 默认方法，内部两条子语句
 * 只用 SQLite 3.24 之前就存在的语法（INSERT OR IGNORE + 普通
 * UPDATE），该 DAO 不得再出现 `ON CONFLICT ... DO UPDATE` 的
 * UPSERT 语法——minSdk 24 的部分受支持设备平台 SQLite 低于
 * 3.24，该语法会让每次偏好写入抛语法错误、偏好永远学不到。
 *
 * 断言方式：androidx.room 注解是 CLASS 级 retention，运行时反射
 * （getAnnotation）读不到，故直接读编译后 class 文件的常量池
 * 字节做子串断言（SQL 原文与注解描述符都以 UTF8 常量在场）。
 * upsert 非抽象（带默认实现体）一条仍走反射修饰符检查。
 *
 * 本测试只锁定语句形态；两语句的语义等价（首次=1、重复递增、
 * context/时间刷新、created_at 不动）已在实施时以真实建表 SQL
 * 对照原 UPSERT 逐项验证（见 review14 的 impl-prefer.md），且
 * Room 生成的 _Impl 已核对为 performInTransactionSuspending 包
 * 裹默认方法体（真事务）。真实旧 SQLite 上的端到端行为需仪器
 * 验证，不在本 JVM 单测覆盖内。
 */
class CandidatePreferDaoShapeTest {

    /** DAO 接口编译后 class 文件全文（ISO-8859-1 保字节解码后做空白归一化）。 */
    private val classText: String by lazy {
        val path = "/com/jobeen/ime/data/database/CandidatePreferDao.class"
        val bytes = CandidatePreferDao::class.java.getResourceAsStream(path)?.readBytes()
        assertNotNull("应能从测试 classpath 读到 DAO 的 class 文件", bytes)
        String(bytes!!, StandardCharsets.ISO_8859_1).replace(Regex("\\s+"), " ")
    }

    @Test
    fun upsertIsNonAbstractDefaultMethodWithTransactionAnnotation() {
        val upsert = CandidatePreferDao::class.java.methods
            .firstOrNull { it.name == "upsert" }
        assertNotNull("DAO 应有 upsert", upsert)
        assertFalse(
            "upsert 应是带默认实现的接口方法（两语句同事务执行）",
            Modifier.isAbstract(upsert!!.modifiers),
        )
        assertTrue(
            "upsert 应带 @Transaction（常量池应有其描述符）",
            classText.contains("Landroidx/room/Transaction;"),
        )
    }

    @Test
    fun subStatementsUsePreUpsertSyntax() {
        assertTrue(
            "建行语句应为 INSERT OR IGNORE 并以 0 计数落行",
            classText.contains("INSERT OR IGNORE INTO candidate_prefers") &&
                classText.contains("VALUES (:text, :context, 0, :now, :now)"),
        )
        assertTrue(
            "累加语句应为普通 UPDATE 自增并按 text 定位",
            classText.contains("UPDATE candidate_prefers SET") &&
                classText.contains("click_count = click_count + 1") &&
                classText.contains("context = :context") &&
                classText.contains("updated_at = :now") &&
                classText.contains("WHERE text = :text"),
        )
    }

    @Test
    fun noStatementUsesOnConflictDoUpdate() {
        assertFalse(
            "DAO 内不得再出现 ON CONFLICT（UPSERT 语法）",
            classText.contains("ON CONFLICT"),
        )
        assertFalse(
            "DAO 内不得再出现 DO UPDATE（UPSERT 语法）",
            classText.contains("DO UPDATE"),
        )
    }
}
