// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.SchemePackageImport
import com.jobeen.ime.data.manager.hasZipMagic
import com.jobeen.ime.data.manager.SchemePackageImport.EntryKind
import com.jobeen.ime.data.manager.SchemePackageImport.PlanResult
import com.jobeen.ime.data.manager.SchemePackageImport.SchemePackageRecord
import com.jobeen.ime.data.manager.SchemePackageImport.SchemePackageRegistry
import com.jobeen.ime.data.manager.SchemePackageImport.SkipReason
import com.jobeen.ime.data.manager.SchemePackageImport.StagedEntry
import com.jobeen.ime.data.manager.SchemePackageImport.StagedPackage
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二期本地导入：路径归一化、yaml 行级解析、版本比较、安装规划
 * （同 ID 替换 / 共享文件比版本 / 受保护文件）、登记编解码、落盘与归档。
 * 界面与 SAF 选择不在此测（需真机复核，见验收报告）。
 */
class SchemePackageImportTest {

    private fun staged(
        files: Map<String, String>,
        sizes: Map<String, Long> = emptyMap(),
    ): StagedPackage {
        val entries = files.keys.map { StagedEntry(it, sizes[it] ?: 100L) }
        return StagedPackage(entries) { rel -> files[rel] }
    }

    private fun planOf(
        files: Map<String, String>,
        existingUser: Set<String> = emptySet(),
        existingSharedIds: Set<String> = emptySet(),
        registry: SchemePackageRegistry = SchemePackageRegistry(emptyList()),
        zipBytes: Long = 1000L,
    ): PlanResult = SchemePackageImport.planInstall(
        packageName = "test-pkg",
        zipBytes = zipBytes,
        staged = staged(files),
        preSkipped = emptyList(),
        existingUserFiles = existingUser,
        existingSharedSchemeIds = existingSharedIds,
        registry = registry,
    )

    @Test
    fun normalizeEntryPath_stripsDotAndDotDotInside() {
        assertEquals(
            SchemePackageImport.NormalizedEntry.File("a/b.yaml"),
            SchemePackageImport.normalizeEntryPath("./a/./b.yaml"),
        )
        assertEquals(
            SchemePackageImport.NormalizedEntry.File("a/b.yaml"),
            SchemePackageImport.normalizeEntryPath("a/x/../b.yaml"),
        )
    }

    @Test
    fun normalizeEntryPath_traversalIsFlagged() {
        assertEquals(
            SchemePackageImport.NormalizedEntry.Traversal,
            SchemePackageImport.normalizeEntryPath("../evil.yaml"),
        )
        assertEquals(
            SchemePackageImport.NormalizedEntry.Traversal,
            SchemePackageImport.normalizeEntryPath("a/../../evil.yaml"),
        )
        assertEquals(
            SchemePackageImport.NormalizedEntry.Traversal,
            SchemePackageImport.normalizeEntryPath("/abs/path.yaml"),
        )
    }

    @Test
    fun normalizeEntryPath_directoryIsIgnored() {
        assertEquals(
            SchemePackageImport.NormalizedEntry.Ignored,
            SchemePackageImport.normalizeEntryPath("dicts/"),
        )
    }

    @Test
    fun stripTopWrapper_stripsSingleCommonDirOnly() {
        assertEquals(
            listOf("wanxiang.schema.yaml", "dicts/wanxiang.dict.yaml"),
            SchemePackageImport.stripTopWrapper(
                listOf(
                    "rime-ice-main/wanxiang.schema.yaml",
                    "rime-ice-main/dicts/wanxiang.dict.yaml",
                ),
            ),
        )
        // 首段不一致不剥；根文件存在也不剥。
        assertEquals(
            listOf("a.schema.yaml", "dicts/a.dict.yaml"),
            SchemePackageImport.stripTopWrapper(
                listOf("a.schema.yaml", "dicts/a.dict.yaml"),
            ),
        )
    }

    @Test
    fun parseSchemaYamlFields_readsNameVersionLayout() {
        val fields = SchemePackageImport.parseSchemaYamlFields(
            """
            schema:
              schema_id: wanxiang_pro
              name: "万象拼音 Pro"
              version: '18.1.7' # trailing comment
              layout: T9
            engine:
              name: something
            """.trimIndent(),
        )
        assertEquals("万象拼音 Pro", fields.name)
        assertEquals("18.1.7", fields.version)
        assertEquals("T9", fields.layout)
    }

    @Test
    fun parseSchemaYamlFields_nestedNameIsNotPickedUp() {
        val fields = SchemePackageImport.parseSchemaYamlFields(
            """
            schema:
              schema_id: x
              switches:
                - name: ascii_mode
                  states: [中文, 西文]
            """.trimIndent(),
        )
        assertEquals(null, fields.name)
    }

    @Test
    fun parseSchemaListFromCustomYaml_blockAndFlow() {
        assertEquals(
            listOf("wanxiang_lite", "wanxiang_t9"),
            SchemePackageImport.parseSchemaListFromCustomYaml(
                """
                patch:
                  schema_list:
                    - wanxiang_lite
                    - wanxiang_t9
                """.trimIndent(),
            ),
        )
        assertEquals(
            listOf("a", "b"),
            SchemePackageImport.parseSchemaListFromCustomYaml("patch:\n  schema_list: [a, b]\n"),
        )
    }

    @Test
    fun compareVersions_mirrorsWanxiangUpdateManager() {
        assertTrue(SchemePackageImport.compareVersions("18.1.7", "18.1.6") > 0)
        assertTrue(SchemePackageImport.compareVersions("v18.1.7", "18.1.7") == 0)
        assertTrue(SchemePackageImport.compareVersions("LTS", "18.1.7") < 0)
        assertTrue(SchemePackageImport.compareVersions("1.0", "1.0.1") < 0)
    }

    @Test
    fun planInstall_noSchemaFails() {
        val result = planOf(mapOf("version.txt" to "18.1.7\n"))
        assertTrue(result is PlanResult.Failed)
        assertEquals(
            SchemePackageImport.PlanFailure.NO_SCHEMA,
            (result as PlanResult.Failed).reason,
        )
    }

    @Test
    fun planInstall_zipTooLargeFails() {
        val result = planOf(
            mapOf("x.schema.yaml" to "schema:\n  name: X\n"),
            zipBytes = SchemePackageImport.MAX_ZIP_BYTES + 1,
        )
        assertEquals(
            SchemePackageImport.PlanFailure.ZIP_TOO_LARGE,
            (result as PlanResult.Failed).reason,
        )
    }

