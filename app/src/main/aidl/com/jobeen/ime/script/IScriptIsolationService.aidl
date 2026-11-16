// SPDX-License-Identifier: Apache-2.0
package com.jobeen.ime.script;

import com.jobeen.ime.script.IScriptHost;

// 主进程 → 脚本隔离进程的运行 Binder 面：源码与入参经 Binder 传进
// 隔离进程，执行结果经 Binder 返回（Bundle，键见
// ScriptIsolationService 的 KEY_* 常量）。
// 变量随入参一起进隔离进程（variableKeys／variableValues 两列平行
// 数组，主进程已按 ScriptVariableInjection.prepare() 备好：敏感变量
// 在主进程剔除，永不经 Binder；skippedVariableLogLines 为跳过行的
// 日志文本，随结果的日志列表一起返回）。隔离进程不直接读库。
// runScript() 为同步调用：在隔离进程的 Binder 线程上阻塞直至脚本
// 结算、超时或取消——调用方必须在后台线程调用，不得在主线程调。
// 同一时刻只跑一段脚本：已有运行在进行时立即返回错误 Bundle
// （KEY_SUCCESS=false，KEY_ERROR_KIND="busy"）。
interface IScriptIsolationService {
    /** 在隔离进程内跑一段脚本并等结果返回。 */
    Bundle runScript(String source, String searchText, String selectedText,
        String pasteboardContent, long timeoutNanos, IScriptHost host,
        in String[] variableKeys, in String[] variableValues,
        in String[] skippedVariableLogLines);

    /** 取消在途运行（无运行在进行时为空操作）。 */
    void cancelRun();
}
