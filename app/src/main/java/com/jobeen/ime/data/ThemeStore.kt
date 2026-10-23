package com.jobeen.ime.data

import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.data.keyboard.theme.KeyboardThemePresets
import com.jobeen.ime.data.theme.ReadableTheme
import kotlinx.serialization.json.Json
import java.io.File
import com.jobeen.ime.base.util.appScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

object ThemeStore {
    private const val THEMES_FILE = "themes.json"
    const val MAX_CUSTOM_THEMES = 2

    // themes.json 使用可读的关键字（长 key）与 #AARRGGBB 十六进制颜色，美化排版。
    // 只认这一种现行格式：两代旧格式（长 key+Int 颜色、紧凑短 key）的
    // 三级 fallback 兼容已于清点 S23 拆除——themes.json 只由本 App 自
    // 写，旧格式文件按解析失败处理（保留上一个可用状态，不落盘覆盖）。
    private val readableJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun refresh() {
        val file = File(App.themesDir, THEMES_FILE)
        if (!file.isFile) {
            KeyboardThemePresets.setCustomThemes(emptyList())
            return
        }
        val themes = runCatching { file.readText() }.getOrNull()?.let { text ->
            runCatching {
                readableJson.decodeFromString<List<ReadableTheme>>(text)
                    .map { it.toKeyboardTheme() }
            }.getOrNull()
        }
        if (themes == null) {
            // 解析失败（文件损坏/格式不符/读失败）：保留上一个可用状态，
            // 内存不清空、磁盘不覆盖，等下一次 refresh 或保存自然收敛；
            // 旧实现落到 emptyList 会把内存态清零、与磁盘内容不一致
            Timber.w("ThemeStore: failed to parse %s; keeping previous custom themes", THEMES_FILE)
            return
        }
        KeyboardThemePresets.setCustomThemes(
            themes.take(MAX_CUSTOM_THEMES).map { it }
        )
    }

    /**
     * 直接导入主题。
     * 相同 id 的主题会被原位替换；槽位未满（含已有同 id 替换）时返回 true。
     */
    fun import(theme: KeyboardTheme): Boolean {
        val current = KeyboardThemePresets.customThemes.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == theme.id }
        when {
            existingIndex >= 0 -> current[existingIndex] = theme
            current.size < MAX_CUSTOM_THEMES -> current.add(theme)
            else -> return false
        }
        applyCustomThemes(current)
        return true
    }

    /** 覆盖指定槽位的用户主题。 */
    fun overwrite(index: Int, theme: KeyboardTheme) {
        val current = KeyboardThemePresets.customThemes.toMutableList()
        if (index in current.indices) {
            current[index] = theme
        } else {
            current.add(theme)
        }
        applyCustomThemes(current)
    }

    /** 落盘串行：写 tmp 与 rename 在同一把锁内整体执行，防并发写交错 */
    private val writeMutex = Mutex()

    private fun applyCustomThemes(themes: List<KeyboardTheme>) {
        KeyboardThemePresets.setCustomThemes(themes.take(MAX_CUSTOM_THEMES))
        val snapshot = themes.map { ReadableTheme.from(it) }
        // 文件写放 IO 线程，避免阻塞主线程
        appScope.launch(Dispatchers.IO) {
            val file = File(App.themesDir, THEMES_FILE)
            runCatching {
                // 原子写：锁内先写临时文件再 rename，进程在写一半时被杀
                // 不会留下截断的 themes.json（writeText 直写会丢全部自
                // 定义主题）；原「写入序号＋比序号作废旧快照」装置已于
                // 清点 S23 一并拆除——保存是用户节奏的低频操作，锁串行
                // 后完成顺序即调用顺序，不存在旧覆盖新的窗口
                writeMutex.withLock {
                    val tmp = File(App.themesDir, "$THEMES_FILE.tmp")
                    tmp.writeText(readableJson.encodeToString(snapshot))
                    check(tmp.renameTo(file)) { "rename themes file failed" }
                }
            }
        }
    }
}