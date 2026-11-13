// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import com.jobeen.ime.base.update.DictFingerprint
import com.jobeen.ime.base.util.DigestUtil
import java.io.File

/**
 * 部署输入指纹：目录名＋启用镜像串＋三棵树（用户目录、共享目录、
 * 主题目录）的文件清单。清单每条为「相对路径|长度|mtime」，顶层 yaml
 * 与主题文件再加内容 SHA（防纯 mtime 误判：解压/导入重写同内容文件
 * 只改 mtime 时，没有 SHA 会误 hit；内容变而 mtime 不变时，没有 SHA
 * 同样误 hit）。判定方向宁可误 miss（多编一次）不可误 hit（漏编一次）：
 * 任何读失败都返回 null，按 miss 处理。
 *
 * 主题树（WO38 E1-6）：主题本体 themes.json 住在外部根的 themes/
 *（App.themesDir），与用户／共享两棵树是同一外部根下的兄弟树；
 * 此前指纹只扫用户／共享两棵树，主题切换改 themes.json 指纹不变，
 * 部署跳过判定会跳过一次必需的重编——是真缺口，本文件补上。
 */

/** 部署输入文件一条：相对路径带 user/、shared/ 或 theme/ 根前缀。 */
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
 * 收集三棵树的部署输入清单。任一根不是目录、遍历或读摘要失败都
 * 返回 null（调用方按 miss 处理）。清单顺序不敏感：指纹计算时排序。
 * 主题目录不存在（旧版数据／布局外路径）时主题树不计入，与旧行为一致。
 */
internal fun collectDeployInputs(userDir: File, sharedDir: File): List<DeployInputFile>? {
    if (!userDir.isDirectory || !sharedDir.isDirectory) return null
    return runCatching {
        val out = mutableListOf<DeployInputFile>()
        collectRoot(userDir, "user", out)
        collectRoot(sharedDir, "shared", out)
        // 主题树（WO38 E1-6）：主题切换改 themes.json 必须让指纹变，
        // 否则部署跳过判定会跳过一次必需的重编；主题树读失败则整体
        // 返回 null（按 miss 处理，绝不误 hit）。
        val themesDir = resolveThemesDir(userDir)
        if (themesDir != null && themesDir.isDirectory) {
            out += collectThemeInputs(themesDir)
        }
        out
    }.getOrNull()
}

/**
 * 主题目录定位（WO38 E1-6）：主题文件住在外部根的 themes/
 *（App.themesDir = extDir("themes")），引擎 user 目录是外部根的
 * user/＜目录名＞（DataManager.schemeDirsRoot／userDataDir），
 * 故从 userDir 上溯两级即外部根。推不出来（布局外路径，如单测的
 * 孤立临时目录）时返回 null，主题树不计入（与旧行为一致）。
 */
internal fun resolveThemesDir(userDir: File): File? {
    val extRoot = userDir.parentFile?.parentFile ?: return null
    return File(extRoot, "themes")
}

/**
 * 收集主题树输入（WO38 E1-6，前缀 theme/）：themes.json 是主题本体，
 * 内容 SHA 计入（小文件；与顶层 yaml 同口径——内容变而 mtime 不变
 * 时也不误 hit）。`*.tmp` 是保存中途的临时文件（ThemeStore 写 tmp
 * 再 rename），不计入：残留 tmp 本不是主题输入，计入只会让指纹多 miss。
 */
internal fun collectThemeInputs(themesDir: File): List<DeployInputFile> {
    val out = mutableListOf<DeployInputFile>()
    themesDir.walkTopDown().filter { it.isFile }.forEach { file ->
        if (file.name.endsWith(".tmp")) return@forEach
        val rel = "theme/${file.relativeTo(themesDir).invariantSeparatorsPath}"
        val sha = if (file.name == "themes.json") DigestUtil.sha256Hex(file) else null
        out.add(DeployInputFile(rel, file.length(), file.lastModified(), sha))
    }
    return out
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
