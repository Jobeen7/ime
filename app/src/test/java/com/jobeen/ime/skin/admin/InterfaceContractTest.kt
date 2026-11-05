package com.jobeen.ime.skin.admin

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接口委托增量陷阱（AGENTS.md）契约：新增的 AdminStore /
 * StartupLoadDeps 接口由显式 override 的直实现对象承接（无
 * `by` 委托），本测试以反射断言实现类确实声明了接口的每个
 * 方法——增量构建漏编转发器时单测即失败，不等到运行时
 * AbstractMethodError。接口日后新增方法而直实现漏写 override
 * 时，编译即失败；本测试兜底的是「编译通过但字节码缺方法」
 * 的增量构建场景。
 */
class InterfaceContractTest {

    private fun assertDeclaresAll(interfaceName: String, implName: String) {
        val iface = Class.forName(interfaceName)
        val impl = Class.forName(implName)
        for (method in iface.methods) {
            if (method.isSynthetic || method.isBridge) continue
            if (method.declaringClass == Any::class.java) continue
            val declared = runCatching {
                impl.getDeclaredMethod(method.name, *method.parameterTypes)
            }.getOrNull()
            assertTrue(
                "$implName must declare ${method.name}" +
                    "(${method.parameterTypes.joinToString { it.simpleName }}) " +
                    "from $interfaceName (explicit override, no by-delegation)",
                declared != null,
            )
        }
    }

    @Test
    fun realAdminStore_declaresEveryAdminStoreMethod() {
        assertDeclaresAll(
            "com.jobeen.ime.skin.admin.SkinLibraryAdmin\$AdminStore",
            "com.jobeen.ime.skin.admin.SkinLibraryAdmin\$RealAdminStore",
        )
    }

    @Test
    fun realStartupLoadDeps_declaresEveryStartupLoadDepsMethod() {
        assertDeclaresAll(
            "com.jobeen.ime.skin.runtime.SkinSnapshotProvider\$StartupLoadDeps",
            "com.jobeen.ime.skin.runtime.SkinSnapshotProvider\$RealStartupLoadDeps",
        )
    }
}
