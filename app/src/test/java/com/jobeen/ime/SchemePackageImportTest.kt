// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.SchemePackageImport
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
}
