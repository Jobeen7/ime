// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.net

import com.jobeen.ime.error.DeepErrorMapper
import com.jobeen.ime.error.DeepErrorMapper.DeepErrorCode
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO36 扫尾第 2 项（ApiException）：HttpUtil 的 ApiException 消息
 * 走 DeepErrorMapper 的钉住测试。
 *
 * 口径（见 HttpUtil.kt 与 DeepErrorMapper KDoc）：
 * - HTTP 状态码进 ApiException.message（"HTTP NNN"）时，按消息里的
 *   HTTP 码归类（5xx→SERVER、404→NOT_FOUND、401/403→PERMISSION、
 *   408/504→TIMEOUT），不新增 DeepErrorMapper 码、不改其公开契约；
 * - 响应体超限（code=-1）的 "响应体过大" 无深层特征，归类为 null，
 *   DeepErrorMapper.userMessage 会原样保留该文案（应用层自写中文
 *   提示），故它只需抽串、不另设码；
 * - 业务错误码的服务端 msg 走 sanitizeServerMessage 收口后才上屏，
 *   空文案时回落 http_request_failed_business（已是资源串）。
 */
class Wo36SweepHttpUtilTest {

    @Test
    fun apiException_carriesCodeAndMessage() {
        val e = HttpUtil.ApiException(500, "HTTP 500")
        assertEquals(500, e.code)
        assertEquals("HTTP 500", e.message)
    }

    @Test
    fun httpApiException_classifiesByHttpCode() {
        assertEquals(
            DeepErrorCode.SERVER,
            DeepErrorMapper.classify(HttpUtil.ApiException(500, "HTTP 500")),
        )
        assertEquals(
            DeepErrorCode.SERVER,
            DeepErrorMapper.classify(HttpUtil.ApiException(503, "HTTP 503")),
        )
        assertEquals(
            DeepErrorCode.NOT_FOUND,
            DeepErrorMapper.classify(HttpUtil.ApiException(404, "HTTP 404")),
        )
        assertEquals(
            DeepErrorCode.PERMISSION,
            DeepErrorMapper.classify(HttpUtil.ApiException(401, "HTTP 401")),
        )
        assertEquals(
            DeepErrorCode.PERMISSION,
            DeepErrorMapper.classify(HttpUtil.ApiException(403, "HTTP 403")),
        )
        assertEquals(
            DeepErrorCode.TIMEOUT,
            DeepErrorMapper.classify(HttpUtil.ApiException(504, "HTTP 504")),
        )
        assertEquals(
            DeepErrorCode.TIMEOUT,
            DeepErrorMapper.classify(HttpUtil.ApiException(408, "HTTP 408")),
        )
    }

    @Test
    fun httpApiException_nonErrorCodeHasNoMapping() {
        assertNull(DeepErrorMapper.classify(HttpUtil.ApiException(200, "HTTP 200")))
    }

    @Test
    fun oversizeMessage_hasNoDeepErrorCode_fallsBackToRaw() {
        // "响应体过大" 无 DeepErrorMapper 可认的特征：归类 null，
        // 调用方走 userMessage 时原样保留，不硬套通用文案。
        assertNull(DeepErrorMapper.classifyMessage("响应体过大"))
        assertNull(
            DeepErrorMapper.classify(HttpUtil.ApiException(-1, "响应体过大")),
        )
    }

    @Test
    fun stringsXml_hasHttpResponseTooLarge() {
        // 与 PerfPanelLabelsTest 同款做法：单测工作目录即模块目录，
        // 直接读 strings.xml 源码比对，防止资源与生产文案静默漂移。
        val stringsXml = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml not found at ${stringsXml.absolutePath}", stringsXml.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsXml)
        val values = HashMap<String, String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
            values[name] = node.textContent
        }
        assertEquals("响应体过大", values["http_response_too_large"])
    }
}
