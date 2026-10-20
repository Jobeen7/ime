package com.jobeen.ime.data.database

import android.content.Context
import androidx.core.content.edit
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CandidateSorting::class, ClipboardRecord::class, CandidatePrefer::class, PhraseRecord::class], version = 13, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun candidateSortingDao(): CandidateSortingDao

    abstract fun clipboardDao(): ClipboardDao

    abstract fun candidatePreferDao(): CandidatePreferDao

    abstract fun phraseDao(): PhraseDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext, "ime_database")
                    .also { INSTANCE = it }
                // 注意：不要加 fallbackToDestructiveMigration() —— 漏写 Migration 时宁可启动崩溃（fail-fast，
                // 发布前真机测试会先暴露），也不要静默清空用户的剪贴板/常用语/选词偏好。每次 bump version
                // 都必须写 Migration（无结构变更时写空迁移，见 MIGRATION_7_8）。
            }
        }

        /**
         * 以指定库名新建实例（不走单例缓存）：迁移仪器测试以测试专用
         * 库名走同一条迁移链验证，避免在真机上触碰生产库文件。
         * 生产代码只用 [getInstance]。
         */
        fun createWithName(context: Context, name: String): AppDatabase =
            build(context.applicationContext, name)

        private fun build(context: Context, name: String): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                .addCallback(VACUUM_CALLBACK)
                .build()

        private const val MAINTENANCE_PREFS = "db_maintenance"
        private const val KEY_VACUUM_V10_DONE = "vacuum_after_v10_done"
        private const val KEY_VACUUM_CLIP_DONE = "vacuum_after_clipboard_split_done"
        private const val KEY_VACUUM_CLIP_LAST_FAIL = "vacuum_after_clipboard_split_last_fail"

        /** 生产库名：v10 VACUUM 标记的兼容读与按库分键都以它为准 */
        private const val PRODUCTION_DB_NAME = "ime_database"

        /**
         * 拆库 VACUUM 一次性补跑标记（见 [runPostSplitVacuumMaintenance]）：
         * 在 v1.1.11.x 已完成迁移、且拆库 VACUUM 完成标记被误置的子集
         * 设备上，迁移体按 KEY_DB_MIGRATED 早退、清标永不执行，补跑又被
         * 误置的完成标记短路——本键未置位时强制清标重跑一次，补上
         * 这条覆盖缺口。
         */
        private const val KEY_VACUUM_CLIP_REDO_V2 = "vacuum_clip_redo_v2"

        /** 拆库补跑 VACUUM 失败后的退避间隔：失败多为空间不足/库忙，短间隔重试只是反复付费 */
        private const val VACUUM_CLIP_RETRY_BACKOFF_MS = 24L * 60 * 60 * 1000

        /**
         * 全部 VACUUM 执行的串行锁（v10 与拆库两条路径共用同一咽喉
         * [vacuumAndMark]）：把「查标记→执行→落标记」收成临界区，
         * 消除并发首跑时多个调用方同时看到未完成标记、各自跑一遍
         * VACUUM 的 check-then-act 窗口。可重入：拆库路径持锁时调
         * vacuumAndMark 会同线程再次取它。与 SQLiteOpenHelper 监
         * 视器的锁序全局统一为「helper 监视器 → vacuumLock」：
         * onOpen 回调在监视器内取本锁，拆库路径因此先开库后取锁
         * （见 vacuumAfterClipboardSplit），不得反序。
         */
        private val vacuumLock = java.util.concurrent.locks.ReentrantLock()

        /**
         * 剪贴板拆库迁移完成后补跑的一次性 VACUUM：旧剪贴板行在迁移时
         * 已从主库整表删除，但全文还留在主库文件的空闲页与 WAL 里，
         * 而 onOpen 的一次性 VACUUM 在启动时（迁移发生之前）就已跑完
         * 并置了标记，擦不到这批残留。由 ClipboardManager 的常规访问
         * 路径在每次迁移检查之后调用（本函数自带完成标记，未完成才
         * 真正执行）：补跑点不能放在迁移体内部——已完成迁移的老用户
         * 在迁移体开头就按标记返回，永远走不到，残留全文会一直留在
         * 会进云备份的主库里。VACUUM 后再截断 WAL，成功落标记、失败
         * 记失败时刻并退避（见 [VACUUM_CLIP_RETRY_BACKOFF_MS]），不
         * 再每次访问都重试。由 ClipboardManager 在后台作用域异步
         * 派发调用，不阻塞剪贴板操作本身。
         */
        fun vacuumAfterClipboardSplit() {
            val prefs = com.jobeen.ime.base.util.appContext
                .getSharedPreferences(MAINTENANCE_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_VACUUM_CLIP_DONE, false)) return
            // 先开库、后拿 vacuumLock（锁序与 onOpen 路径统一）：
            // onOpen 回调是在 SQLiteOpenHelper 的监视器内调
            // vacuumAndMark 取 vacuumLock 的；若本路径先持 vacuumLock
            // 再开库，恰有另一线程同时在首开数据库（持 helper 监视
            // 器、在 onOpen 里等 vacuumLock）时两边互等死锁。先开库
            // 后，本路径的取锁顺序与 onOpen 一致（helper 监视器 →
            // vacuumLock），反转消除；开库触发的 onOpen 只处理 v10
            // 标记（与本路径的拆库标记各查各的），不会重复执行。
            val db = getInstance(com.jobeen.ime.base.util.appContext)
                .openHelper.writableDatabase
            vacuumLock.lock()
            try {
                // 锁内复查：锁外快检会让并发首跑一起走到这里，二次确认
                // 保证整个进程只有第一个真正执行，其余直接返回
                if (prefs.getBoolean(KEY_VACUUM_CLIP_DONE, false)) return
                // 失败退避：上次失败未过退避间隔不再重试，避免失败态下
                // 每次剪贴板访问都同步重跑一遍注定失败的 VACUUM
                val lastFail = prefs.getLong(KEY_VACUUM_CLIP_LAST_FAIL, 0L)
                if (lastFail > 0L &&
                    System.currentTimeMillis() - lastFail < VACUUM_CLIP_RETRY_BACKOFF_MS
                ) return
                // 空间预检：VACUUM 要另建一份库副本，临时空间约需一倍
                // 库大小；不足时执行必然失败，直接记失败走退避。需求
                // 口径把 WAL 一并计入：未 checkpoint 的 WAL 页同样占着
                // 空间、且 VACUUM 期间仍在写，只按主文件大小估会把恰
                // 在边界上的设备放行后照样失败（-wal 不存在按 0）
                val dbFile = com.jobeen.ime.base.util.appContext
                    .getDatabasePath("ime_database")
                if (dbFile.isFile) {
                    val usable = runCatching {
                        android.os.StatFs(dbFile.parent).availableBytes
                    }.getOrDefault(Long.MAX_VALUE)
                    val walFile = java.io.File(dbFile.path + "-wal")
                    val walLength = if (walFile.isFile) walFile.length() else 0L
                    val required = dbFile.length() + walLength
                    if (usable < required) {
                        timber.log.Timber.w(
                            "Post-split VACUUM skipped: usable space %d < required %d (db %d + wal %d)",
                            usable, required, dbFile.length(), walLength,
                        )
                        prefs.edit { putLong(KEY_VACUUM_CLIP_LAST_FAIL, System.currentTimeMillis()) }
                        return
                    }
                }
                if (!vacuumAndMark(db, prefs, KEY_VACUUM_CLIP_DONE)) {
                    prefs.edit { putLong(KEY_VACUUM_CLIP_LAST_FAIL, System.currentTimeMillis()) }
                }
            } finally {
                vacuumLock.unlock()
            }
        }

        /**
         * 拆库 VACUUM 维护入口（ClipboardManager 的 db() 补跑点调用）：
         * [KEY_VACUUM_CLIP_REDO_V2] 未置位时，先清一遍拆库完成/失败
         * 标记再走既有补跑——覆盖「迁移早已完成、完成标记却被误置」
         * 的设备子集（迁移体早退使 [clearPostSplitVacuumMarks] 对这批
         * 设备永不执行，见该键注释）。只有补跑真正完成（完成标记已
         * 落）才置位本键：失败/退避时不置位，下次访问继续重试，不把
         * 一次失败固化成「已补跑」。置位后本入口退化为直接走既有
         * 补跑（迁移新删行后的常规补跑仍靠它）。
         */
        fun runPostSplitVacuumMaintenance() {
            val prefs = com.jobeen.ime.base.util.appContext
                .getSharedPreferences(MAINTENANCE_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_VACUUM_CLIP_REDO_V2, false)) {
                vacuumAfterClipboardSplit()
                return
            }
            // 退避优先于清标：clearPostSplitVacuumMarks 会连失败标记
            // 一并清掉，若每次补跑入口都先清标，vacuumAfterClipboardSplit
            // 内的 24 小时退避永远读到空值、持续失败的设备每次剪贴板
            // 访问都要重复一遍空间预检。故上次补跑失败且未出退避窗
            // 时直接返回，不清标不重跑。
            val lastFail = prefs.getLong(KEY_VACUUM_CLIP_LAST_FAIL, 0L)
            if (lastFail > 0L &&
                System.currentTimeMillis() - lastFail < VACUUM_CLIP_RETRY_BACKOFF_MS
            ) return
            clearPostSplitVacuumMarks()
            vacuumAfterClipboardSplit()
            if (prefs.getBoolean(KEY_VACUUM_CLIP_DONE, false)) {
                prefs.edit { putBoolean(KEY_VACUUM_CLIP_REDO_V2, true) }
            }
        }

        /**
         * 清除拆库 VACUUM 的完成/失败标记（供 ClipboardManager 的拆库
         * 迁移在真正删掉旧行后调用）：在 v1.1.11.x 上迁移失败但
         * VACUUM 照样跑完的设备，完成标记已被误置为 true——此后迁
         * 移即使成功删行，[vacuumAfterClipboardSplit] 也会被完成标
         * 记短路，旧剪贴板全文永久留在主库空闲页里、随云备份外带，
         * 拆库的隐私隔离对这批设备等于没做。清标后，db() 随后派发
         * 的补跑才会真正重新执行一次物理擦除；失败标记一并清掉，
         * 免得 24 小时退避期挡住这次正当补跑。
         */
        fun clearPostSplitVacuumMarks() {
            val prefs = com.jobeen.ime.base.util.appContext
                .getSharedPreferences(MAINTENANCE_PREFS, Context.MODE_PRIVATE)
            prefs.edit {
                putBoolean(KEY_VACUUM_CLIP_DONE, false)
                remove(KEY_VACUUM_CLIP_LAST_FAIL)
            }
        }

        /**
         * VACUUM + 落标记 + 截断 WAL 的统一实现。顺序是关键：
         * 标记必须紧随 VACUUM 成功立即落盘——wal_checkpoint 会返回
         * 结果行，只能用 query 读掉（execSQL 执行返回行的语句在多
         * 个 Android 版本上会抛异常）；旧写法把标记放在 checkpoint
         * 之后、同一 runCatching 内，checkpoint 一抛错标记永远写不
         * 上，每次打开库都重跑 VACUUM、WAL 也永远截不断。
         * checkpoint 失败只记日志：主文件已被 VACUUM 重建，标记
         * 不应因此缺失。
         */
        private fun vacuumAndMark(
            db: SupportSQLiteDatabase,
            prefs: android.content.SharedPreferences,
            doneKey: String,
        ): Boolean {
            // 全部 VACUUM 经同一把锁串行化（v10 打开回调与拆库补跑
            // 两条路径）：同一时刻只有一个 VACUUM 在跑，不会互相
            // 抢库锁把对方打成失败
            vacuumLock.lock()
            try {
                return runCatching {
                    db.execSQL("VACUUM")
                    prefs.edit { putBoolean(doneKey, true) }
                    runCatching {
                        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                    }.onFailure { timber.log.Timber.w(it, "wal_checkpoint(TRUNCATE) failed") }
                    true
                }.onFailure {
                    timber.log.Timber.w(it, "VACUUM failed; will retry")
                }.getOrDefault(false)
            } finally {
                vacuumLock.unlock()
            }
        }

        /**
         * v10 VACUUM 完成标记按库名分键：本回调挂在 [build] 上，生产库
         * 与 createWithName 的测试库共用——共用一个全局键时，测试库打开
         * 也会置位生产标记（反之生产先跑完也会让测试库误以为已跑），
         * 两边各记各的后互不污染。
         */
        private fun vacuumV10DoneKey(dbName: String): String =
            "${KEY_VACUUM_V10_DONE}_$dbName"

        /**
         * 一次性 VACUUM：v10 迁移只把 candidate_prefers.context 的值清空，
         * 旧输入片段仍留在数据库空闲页中（迁移在事务内、无法执行 VACUUM）。
         * 库打开后（不在事务中）补跑一次 VACUUM 重建文件彻底擦除，成功后
         * 落标记，之后每次打开只是一次偏好读检查。
         * 兼容读：生产库此前用无后缀的旧键记账，已跑过的设备旧键为
         * true——直接认账并回填分键，不重复跑一次 VACUUM；旧键只对
         * 生产库名生效，测试库不读、也永不写旧键。
         */
        private val VACUUM_CALLBACK = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                val prefs = com.jobeen.ime.base.util.appContext
                    .getSharedPreferences(MAINTENANCE_PREFS, Context.MODE_PRIVATE)
                val dbName = db.path?.let { java.io.File(it).name }
                    ?.takeIf { it.isNotEmpty() } ?: return
                val doneKey = vacuumV10DoneKey(dbName)
                if (prefs.getBoolean(doneKey, false)) return
                if (dbName == PRODUCTION_DB_NAME &&
                    prefs.getBoolean(KEY_VACUUM_V10_DONE, false)
                ) {
                    prefs.edit { putBoolean(doneKey, true) }
                    return
                }
                // VACUUM 重建主文件后旧页还可能残留在 WAL 里，vacuumAndMark 一并截断
                vacuumAndMark(db, prefs, doneKey)
            }
        }

        private val MIGRATION_1_2: Migration = Migration(
            startVersion = 1,
            endVersion = 2,
        ) { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `clipboard_records` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`text` TEXT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`cloud` INTEGER NOT NULL)"
            )
        }

        private val MIGRATION_2_3: Migration = Migration(
            startVersion = 2,
            endVersion = 3,
        ) { db ->
            db.execSQL("ALTER TABLE `clipboard_records` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
        }

        private val MIGRATION_3_4: Migration = Migration(
            startVersion = 3,
            endVersion = 4,
        ) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `candidate_prefers` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `text` TEXT NOT NULL,
                    `context` TEXT NOT NULL,
                    `click_count` INTEGER NOT NULL DEFAULT 1,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """
            )
        }

        private val MIGRATION_4_5: Migration = Migration(
            startVersion = 4,
            endVersion = 5,
        ) { db ->
            // 主键从自增 id 改为 text：同结构搬数据，不要 DROP 后重建白白丢掉用户选词偏好
            db.execSQL(
                """
                CREATE TABLE `candidate_prefers_new` (
                    `text` TEXT NOT NULL PRIMARY KEY,
                    `context` TEXT NOT NULL,
                    `click_count` INTEGER NOT NULL DEFAULT 1,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """
            )
            db.execSQL(
                """
                INSERT INTO `candidate_prefers_new` (text, context, click_count, created_at, updated_at)
                SELECT text,
                    (SELECT context FROM candidate_prefers o2
                     WHERE o2.text = o.text ORDER BY updated_at DESC LIMIT 1),
                    SUM(click_count), MIN(created_at), MAX(updated_at)
                FROM candidate_prefers o GROUP BY text
                """
            )
            db.execSQL("DROP TABLE IF EXISTS `candidate_prefers`")
            db.execSQL("ALTER TABLE `candidate_prefers_new` RENAME TO `candidate_prefers`")
        }

        private val MIGRATION_5_6: Migration = Migration(
            startVersion = 5,
            endVersion = 6,
        ) { db ->
            db.execSQL("ALTER TABLE `clipboard_records` ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0")
        }

        private val MIGRATION_6_7: Migration = Migration(
            startVersion = 6,
            endVersion = 7,
        ) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `phrase_records` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `text` TEXT NOT NULL,
                    `label` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """
            )
        }

        // v8 仅随包名改版 bump，无结构变更；空迁移避免 fallbackToDestructiveMigration 清库
        private val MIGRATION_7_8: Migration = Migration(
            startVersion = 7,
            endVersion = 8,
        ) { _ -> }

        // v9：与 ClipboardDatabase v2 同步——ClipboardRecord 实体共用，旧主库里的
        // clipboard_records 残表也要补 pinned 列，否则实体 schema 校验不过（fail-fast 崩）
        private val MIGRATION_8_9: Migration = Migration(
            startVersion = 8,
            endVersion = 9,
        ) { db ->
            db.execSQL("ALTER TABLE `clipboard_records` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
        }

        // v10：清空历史遗留的 candidate_prefers.context（旧版本存了光标前 20 字的输入片段，
        // 该列从未被读取）。结构不变，只擦内容；列保留以兼容旧备份与 Room 实体。
        // 注意：SQLite 空闲页不会被立即覆写，彻底擦除需另行 VACUUM。
        private val MIGRATION_9_10: Migration = Migration(
            startVersion = 9,
            endVersion = 10,
        ) { db ->
            db.execSQL("UPDATE `candidate_prefers` SET `context` = ''")
        }

        // v11：phrase_records 新增 sortOrder（常用语拖动排序），回填
        // -createdAt 使既有顺序（新建在前）不变；主库里的 clipboard_records
        // 残表与独立库共用同一实体，列必须同步加上（残表数据已迁走，
        // 无需回填）
        private val MIGRATION_10_11: Migration = Migration(
            startVersion = 10,
            endVersion = 11,
        ) { db ->
            db.execSQL("ALTER TABLE `phrase_records` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `phrase_records` SET `sortOrder` = -`createdAt`")
            db.execSQL("ALTER TABLE `clipboard_records` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
        }

        // v12：phrase_records 新增 pinned（常用语置顶，与剪贴板同语义），默认未置顶
        private val MIGRATION_11_12: Migration = Migration(
            startVersion = 11,
            endVersion = 12,
        ) { db ->
            db.execSQL("ALTER TABLE `phrase_records` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
        }

        // v13：与 ClipboardDatabase v4 同步——ClipboardRecord 实体共用，实体
        // 新增了 text 唯一索引，主库里的 clipboard_records 残表必须配套建
        // 同名索引，否则 Room identity 校验不过（fail-fast 崩）。步骤与
        // ClipboardDatabase.MIGRATION_3_4 严格同规：先清软删行，再按
        // 「pinned 优先、timestamp 最新、id 最大」EXISTS 去重，最后建
        // 唯一索引。残表数据本已迁去独立库，这里多为空表/少量残行，
        // 但 SQL 按全表口径写，老设备上的任何存量都不例外。
        private val MIGRATION_12_13: Migration = Migration(
            startVersion = 12,
            endVersion = 13,
        ) { db ->
            db.execSQL("DELETE FROM clipboard_records WHERE deleted = 1")
            db.execSQL(
                "DELETE FROM clipboard_records WHERE EXISTS (" +
                    "SELECT 1 FROM clipboard_records other " +
                    "WHERE other.text = clipboard_records.text AND (" +
                    "other.pinned > clipboard_records.pinned OR " +
                    "(other.pinned = clipboard_records.pinned AND other.timestamp > clipboard_records.timestamp) OR " +
                    "(other.pinned = clipboard_records.pinned AND other.timestamp = clipboard_records.timestamp AND other.id > clipboard_records.id)))"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_clipboard_records_text` ON `clipboard_records` (`text`)"
            )
        }
    }
}