// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.script

/**
 * `QuickJsEngine.java` 一一对应（eval／runJobs／callOutput／
 * outputValue／outputState／isFunction／setDeadline／setGlobalString／
 * settle／cancel／destroy／liveCount）。native 实现见
 * app/src/main/cpp/quickjs_jni/quickjs_jni.c（libhamster_quickjs.so，
 * QuickJS 2026-06-04 核心，见 app/src/main/cpp/quickjs/README.md）。
 *
 * 线程模型：单线程。一次引擎实例的所有 JS 调用（eval／runJobs／
 * callOutput／…）必须来自同一线程（脚本运行线程）；只有 [cancel]
 * 可从任意线程调（只翻一个原子标志，native 中断处理器在下一次
 * 字节码检查时让执行抛错）。
 *
 * 错误约定：native 方法出错时返回 `Array<String>?`——null＝成功，
 * 否则 `[message, kind]`，kind ∈ error／timeout／cancelled／
 * no-output。超时与取消由 native 按中断原因区分（不是按消息文本猜）。
 */
class QuickJsEngine private constructor(
    private var ptr: Long,
) {
    /** 宿主 API 实现（$log／$http／…）。运行前由调用方设置；默认空实现。 */
    var host: ScriptHost = ScriptHost.NOOP

    val isValid: Boolean get() = ptr != 0L

    /**
     * JNI 回调入口：native 层把 JS 侧的宿主调用包成
     * (name, types, strArgs, objJson) 交给 host。
     * types：0 Undefined／1 Null／2 String／3 Primitive（数字／布尔，
     * strArgs 为其 ToString）／4 Object（objJson 为 JSON.stringify，
     * strArgs 与 objJson 相同）；undefined／null 的 strArgs 为 ""。
     * 返回值按 String／Long／Boolean／null 转回 JS 值。
     * 只供 JNI 调，不要直接调。
     */
    fun onHostCall(
        name: String,
        types: IntArray,
        strArgs: Array<String>,
        objJson: Array<String>,
    ): Any? = host.dispatch(name, types, strArgs, objJson)

    /** 执行一段脚本（全局作用域）。成功返回 null，否则返回失败。 */
    fun eval(code: String, filename: String = DEFAULT_FILENAME): ScriptFailure? {
        checkValid()
        return QuickJsNative.nativeEval(ptr, code, filename)?.toFailure()
    }

    /**
     * 泵微任务队列（含 $http 完成回调的投递）。成功返回 null，否则返回失败。
     * 每调用最多执行 [maxJobs] 个微任务（默认 1000）：脚本的 await 链
     * 每步都产生下一个微任务，一次泵完永不返回，调用方中间插不进 $http
     * 完成的投递；到上限返回 null（不是错），调用方下次再泵（runner 每
     * 10ms 轮询即如此）。
     */
    fun runJobs(maxJobs: Int = 1000): ScriptFailure? {
        checkValid()
        return QuickJsNative.nativeRunJobs(ptr, maxJobs)?.toFailure()
    }

    /**
     * 调用全局 `output()`。output 须为函数（否则 [ScriptFailure.NoOutputFunction]）；
     * 返回值若为 thenable 则挂 fulfil／reject 处理器等待结算，否则视为已结算。
     */
    fun callOutput(): ScriptFailure? {
        checkValid()
        return QuickJsNative.nativeCallOutput(ptr)?.toFailure()
    }

    /** output Promise 的结算态（PENDING／FULFILLED／REJECTED）。 */
    fun outputState(): OutputState {
        checkValid()
        return when (QuickJsNative.nativeOutputState(ptr)) {
            1 -> OutputState.FULFILLED
            2 -> OutputState.REJECTED
            else -> OutputState.PENDING
        }
    }

    /** 已结算时 output 的字符串列表；未结算／被拒时为 null。 */
    fun outputValue(): List<String>? {
        checkValid()
        return QuickJsNative.nativeOutputValue(ptr)?.toList()
    }

    /** 被拒时拒绝原因；其余态为 null。 */
    fun outputError(): String? {
        checkValid()
        return QuickJsNative.nativeOutputError(ptr)
    }

    fun isFunction(name: String): Boolean {
        checkValid()
        return QuickJsNative.nativeIsFunction(ptr, name)
    }

    /** 设置执行截止（从现在起的纳秒数；0＝不清截止）。30 秒超时即 30_000_000_000L。 */
    fun setDeadline(nanos: Long) {
        checkValid()
        QuickJsNative.nativeSetDeadline(ptr, nanos)
    }

    fun setGlobalString(name: String, value: String) {
        checkValid()
        QuickJsNative.nativeSetGlobalString(ptr, name, value)
    }

    /**
     * 投递一次 $http 完成（settle）：fulfilled＝false 时 value 为错误
     * 文本。只入队不执行 JS；下一次 [runJobs] 在引擎线程上调用脚本
     * 的 `__jimeHttpResult(id, rejected, payload)`（若定义为函数）。
     */
    fun settle(id: Long, fulfilled: Boolean, value: String) {
        checkValid()
        QuickJsNative.nativeSettle(ptr, id, fulfilled, value)
    }

    /** 取消在途执行（线程安全）：下一次字节码检查即抛错。 */
    fun cancel() {
        val p = ptr
        if (p != 0L) QuickJsNative.nativeCancel(p)
    }

    /** 释放引擎（幂等）。销毁后其余方法均抛 IllegalStateException。 */
    fun destroy() {
        val p = ptr
        ptr = 0L
        if (p != 0L) QuickJsNative.nativeDestroy(p)
    }

    private fun checkValid() {
        check(ptr != 0L) { "QuickJsEngine 已销毁" }
    }

    private fun Array<String>.toFailure(): ScriptFailure {
        val message = getOrNull(0).orEmpty()
        return when (getOrNull(1)) {
            "timeout" -> ScriptFailure.Timeout
            "cancelled" -> ScriptFailure.Cancelled
            "no-output" -> ScriptFailure.NoOutputFunction
            else -> ScriptFailure.Error(message)
        }
    }

    companion object {
        /** 元书执行约定：脚本文件名固定为该 URI。 */
        const val DEFAULT_FILENAME = "hamster-script://editor.js"

        /** 30 秒超时（纳秒，与元书 z42 的 setDeadline(30000000000L) 一致）。 */
        const val TIMEOUT_NANOS = 30_000_000_000L

        fun create(): QuickJsEngine {
            val engine = QuickJsEngine(0L)
            val p = QuickJsNative.nativeCreate(engine)
            check(p != 0L) { "QuickJS 引擎创建失败" }
            // nativeCreate 拿到 engine 引用后才把指针写回（构造期间
            // host 回调不可能发生，指针此时才有效）。
            engine.ptr = p
            return engine
        }

        /** 当前存活引擎数（native 计数；单测观测用）。 */
        fun liveCount(): Int = QuickJsNative.nativeLiveCount()
    }
}