    @Test
    fun planInstall_wrapperDirPackageParsesSchemes() {
        val files = mapOf(
            "rime-ice-main/wanxiang_pro.schema.yaml" to
                "schema:\n  name: 万象拼音 Pro\n  version: 18.1.7\n",
            "rime-ice-main/wanxiang_pro.dict.yaml" to "x\n",
            "rime-ice-main/version.txt" to "18.1.7\n",
        )
        val result = planOf(files)
        val plan = (result as PlanResult.Success).plan
        assertEquals("18.1.7", plan.packageVersion)
        assertEquals(listOf("wanxiang_pro"), plan.schemes.map { it.id })
        assertEquals("万象拼音 Pro", plan.schemes.first().name)
        assertTrue("wanxiang_pro.schema.yaml" in plan.installFiles)
        assertTrue("wanxiang_pro.dict.yaml" in plan.installFiles)
    }

    @Test
    fun planInstall_protectedFilesNeverInstalled() {
        val files = mapOf(
            "x.schema.yaml" to "schema:\n  name: X\n",
            "x.userdb" to "data",
            "user.yaml" to "x",
            "installation.yaml" to "x",
            "build/x.yaml" to "x",
            "x.custom.yaml" to "patch: {}\n",
        )
        val result = planOf(
            files,
            existingUser = setOf("x.custom.yaml"),
        )
        val plan = (result as PlanResult.Success).plan
        assertFalse("x.userdb" in plan.installFiles)
        assertFalse("user.yaml" in plan.installFiles)
        assertFalse("installation.yaml" in plan.installFiles)
        assertFalse("build/x.yaml" in plan.installFiles)
        // 已存在的 *.custom.yaml 视为用户改过，跳过。
        assertFalse("x.custom.yaml" in plan.installFiles)
        assertTrue(
            plan.skippedFiles.any {
                it.relPath == "x.custom.yaml" && it.reason == SkipReason.USER_CUSTOM
            },
        )
        assertTrue(
            plan.skippedFiles.any {
                it.relPath == "x.userdb" && it.reason == SkipReason.PROTECTED
            },
        )
    }

    @Test
    fun planInstall_defaultCustomMergesOnly() {
        val files = mapOf(
            "x.schema.yaml" to "schema:\n  name: X\n",
            "default.custom.yaml" to "patch:\n  schema_list:\n    - x\n    - y\n",
        )
        val plan = (planOf(files) as PlanResult.Success).plan
        assertEquals(listOf("x", "y"), plan.defaultCustomSchemaIds)
        assertFalse("default.custom.yaml" in plan.installFiles)
    }

    @Test
    fun planInstall_sameIdReplacementStillDetected() {
        val registry = SchemePackageRegistry(
            listOf(
                SchemePackageRecord(
                    packageName = "old-pro",
                    version = "18.1.6",
                    schemeIds = listOf("wanxiang_pro"),
                    files = listOf("wanxiang_pro.schema.yaml", "wanxiang_pro.dict.yaml"),
                ),
            ),
        )
        val files = mapOf(
            "wanxiang_pro.schema.yaml" to "schema:\n  name: 万象拼音 Pro\n  version: 18.1.7\n",
            "wanxiang_pro.dict.yaml" to "x\n",
        )
        val plan = (planOf(
            files,
            existingUser = setOf("wanxiang_pro.schema.yaml", "wanxiang_pro.dict.yaml"),
            registry = registry,
        ) as PlanResult.Success).plan
        assertEquals(1, plan.replacements.size)
        assertEquals("wanxiang_pro", plan.replacements.first().schemeId)
        assertEquals("old-pro", plan.replacements.first().previousPackage)
        assertFalse(plan.replacements.first().overridesBuiltin)
        assertEquals(
            listOf("wanxiang_pro.schema.yaml", "wanxiang_pro.dict.yaml"),
            plan.overwrittenExisting,
        )
    }

    @Test
    fun planInstall_builtinOverrideIsFlaggedSeparately() {
        val files = mapOf(
            "wanxiang_lite.schema.yaml" to "schema:\n  name: 万象拼音 Lite\n",
        )
        val plan = (planOf(
            files,
            existingSharedIds = setOf("wanxiang_lite"),
        ) as PlanResult.Success).plan
        assertEquals(1, plan.replacements.size)
        assertTrue(plan.replacements.first().overridesBuiltin)
        assertEquals(null, plan.replacements.first().previousPackage)
    }

    @Test
    fun planInstall_sharedVersionCompare() {
        val registry = SchemePackageRegistry(
            listOf(
                SchemePackageRecord(
                    packageName = "old",
                    version = "18.1.6",
                    schemeIds = listOf("a"),
                    files = listOf("wanxiang_algebra.yaml", "x.gram"),
                ),
            ),
        )
        val files = mapOf(
            "a.schema.yaml" to "schema:\n  name: A\n  version: 18.1.7\n",
            "wanxiang_algebra.yaml" to "x",
            "x.gram" to "x",
        )
        // 新包 18.1.7 > 旧 18.1.6：共享文件覆盖。
        val newer = (planOf(
            files,
            existingUser = setOf("wanxiang_algebra.yaml", "x.gram"),
            registry = registry,
        ) as PlanResult.Success).plan
        assertTrue("wanxiang_algebra.yaml" in newer.installFiles)
        assertTrue(newer.sharedDecisions.all { it.overwrite })

        // 旧包 18.1.5 < 已装 18.1.6：保留现有。
        val olderFiles = files + mapOf(
            "a.schema.yaml" to "schema:\n  name: A\n  version: 18.1.5\n",
        )
        val older = (planOf(
            olderFiles,
            existingUser = setOf("wanxiang_algebra.yaml", "x.gram"),
            registry = registry,
        ) as PlanResult.Success).plan
        assertFalse("wanxiang_algebra.yaml" in older.installFiles)
        assertTrue(
            older.skippedFiles.any {
                it.relPath == "wanxiang_algebra.yaml" &&
                    it.reason == SkipReason.SHARED_OLDER
            },
        )
    }

    @Test
    fun planInstall_sharedUnknownVersionKeepsExisting() {
        val registry = SchemePackageRegistry(
            listOf(
                SchemePackageRecord(
                    packageName = "old",
                    version = null,
                    schemeIds = listOf("a"),
                    files = listOf("wanxiang_algebra.yaml"),
                ),
            ),
        )
        val files = mapOf(
            "a.schema.yaml" to "schema:\n  name: A\n",
            "wanxiang_algebra.yaml" to "x",
        )
        val plan = (planOf(
            files,
            existingUser = setOf("wanxiang_algebra.yaml"),
            registry = registry,
        ) as PlanResult.Success).plan
        assertFalse("wanxiang_algebra.yaml" in plan.installFiles)
        assertTrue(
            plan.skippedFiles.any {
                it.relPath == "wanxiang_algebra.yaml" &&
                    it.reason == SkipReason.SHARED_UNKNOWN_VERSION
            },
        )
    }

    @Test
    fun mergeSchemaList_appendsOnlyMissing() {
        assertEquals(
            listOf("a", "b", "c"),
            SchemePackageImport.mergeSchemaList(listOf("a", "b"), listOf("b", "c")),
        )
    }

