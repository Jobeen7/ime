package com.jobeen.ime.base.net

import com.jobeen.ime.base.ngram.normalizeSha256
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * GitHub Release 资产取数（检查更新/模型下载共用）。
 *
 * 摘要只取 Release 对象内嵌的 digest：内嵌 assets 数组偶有缺项
 * （2026-10-10 实测 sherpa-onnx 的 asr-models 发布内嵌 498 个、
 * 专用端点 499 个），缺项时返回空串，由调用方按「查不到降级」
 * 口径处理（继续下载、自算哈希存记录），不再为补一个摘要养资
 * 产分页端点机器、更不许因查不到摘要拒绝更新（S2）。
 */

/** 资产列表函数的页数上限（每页 100 个；asr-models 约 500 个，12 页足够且防失控） */
private const val MAX_LIST_PAGES = 12

/**
 * 拉取某个 Release 的全部资产（名 → 登记 SHA-256，已归一化；资产无
 * 登记摘要时值为空串）。走资产分页端点：选包要看到全量资产名，
 * 内嵌数组可缺项，不能只读内嵌。任何失败（取不到 release id、
 * 某页请求失败、结构变化）返回空列表，调用方据此走兜底（如内
 * 置钉死表），不把部分结果当全量清单。
 */
internal fun fetchGithubReleaseAssets(
    client: OkHttpClient,
    owner: String,
    repo: String,
    tag: String,
): List<Pair<String, String>> = runCatching {
    val releaseId = fetchReleaseId(client, owner, repo, tag)
    if (releaseId <= 0L) return@runCatching emptyList()
    val out = mutableListOf<Pair<String, String>>()
    var page = 1
    while (page <= MAX_LIST_PAGES) {
        val url = "https://api.github.com/repos/$owner/$repo" +
            "/releases/$releaseId/assets?per_page=100&page=$page"
        val body = githubGet(client, url) ?: return@runCatching emptyList()
        val assets = JSONArray(body)
        if (assets.length() == 0) break
        for (i in 0 until assets.length()) {
            val item = assets.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isNotEmpty()) {
                out.add(name to normalizeSha256(item.optString("digest")))
            }
        }
        if (assets.length() < 100) break
        page++
    }
    out
}.getOrElse {
    Timber.w(it, "Failed to list GitHub release assets")
    emptyList()
}

/**
 * 向 GitHub 查某个 Release 资产登记的官方 SHA-256，只读 Release
 * 对象内嵌的 assets 数组（单次请求、无分页）。内嵌缺项或任何
 * 失败（网络、限流、结构变化、找不到资产）返回空串，调用方降
 * 级处理，不得据此拒绝下载。
 */
internal fun fetchGithubReleaseEmbeddedDigest(
    client: OkHttpClient,
    owner: String,
    repo: String,
    tag: String,
    assetName: String,
): String = runCatching {
    val body = githubGet(
        client, "https://api.github.com/repos/$owner/$repo/releases/tags/$tag"
    ) ?: return@runCatching ""
    val assets = JSONObject(body).optJSONArray("assets") ?: return@runCatching ""
    val pairs = (0 until assets.length()).mapNotNull { i ->
        assets.optJSONObject(i)?.let {
            it.optString("name") to it.optString("digest")
        }
    }
    findAssetDigest(pairs, assetName) ?: ""
}.getOrElse {
    Timber.w(it, "Failed to fetch GitHub release embedded digest")
    ""
}

private fun fetchReleaseId(
    client: OkHttpClient, owner: String, repo: String, tag: String
): Long {
    val body = githubGet(
        client, "https://api.github.com/repos/$owner/$repo/releases/tags/$tag"
    ) ?: return 0L
    return JSONObject(body).optLong("id", 0L)
}

private fun githubGet(client: OkHttpClient, url: String): String? {
    val request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .build()
    return client.newCall(request).execute().use { response ->
        if (response.isSuccessful) response.body?.string() else null
    }
}

/** 在资产（名→登记摘要）列表里按名找摘要；找不到返回 null（与「摘要为空」区分）。 */
internal fun findAssetDigest(
    assets: List<Pair<String, String>>, assetName: String
): String? {
    for ((name, digest) in assets) {
        if (name == assetName) return normalizeSha256(digest)
    }
    return null
}