/** output Promise 的结算态（与元书 iw3 三态对应）。 */
enum class OutputState {
    PENDING,
    FULFILLED,
    REJECTED,
}

/** 脚本失败（与元书 fw3 对应：message＋interrupted／not-thenable 标志折叠为类型）。 */
sealed interface ScriptFailure {
    /** JS 抛错或宿主错误（message 为异常文本，不含源码）。 */
    data class Error(val message: String) : ScriptFailure

    /** 30 秒超时（执行截止或等待结算超时）。 */
    data object Timeout : ScriptFailure

    /** 调用方取消（cancel() 或协程取消）。 */
    data object Cancelled : ScriptFailure

    /** 脚本未定义 output 函数。 */
    data object NoOutputFunction : ScriptFailure
}

/**
 * JNI 原生方法表（@JvmStatic 使 JNI 名为
 * Java_com_jobeen_ime_script_QuickJsNative_nativeXxx）。
 *
 * 库加载：设备上走 System.loadLibrary("hamster_quickjs")；主机单测
 * 上该库不存在（arm64 .so 载不进主机 JVM），改走系统属性
 * `jime.quickjs.hostLib` 指定的主机编译 .so（见
 * app/build.gradle.kts 的 buildHostQuickJs 任务；属性未设时保持
 * loadLibrary 失败原样，不静默吞掉）。
 */
internal object QuickJsNative {
    init {
        val loaded = runCatching { System.loadLibrary("hamster_quickjs") }.isSuccess
        if (!loaded) {
            val hostLib = System.getProperty("jime.quickjs.hostLib")
            if (!hostLib.isNullOrBlank()) {
                System.load(hostLib)
            }
        }
    }

    @JvmStatic external fun nativeCreate(host: QuickJsEngine): Long

    @JvmStatic external fun nativeEval(ptr: Long, code: String, filename: String): Array<String>?

    @JvmStatic external fun nativeRunJobs(ptr: Long, maxJobs: Int): Array<String>?

    @JvmStatic external fun nativeCallOutput(ptr: Long): Array<String>?

    @JvmStatic external fun nativeOutputState(ptr: Long): Int

    @JvmStatic external fun nativeOutputValue(ptr: Long): Array<String>?

    @JvmStatic external fun nativeOutputError(ptr: Long): String?

    @JvmStatic external fun nativeIsFunction(ptr: Long, name: String): Boolean

    @JvmStatic external fun nativeSetDeadline(ptr: Long, nanos: Long)

    @JvmStatic external fun nativeSetGlobalString(ptr: Long, name: String, value: String)

    @JvmStatic external fun nativeSettle(ptr: Long, id: Long, fulfilled: Boolean, value: String)

    @JvmStatic external fun nativeCancel(ptr: Long)

    @JvmStatic external fun nativeDestroy(ptr: Long)

    @JvmStatic external fun nativeLiveCount(): Int
}
