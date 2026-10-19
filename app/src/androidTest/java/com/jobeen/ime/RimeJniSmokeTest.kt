package com.jobeen.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * JNI 冒烟测试（§7.4）：只断言 rime JNI 库可加载。
 *
 * 未调用任何业务入口：librime_jni 的全部 JNI 导出（rime_engine.cc 等）
 * 均为会话操作（bootstrap/processKey/getContext…），没有版本查询类
 * 的无副作用入口；贸然调 bootstrap 需要真实的 shared/user 目录与完整
 * 会话生命周期，超出冒烟范围。库能加载、JNI_OnLoad 能跑通，至少能
 * 拦住「原生库缺失/入口注册失败」这一类问题（v1.0.986 式 R8 keep
 * 事故的第一道烟雾）。本机未跑（需真机/模拟器）。
 */
@RunWith(AndroidJUnit4::class)
class RimeJniSmokeTest {

    @Test
    fun rimeJniLibraryLoads() {
        // 与 Rime 类静态初始化一致的库名；loadLibrary 失败即抛
        // UnsatisfiedLinkError，测试自然失败，无需额外断言
        System.loadLibrary("rime_jni")
    }
}
