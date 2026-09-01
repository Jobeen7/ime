package com.ninthsoft.ime.base.net

/**
 * 全局基础 API 配置，所有网络请求共用。
 * 具体接口地址在 [BASE_URL] 基础上拼接相对路径。
 */
object ApiConfig {
    const val BASE_URL = "https://mapi.lutrip.com/"
    const val CONNECT_TIMEOUT = 30_000L
    const val READ_TIMEOUT = 60_000L
}
