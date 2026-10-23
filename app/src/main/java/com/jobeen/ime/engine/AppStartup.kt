package com.jobeen.ime.engine

import android.content.Context
import com.jobeen.ime.ImeApplication
import com.jobeen.ime.base.log.AppLogBuffer
import com.jobeen.ime.base.feedback.InputFeedbacks
import com.jobeen.ime.base.util.ResourceExtractorUtil
import com.jobeen.ime.base.util.TraditionalConverter
import com.jobeen.ime.base.util.appScope
import com.jobeen.ime.base.update.WanxiangUpdateManager
import com.jobeen.ime.data.ThemeStore
import com.jobeen.ime.input.keyboard.window.KeyboardStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.jobeen.ime.BuildConfig
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * 手动启动入口，取代 androidx.startup 的 Initializer 链。
 *
 * 调用 [initialize] 会按顺序完成：
 * 1. 初始化日志（Timber）
 * 2. 按需解压资源（resource.zip）
 * 3. 创建并切换到 [RimeEngine]
 *
 * 步骤框架（完成集＋依赖图）已于清点 S19 拆除：initialize 全仓只有
 * Application.onCreate 一个调用点、每进程一次，旧框架的「失败下次
 * 调用重试」实际等价于下次进程启动。现为顺序调用＋逐步 runCatching
 * （某一步失败不直接杀进程，下次进程启动自然重走全程；旧故障「失
 * 败仍置 initialized=true 致引擎同进程永不恢复」由逐步捕获本身堵
 * 住）。仅保留一条显式依赖：引擎初始化依赖资源解压成功，资源失败
 * 时本轮跳过引擎，避免半初始化状态。
 */
object AppStartup {
    private const val VERSION_FILE = "version.txt"
    private const val RESOURCE_ASSET = "resource.zip"

    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            runStep("setupLogger") { setupLogger(context) }
            runStep("setupThemeStore") { setupThemeStore(context) }
            val resourcesReady =
                runStep("releaseResourcesIfNeeded") { releaseResourcesIfNeeded(context) }
            runStep("setupInputFeedbacks") { setupInputFeedbacks(context) }
            // Resolve a process death during paired dictionary/model update
            // before the engine can open either resource. 该步自身保证不
            // 抛错（损坏日志会被隔离），不在引擎的硬依赖内：一次恢复
            // 异常绝不能把引擎启动永久堵死
            runStep("recoverInterruptedUpdate") { WanxiangUpdateManager.recoverInterruptedUpdate() }
            if (resourcesReady) {
                runStep("setupEngine") { setupEngine(context) }
            } else {
                android.util.Log.w(
                    "AppStartup",
                    "step setupEngine skipped, blocked by incomplete step: releaseResourcesIfNeeded"
                )
            }
            runStep("prewarmOpencc") { prewarmOpencc(context) }
        }
    }

    /** 单步执行：独立捕获异常并记日志，返回是否成功（供显式依赖判断）。 */
    private fun runStep(name: String, block: () -> Unit): Boolean =
        runCatching(block)
            .onFailure { e ->
                // 用 android.util.Log 确保 release 包（Timber 无 tree）也能在 logcat 看到
                android.util.Log.e("AppStartup", "step $name failed", e)
                Timber.e(e, "AppStartup step failed: %s", name)
            }
            .isSuccess

    private fun setupThemeStore(context: Context) {
        ThemeStore.refresh()
    }

    private fun setupLogger(context: Context) {
        // 仅 debug 构建开启日志：AppLogBuffer 的 Timber 缓冲收集 + DebugTree。
        // release（assembleRelease）不装任何 timber Tree → Timber 全部 no-op，不输出、零开销；
        // 崩溃记录由 CrashTrap 单点落盘（Application.onCreate 已安装，不经此处）。
        AppLogBuffer.install(context, enableLogging = BuildConfig.DEBUG)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    private fun setupEngine(context: Context) {
        val app = context.applicationContext as ImeApplication
        val engine = EngineFactory.switchTo(context, RimeEngine::class)
        engine.observeMessages(app.applicationScope) {
            KeyboardStateManager.handleEngineMessage(it)
        }
        engine.initialize(context)
    }

    private fun setupInputFeedbacks(context: Context) {
        InputFeedbacks.initSoundPool(context)
    }

    private fun prewarmOpencc(context: Context) {
        appScope.launch(Dispatchers.IO) {
            // 触发一次简繁词库加载，避免首次繁体转换卡顿
            runCatching { TraditionalConverter.toTraditional("汉字") }
        }
    }

    private fun releaseResourcesIfNeeded(context: Context) {
        val destDir = context.getExternalFilesDir(null) ?: context.filesDir
        val versionFile = File(destDir, VERSION_FILE)
        val currentVc = currentVersionCode(context).toString()

        // version.txt 存 "versionCode:md5"；versionCode 未变且哨兵文件存在 → 零 I/O 直接返回，
        // 省掉每次冷启动对 65MB resource.zip 算 MD5
        val stored = versionFile.takeIf { it.isFile }?.readText()?.trim()
        val storedVc = stored?.substringBefore(':')
        // 哨兵：资源目录被用户/清理工具删掉时不能只看 versionCode，必须重新解压
        val sentinelOk = File(destDir, "shared/default.yaml").isFile
        if (!storedVc.isNullOrEmpty() && storedVc == currentVc && sentinelOk) {
            Timber.d("Resources up to date (vc=%s), skip md5 and extraction", currentVc)
            return
        }

        // APK 更新了（或首次安装/旧格式 version.txt/资源目录缺失）：算一次 MD5 做二次确认。
        // MD5 算失败时不能当"未变"直接返回：按"已变"处理，走解压兜底。
        val md5 = assetMd5(context, RESOURCE_ASSET)
        val storedMd5 = stored?.substringAfter(':')
        if (md5 != null && storedMd5 == md5 && sentinelOk) {
            // 资源内容没变（APK 只是重新打包），只更新 versionCode，跳过解压
            versionFile.writeText("$currentVc:$md5")
            Timber.d("Resources unchanged (md5=%s), skip extraction", md5)
            return
        }

        val app = context.applicationContext as ImeApplication
        app.notifyState(ImeApplication.AppState.ResourcePreparing)
        runBlocking(Dispatchers.IO) {
            ResourceExtractorUtil.extract(context, RESOURCE_ASSET, destDir)
            versionFile.writeText("$currentVc:${md5.orEmpty()}")
        }
    }

    private fun currentVersionCode(context: Context): Long {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)
    }

    private fun assetMd5(context: Context, assetName: String): String? {
        val digest = MessageDigest.getInstance("MD5")
        return runCatching {
            context.assets.open(assetName).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }.getOrNull()
    }
}
