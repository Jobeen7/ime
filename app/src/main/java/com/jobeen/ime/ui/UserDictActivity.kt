package com.jobeen.ime.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.daemon.RimeSession
import com.jobeen.ime.engine.rime.data.userdict.RemoteTransferEvents
import com.jobeen.ime.engine.rime.data.userdict.RemoteTransferKind
import com.jobeen.ime.engine.rime.data.userdict.RemoteTransferService
import com.jobeen.ime.engine.rime.data.userdict.RemoteTransferStatus
import com.jobeen.ime.engine.rime.data.userdict.UserDictManager
import com.jobeen.ime.engine.rime.data.userdict.UserDictMergeSafety
import com.jobeen.ime.engine.rime.data.userdict.UserDictPrefs
import com.jobeen.ime.engine.rime.data.userdict.PeriodicSyncPrefs
import com.jobeen.ime.engine.rime.data.userdict.PeriodicSyncScheduler
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import com.jobeen.ime.error.DeepErrorMapper
import com.jobeen.ime.ui.screen.UserDictScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用户词典：手动导出/导入 + WebDAV 同步。
 *
 * 打开时创建 Rime 会话以加载词典列表；导入（本地文件/WebDAV 下载）为合并操作，
 * 导入/导出使用热路径直接操作运行中引擎的词库，无需停止 Rime，导入后立即生效。
 */
