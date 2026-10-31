// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import com.jobeen.ime.base.update.DictFingerprint
import com.jobeen.ime.base.util.DigestUtil
import java.io.File

/**
 * 部署输入指纹：目录名＋启用镜像串＋两棵树（用户目录、共享目录）
 * 的文件清单。清单每条为「相对路径|长度|mtime」，顶层 yaml 再加
 * 内容 SHA（防纯 mtime 误判：解压/导入重写同内容文件只改 mtime
 * 时，没有 SHA 会误 hit；内容变而 mtime 不变时，没有 SHA 同样误 hit）。
 * 判定方向宁可误 miss（多编一次）不可误 hit（漏编一次）：
 * 任何读失败都返回 null，按 miss 处理。
 */

/** 部署输入文件一条：相对路径带 user/ 或 shared/ 根前缀。 */
internal data class DeployInputFile(
    val relPath: String,
    val length: Long,
    val lastModified: Long,
    val contentSha: String?,
)

/** 部署指纹偏好键：每目录分键存放（目录切换本身永不跳过，但各目录的指纹互不覆盖）。 */
internal fun deployFingerprintKey(dirName: String): String = "deploy_fingerprint_$dirName"

/**
 * 指纹排除项——只列这三类已确认的非输入，余者一律计入：
 * - 任意 build/ 段：部署产物目录（用户目录 build/、共享 build/），每次部署都变；
 * - 文件名 user.yaml：librime 每次部署都重写其 last_build_time；
 * - 任意含 .userdb 的段：用户词典运行期数据（LevelDB 目录与 .userdb.txt 快照），学词即变。
 * 排除之外的新生成文件若出现，只会让指纹 miss（多编一次），不会误 hit。
 */
internal fun isDeployFingerprintExcluded(relPath: String): Boolean {
    val segments = relPath.split('/')
    if ("build" in segments) return true
    if (segments.lastOrNull() == "user.yaml") return true
    if (segments.any { ".userdb" in it }) return true
    return false
}

/** 顶层 yaml（根下一层、.yaml/.yml）才算内容 SHA：小文件读代价可忽略；嵌套大词库只用 stat，免得每次检查都读几十 MB。 */
private fun needsContentSha(relPath: String): Boolean {
    val segments = relPath.split('/')
    if (segments.size != 2) return false
    val name = segments[1]
    return name.endsWith(".yaml") || name.endsWith(".yml")
}

/**
 * 收集两棵树的部署输入清单。任一根不是目录、遍历或读摘要失败都
 * 返回 null（调用方按 miss 处理）。清单顺序不敏感：指纹计算时排序。
 */
internal fun collectDeployInputs(userDir: File, sharedDir: File): List<DeployInputFile>? {
    if (!userDir.isDirectory || !sharedDir.isDirectory) return null
    return runCatching {
        val out = mutableListOf<DeployInputFile>()
        collectRoot(userDir, "user", out)
        collectRoot(sharedDir, "shared", out)
        out
    }.getOrNull()
}

private fun collectRoot(root: File, prefix: String, out: MutableList<DeployInputFile>) {
    root.walkTopDown().filter { it.isFile }.forEach { file ->
        val rel = "$prefix/${file.relativeTo(root).invariantSeparatorsPath}"
        if (isDeployFingerprintExcluded(rel)) return@forEach
        val sha = if (needsContentSha(rel)) DigestUtil.sha256Hex(file) else null
        out.add(DeployInputFile(rel, file.length(), file.lastModified(), sha))
    }
}

/** 指纹本体：目录名＋启用镜像串＋排序后的文件清单条，拼成 SHA-256。 */
internal fun computeDeployFingerprint(
    dirName: String,
    enabledMirror: String,
    files: List<DeployInputFile>,
): String {
    val parts = files.map { f ->
        val base = "${f.relPath}|${f.length}|${f.lastModified}"
        if (f.contentSha != null) "$base|${f.contentSha}" else base
    }
    return DictFingerprint.combine((listOf("dir=$dirName", "mirror=$enabledMirror") + parts).sorted())
}

/**
 * 跳过判定：四者缺一不可。forceFull 为硬约束——切换/导入即使误调
 * wrapper 也不得跳过（切换/导入本身直调 deployAndAwait，不走 wrapper）。
 */
internal fun shouldSkipDeploy(
    stored: String?,
    computed: String?,
    productsPresent: Boolean,
    forceFull: Boolean,
): Boolean {
    if (forceFull) return false
    if (stored == null || computed == null) return false
    if (!productsPresent) return false
    return stored == computed
}
