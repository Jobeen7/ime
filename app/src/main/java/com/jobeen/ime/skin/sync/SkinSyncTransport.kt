package com.jobeen.ime.skin.sync

/**
 * 皮肤库同步传输接缝（详设 §4）：路径相对远端根
 * `jime_skins/v1/`，如 `index.json`、`skins/<id>.json`、
 * `layouts/<id>.json`、`assets/<sha256>`。
 *
 * 错误口径：401/403 → [AuthException]（整轮中止）；404 在 GET
 * 时以 null 表示「无此单元」、在 DELETE 时视为已删除；其余
 * HTTP 错误 → [HttpException]（带状态码与正文摘要，不记凭据）。
 */
interface SkinSyncTransport {

    class AuthException(message: String) : Exception(message)

    class HttpException(val code: Int, message: String) : Exception(message)

    /** GET 一个单元；远端不存在返回 null。 */
    suspend fun get(path: String): ByteArray?

    /** PUT 一个单元（父目录由实现逐级 MKCOL 确保）。 */
    suspend fun put(path: String, bytes: ByteArray)

    /** DELETE 一个单元；已不存在视为成功。 */
    suspend fun delete(path: String)
}
