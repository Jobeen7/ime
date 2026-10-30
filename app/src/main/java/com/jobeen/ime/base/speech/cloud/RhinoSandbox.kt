package com.jobeen.ime.base.speech.cloud

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 脚本执行超预算：宿主据此杀实例、记元数据日志、停用插件待用户重开。
 * 超预算绝不重试、绝不静默恢复。
 */
class ScriptBudgetExceeded : RuntimeException()

/**
 * 已消毒的脚本错误信息：只含异常类名与行号。
 * 绝不含脚本源码、行内容、输入文本与网络体（Rhino 原始报错可能带行内容，
 * 故调用方只许记本对象，绝不直接记原始异常消息）。
 */
data class ScriptErrorInfo(val kind: String, val line: Int)

fun sanitizeScriptError(e: Throwable): ScriptErrorInfo {
    val line = (e as? RhinoException)?.lineNumber() ?: -1
    return ScriptErrorInfo(kind = e.javaClass.simpleName, line = line)
}

/**
 * 脚本引擎沙箱（Rhino 解释执行，纯 Java、无 NDK、无类生成）。
 *
 * 隔离手段（缺一不可）：
 * - 解释级执行（optimizationLevel = -1），脚本无法生成或加载类；
 * - ClassShutter 全拒：脚本按名取不到任何 Java 类；
 * - 危险顶层名删除：Packages／java／org／com／importClass 等；
 * - 桥接只以 [BaseFunction] 形态暴露：脚本能碰到的 Java 面只有宿主
 *   显式给出的函数，函数体内全部逻辑由宿主控制，绝无反射面；
 * - 每会话独立 Context 与顶层作用域，会话间、插件间无共享全局态；
 * - 每次脚本调用都带墙钟预算（见 [eval]／[callFunction] 的 budgetMs），
 *   超限抛 [ScriptBudgetExceeded]，宿主杀实例。
 *
 * 线程纪律：一个会话只活在一个线程上（引擎 Context 线程绑定）；
 * 非属主线程调用一律抛错，宿主以串行工作线程持有本会话。
 */
class RhinoSession private constructor(
    private val cx: Context,
    private val scope: Scriptable,
) : Closeable {

    private val ownerThread: Thread = Thread.currentThread()

    @Volatile
    private var closed = false

    private fun checkThread() {
        check(Thread.currentThread() === ownerThread) {
            "RhinoSession must be used on its owner thread"
        }
    }

    /**
     * 以 budgetMs 为墙钟预算执行一段脚本（插件文件加载、仅读元数据运行
     * 均走此口）。预算内未完成即抛 [ScriptBudgetExceeded]。
     */
    fun eval(source: String, sourceName: String, budgetMs: Long) {
        checkThread()
        check(!closed) { "session closed" }
        runBudgeted(budgetMs) {
            cx.evaluateString(scope, source, sourceName, 1, null)
        }
    }

    /** 顶层作用域挂一个桥接对象（BaseFunction 形态，见类注释）。 */
    fun putBridge(name: String, obj: Scriptable) {
        checkThread()
        ScriptableObject.putProperty(scope, name, obj)
    }

    /** 取顶层函数（插件的 start／audio／onMessage／stop 等）。 */
    fun getFunction(name: String): Function? {
        checkThread()
        val value = ScriptableObject.getProperty(scope, name)
        return value as? Function
    }

    /** 以 budgetMs 预算调用顶层函数；函数缺失返回 null（调用方判缺失）。 */
    fun callFunction(name: String, args: Array<Any?>, budgetMs: Long): Any? {
        val fn = getFunction(name) ?: return null
        return callFunction(fn, args, budgetMs)
    }

    fun callFunction(fn: Function, args: Array<Any?>, budgetMs: Long): Any? {
        checkThread()
        check(!closed) { "session closed" }
        return runBudgeted(budgetMs) {
            fn.call(cx, scope, scope, args)
        }
    }

    /** 读宿主 register 捕获（仅读元数据运行与会话加载后均可用）。 */
    fun readRegisterArg(): Any? {
        checkThread()
        val value = ScriptableObject.getProperty(scope, REGISTER_CAPTURE_NAME)
        return if (value === Scriptable.NOT_FOUND || value === org.mozilla.javascript.Undefined.instance) {
            null
        } else {
            value
        }
    }

    private fun <T> runBudgeted(budgetMs: Long, block: () -> T): T {
        val deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L
        cx.putThreadLocal(DEADLINE_KEY, deadlineNanos)
        try {
            return block()
        } finally {
            cx.removeThreadLocal(DEADLINE_KEY)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Context.exit 必须在进入线程上调用；宿主保证从工作线程关闭。
        if (Thread.currentThread() === ownerThread) {
            runCatching { Context.exit() }
        }
    }

    companion object {
        const val REGISTER_CAPTURE_NAME = "__registerArg"

        private const val DEADLINE_KEY = "cloudVoiceDeadlineNanos"

        /** 指令观察粒度：解释器每执行这么多指令查一次墙钟。 */
        private const val OBSERVER_THRESHOLD = 4096

        /** 宿主 register 捕获代码：插件顶层调用 register 即被捕获，绝不执行插件逻辑。 */
        private const val REGISTER_SETUP =
            "var $REGISTER_CAPTURE_NAME = null;\n" +
                "function register(arg) { $REGISTER_CAPTURE_NAME = arg; }\n"

        /** 必须从顶层作用域删除的名字（ClassShutter 是第二道防线）。 */
        private val DELETED_GLOBALS = arrayOf(
            "Packages", "java", "org", "com", "javax", "edu", "net",
            "JavaAdapter", "JavaImporter", "importClass", "importPackage",
        )

        private val factoryInstalled = AtomicBoolean(false)

        private val factory = object : ContextFactory() {
            override fun makeContext(): Context {
                val cx = super.makeContext()
                cx.instructionObserverThreshold = OBSERVER_THRESHOLD
                return cx
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                val deadline = cx.getThreadLocal(DEADLINE_KEY) as? Long ?: return
                if (System.nanoTime() > deadline) throw ScriptBudgetExceeded()
            }
        }

        /** 在当前线程新建会话（调用线程即属主线程，宿主须以串行工作线程持有）。 */
        fun create(): RhinoSession {
            if (factoryInstalled.compareAndSet(false, true)) {
                ContextFactory.initGlobal(factory)
            }
            val cx = factory.enterContext()
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.setClassShutter(DenyAllClassShutter)
            val scope = cx.initStandardObjects()
            for (name in DELETED_GLOBALS) {
                scope.delete(name)
            }
            val session = RhinoSession(cx, scope)
            // register 捕获是宿主代码，先于插件源码执行。
            session.eval(REGISTER_SETUP, "host-register", 500L)
            return session
        }

        private object DenyAllClassShutter : ClassShutter {
            override fun visibleToScripts(className: String): Boolean = false
        }
    }
}

/** 以 Kotlin lambda 包装的桥接函数：脚本调用它只进这段宿主逻辑，别无他途。 */
class BridgeFunction(
    private val fn: (args: Array<Any?>) -> Any?,
) : BaseFunction() {
    override fun call(
        cx: Context,
        scope: Scriptable,
        thisObj: Scriptable,
        args: Array<Any?>,
    ): Any? = fn(args)
}