class UserDictActivity : ImeActivity() {
    // C-L-42：uiState 从 Activity 字段移到 ViewModel——旋转重建
    // 后状态保留（busy／词典列表／对话框等）；SavedStateHandle
    // 记 busy＋任务 runId，进程被杀后重建仍能恢复“传输进行中”。
    private val viewModel: UserDictViewModel by viewModels()
    private val uiState get() = viewModel.uiState
    private var session: RimeSession? = null
    private var loadJob: Job? = null

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            doImport(uri)
        }
    }

    override fun onCreateBeforeContent(savedInstanceState: Bundle?) {
        uiState.webdavServer = UserDictPrefs.server
        uiState.webdavUsername = UserDictPrefs.username
        uiState.webdavPassword = UserDictPrefs.password
        uiState.webdavSyncPath = UserDictPrefs.syncPath
        uiState.webdavAllowHttp = UserDictPrefs.allowHttp
        uiState.lastUpload = UserDictPrefs.lastUploadTime
        uiState.lastDownload = UserDictPrefs.lastDownloadTime
        refreshPeriodicSyncState()
        // 强制停止后持久任务不再唤醒：开关仍开着但任务丢了时，在此补调度
        if (PeriodicSyncPrefs.enabled && !PeriodicSyncScheduler.isScheduled(this)) {
            PeriodicSyncScheduler.schedule(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadDictList()
        observeRemoteTransfer()
    }

    /**
     * 订阅手动传输结果（WO52 4.5）：上传／下载改走前台服务，
     * 结果经 RemoteTransferEvents 回传；终端态只消费一次
     * （consumeIfNewTerminal 防旋转重建后重复弹 Toast），
     * Toast／失败提示口径与原来直调 WebDavSync 时一致。
     */
    private fun observeRemoteTransfer() {
        lifecycleScope.launch {
            RemoteTransferEvents.state.collect { state ->
                val done = state ?: return@collect
                if (done.status == RemoteTransferStatus.RUNNING) {
                    // C-L-42：传输开始时记 runId（进 SavedStateHandle），
                    // 重建后核对终端态归属；终端态 60 秒丢弃照旧。
                    viewModel.noteRunId(done.runId)
                    return@collect
                }
                // 过期终端态（发出超过 60 秒，多半是上次打开本页
                // 时留下的）不再弹结果，只消费新鲜的一次。
                if (System.currentTimeMillis() - done.postedAtMs > 60_000L) return@collect
                if (!RemoteTransferEvents.consumeIfNewTerminal(done)) return@collect
                setIdle()
                when (done.kind) {
                    RemoteTransferKind.UPLOAD -> {
                        uiState.lastUpload = UserDictPrefs.lastUploadTime
                        if (done.status == RemoteTransferStatus.SUCCESS) {
                            ToastUtil.showToast(
                                getString(
                                    R.string.user_dict_uploaded_count,
                                    done.count ?: 0,
                                    WebDavSync.remoteFileName(done.dict),
                                ),
                            )
                        } else {
                            showNotice(
                                getString(R.string.user_dict_op_failed_title),
                                done.error ?: "",
                            )
                        }
                    }
                    RemoteTransferKind.DOWNLOAD -> {
                        uiState.lastDownload = UserDictPrefs.lastDownloadTime
                        if (done.status == RemoteTransferStatus.SUCCESS) {
                            refreshBackupInfo()
                            val count = done.count ?: 0
                            ToastUtil.showToast(
                                if (count == 0) {
                                    getString(R.string.user_dict_no_words_in_file)
                                } else {
                                    getString(R.string.user_dict_downloaded_imported, count)
                                },
                            )
                        } else {
                            showNotice(
                                getString(R.string.user_dict_op_failed_title),
                                done.error ?: "",
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun Content() {
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
                ToastUtil.showToast(getString(R.string.user_dict_saved))
            },
            onAllowHttpChange = { allowed ->
                UserDictPrefs.allowHttp = allowed
                uiState.webdavAllowHttp = allowed
                ToastUtil.showToast(
                    if (allowed) getString(R.string.user_dict_http_allowed) else getString(R.string.user_dict_http_restored)
                )
            },
            onUpload = { doUpload() },
            onDownload = { doDownload() },
            onUploadSchemes = { doUploadSchemes() },
            onDownloadSchemes = { doDownloadSchemes() },
            onTestConnection = { doTestConnection() },
            onPickRemoteFile = { file ->
                uiState.remoteFiles = null
                selectedDict()?.let { doDownloadFile(it, file) }
            },
            onDismissRemoteFiles = { uiState.remoteFiles = null },
            onDismissNotice = { uiState.noticeMessage = null },
            onShowDeletedWords = {
                uiState.deletedWords =
                    com.jobeen.ime.data.manager.DeletedWordsStore.deletedWords()
            },
            onRestoreDeletedWord = { word ->
                com.jobeen.ime.data.manager.DeletedWordsStore.remove(word)
                uiState.deletedWords =
                    com.jobeen.ime.data.manager.DeletedWordsStore.deletedWords()
                ToastUtil.showToast(
                    getString(R.string.user_dict_deleted_words_restored),
                )
            },
            onDismissDeletedWords = { uiState.deletedWords = null },
            onRestoreBeforeMerge = { doRestoreBeforeMerge() },
            onOpenWordCreation = {
                startActivity(
                    android.content.Intent(
                        this@UserDictActivity,
                        WordCreationActivity::class.java,
                    )
                )
            },
            onPeriodicSyncEnabledChange = { enabled ->
                PeriodicSyncPrefs.enabled = enabled
                uiState.periodicSyncEnabled = enabled
                if (enabled) {
                    PeriodicSyncScheduler.schedule(this@UserDictActivity)
                } else {
                    PeriodicSyncScheduler.cancel(this@UserDictActivity)
                }
                ToastUtil.showToast(
                    if (enabled) getString(R.string.user_dict_periodic_on) else getString(R.string.user_dict_periodic_off)
                )
            },
            onPeriodicIntervalChange = { hours ->
                PeriodicSyncPrefs.intervalHours = hours
                uiState.periodicIntervalHours = hours
                if (PeriodicSyncPrefs.enabled) {
                    PeriodicSyncScheduler.schedule(this@UserDictActivity)
                }
            },
            onRetryDictList = { loadDictList() },
        )
    }

    override fun onResumeExtra() {
        // 曾加载失败（重置后引擎重启/部署窗口内打开正撞上超时）时，
        // 回到本页自动重载，不再靠用户重进页面才恢复。
        if (uiState.dicts == null) {
            loadDictList()
        }
        // 后台任务可能在外跑过：回来时刷新上次同步显示
        refreshPeriodicSyncState()
        refreshBackupInfo()
    }

    /** 后台定期同步状态 → 界面状态（开关／间隔／上次结果） */
    private fun refreshPeriodicSyncState() {
        uiState.periodicSyncEnabled = PeriodicSyncPrefs.enabled
        uiState.periodicIntervalHours = PeriodicSyncPrefs.intervalHours
        uiState.lastPeriodicSync = PeriodicSyncPrefs.lastSyncTime
        uiState.lastPeriodicSyncOk = PeriodicSyncPrefs.lastSyncOk
    }

    /**
     * 合并前备份信息 → 界面状态（恢复入口的副标题）：无备份时为 null，
     * 恢复行显示“暂无合并前备份”且不可点。读备份是纯文件 IO，不碰引擎。
     */
    private fun refreshBackupInfo() {
        val dict = selectedDict() ?: run {
            uiState.backupInfo = null
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val snapshot = UserDictMergeSafety.readBackupMeta(filesDir, dict)
            withContext(Dispatchers.Main) {
                // 切词典后才回来的旧结果不覆盖当前词典的显示。
                if (selectedDict() != dict) return@withContext
                uiState.backupInfo = snapshot?.let {
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(Date(it.createdAtMs))
                    getString(
                        R.string.user_dict_backup_info,
                        time,
                        it.entries.size,
                        it.sourceLabel,
                    )
                }
            }
        }
    }

    /**
     * 恢复到合并前备份（用户在确认对话框里点过“恢复”才到这里）：
     * 按 before-merge.json 把合并后新增的词删掉、被抬高的权重恢复。
     * 删除标记行只在这条恢复流程内部产生。
     */
    private fun doRestoreBeforeMerge() {
        val dict = selectedDict() ?: return
        setBusy(getString(R.string.user_dict_restoring_backup))
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val s = session
                    ?: throw IllegalStateException("Rime session not established")
                val result = UserDictMergeSafety.restoreBeforeMerge(
                    dictName = dict,
                    filesDir = filesDir,
                    cacheDir = cacheDir,
                    exportCurrent = { tmp ->
                        s.runOnReady { exportUserDictLive(dict, tmp.absolutePath) }
                    },
                    importFile = { file ->
                        s.runOnReady {
                            UserDictManager.importUserDictInChunks(file) { chunk ->
                                importUserDictLive(dict, chunk.absolutePath)
                            }
                        }
                    },
                )
                withContext(Dispatchers.Main) {
                    setIdle()
                    refreshBackupInfo()
                    result.onSuccess { report ->
                        ToastUtil.showToast(
                            if (report.removed == 0 && report.restored == 0) {
                                getString(R.string.user_dict_restore_noop)
                            } else {
                                getString(
                                    R.string.user_dict_restore_done,
                                    report.removed,
                                    report.restored,
                                )
                            },
                        )
                    }.onFailure { e ->
                        showNotice(
                            getString(R.string.user_dict_op_failed_title),
                            e.message ?: getString(R.string.user_dict_restore_failed),
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Restore before-merge backup failed")
                withContext(Dispatchers.Main) {
                    setIdle()
                    showNotice(
                        getString(R.string.user_dict_op_failed_title),
                        getString(R.string.user_dict_restore_failed),
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 配置变化（旋转等）重建时跳过销毁：会话按名共享且无持有者计数，
        // 新实例正用着同名会话，此处销毁会让新界面拿到失效会话
        if (!isChangingConfigurations) {
            // U-M2：在途作业（加载／导入／导出／上传／下载均经本会话做原生
            // 调用）先落定再销毁会话——旧代码用无结构作用域 fire-and-forget
            // 销毁，与在途原生调用竞跑，结果未定义。作业挂在 lifecycleScope
            // 下，销毁时取消随之到达，joinAll 只等取消收尾，不等原操作跑完。
            val inFlight = lifecycleScope.coroutineContext[Job]?.children?.toList().orEmpty()
            CoroutineScope(Dispatchers.IO).launch {
                inFlight.joinAll()
                runCatching { RimeDaemon.destroySession(SESSION_NAME) }
            }
        }
        session = null
    }

    private fun loadDictList() {
        // 失败不锁死为空表：加载中保持 null（转圈），失败置失败态（重试入口），
        // 只有真正取回的表才写 dicts；取表异常也不再用空表吞掉。
        loadJob?.cancel()
        loadJob = lifecycleScope.launch(Dispatchers.IO) {
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
                val dicts = s.runOnReady { getUserDictList() }
                withContext(Dispatchers.Main) {
                    uiState.dictsLoadFailed = false
                    uiState.dicts = dicts
                    uiState.selectedDict = dicts.firstOrNull()
                }
                refreshBackupInfo()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 被新一次加载取代的旧加载：不置失败态、不弹提示。
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to load user dict list")
                withContext(Dispatchers.Main) {
                    uiState.dictsLoadFailed = true
                    ToastUtil.showToast(getString(R.string.user_dict_list_failed))
                }
            }
        }
    }

    private fun selectedDict(): String? = uiState.selectedDict

    private fun doExport() {
        val dict = selectedDict() ?: return
        setBusy(getString(R.string.user_dict_exporting))
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
                        count < 0 -> ToastUtil.showToast(getString(R.string.user_dict_export_failed))
                        count == 0 -> ToastUtil.showToast(getString(R.string.user_dict_empty))
                        else -> shareFile(file, dict, count)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Export user dict failed")
                withContext(Dispatchers.Main) {
                    setIdle()
                    ToastUtil.showToast(getString(R.string.user_dict_export_failed))
                }
            }
        }
    }

    private fun shareFile(file: File, dict: String, count: Int) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.user_dict_share_subject, dict, count))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.user_dict_export_chooser, dict)))
    }

    private fun doImport(uri: Uri) {
        val dict = selectedDict() ?: return
        setBusy(getString(R.string.user_dict_importing))
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val s = session
                    ?: throw IllegalStateException("Rime session not established")
                val tempFile = contentResolver.openInputStream(uri)?.use { input ->
                    UserDictManager.stageImportFile(input, "import-$dict.txt").getOrThrow()
                } ?: throw IllegalStateException("无法读取文件")
                // 安全合并（UserDictMergeSafety）：批级问题整批不写并在
                // 对话框里说明哪一批、为什么；行级问题只跳过该行、其余
                // 照常导入；合格才导出当前词库存 before-merge.json 安全
                // 副本、批内同词条取较大权重去重后再导入。经引擎线程串行
                // 导入，不与引擎查词/学词并发访问同一个 LevelDB；大文件
                // 分批导入，避免整段独占引擎线程导致导入期间按键卡顿
                val result = try {
                    UserDictMergeSafety.mergeValidatedBatch(
                        dictName = dict,
                        batchFile = tempFile,
                        batchLabel = tempFile.name,
                        filesDir = filesDir,
                        cacheDir = cacheDir,
                        exportCurrent = { tmp ->
                            s.runOnReady { exportUserDictLive(dict, tmp.absolutePath) }
                        },
                        importFile = { file ->
                            s.runOnReady {
                                UserDictManager.importUserDictInChunks(file) { chunk ->
                                    importUserDictLive(dict, chunk.absolutePath)
                                }
                            }
                        },
                    )
                } finally {
                    tempFile.delete()
                }
                withContext(Dispatchers.Main) {
                    setIdle()
                    refreshBackupInfo()
                    // 导入成功后展示跳过数（1.6）：行级坏行只跳过该行、其余
                    // 照常导入，跳过几行必须让用户看见，不得静默丢行。
                    val imported = result.importedCount
                    val skipped = result.skippedRows
                    when {
                        imported == 0 && skipped == 0 ->
                            ToastUtil.showToast(getString(R.string.user_dict_no_words_in_file))
                        imported == 0 ->
                            ToastUtil.showToast(getString(R.string.user_dict_imported_none_skipped, skipped))
                        skipped == 0 ->
                            ToastUtil.showToast(getString(R.string.user_dict_imported_count, imported))
                        else ->
                            ToastUtil.showToast(getString(R.string.user_dict_imported_count_skipped, imported, skipped))
                    }
                }
            } catch (e: UserDictMergeSafety.UserDictMergeException) {
                // 校验/备份/导入中止：message 已是给用户看的整批说明，
                // 用对话框原样展示（哪一批、为什么不合格），不只弹“导入失败”。
                Timber.w(e, "Import user dict rejected")
                withContext(Dispatchers.Main) {
                    setIdle()
                    showNotice(
                        getString(R.string.user_dict_merge_invalid_title),
                        e.message ?: getString(R.string.import_failed),
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "Import user dict failed")
                withContext(Dispatchers.Main) {
                    setIdle()
                    ToastUtil.showToast(getString(R.string.import_failed))
                }
            }
        }
    }

    private fun doUpload() {
        val dict = selectedDict() ?: return
        if (!UserDictPrefs.isConfigured()) {
            ToastUtil.showToast(getString(R.string.user_dict_not_configured))
            return
        }
        // WO52 4.5：上传走前台服务；结果经 RemoteTransferEvents
        // 回 observeRemoteTransfer，Toast 口径不变。
        setBusy(getString(R.string.user_dict_uploading))
        RemoteTransferService.startUpload(this, dict)
    }

    private fun doDownload() {
        val dict = selectedDict() ?: return
        if (!UserDictPrefs.isConfigured()) {
            ToastUtil.showToast(getString(R.string.user_dict_not_configured))
            return
        }
        // 先列出服务器上的备份文件：电脑端（万象 base，wanxiang）与手机端
        // （lite/T9，wanxiang_lite）词典名不同，需让用户选择下载哪一个
        setBusy(getString(R.string.user_dict_fetching_files))
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
                    // 取消不是失败（S-M1）：不报提示、不走同名文件回退。
                    if (DeepErrorMapper.isCancellation(e)) {
                        setIdle()
                        return@onFailure
                    }
                    val code = (e as? WebDavSync.WebDavHttpException)?.httpCode
                    if (code == 403 || code == 404) {
                        // 同步目录本身不可用，此时再去 GET 同名文件注定也是 403/404，
                        // 直接把目录问题告诉用户，不做无意义的回退
                        setIdle()
                        Timber.w(e, "WebDAV sync dir unavailable")
                        showNotice(getString(R.string.user_dict_op_failed_title), DeepErrorMapper.userMessage(this@UserDictActivity, e))
                    } else {
                        // 某些 WebDAV 服务器不支持 PROPFIND，回退到同名文件直接下载
                        Timber.w(e, "List remote files failed, fallback to same-name download")
                        doDownloadFile(dict, WebDavSync.remoteFileName(dict))
                    }
                }
        }
    }

    private fun doUploadSchemes() {
        if (!UserDictPrefs.isConfigured()) {
            ToastUtil.showToast(getString(R.string.user_dict_not_configured))
            return
        }
        setBusy(getString(R.string.user_dict_uploading_schemes))
        lifecycleScope.launch {
            val result = WebDavSync.uploadSchemes()
            setIdle()
            result.onSuccess { count ->
                ToastUtil.showToast(getString(R.string.user_dict_uploaded_schemes, count))
            }.onFailure {
                // 取消不是失败（S-M1）：不报失败提示。
                if (DeepErrorMapper.isCancellation(it)) return@onFailure
                Timber.w(it, "uploadSchemes failed")
                showNotice(getString(R.string.user_dict_op_failed_title), DeepErrorMapper.userMessage(this@UserDictActivity, it))
            }
        }
    }

    private fun doDownloadSchemes() {
        if (!UserDictPrefs.isConfigured()) {
            ToastUtil.showToast(getString(R.string.user_dict_not_configured))
            return
        }
        setBusy(getString(R.string.user_dict_downloading_schemes))
        lifecycleScope.launch {
            val result = WebDavSync.downloadSchemes()
            setIdle()
            result.onSuccess { summary ->
                val skipNote =
                    if (summary.skippedEmpty.isNotEmpty()) getString(R.string.user_dict_skip_empty_suffix, summary.skippedEmpty.size) else ""
                ToastUtil.showToast(getString(R.string.user_dict_downloaded_schemes, summary.count, skipNote))
            }.onFailure {
                // 取消不是失败（S-M1）：不报失败提示。
                if (DeepErrorMapper.isCancellation(it)) return@onFailure
                Timber.w(it, "downloadSchemes failed")
                showNotice(getString(R.string.user_dict_op_failed_title), DeepErrorMapper.userMessage(this@UserDictActivity, it))
            }
        }
    }

    private fun showNotice(title: String, message: String) {
        uiState.noticeTitle = title
        uiState.noticeMessage = message
    }

    private fun doTestConnection() {
        if (!UserDictPrefs.isConfigured()) {
            ToastUtil.showToast(getString(R.string.user_dict_not_configured))
            return
        }
        setBusy(getString(R.string.user_dict_testing_connection))
        lifecycleScope.launch {
            val result = WebDavSync.testConnection()
            setIdle()
            result
                .onSuccess { entries ->
                    val dir = UserDictPrefs.syncDirUrl()
                    val msg = buildString {
                        appendLine(getString(R.string.user_dict_test_dir, dir))
                        if (entries.isEmpty()) {
                            append(getString(R.string.user_dict_test_dir_empty))
                        } else {
                            appendLine(getString(R.string.user_dict_test_dir_count, entries.size))
                            entries.forEach {
                                appendLine("• ${it.name}${if (it.isDirectory) "/" else ""}")
                            }
                        }
                    }.trimEnd()
                    showNotice(getString(R.string.user_dict_test_ok), msg)
                }
                .onFailure {
                    // 取消不是失败（S-M1）：不报失败提示。
                    if (DeepErrorMapper.isCancellation(it)) return@onFailure
                    Timber.w(it, "WebDAV test connection failed")
                    showNotice(getString(R.string.user_dict_op_failed_title), DeepErrorMapper.userMessage(this@UserDictActivity, it))
                }
        }
    }

    private fun doDownloadFile(dict: String, remoteFile: String) {
        // WO52 4.5：下载走前台服务；结果经 RemoteTransferEvents
        // 回 observeRemoteTransfer，Toast 口径不变。
        setBusy(getString(R.string.user_dict_downloading_from, remoteFile))
        RemoteTransferService.startDownload(this, dict, remoteFile)
    }

    private fun setBusy(message: String) {
        viewModel.setBusy(message)
    }

    private fun setIdle() {
        viewModel.setIdle()
    }

    companion object {
        private const val SESSION_NAME = "userdict"
    }
}