    @Test
    fun registryEncodeDecode_roundTripsAndSkipsMalformed() {
        val registry = SchemePackageRegistry(
            listOf(
                SchemePackageRecord(
                    packageName = "pkg-a",
                    version = "18.1.7",
                    schemeIds = listOf("x", "y"),
                    files = listOf("x.schema.yaml", "x.dict.yaml"),
                ),
                SchemePackageRecord(
                    packageName = "pkg-b",
                    version = null,
                    schemeIds = listOf("z"),
                    files = listOf("z.schema.yaml"),
                ),
            ),
        )
        val decoded = SchemePackageImport.decodeRegistry(
            SchemePackageImport.encodeRegistry(registry) + "\nmalformed-line\n",
        )
        assertEquals(registry, decoded)
        assertEquals("pkg-a", decoded.packageForScheme("y"))
        assertEquals("18.1.7", decoded.versionForFile("x.dict.yaml"))
        assertEquals(null, decoded.versionForFile("z.schema.yaml"))
        assertEquals(
            listOf("x.schema.yaml", "x.dict.yaml"),
            decoded.filesForPackage("pkg-a"),
        )
        // 同名重装替换旧记录。
        val replaced = decoded.withRecord(
            SchemePackageRecord("pkg-a", "18.1.8", listOf("x"), listOf("x.schema.yaml")),
        )
        assertEquals(2, replaced.records.size)
        assertEquals("pkg-a", replaced.packageForScheme("x"))
        assertEquals("18.1.8", replaced.versionForFile("x.schema.yaml"))
    }

    @Test
    fun registryLookups_filterByDirAndLegacyRowsCountAsInitialDir() {
        val registry = SchemePackageRegistry(
            listOf(
                SchemePackageRecord(
                    packageName = "pkg-a",
                    version = "18.1.7",
                    schemeIds = listOf("x"),
                    files = listOf("x.schema.yaml"),
                    source = SchemePackageImport.PackageSource.URL,
                    dir = "dir-a",
                ),
                SchemePackageRecord(
                    packageName = "pkg-a",
                    version = "18.1.8",
                    schemeIds = listOf("x"),
                    files = listOf("x.schema.yaml"),
                    source = SchemePackageImport.PackageSource.LOCAL,
                    dir = "dir-b",
                ),
                SchemePackageRecord(
                    packageName = "pkg-legacy",
                    version = "18.1.6",
                    schemeIds = listOf("y"),
                    files = listOf("y.schema.yaml"),
                    dir = "",
                ),
            ),
        )
        // 同名包跨目录不得串：dir 过滤后各目录只看到自己的记录。
        assertEquals("pkg-a", registry.packageForScheme("x", "dir-a"))
        assertEquals("18.1.7", registry.versionForFile("x.schema.yaml", "dir-a"))
        assertEquals(
            SchemePackageImport.PackageSource.URL,
            registry.sourceForScheme("x", "dir-a"),
        )
        assertEquals("18.1.8", registry.versionForFile("x.schema.yaml", "dir-b"))
        assertEquals(
            SchemePackageImport.PackageSource.LOCAL,
            registry.sourceForScheme("x", "dir-b"),
        )
        assertEquals(listOf("x.schema.yaml"), registry.filesForPackage("pkg-a", "dir-b"))
        assertEquals(null, registry.packageForScheme("x", "dir-missing"))
        // 不传 dir 时保留跨目录汇总语义（首条命中）。
        assertEquals("pkg-a", registry.packageForScheme("x"))
        assertEquals("18.1.7", registry.versionForFile("x.schema.yaml"))
        // 目录为空的旧记录按初始目录计：只在 dir == "default" 时命中。
        assertEquals("pkg-legacy", registry.packageForScheme("y", "default"))
        assertEquals(null, registry.packageForScheme("y", "dir-a"))
    }

