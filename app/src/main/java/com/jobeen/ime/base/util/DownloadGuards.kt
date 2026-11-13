package com.jobeen.ime.base.util

import android.os.StatFs
import java.io.File

/**
 * 大文件下载的两个共用守卫（E2，语音模型与语法模型下载器共用）：
 * 下载前余量检查与主 URL 失败后的国内镜像 fallback。判定逻辑是
 * 纯函数（[hasEnoughSpace]、[mirrorDownloadUrl]），供单测钉住；
 * 只有 [availableBytes] 碰文件系统（StatFs）。
 */

// 国内镜像：gh-proxy.com（与主用 gh-proxy.org 同类 GitHub 代理）。
// 2026-11-13 自本机实测：sherpa 资产 Range 请求回 206＋1 字节
// application/octet-stream；RIME-LMDG 语法模型经一次同站 301
// 后同样回 206。ghproxy.cn 同期实测回 text/html（拦截页）、
// mirror.ghproxy.com 不可达，故不选它们。
private const val MIRROR_PROXY_PREFIX = "https://gh-proxy.com/https://github.com/"
private const val PRIMARY_PROXY_PREFIX = "https://gh-proxy.org/https://github.com/"
private const val GITHUB_PREFIX = "https://github.com/"

/**
 * 余量是否够下：可用空间须 ≥ 待下体积＋预留。
 * - 可用空间未知（StatFs 失败，[availableBytes] 返回 -1）时返回 true：
 *   判不出来就不拦，拦了会把可下的也拒了；
 * - 待下体积未知（Content-Length -1，分块传输）时同样返回 true；
 * - 预留须 ≥ 0，否则拒绝（调用方传错预算时 fail-closed）。
 * 用减法比较，避免 downloadBytes＋reserveBytes 的 Long 溢出。
 */
internal fun hasEnoughSpace(
    availableBytes: Long,
    downloadBytes: Long,
    reserveBytes: Long,
): Boolean {
    if (availableBytes < 0 || downloadBytes <= 0) return true
    if (reserveBytes < 0) return false
    return availableBytes - reserveBytes >= downloadBytes
}

/** 目标目录所在文件系统的可用字节数；目录不存在或 StatFs 失败时返回 -1（未知）。 */
internal fun availableBytes(dir: File?): Long {
    if (dir == null) return -1
    return runCatching { StatFs(dir.path).availableBytes }.getOrNull() ?: -1
}

/**
 * 主下载 URL 的国内镜像候选（纯函数）：只认 GitHub 形态——
 * gh-proxy.org 代理地址与 github.com 直连地址都换成 gh-proxy.com
 * 代理；其它形态（清单服务器给的非 GitHub 链接等）返回 null，
 * 调用方按无镜像处理，不猜。已是镜像地址时同样返回 null，
 * 避免主备来回倒。
 */
internal fun mirrorDownloadUrl(primaryUrl: String): String? {
    val rest = when {
        primaryUrl.startsWith(PRIMARY_PROXY_PREFIX) ->
            primaryUrl.substring(PRIMARY_PROXY_PREFIX.length)
        primaryUrl.startsWith(GITHUB_PREFIX) ->
            primaryUrl.substring(GITHUB_PREFIX.length)
        else -> return null
    }
    if (rest.isEmpty()) return null
    return MIRROR_PROXY_PREFIX + rest
}
