package com.jobeen.ime.base.util

import java.lang.ref.WeakReference
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * 单例回调槽专用委托：弱引用后备存储。
 *
 * 全局单例（object）持有的回调若用强引用，注册方（View/Panel）销毁时
 * 只要漏走一条清理路径，单例就会把整个对象连同其视图树、Context 一起
 * 强持到进程结束。弱引用后备让槽位随注册方销毁自动失效，从结构上
 * 消除这类泄漏；注册方必须自己强持有回调实例（如用 val 字段保存
 * lambda），保证存活期间不被提前回收。
 */
class WeakProperty<T> : ReadWriteProperty<Any?, T?> {
    @Volatile
    private var ref: WeakReference<T>? = null

    override fun getValue(thisRef: Any?, property: KProperty<*>): T? = ref?.get()

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T?) {
        ref = value?.let { WeakReference(it) }
    }
}
