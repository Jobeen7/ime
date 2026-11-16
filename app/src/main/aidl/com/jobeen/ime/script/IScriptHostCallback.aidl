// SPDX-License-Identifier: Apache-2.0
package com.jobeen.ime.script;

// 脚本隔离进程 → 主进程的 $http 完成回调（单向：主进程 OkHttp 线程
// 投递时不阻塞等隔离进程）。
oneway interface IScriptHostCallback {
    /** $http 一次完成：rejected=true 时 payload 为错误文本，否则为响应体。 */
    void onHttpCompletion(long id, boolean rejected, String payload);
}
