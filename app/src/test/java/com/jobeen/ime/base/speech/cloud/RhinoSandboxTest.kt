// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.speech.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject

/** 脚本沙箱：捕获、桥接、隔离、预算、错误消毒（真实 Rhino 引擎）。 */
class RhinoSandboxTest {

    @Test
    fun registerCapture_readsPluginMeta() {
        RhinoSession.create().use { session ->
            session.eval(
                "register({id: 'example.asr', version: '1.0', hosts: ['asr.example.com']});",
                "plugin",
                1000L,
            )
            val captured = session.readRegisterArg() as? NativeObject
                ?: error("register arg not captured")
            assertEquals("example.asr", ScriptableObject.getProperty(captured, "id"))
            assertEquals("1.0", ScriptableObject.getProperty(captured, "version"))
        }
    }

    @Test
    fun bridgeFunction_calledFromScript() {
        RhinoSession.create().use { session ->
            val seen = mutableListOf<Any?>()
            val cb = NativeObject()
            cb.put("partial", cb, BridgeFunction { args ->
                seen.addAll(args.toList())
                null
            })
            session.putBridge("cb", cb)
            session.eval("cb.partial('hello');", "plugin", 1000L)
            assertEquals(listOf("hello"), seen)
        }
    }

    @Test
    fun dangerousGlobals_unavailable() {
        RhinoSession.create().use { session ->
            session.eval("var a = typeof Packages;", "plugin", 1000L)
            session.eval("var b = typeof java;", "plugin", 1000L)
            session.eval("var c = typeof JavaAdapter;", "plugin", 1000L)
            val scope = session.evalToString("'' + a + ',' + b + ',' + c;")
            assertEquals("undefined,undefined,undefined", scope)
            try {
                session.eval("Packages.java.lang.System.exit(0);", "plugin", 1000L)
                fail("Packages access must throw")
            } catch (expected: Exception) {
                // ReferenceError：隔离成立（绝不能走到 exit）。
                assertTrue(expected.javaClass.simpleName.isNotEmpty())
            }
        }
    }

    @Test
    fun sessions_isolated() {
        RhinoSession.create().use { first ->
            first.eval("var sessionMarker = 42;", "plugin", 1000L)
        }
        RhinoSession.create().use { second ->
            assertEquals("undefined", second.evalToString("typeof sessionMarker;"))
        }
    }

    @Test
    fun budgetExceeded_abortsInfiniteLoop() {
        RhinoSession.create().use { session ->
            try {
                session.eval("while (true) { }", "plugin", 200L)
                fail("infinite loop must exceed budget")
            } catch (expected: ScriptBudgetExceeded) {
                // 预算生效。
            }
        }
    }

    @Test
    fun sanitizeError_containsNoSource() {
        RhinoSession.create().use { session ->
            try {
                session.eval("function broken( {", "plugin", 1000L)
                fail("syntax error must throw")
            } catch (e: Exception) {
                val info = sanitizeScriptError(e)
                assertTrue(info.kind.isNotEmpty())
                assertTrue(info.line >= 1)
                // 消毒后信息只含类名与行号，不含源码行内容。
                assertTrue(!info.toString().contains("broken"))
            }
        }
    }

    @Test
    fun wrongThread_rejected() {
        val session = RhinoSession.create()
        try {
            var thrown: Throwable? = null
            val t = Thread {
                try {
                    session.eval("1;", "plugin", 1000L)
                } catch (e: Throwable) {
                    thrown = e
                }
            }
            t.start()
            t.join()
            assertTrue(thrown is IllegalStateException)
        } finally {
            session.close()
        }
    }

    @Test
    fun bridgeReturnObject_propertiesReadable() {
        RhinoSession.create().use { session ->
            val net = NativeObject()
            net.put("httpPost", net, BridgeFunction {
                val out = NativeObject()
                out.put("ok", out, false)
                out.put("error", out, "bad_body")
                out
            })
            session.putBridge("net", net)
            val seen = mutableListOf<String>()
            val cb = NativeObject()
            cb.put("partial", cb, BridgeFunction { args ->
                // 脚本拼接结果是 ConsString（CharSequence），不可只认 String。
                val v = args.getOrNull(0)
                seen.add((v as? CharSequence)?.toString() ?: "null:" + (v?.javaClass?.name ?: "null"))
                null
            })
            session.putBridge("cb", cb)
            session.eval(
                "var r = net.httpPost('https://x.example.com/', {}, '', null);" +
                    "cb.partial('typeof=' + (typeof r));" +
                    "cb.partial('ok=' + r.ok);" +
                    "cb.partial('error=' + r.error);",
                "probe",
                5000L,
            )
            assertEquals(listOf("typeof=object", "ok=false", "error=bad_body"), seen)
        }
    }

    @Test
    fun missingFunction_returnsNull() {
        RhinoSession.create().use { session ->
            assertNull(session.callFunction("noSuchFunction", emptyArray(), 1000L))
        }
    }

    private fun RhinoSession.evalToString(source: String): String {
        var result: Any? = null
        // 以函数返回值取脚本表达式结果：eval 本身不返回值，包一层函数。
        eval("function __probe() { return $source }", "probe", 1000L)
        val fn = getFunction("__probe") ?: error("probe missing")
        result = callFunction(fn, emptyArray(), 1000L)
        return org.mozilla.javascript.Context.toString(result)
    }
}
