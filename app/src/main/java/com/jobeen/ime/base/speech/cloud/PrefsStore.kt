package com.jobeen.ime.base.speech.cloud

import android.content.Context
import android.content.SharedPreferences

/**
 * 键值存储抽象：生产用 SharedPreferences，单测用内存实现。
 * 插件自有命名空间全部经此口，绝不碰全局设置。
 */
interface PrefsStore {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
    fun keys(): Set<String>
}

class SharedPrefsStore(context: Context, name: String) : PrefsStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String, default: String?): String? =
        prefs.getString(key, default)

    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun keys(): Set<String> = prefs.all.keys
}
