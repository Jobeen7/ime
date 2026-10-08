package com.jobeen.ime.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.daemon.RimeSession
import com.jobeen.ime.engine.rime.data.userdict.UserDictManager
import com.jobeen.ime.engine.rime.data.userdict.UserDictPrefs
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import com.jobeen.ime.ui.screen.UserDictScreen
import com.jobeen.ime.ui.screen.UserDictUiState
import com.jobeen.ime.ui.theme.ImeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File

/**
 * 用户词典：手动导出/导入 + WebDAV 同步。
 *
 * 打开时创建 Rime 会话以加载词典列表；导入（本地文件/WebDAV 下载）为合并操作，
 * 导入/导出使用热路径直接操作运行中引擎的词库，无需停止 Rime，导入后立即生效。
 */
class UserDictActivity : ComponentActivity() {

    private val uiState = UserDictUiState()
    private var session: RimeSession? = null

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            doImport(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        uiState.webdavServer = UserDictPrefs.server
        uiState.webdavUsername = UserDictPrefs.username
        uiState.webdavPassword = UserDictPrefs.password
        uiState.webdavSyncPath = UserDictPrefs.syncPath
        uiState.webdavAllowHttp = UserDictPrefs.allowHttp
        uiState.lastUpload = UserDictPrefs.lastUploadTime
        uiState.lastDownload = UserDictPrefs.lastDownloadTime

        val themeMode = KeyboardManager.Theme.getMode(this)

        setContent {
            ImeTheme(themeMode = themeMode) {
                UserDictScreen(
                    state = uiState,
                    onBack = { finish() },
                    onExport = { doExport() },
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onSaveWebDav = { server, username, password, syncPath ->
                        UserDictPrefs.server = server
                        UserDictPrefs.username = username
                        UserDictPrefs.password = password
                        UserDictPrefs.syncPath = syncPath
                        uiState.webdavServer = UserDictPrefs.server
                        uiState.webdavUsername = UserDictPrefs.username
                        uiState.webdavPassword = UserDictPrefs.password
                        uiState.webdavSyncPath = UserDictPrefs.syncPath
                        ToastUtil.showToast("已保存")
                    },
                    onAllowHttpChange = { allowed ->
                        UserDictPrefs.allowHttp = allowed
                        uiState.webdavAllowHttp = allowed
                        ToastUtil.showToast(
                            if (allowed) "已允许明文 HTTP（高级）" else "已恢复仅允许 HTTPS"
                        )
                    },
                    onUpload = { doUpload() },
                    onDownload = { doDownload() },
                    onTestConnection = { doTestConnection() },
                    onPickRemoteFile = { file ->
                        uiState.remoteFiles = null
                        selectedDict()?.let { doDownloadFile(it, file) }
                    },
                    onDismissRemoteFiles = { uiState.remoteFiles = null },
                    onDismissNotice = { uiState.noticeMessage = null },
                )
            }
        }

        loadDictList()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 配置变化（旋转等）重建时跳过销毁：会话按名共享且无持有者计数，
        // 新实例正用着同名会话，此处销毁会让新界面拿到失效会话
        if (!isChangingConfigurations) {
            // fire-and-forget：若本界面是唯一的会话持有者，顺手停掉 Rime 释放资源
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { RimeDaemon.destroySession(SESSION_NAME) }
            }
        }
        session = null
    }

    private fun loadDictList() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val s = RimeDaemon.createSession(SESSION_NAME)
                session = s
                val ready = withTimeoutOrNull(60_000L) {
                    s.runOnReady { }
                    true
                }
                if (ready != true) {
                    throw IllegalStateException("Rime 启动超时")
                }
                val dicts = runCatching { s.runOnReady { getUserDictList() } }
                    .getOrDefault(emptyList())
                withContext(Dispatchers.Main) {
                    uiState.dicts = dicts
                    uiState.selectedDict = dicts.firstOrNull()
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to load user dict list")
                withContext(Dispatchers.Main) {
                    uiState.dicts = emptyList()
                    ToastUtil.showToast("词典列表加载失败")
                }
            }
        }
    }

    private fun selectedDict(): String? = uiState.selectedDict

    private fun doExport() {
        val dict = selectedDict() ?: return
        setBusy("正在导出…")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sharedDir = File(cacheDir, "shared").apply { mkdirs() }
                val file = File(sharedDir, WebDavSync.remoteFileName(dict))
                // 经引擎线程串行导出，不与引擎查词/学词并发访问同一个 LevelDB
                val count = session?.runOnReady {
                    exportUserDictLive(dict, file.absolutePath)
                } ?: -1
                withContext(Dispatchers.Main) {
                    setIdle()
                    when {
                        count < 0 -> ToastUtil.showToast("导出失败")
                        count == 0 -> ToastUtil.showToast("用户词典为空")
                        else -> shareFile(file, dict, count)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Export user dict failed")
                withContext(Dispatchers.Main) {
                    setIdle()
                    ToastUtil.showToast("导出失败")
                }
            }
        }
    }

    private fun shareFile(file: File, dict: String, count: Int) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "用户词典（$dict，共 $count 个词）")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出用户词（$dict）"))
    }

    private fun doImport(uri: Uri) {
        val dict = selectedDict() ?: return
        setBusy("正在导入…")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val s = session
                    ?: throw IllegalStateException("Rime session not established")
                val tempFile = contentResolver.openInputStream(uri)?.use { input ->
                    UserDictManager.stageImportFile(input, "import-$dict.txt").getOrThrow()
                } ?: throw IllegalStateException("无法读取文件")
                // 经引擎线程串行导入，不与引擎查词/学词并发访问同一个 LevelDB；
                // 大文件分批导入，避免整段独占引擎线程导致导入期间按键卡顿
                val count = try {
                    s.runOnReady {
                        UserDictManager.importUserDictInChunks(tempFile) { chunk ->
                            importUserDictLive(dict, chunk.absolutePath)
                        }
                    }
                } finally {
                    tempFile.delete()
                }
                withContext(Dispatchers.Main) {
                    setIdle()
                    when {
                        count < 0 -> {
                            Timber.w("Import user dict failed: native returned $count")
                            ToastUtil.showToast("导入失败")
                        }
                        count == 0 -> ToastUtil.showToast("文件中没有可导入的词")
                        else -> ToastUtil.showToast("已导入 $count 个词")
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Import user dict failed")
                withContext(Dispatchers.Main) {
                    setIdle()
                    ToastUtil.showToast("导入失败")
                }
            }
        }
    }

    private fun doUpload() {
        val dict = selectedDict() ?: return
        if (!UserDictPrefs.isConfigured()) {
            ToastUtil.showToast("请先填写 WebDAV 服务器地址、用户名和密码并保存")
            return
        }
        val s = session ?: run {
            ToastUtil.showToast("词典服务未就绪，请稍后重试")
            return
        }
        setBusy("正在上传…")
        lifecycleScope.launch {
            val result = WebDavSync.upload(dict, s)
            setIdle()
            uiState.lastUpload = UserDictPrefs.lastUploadTime
            result
                .onSuccess { count -> ToastUtil.showToast("已上传 $count 个词") }
                .onFailure {
                    Timber.w(it, "WebDAV upload failed")
                    showNotice("操作失败", it.message ?: "上传失败")
                }
        }
    }

    private fun doDownload() {
        val dict = selectedDict() ?: return
        if (!UserDictPrefs.isConfigured()) {
            ToastUtil.showToast("请先填写 WebDAV 服务器地址、用户名和密码并保存")
            return
        }
        // 先列出服务器上的备份文件：电脑端（万象 base，wanxiang）与手机端
        // （lite/T9，wanxiang_lite）词典名不同，需让用户选择下载哪一个
        setBusy("正在获取服务器文件列表…")
        lifecycleScope.launch {
            val listResult = WebDavSync.listRemoteFiles()
            listResult
                .onSuccess { files ->
                    setIdle()
                    when {
                        files.isEmpty() ->
                            ToastUtil.showToast(getString(R.string.user_dict_remote_empty))
                        files.size == 1 ->
                            doDownloadFile(dict, files[0])
                        else ->
                            uiState.remoteFiles = files
                    }
                }
                .onFailure { e ->
                    val code = (e as? WebDavSync.WebDavHttpException)?.httpCode
                    if (code == 403 || code == 404) {
                        // 同步目录本身不可用，此时再去 GET 同名文件注定也是 403/404，
                        // 直接把目录问题告诉用户，不做无意义的回退
                        setIdle()
                        Timber.w(e, "WebDAV sync dir unavailable")
                        showNotice("操作失败", e.message ?: "同步目录不可用")
                    } else {
                        // 某些 WebDAV 服务器不支持 PROPFIND，回退到同名文件直接下载
                        Timber.w(e, "List remote files failed, fallback to same-name download")
                        doDownloadFile(dict, WebDavSync.remoteFileName(dict))
                    }
                }
        }
    }

    private fun showNotice(title: String, message: String) {
        uiState.noticeTitle = title
        uiState.noticeMessage = message
    }

    private fun doTestConnection() {
        if (!UserDictPrefs.isConfigured()) {
            ToastUtil.showToast("请先填写 WebDAV 服务器地址、用户名和密码并保存")
            return
        }
        setBusy("正在测试连接…")
        lifecycleScope.launch {
            val result = WebDavSync.testConnection()
            setIdle()
            result
                .onSuccess { entries ->
                    val dir = UserDictPrefs.syncDirUrl()
                    val msg = buildString {
                        appendLine("同步目录：$dir")
                        if (entries.isEmpty()) {
                            append("目录是空的，没有任何文件")
                        } else {
                            appendLine("共 ${entries.size} 项：")
                            entries.forEach {
                                appendLine("• ${it.name}${if (it.isDirectory) "/" else ""}")
                            }
                        }
                    }.trimEnd()
                    showNotice("连接正常", msg)
                }
                .onFailure {
                    Timber.w(it, "WebDAV test connection failed")
                    showNotice("操作失败", it.message ?: "连接失败")
                }
        }
    }

    private fun doDownloadFile(dict: String, remoteFile: String) {
        val s = session ?: run {
            ToastUtil.showToast("词典服务未就绪，请稍后重试")
            return
        }
        setBusy(getString(R.string.user_dict_downloading_from, remoteFile))
        lifecycleScope.launch {
            val result = WebDavSync.download(dict, s, remoteFile)
            setIdle()
            uiState.lastDownload = UserDictPrefs.lastDownloadTime
            result
                .onSuccess { count ->
                    ToastUtil.showToast(
                        if (count == 0) "文件中没有可导入的词" else "已下载并导入 $count 个词"
                    )
                }
                .onFailure {
                    Timber.w(it, "WebDAV download failed")
                    showNotice("操作失败", it.message ?: "下载失败")
                }
        }
    }

    private fun setBusy(message: String) {
        uiState.busyMessage = message
        uiState.busy = true
    }

    private fun setIdle() {
        uiState.busy = false
        uiState.busyMessage = ""
    }

    companion object {
        private const val SESSION_NAME = "userdict"
    }
}
