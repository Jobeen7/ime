package com.jobeen.ime.base.net

import com.jobeen.ime.base.ngram.normalizeSha256
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import timber.log.Timber

/**
 * 向 GitHub 查某个 Release 资产登记的官方 SHA-256。
 *
 * 必须走资产分页端点：Release 对象内嵌的 assets 数组并不完整
 * （2026-10-10 实测 sherpa-onnx 的 asr-models 发布：内嵌 498 个、
 * 专用端点 499 个，缺失的资产按名字永远查不到），只读内嵌数组会
 * 让恰好被省略的资产摘要落空、被上层 fail-closed 误拒。
 *
 * 任何失败（网络、限流、结构变化、找不到资产）返回空串，调用方
 * 退到 APK 内置钉死摘要，两者皆无则拒绝下载。
 */
internal fun fetchGithubReleaseAssetSha256(
    client: OkHttpClient,
    owner: String,
    repo: String,
    tag: String,
    assetName: String,
): String = runCatching {
    val releaseId = fetchReleaseId(client, owner, repo, tag)
    if (releaseId <= 0L) return@runCatching ""
    var page = 1
    while (page <= MAX_ASSET_PAGES) {
        val url = "https://api.github.com/repos/$owner/$repo" +
            "/releases/$releaseId/assets?per_page=100&page=$page"
        val body = githubGet(client, url) ?: return@runCatching ""
        val assets = JSONArray(body)
        val pairs = (0 until assets.length()).mapNotNull { i ->
            assets.optJSONObject(i)?.let {
                it.optString("name") to it.optString("digest")
            }
        }
        findAssetDigest(pairs, assetName)?.let { return@runCatching it }
        if (assets.length() < 100) break
        page++
    }
    ""
}.getOrElse {
    Timber.w(it, "Failed to fetch GitHub release asset digest")
    ""
}

private const val MAX_ASSET_PAGES = 20

private fun fetchReleaseId(
    client: OkHttpClient, owner: String, repo: String, tag: String
): Long {
    val body = githubGet(
        client, "https://api.github.com/repos/$owner/$repo/releases/tags/$tag"
    ) ?: return 0L
    return org.json.JSONObject(body).optLong("id", 0L)
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

/** 在一页资产（名→登记摘要）里按名找摘要；找不到返回 null（与「摘要为空」区分）。 */
internal fun findAssetDigest(
    assets: List<Pair<String, String>>, assetName: String
): String? {
    for ((name, digest) in assets) {
        if (name == assetName) return normalizeSha256(digest)
    }
    return null
}
