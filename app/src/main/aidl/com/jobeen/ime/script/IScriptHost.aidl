// SPDX-License-Identifier: Apache-2.0
package com.jobeen.ime.script;

import com.jobeen.ime.script.IScriptHostCallback;

// 脚本隔离进程 → 主进程的宿主 Binder 面：QuickJS 脚本在隔离进程内
// 跑，6 个宿主 API 里需要主进程能力的 5 个经此接口回主进程执行。
// $log 不走 Binder：日志由隔离进程内 ScriptRunner 的 logSink 直接收进
// 本次运行的日志列表，随运行结果一起返回（见 IScriptIsolationService）。
// $http 是异步的：本方法同步返回自增 id，完成时经 callback 单向投递。
// 其余方法均为同步调用：返回时副作用已在主进程生效，脚本继续执行前
// 不会丢——insertText/setClipboard 的时序与原进程内行为一致。
// 密钥不在此接口上传：主进程代理只转发脚本自己给的请求头，不附加凭据。
interface IScriptHost {
    /** $http(json)：解析并异步发请求，返回自增 id；完成经 callback 投递。 */
    long http(String json, IScriptHostCallback callback);

    /** $setClipboard(txt)：写系统剪贴板。 */
    void setClipboard(String text);

    /** $insertText(txt)：上屏文本。 */
    void insertText(String text);

    /** $openURL(url)：URI 有 scheme 才打开，返回是否打开。 */
    boolean openUrl(String url);

    /** $userNotification(title, shortcutName, content)：发通知，返回是否发出。 */
    boolean userNotification(String title, String shortcutName, String content);
}
