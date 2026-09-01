package com.ninthsoft.ime.engine

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

object EngineFactory {
    private val instances = ConcurrentHashMap<KClass<out IEngine>, IEngine>()
    private val instancesLock = Any()

    @Volatile
    private var currentEngine: IEngine? = null

    fun <T : IEngine> create(
        context: Context,
        clazz: KClass<T>,
    ): T {
        return try {
            clazz.java.getDeclaredConstructor().newInstance()
        } catch (e: Exception) {
            throw e
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : IEngine> getOrCreate(
        context: Context,
        clazz: KClass<T>,
    ): T {
        @Suppress("UNCHECKED_CAST") return synchronized(instancesLock) {
            instances[clazz] ?: create(context, clazz).also {
                instances[clazz] = it
            }
        } as T
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : IEngine> switchTo(
        context: Context,
        clazz: KClass<T>,
    ): T {
        val engine = getOrCreate(context, clazz)
        currentEngine = engine
        return engine
    }

    fun current(): IEngine? = currentEngine

    @Suppress("UNCHECKED_CAST")
    fun <T : IEngine> currentAs(): T? {
        return currentEngine as? T
    }
}