package com.jobeen.ime

import com.jobeen.ime.engine.rime.core.RimeApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * WO52 1.5：两个 T9 native 访问器的 JNI 绑定约定（与 3.1c
 * syncUserData 的 RimeMaintenanceTest 同口径；C-L-18 起
 * t9Preedit 已删，组字串直接取快照 composition.preedit）。
 *
 * JNI 导出名 Java_com_jobeen_ime_engine_rime_core_Rime_<name>
 * 要求 Rime 类上有 public static 的同名方法；RimeApi 侧为同名
 * suspend fun。initialize=false：只读方法元数据，不跑 <clinit>
 * （不加载 .so）。native 实现在 librime-t9 插件＋rime_engine.cc。
 */
class T9NativeBindingTest {

    @Test
    fun t9Methods_declaredOnRimeApi() {
        val list = RimeApi::class.java.methods.single { it.name == "t9PinyinList" }
        assertEquals(1, list.parameterCount)
        assertTrue(
            kotlin.coroutines.Continuation::class.java
                .isAssignableFrom(list.parameterTypes[0]),
        )
        val set = RimeApi::class.java.methods.single { it.name == "t9SetPinyin" }
        assertEquals(2, set.parameterCount)
        assertEquals(String::class.java, set.parameterTypes[0])
        assertTrue(
            kotlin.coroutines.Continuation::class.java
                .isAssignableFrom(set.parameterTypes[1]),
        )
    }

    @Test
    fun t9Methods_jniBindingMatchesNativeExport() {
        val rimeClass = Class.forName(
            "com.jobeen.ime.engine.rime.core.Rime",
            false,
            T9NativeBindingTest::class.java.classLoader,
        )
        val list = rimeClass.getMethod("t9PinyinList")
        assertTrue(Modifier.isStatic(list.modifiers))
        assertEquals(String::class.java, list.returnType)
        assertEquals(0, list.parameterCount)

        val set = rimeClass.getMethod("t9SetPinyin", String::class.java)
        assertTrue(Modifier.isStatic(set.modifiers))
        assertEquals(Boolean::class.javaPrimitiveType, set.returnType)
        assertEquals(1, set.parameterCount)
    }
}