    @Test
    fun extractZip_skipsTraversalAndEnforcesCanonicalGuard() {
        val root = Files.createTempDirectory("scheme-import-test").toFile()
        try {
            val zip = File(root, "pkg.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("wanxiang_lite.schema.yaml"))
                out.write("schema:\n  name: Lite\n".toByteArray())
                out.closeEntry()
                out.putNextEntry(ZipEntry("../evil.yaml"))
                out.write("evil".toByteArray())
                out.closeEntry()
                out.putNextEntry(ZipEntry("rime-ice-main/x.dict.yaml"))
                out.write("dict".toByteArray())
                out.closeEntry()
            }
            val staging = File(root, "staging")
            val result = SchemePackageImport.extractZip(zip, staging)
            val extracted = (result as SchemePackageImport.ExtractResult.Ok).extracted
            assertEquals(listOf("../evil.yaml"), extracted.skippedTraversal)
            assertTrue(File(staging, "wanxiang_lite.schema.yaml").isFile)
            assertTrue(File(staging, "rime-ice-main/x.dict.yaml").isFile)
            assertFalse(File(root, "evil.yaml").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractZip_notZipFails() {
        val root = Files.createTempDirectory("scheme-import-test").toFile()
        try {
            val notZip = File(root, "pkg.zip")
            notZip.writeText("not a zip")
            val result = SchemePackageImport.extractZip(notZip, File(root, "staging"))
            assertEquals(
                SchemePackageImport.ExtractFailure.NOT_ZIP,
                (result as SchemePackageImport.ExtractResult.Failed).reason,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun applyPlan_wrappedZipInstallsFromWrapperDir() {
        // codeload 式包装包：暂存盘保留 wrapper/ 前缀，规划路径已剥；
        // 落盘须拼回前缀，否则全部静默跳过（零落盘、无报错）。
        val root = Files.createTempDirectory("scheme-import-test").toFile()
        try {
            val userDir = File(root, "user").apply { mkdirs() }
            val staging = File(root, "staging").apply { mkdirs() }
            File(staging, "rime-ice-main").mkdirs()
            File(staging, "rime-ice-main/wanxiang_pro.schema.yaml").writeText("wrapped schema")
            val plan = SchemePackageImport.SchemeImportPlan(
                packageName = "wrapped-pro",
                packageVersion = "18.1.7",
                schemes = listOf(
                    SchemePackageImport.ImportedScheme("wanxiang_pro", "Pro", "18.1.7", ""),
                ),
                installFiles = listOf("wanxiang_pro.schema.yaml"),
                wrapperPrefix = "rime-ice-main/",
                skippedFiles = emptyList(),
                sharedDecisions = emptyList(),
                replacements = emptyList(),
                defaultCustomSchemaIds = emptyList(),
                overwrittenExisting = emptyList(),
                totalBytes = 100L,
            )
            val installed = SchemePackageImport.applyPlan(staging, userDir, plan)
            assertEquals(listOf("wanxiang_pro.schema.yaml"), installed)
            assertEquals(
                "wrapped schema",
                File(userDir, "wanxiang_pro.schema.yaml").readText(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun applyPlan_installsNewOverOldWithoutTouchingUserdb() {
        val root = Files.createTempDirectory("scheme-import-test").toFile()
        try {
            val userDir = File(root, "user").apply { mkdirs() }
            val staging = File(root, "staging").apply { mkdirs() }
            File(userDir, "wanxiang_pro.schema.yaml").writeText("old schema")
            File(userDir, "wanxiang_pro.userdb").writeText("user learning data")
            File(staging, "wanxiang_pro.schema.yaml").writeText("new schema")
            File(staging, "wanxiang_pro.dict.yaml").writeText("dict")
            val plan = SchemePackageImport.SchemeImportPlan(
                packageName = "new-pro",
                packageVersion = "18.1.7",
                schemes = listOf(
                    SchemePackageImport.ImportedScheme("wanxiang_pro", "Pro", "18.1.7", ""),
                ),
                installFiles = listOf("wanxiang_pro.schema.yaml", "wanxiang_pro.dict.yaml"),
                skippedFiles = emptyList(),
                sharedDecisions = emptyList(),
                replacements = listOf(
                    SchemePackageImport.ReplacementInfo(
                        schemeId = "wanxiang_pro",
                        previousPackage = "old-pro",
                        overridesBuiltin = false,
                    ),
                ),
                defaultCustomSchemaIds = emptyList(),
                overwrittenExisting = listOf("wanxiang_pro.schema.yaml"),
                totalBytes = 100L,
            )
            val installed = SchemePackageImport.applyPlan(staging, userDir, plan)
            assertEquals(
                listOf("wanxiang_pro.schema.yaml", "wanxiang_pro.dict.yaml"),
                installed,
            )
            assertEquals("new schema", File(userDir, "wanxiang_pro.schema.yaml").readText())
            assertEquals("dict", File(userDir, "wanxiang_pro.dict.yaml").readText())
            // 旧版直接被新版覆盖，不再归档；userdb 绝不碰。
            assertEquals("user learning data", File(userDir, "wanxiang_pro.userdb").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun classifyEntry_coversKinds() {
        assertEquals(EntryKind.SCHEMA, SchemePackageImport.classifyEntry("x.schema.yaml"))
        assertEquals(EntryKind.DICT, SchemePackageImport.classifyEntry("x.dict.yaml"))
        assertEquals(EntryKind.SHARED, SchemePackageImport.classifyEntry("x.gram"))
        assertEquals(EntryKind.SHARED, SchemePackageImport.classifyEntry("lua/x.lua"))
        assertEquals(EntryKind.SHARED, SchemePackageImport.classifyEntry("opencc/x.json"))
        assertEquals(EntryKind.SHARED, SchemePackageImport.classifyEntry("wanxiang_algebra.yaml"))
        assertEquals(EntryKind.CUSTOM, SchemePackageImport.classifyEntry("x.custom.yaml"))
        assertEquals(
            EntryKind.DEFAULT_CUSTOM,
            SchemePackageImport.classifyEntry("default.custom.yaml"),
        )
        assertEquals(EntryKind.PROTECTED, SchemePackageImport.classifyEntry("x.userdb/data"))
        assertEquals(EntryKind.PROTECTED, SchemePackageImport.classifyEntry("build/x.yaml"))
        assertEquals(EntryKind.OTHER, SchemePackageImport.classifyEntry("essay.txt"))
    }

    // ---- 事务化安装（A1）与 extractZip 边界（C2）、白名单与转义（C4） ----

    private fun transactionPlan(
        installFiles: List<String>,
        schemes: List<SchemePackageImport.ImportedScheme> = listOf(
            SchemePackageImport.ImportedScheme("wanxiang_pro", "Pro", "18.1.7", ""),
        ),
        defaultCustomSchemaIds: List<String> = emptyList(),
    ): SchemePackageImport.SchemeImportPlan =
        SchemePackageImport.SchemeImportPlan(
            packageName = "tx-pkg",
            packageVersion = "18.1.7",
            schemes = schemes,
            installFiles = installFiles,
            skippedFiles = emptyList(),
            sharedDecisions = emptyList(),
            replacements = emptyList(),
            defaultCustomSchemaIds = defaultCustomSchemaIds,
            overwrittenExisting = emptyList(),
            totalBytes = 100L,
        )

    private fun writeJournal(
        parent: File,
        operationId: String,
        target: File,
        candidate: File,
        backup: File,
        stage: String,
    ) {
        File(parent, ".install-$operationId.journal").writeText(
            "operationId=$operationId\n" +
                "target=${target.absolutePath}\n" +
                "candidate=${candidate.absolutePath}\n" +
                "backup=${backup.absolutePath}\n" +
                "stage=$stage\n",
        )
    }

    @Test
    fun inheritProtected_keepsUserdbCustomUserYamlAndSkipsBuild() {
        val root = Files.createTempDirectory("scheme-import-tx").toFile()
        try {
            val oldDir = File(root, "old").apply { mkdirs() }
            File(oldDir, "wanxiang.userdb").writeText("learning")
            File(oldDir, "wanxiang_pro.custom.yaml").writeText("old custom")
            File(oldDir, "user.yaml").writeText("user")
            File(oldDir, "installation.yaml").writeText("install")
            File(oldDir, "default.custom.yaml").writeText("old table")
            File(oldDir, "wanxiang_pro.schema.yaml").writeText("old schema")
            File(oldDir, "build").mkdirs()
            File(oldDir, "build/old.yaml").writeText("stale build")
            val candidate = File(root, "candidate").apply { mkdirs() }
            File(candidate, "wanxiang_pro.custom.yaml").writeText("package custom")
            val copied = SchemePackageImport.inheritProtected(oldDir, candidate)
            assertEquals("old custom", File(candidate, "wanxiang_pro.custom.yaml").readText())
            assertEquals("learning", File(candidate, "wanxiang.userdb").readText())
            assertEquals("user", File(candidate, "user.yaml").readText())
            assertEquals("install", File(candidate, "installation.yaml").readText())
            assertEquals("old table", File(candidate, "default.custom.yaml").readText())
            assertFalse(File(candidate, "build/old.yaml").exists())
            assertFalse(File(candidate, "wanxiang_pro.schema.yaml").exists())
            assertTrue("wanxiang.userdb" in copied)
            assertTrue("wanxiang_pro.custom.yaml" in copied)
            assertTrue("build/old.yaml" !in copied)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun inheritProtected_missingOldDirReturnsEmpty() {
        val root = Files.createTempDirectory("scheme-import-tx").toFile()
        try {
            val candidate = File(root, "candidate").apply { mkdirs() }
            assertEquals(
                emptyList<String>(),
                SchemePackageImport.inheritProtected(File(root, "nope"), candidate),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installTransaction_successSwapsWritesTableAndKeepsBackup() {
        val root = Files.createTempDirectory("scheme-import-tx").toFile()
        try {
            val target = File(root, "target").apply { mkdirs() }
            File(target, "wanxiang_pro.schema.yaml").writeText("old schema")
            File(target, "wanxiang_pro.custom.yaml").writeText("old custom")
            File(target, "wanxiang.userdb").writeText("learning")
            File(target, "build").mkdirs()
            File(target, "build/old.yaml").writeText("stale build")
            val staging = File(root, "staging").apply { mkdirs() }
            File(staging, "wanxiang_pro.schema.yaml")
                .writeText("schema:\n  name: Pro\n  version: 18.1.7\n")
            File(staging, "wanxiang_pro.dict.yaml").writeText("dict")
            val entries = listOf(
                StagedEntry(
                    "wanxiang_pro.schema.yaml",
                    File(staging, "wanxiang_pro.schema.yaml").length(),
                ),
                StagedEntry(
                    "wanxiang_pro.dict.yaml",
                    File(staging, "wanxiang_pro.dict.yaml").length(),
                ),
            )
            val result = SchemePackageImport.installTransaction(
                stagingDir = staging,
                targetDir = target,
                plan = transactionPlan(
                    listOf("wanxiang_pro.schema.yaml", "wanxiang_pro.dict.yaml"),
                ),
                stagedEntries = entries,
                operationId = "op1",
            )
            val tx = (result as SchemePackageImport.InstallTransactionResult.Success).transaction
            assertEquals("op1", tx.operationId)
            assertFalse(File(root, ".install-op1").exists())
            assertTrue(tx.backupDir.isDirectory)
            assertEquals("old schema", File(tx.backupDir, "wanxiang_pro.schema.yaml").readText())
            assertEquals(
                "schema:\n  name: Pro\n  version: 18.1.7\n",
                File(target, "wanxiang_pro.schema.yaml").readText(),
            )
            assertEquals("dict", File(target, "wanxiang_pro.dict.yaml").readText())
            // 旧受保护文件进新目录；旧 build/ 不带过来。
            assertEquals("old custom", File(target, "wanxiang_pro.custom.yaml").readText())
            assertEquals("learning", File(target, "wanxiang.userdb").readText())
            assertFalse(File(target, "build/old.yaml").exists())
            // 方案表由 defaultCustomSchemaIds 为空回落到 schemes id，并可回读。
            assertEquals(
                listOf("wanxiang_pro"),
                SchemePackageImport.parseSchemaListFromCustomYaml(
                    File(target, "default.custom.yaml").readText(),
                ),
            )
            assertTrue(tx.journalFile.isFile)
            assertTrue(tx.journalFile.readText().contains("stage=SWAPPED"))
            // 回滚恢复旧版并清日志。
            assertTrue(SchemePackageImport.rollbackTransaction(tx.backupDir, target))
            assertEquals("old schema", File(target, "wanxiang_pro.schema.yaml").readText())
            assertFalse(tx.backupDir.exists())
            assertFalse(tx.journalFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installTransaction_customSchemaListOverrideAndCleanupBackup() {
        val root = Files.createTempDirectory("scheme-import-tx").toFile()
        try {
            val target = File(root, "target")
            val staging = File(root, "staging").apply { mkdirs() }
            File(staging, "wanxiang_pro.schema.yaml").writeText("schema:\n  name: Pro\n")
            val entries = listOf(
                StagedEntry(
                    "wanxiang_pro.schema.yaml",
                    File(staging, "wanxiang_pro.schema.yaml").length(),
                ),
            )
            val result = SchemePackageImport.installTransaction(
                stagingDir = staging,
                targetDir = target,
                plan = transactionPlan(listOf("wanxiang_pro.schema.yaml")),
                stagedEntries = entries,
                schemaList = listOf("wanxiang_pro", "extra_scheme"),
                operationId = "op2",
            )
            val tx = (result as SchemePackageImport.InstallTransactionResult.Success).transaction
            assertEquals(
                listOf("wanxiang_pro", "extra_scheme"),
                SchemePackageImport.parseSchemaListFromCustomYaml(
                    File(target, "default.custom.yaml").readText(),
                ),
            )
            // 全新安装无旧目录：无备份；清理幂等成功。
            assertFalse(tx.backupDir.exists())
            assertTrue(SchemePackageImport.cleanupBackup(tx.backupDir))
            assertFalse(tx.journalFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installTransaction_midCopyFailureKeepsOldAndDeletesCandidate() {
        // 可达的中途失败：两条目经 canonical 归一到同一目标，第二项
        // copyTo(overwrite=false) 抛已存在 → COPY_FAILED，已拷的随候选一起删。
        val root = Files.createTempDirectory("scheme-import-tx").toFile()
        try {
            val target = File(root, "target").apply { mkdirs() }
            File(target, "keep.txt").writeText("old")
            val staging = File(root, "staging").apply { mkdirs() }
            File(staging, "a").mkdirs()
            File(staging, "a/b.txt").writeText("inner")
            val size = File(staging, "a/b.txt").length()
            val plan = transactionPlan(
                installFiles = listOf("a/b.txt", "a/../a/b.txt"),
                schemes = emptyList(),
            )
            val result = SchemePackageImport.installTransaction(
                stagingDir = staging,
                targetDir = target,
                plan = plan,
                stagedEntries = listOf(
                    StagedEntry("a/b.txt", size),
                    StagedEntry("a/../a/b.txt", size),
                ),
                operationId = "op-copy",
            )
            val failed = result as SchemePackageImport.InstallTransactionResult.Failed
            assertEquals(SchemePackageImport.TransactionFailure.COPY_FAILED, failed.failure)
            assertEquals("old", File(target, "keep.txt").readText())
            assertFalse(File(root, ".install-op-copy").exists())
            assertFalse(File(root, ".install-op-copy.journal").exists())
            assertFalse(File(root, ".backup-op-copy").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installTransaction_prevalidationFailuresKeepOldAndCreateNothing() {
        val root = Files.createTempDirectory("scheme-import-tx").toFile()
        try {
            val target = File(root, "target").apply { mkdirs() }
            File(target, "keep.txt").writeText("old")
            val staging = File(root, "staging").apply { mkdirs() }
            File(staging, "ok.txt").writeText("ok")
            // 源缺失：零落盘即失败，不静默部分安装。
            val missing = SchemePackageImport.installTransaction(
                stagingDir = staging,
                targetDir = target,
                plan = transactionPlan(listOf("nope.yaml"), schemes = emptyList()),
                stagedEntries = emptyList(),
                operationId = "op-missing",
            )
            assertEquals(
                SchemePackageImport.TransactionFailure.PREVALIDATION,
                (missing as SchemePackageImport.InstallTransactionResult.Failed).failure,
            )
            // 越界条目：零落盘即失败。
            val traversal = SchemePackageImport.installTransaction(
                stagingDir = staging,
                targetDir = target,
                plan = transactionPlan(listOf("../evil.yaml"), schemes = emptyList()),
                stagedEntries = listOf(StagedEntry("../evil.yaml", 3L)),
                operationId = "op-trav",
            )
            assertEquals(
                SchemePackageImport.TransactionFailure.PREVALIDATION,
                (traversal as SchemePackageImport.InstallTransactionResult.Failed).failure,
            )
            assertEquals("old", File(target, "keep.txt").readText())
            assertFalse(File(root, ".install-op-missing").exists())
            assertFalse(File(root, ".install-op-missing.journal").exists())
            assertFalse(File(root, ".install-op-trav").exists())
            assertFalse(File(root, ".install-op-trav.journal").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installTransaction_emptySchemaFileFailsVerification() {
        val root = Files.createTempDirectory("scheme-import-tx").toFile()
        try {
            val target = File(root, "target").apply { mkdirs() }
            File(target, "keep.txt").writeText("old")
            val staging = File(root, "staging").apply { mkdirs() }
            File(staging, "empty.schema.yaml").writeText("")
            val result = SchemePackageImport.installTransaction(
                stagingDir = staging,
                targetDir = target,
                plan = transactionPlan(listOf("empty.schema.yaml")),
                stagedEntries = listOf(StagedEntry("empty.schema.yaml", 0L)),
                operationId = "op-empty",
            )
            assertEquals(
                SchemePackageImport.TransactionFailure.VERIFICATION_FAILED,
                (result as SchemePackageImport.InstallTransactionResult.Failed).failure,
            )
            assertEquals("old", File(target, "keep.txt").readText())
            assertFalse(File(root, ".install-op-empty").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoverIncompleteTransactions_restoresOldKeepsSwapped() {
        val root = Files.createTempDirectory("scheme-import-tx").toFile()
        try {
            // TARGET_MOVED：旧目录已移走、候选未就位 → 备份移回目标。
            val target1 = File(root, "target1")
            val candidate1 = File(root, ".install-op9").apply { mkdirs() }
            File(candidate1, "new.txt").writeText("new")
            val backup1 = File(root, ".backup-op9").apply { mkdirs() }
            File(backup1, "keep.txt").writeText("old")
            writeJournal(root, "op9", target1, candidate1, backup1, "TARGET_MOVED")
            // VERIFIED 且目标还在 → 删候选与日志，旧版原样。
            val target2 = File(root, "target2").apply { mkdirs() }
            File(target2, "keep.txt").writeText("old2")
            val candidate2 = File(root, ".install-op8").apply { mkdirs() }
            val backup2 = File(root, ".backup-op8")
            writeJournal(root, "op8", target2, candidate2, backup2, "VERIFIED")
            // SWAPPED：完整新版，备份与日志原样保留。
            val target3 = File(root, "target3").apply { mkdirs() }
            File(target3, "new.txt").writeText("new3")
            val backup3 = File(root, ".backup-op7").apply { mkdirs() }
            File(backup3, "keep.txt").writeText("old3")
            val candidate3 = File(root, ".install-op7")
            writeJournal(root, "op7", target3, candidate3, backup3, "SWAPPED")
            val recovered = SchemePackageImport.recoverIncompleteTransactions(root)
            assertEquals(3, recovered.size)
            val byId = recovered.associateBy { it.operationId }
            assertTrue(byId.getValue("op9").restoredOld)
            assertTrue(byId.getValue("op8").restoredOld)
            assertFalse(byId.getValue("op7").restoredOld)
            assertEquals("old", File(target1, "keep.txt").readText())
            assertFalse(candidate1.exists())
            assertFalse(File(root, ".install-op9.journal").exists())
            assertEquals("old2", File(target2, "keep.txt").readText())
            assertFalse(candidate2.exists())
            assertFalse(File(root, ".install-op8.journal").exists())
            assertEquals("new3", File(target3, "new.txt").readText())
            assertTrue(backup3.isDirectory)
            assertTrue(File(root, ".install-op7.journal").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractZip_tooManyEntriesFails() {
        val root = Files.createTempDirectory("scheme-import-test").toFile()
        try {
            val zip = File(root, "pkg.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                repeat(SchemePackageImport.MAX_EXTRACT_ENTRIES + 1) { i ->
                    out.putNextEntry(ZipEntry("f%05d.txt".format(i)))
                    out.write("x".toByteArray())
                    out.closeEntry()
                }
            }
            val staging = File(root, "staging")
            val result = SchemePackageImport.extractZip(zip, staging)
            assertEquals(
                SchemePackageImport.ExtractFailure.TOO_MANY_ENTRIES,
                (result as SchemePackageImport.ExtractResult.Failed).reason,
            )
            assertFalse(staging.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractZip_pathLimitsDuplicatesAndConflictsFail() {
        val root = Files.createTempDirectory("scheme-import-test").toFile()
        try {
            fun zipWith(vararg names: String): File {
                val zip = File(root, "pkg-${names.hashCode()}.zip")
                ZipOutputStream(zip.outputStream()).use { out ->
                    names.forEach { name ->
                        out.putNextEntry(ZipEntry(name))
                        out.write("x".toByteArray())
                        out.closeEntry()
                    }
                }
                return zip
            }
            fun reasonFor(zip: File): SchemePackageImport.ExtractFailure {
                val staging = File(root, "staging-${zip.name}")
                val result = SchemePackageImport.extractZip(zip, staging)
                return (result as SchemePackageImport.ExtractResult.Failed).reason
            }
            // 路径超长（单段 1030 字符）。
            assertEquals(
                SchemePackageImport.ExtractFailure.ENTRY_PATH_TOO_LONG,
                reasonFor(zipWith("a".repeat(1025) + ".yaml")),
            )
            // 路径超深（34 段）。
            assertEquals(
                SchemePackageImport.ExtractFailure.ENTRY_PATH_TOO_DEEP,
                reasonFor(zipWith((List(33) { "d" } + "f.yaml").joinToString("/"))),
            )
            // 归一化后重复。
            assertEquals(
                SchemePackageImport.ExtractFailure.DUPLICATE_ENTRY,
                reasonFor(zipWith("a/./b.yaml", "a/b.yaml")),
            )
            // 文件与目录冲突：先文件 a，再 a/b.yaml。
            assertEquals(
                SchemePackageImport.ExtractFailure.ENTRY_CONFLICT,
                reasonFor(zipWith("a", "a/b.yaml")),
            )
            // 目录条目与文件冲突：先目录 a/，再文件 a。
            val dirConflictZip = File(root, "pkg-dirconflict.zip")
            ZipOutputStream(dirConflictZip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("a/"))
                out.closeEntry()
                out.putNextEntry(ZipEntry("a"))
                out.write("x".toByteArray())
                out.closeEntry()
            }
            assertEquals(
                SchemePackageImport.ExtractFailure.ENTRY_CONFLICT,
                reasonFor(dirConflictZip),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractZip_stagingFailureIsIoErrorNotNotZip() {
        val root = Files.createTempDirectory("scheme-import-test").toFile()
        try {
            val zip = File(root, "pkg.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("a.schema.yaml"))
                out.write("schema:\n  name: A\n".toByteArray())
                out.closeEntry()
            }
            // 暂存父目录被文件挡住：mkdirs 失败 → IO_ERROR。
            val blocker = File(root, "blocker").apply { writeText("x") }
            val staging = File(blocker, "staging")
            val result = SchemePackageImport.extractZip(zip, staging)
            assertEquals(
                SchemePackageImport.ExtractFailure.IO_ERROR,
                (result as SchemePackageImport.ExtractResult.Failed).reason,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractZip_skippedTraversalKeepsOnlySamples() {
        val root = Files.createTempDirectory("scheme-import-test").toFile()
        try {
            val zip = File(root, "pkg.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("a.schema.yaml"))
                out.write("schema:\n  name: A\n".toByteArray())
                out.closeEntry()
                repeat(25) { i ->
                    out.putNextEntry(ZipEntry("../evil-$i.yaml"))
                    out.write("evil".toByteArray())
                    out.closeEntry()
                }
            }
            val staging = File(root, "staging")
            val result = SchemePackageImport.extractZip(zip, staging)
            val extracted = (result as SchemePackageImport.ExtractResult.Ok).extracted
            assertEquals(SchemePackageImport.MAX_SKIPPED_TRAVERSAL_SAMPLES, extracted.skippedTraversal.size)
            assertEquals(25, extracted.skippedTraversalCount)
            assertEquals("../evil-0.yaml", extracted.skippedTraversal.first())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun planInstall_schemeIdEqualsFileStem() {
        // id 恒等于文件主干：应用按 File(dir, "${item.id}.schema.yaml") 查方案，
        // 引擎按 schema_list 条目找同名文件，id 经任何字符映射都会找不到文件。
        fun idsOf(files: Map<String, String>): List<String> =
            ((planOf(files) as PlanResult.Success).plan.schemes.map { it.id })
        assertEquals(listOf("my scheme"), idsOf(mapOf("my scheme.schema.yaml" to "schema:\n  name: S\n")))
        assertEquals(listOf("a\"b\nc"), idsOf(mapOf("a\"b\nc.schema.yaml" to "schema:\n  name: S\n")))
        assertEquals(listOf("中文方案"), idsOf(mapOf("中文方案.schema.yaml" to "schema:\n  name: S\n")))
        assertEquals(listOf("wanxiang"), idsOf(mapOf("wanxiang.schema.yaml" to "schema:\n  name: S\n")))
        assertEquals(listOf("c d"), idsOf(mapOf("a/b/c d.schema.yaml" to "schema:\n  name: S\n")))
        assertEquals(listOf("evil"), idsOf(mapOf("../evil.schema.yaml" to "schema:\n  name: S\n")))
        // 白名单碰撞消失："a b" 与 "a_b" 是两个不同主干，id 不再撞成同一个。
        assertEquals(
            listOf("a b", "a_b"),
            idsOf(
                mapOf(
                    "a b.schema.yaml" to "schema:\n  name: S\n",
                    "a_b.schema.yaml" to "schema:\n  name: T\n",
                ),
            ),
        )
        // 登记直接取 plan id：id 原样进登记，与文件主干一致。
        val plan = (planOf(mapOf("my scheme.schema.yaml" to "schema:\n  name: S\n")) as PlanResult.Success).plan
        val record = SchemePackageImport.SchemePackageRecord(
            packageName = "pkg",
            version = null,
            schemeIds = plan.schemes.map { it.id },
            files = plan.installFiles,
        )
        assertEquals(listOf("my scheme"), record.schemeIds)
    }

    @Test
    fun yamlPlainScalar_escapesUnsafeValues() {
        assertEquals("wanxiang_pro", SchemePackageImport.yamlPlainScalar("wanxiang_pro"))
        assertEquals("'a b'", SchemePackageImport.yamlPlainScalar("a b"))
        assertEquals("'a\"b'", SchemePackageImport.yamlPlainScalar("a\"b"))
        assertEquals("\"a\\nb\"", SchemePackageImport.yamlPlainScalar("a\nb"))
        assertEquals("'true'", SchemePackageImport.yamlPlainScalar("true"))
        assertEquals("'123'", SchemePackageImport.yamlPlainScalar("123"))
        assertEquals("''", SchemePackageImport.yamlPlainScalar(""))
        // 生成的方案表经回读与原表一致（含需转义的 id）。
        val ids = listOf("wanxiang_pro", "a b", "true")
        assertEquals(
            ids,
            SchemePackageImport.parseSchemaListFromCustomYaml(
                SchemePackageImport.buildDefaultCustomYamlContent(ids),
            ),
        )
    }

    @Test
    fun scalarValue_roundTripsEscapedIds() {
        // 往返：yamlPlainScalar 写出 → 自读解析还原（普通 / 空格 /
        // 换行 / 单引号 / 双引号与反斜杠 / 保留字）。
        val ids = listOf(
            "wanxiang_pro",
            "a b",
            "a\nb",
            "o'brien",
            "a\"b\\c",
            "true",
            "123",
            "yes",
        )
        assertEquals(
            ids,
            SchemePackageImport.parseSchemaListFromCustomYaml(
                SchemePackageImport.buildDefaultCustomYamlContent(ids),
            ),
        )
    }

    @Test
    fun parseSchemaListFromCustomYaml_readsMapItems() {
        assertEquals(
            listOf("wanxiang_t9", "wanxiang_english", "wanxiang"),
            SchemePackageImport.parseSchemaListFromCustomYaml(
                "patch:\n" +
                    "  schema_list:\n" +
                    "    - schema: wanxiang_t9\n" +
                    "    - schema: wanxiang_english\n" +
                    "    - schema: wanxiang\n",
            ),
        )
    }

    @Test
    fun parseSchemaListFromCustomYaml_readsMixedMapAndBareItems() {
        // 存量旧文件（裸字符串）与新文件（map 项）混写时均读对。
        assertEquals(
            listOf("a", "b", "c d"),
            SchemePackageImport.parseSchemaListFromCustomYaml(
                "patch:\n" +
                    "  schema_list:\n" +
                    "    - a\n" +
                    "    - schema: b\n" +
                    "    - schema: 'c d'\n",
            ),
        )
    }

    @Test
    fun scalarValue_unescapesDoubleQuotedNameField() {
        val fields = SchemePackageImport.parseSchemaYamlFields(
            "schema:\n  name: \"a\\\"b\\\\c\\nd\"\n",
        )
        assertEquals("a\"b\\c\nd", fields.name)
    }

    @Test
    fun planInstall_skipsEmptySchemaStem() {
        val result = planOf(
            mapOf(
                ".schema.yaml" to "schema:\n  name: Empty\n",
                "ok.schema.yaml" to "schema:\n  name: Ok\n",
            ),
        )
        val plan = (result as PlanResult.Success).plan
        assertEquals(listOf("ok"), plan.schemes.map { it.id })
        assertFalse(".schema.yaml" in plan.installFiles)
        assertTrue(
            plan.skippedFiles.any {
                it.relPath == ".schema.yaml" && it.reason == SkipReason.EMPTY_SCHEMA_ID
            },
        )
    }

    @Test
    fun planInstall_onlyEmptySchemaStemIsNoSchema() {
        val result = planOf(
            mapOf(".schema.yaml" to "schema:\n  name: Empty\n"),
        )
        assertTrue(result is PlanResult.Failed)
        assertEquals(
            SchemePackageImport.PlanFailure.NO_SCHEMA,
            (result as PlanResult.Failed).reason,
        )
    }

    @Test
    fun deployTimeoutMs_scalesAndCaps() {
        assertEquals(180_000L, SchemePackageImport.deployTimeoutMs(0L))
        assertEquals(180_000L, SchemePackageImport.deployTimeoutMs(8L * 1024 * 1024 - 1))
        assertEquals(210_000L, SchemePackageImport.deployTimeoutMs(8L * 1024 * 1024))
        assertEquals(390_000L, SchemePackageImport.deployTimeoutMs(63L * 1024 * 1024))
        assertEquals(1_800_000L, SchemePackageImport.deployTimeoutMs(Long.MAX_VALUE))
    }

    @Test
    fun hasZipMagic_acceptsOnlyZipSignatures() {
        assertTrue(hasZipMagic(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertTrue(hasZipMagic(byteArrayOf(0x50, 0x4B, 0x05, 0x06)))
        assertTrue(hasZipMagic(byteArrayOf(0x50, 0x4B, 0x07, 0x08)))
        assertFalse(hasZipMagic(byteArrayOf(0x50, 0x4B, 0x01, 0x02)))
        assertFalse(hasZipMagic(byteArrayOf(0x50, 0x4B, 0x03)))
        assertFalse(hasZipMagic(byteArrayOf()))
        assertFalse(hasZipMagic("Rar!".toByteArray()))
    }

    // ---- 日志原子写（tmp＋rename）与三次 rename 回滚 ----

    @Test
    fun installTransaction_journalIsCompleteAndLeavesNoTmp() {
        val root = Files.createTempDirectory("scheme-import-tx").toFile()
        try {
            val target = File(root, "target").apply { mkdirs() }
            File(target, "wanxiang_pro.schema.yaml").writeText("old schema")
            val staging = File(root, "staging").apply { mkdirs() }
            File(staging, "wanxiang_pro.schema.yaml")
                .writeText("schema:\n  name: Pro\n  version: 18.1.7\n")
            File(staging, "wanxiang_pro.dict.yaml").writeText("dict")
            val entries = listOf(
                StagedEntry(
                    "wanxiang_pro.schema.yaml",
                    File(staging, "wanxiang_pro.schema.yaml").length(),
                ),
                StagedEntry(
                    "wanxiang_pro.dict.yaml",
                    File(staging, "wanxiang_pro.dict.yaml").length(),
                ),
            )
            val result = SchemePackageImport.installTransaction(
                stagingDir = staging,
                targetDir = target,
                plan = transactionPlan(
                    listOf("wanxiang_pro.schema.yaml", "wanxiang_pro.dict.yaml"),
                ),
                stagedEntries = entries,
                operationId = "op1",
            )
            val tx = (result as SchemePackageImport.InstallTransactionResult.Success).transaction
            // 日志五键齐全、可解析：恢复能认出 SWAPPED 阶段且不判为旧版恢复。
            val journalText = tx.journalFile.readText()
            assertTrue(journalText.contains("operationId=op1"))
            assertTrue(journalText.contains("target="))
            assertTrue(journalText.contains("candidate="))
            assertTrue(journalText.contains("backup="))
            assertTrue(journalText.contains("stage=SWAPPED"))
            assertTrue(root.walkTopDown().none { ".tmp" in it.name })
            val recovered = SchemePackageImport.recoverIncompleteTransactions(root)
            assertEquals(1, recovered.size)
            assertFalse(recovered.single().restoredOld)
            assertTrue(tx.journalFile.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    /** renameTo 恒失败的 File：模拟 rename 失败路径（真实文件系统 rename 几乎总成功）。 */
    private class FailRenameFile(path: String) : File(path) {
        override fun renameTo(dest: File): Boolean = false
    }

    @Test
    fun rollbackTransaction_targetRenameFailureKeepsBoth() {
        val root = Files.createTempDirectory("scheme-import-tx").toFile()
        try {
            val realTarget = File(root, "target").apply { mkdirs() }
            File(realTarget, "f.txt").writeText("new")
            val backup = File(root, "backup").apply { mkdirs() }
            File(backup, "f.txt").writeText("old")
            val target = FailRenameFile(realTarget.absolutePath)
            assertFalse(SchemePackageImport.rollbackTransaction(backup, target))
            // 目标 rename 失败：目标原样、备份原样、无 discarded 残留。
            assertEquals("new", File(realTarget, "f.txt").readText())
            assertEquals("old", File(backup, "f.txt").readText())
            assertTrue(root.walkTopDown().none { ".discarded-" in it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rollbackTransaction_backupRenameFailureRestoresTarget() {
        val root = Files.createTempDirectory("scheme-import-tx").toFile()
        try {
            val target = File(root, "target").apply { mkdirs() }
            File(target, "f.txt").writeText("new")
            val realBackup = File(root, "backup").apply { mkdirs() }
            File(realBackup, "f.txt").writeText("old")
            val backup = FailRenameFile(realBackup.absolutePath)
            assertFalse(SchemePackageImport.rollbackTransaction(backup, target))
            // 备份 rename 失败：刚挪走的目标挪回来（仍是新版内容）、备份原样、无残留。
            assertEquals("new", File(target, "f.txt").readText())
            assertEquals("old", File(realBackup, "f.txt").readText())
            assertTrue(root.walkTopDown().none { ".discarded-" in it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deployTimeoutForDir_sumsFilesRecursively() {
        val root = Files.createTempDirectory("deploy-timeout").toFile()
        try {
            assertEquals(180_000L, SchemePackageImport.deployTimeoutForDir(root))
            val sub = File(root, "sub").apply { mkdirs() }
            File(sub, "big.bin").writeBytes(ByteArray(8 * 1024 * 1024))
            assertEquals(210_000L, SchemePackageImport.deployTimeoutForDir(root))
            assertEquals(180_000L, SchemePackageImport.deployTimeoutForDir(File(root, "missing")))
        } finally {
            root.deleteRecursively()
        }
    }
}
