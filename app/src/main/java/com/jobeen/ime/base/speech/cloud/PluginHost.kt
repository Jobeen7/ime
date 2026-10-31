package com.jobeen.ime.base.speech.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 插件会话宿主：每插件每会话一个实例、一条串行工作线程、一个引擎实例。
 *
 * 桥接表（固定，超出须另立确认，不可顺带扩权——本轮唯一新增为
 * crypto.hmacSha1Base64，见方法注释；它是签名鉴权类插件所必需，
 * 且与 authHeaders 同信任模型：脚本只给字段名，宿主只返派生值）：
 * - net.httpPost(url, headers, bodyBase64, contentType?) → {ok, status?, bodyBase64?, error?}
 * - net.websocketConnect(url, headers?) → {ok, ws?, error?}；ws = {send, sendBytes, close}
 * - net.audioFrame(pcmBase64, last?) → base64 音频帧或 null（宿主组帧，
 *   流式协议帧：[0x11, 0x20/0x22, 0x01, 0x00]＋长度＋gzip(PCM)；只处理
 *   脚本已持有的 PCM，不触密钥／网络／文件。2026-10-30 音频预算轮新增：
 *   脚本侧逐字节编解码在 Rhino 下超 50ms 事件预算，改由宿主一次组帧）
 * - net.configFrame(jsonBase64) → base64 配置帧或 null（宿主组帧，
 *   [0x11, 0x10, 0x11, 0x00]＋长度＋gzip(JSON)，与音频帧同 trust 模型）
 * - net.gunzip(payloadBase64) → base64 解压后字节或 null（只解服务端
 *   响应载荷，不触密钥／网络／文件；豆包脚本自 2026-10-30 响应解析轮
 *   起不再经此桥，改走 net.doubaoResult，本桥为导入插件兼容保留）
 * - net.doubaoResult(frameBase64) → {text, final, error} 或 null（宿主
 *   解析豆包流式响应帧：帧头、gzip 解压、UTF-8 解码、JSON 取文本；
 *   2026-10-30 响应解析轮新增：脚本内四遍逐字节循环随载荷增大超
 *   50ms 事件预算，真机 partial 之后即因此被杀，改由宿主一次解析，
 *   脚本只做路由；null＝丢帧，error＝服务端错误帧）
 * - cb.partial(text)／cb.final(text)／cb.error(_)／cb.ended()
 * - files.read(name)／files.write(name, text)／files.list()
 * - crypto.hmacSha1Base64(field, message) → base64 签名或 null
 * - 顶层生命周期：start(ctx)／audio(ctx)／onMessage(ctx)／stop(ctx)／onSocketClosed()
 *
 * 预算：事件回调（audio／onMessage／onSocketClosed）50ms，
 * 后台任务（脚本加载／start／stop）500ms。超限杀实例＋停用待用户重开。
 * 日志只记插件 id、操作、异常类名、文本长度——绝不记脚本源码、
 * 识别文本、网络体、密钥值。
 */
class PluginHost(
    private val plugin: PluginStore.InstalledPlugin,
    settings: Map<String, String>,
    private val secrets: SecretStore,
    private val transport: NetworkTransport,
    private val listener: Listener,
    private val base64: Base64Codec = AndroidBase64Codec,
) {
    interface Listener {
        fun onReady()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError()
        fun onEnded()
        fun onBudgetDisabled()
    }

    /** 非密钥设置（宿主按元数据再过滤一次：SECRET 键即使误传也被丢弃）。 */
    private val safeSettings: Map<String, String> = run {
        val secretKeys = plugin.meta.settings
            .filter { it.type == SettingType.SECRET }
            .map { it.key }
            .toSet()
        settings.filterKeys { it !in secretKeys }
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cloud-voice-worker")
    }

    /**
     * 独立网络执行器：HTTP 调用跑在此线程，不占音频工作线程做 socket I/O。
     * 单线程＋有界队列（脚本回调串行，正常情况下队列恒空；满了直接拒，
     * 桥接返 network_busy，绝不让工作线程无限排队）。守护线程，防会话
     * 遗弃后拖住进程退出；正常路径 killInternal 会显式 shutdown。
     */
    private val networkExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(NETWORK_QUEUE_CAPACITY),
        ThreadFactory { runnable ->
            Thread(runnable, "cloud-voice-network").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** 音频积压计数（已投递未完成，含正在跑的一块）：投递线程增、工作线程完成时减；超上限直接丢弃，不进工作队列。 */
    private val audioBacklog = AtomicInteger(0)

    /** 累计丢弃的音频块数（拥塞可观察：节流日志＋测试可读计数）。 */
    private val audioDropped = AtomicLong(0)

    private val lastCongestionLogNanos = AtomicLong(0)

    /** 密钥访问串行化：auth 代附（网络线程）与签名桥（工作线程）共用同一 SecretStore，串行化后不依赖 store 自身的线程安全。 */
    private val secretsLock = Any()

    /** 测试可读：当前音频积压（内部可见，不属公开契约）。 */
    internal val audioBacklogSize: Int get() = audioBacklog.get()

    /** 测试可读：累计丢弃的音频块数（内部可见，不属公开契约）。 */
    internal val audioDroppedCount: Long get() = audioDropped.get()

    @Volatile
    private var closed = false

    // 以下字段只在工作线程上读写。
    private var session: RhinoSession? = null
    private val openSockets = mutableSetOf<NetworkTransport.WsHandle>()
    private var wsNoHandlerLogged = false
    private var budgetNotified = false

    /** 最近一次脚本入口（cb.error 原因码日志用，只记阶段，不记内容）。 */
    @Volatile
    private var currentOp = "unknown"

    /** 启动：工作线程上建会话、加载脚本、调 start；成功回 [Listener.onReady]。 */
    fun start() {
        post {
            if (closed) return@post
            try {
                val source = plugin.scriptFile.readText(Charsets.UTF_8)
                if (source.toByteArray(Charsets.UTF_8).size > PluginContract.MAX_SCRIPT_BYTES) {
                    failSession("script_too_large")
                    return@post
                }
                val created = RhinoSession.create()
                session = created
                installBridges(created)
                created.eval(source, "plugin", BACKGROUND_BUDGET_MS)
                val audioFn = created.getFunction("audio")
                if (audioFn == null) {
                    failSession("audio_missing")
                    return@post
                }
                currentOp = "start"
                created.callFunction("start", arrayOf(buildStartCtx()), BACKGROUND_BUDGET_MS)
                listener.onReady()
            } catch (e: ScriptBudgetExceeded) {
                onBudgetExceeded("start")
            } catch (e: Exception) {
                Timber.w(
                    "cloudVoice session failed plugin=%s op=start err=%s",
                    plugin.meta.id, sanitizeScriptError(e).kind,
                )
                failSession("start_failed")
            }
        }
    }

    /**
     * 喂一块 PCM（base64）：工作线程上调 audio，50ms 预算。
     * 积压超上限直接丢弃（拥塞保护）：工作线程被慢网络拖住时，
     * 录音回调仍在产块，队列绝不能无限增长；丢弃记数＋节流日志，
     * 停止／杀实例时不回放陈旧块（见 killInternal 清空与任务内 closed 检查）。
     */
    fun audio(pcmBase64: String) {
        if (closed) return
        if (audioBacklog.incrementAndGet() > MAX_AUDIO_BACKLOG) {
            audioBacklog.decrementAndGet()
            logCongestion(audioDropped.incrementAndGet())
            return
        }
        val posted = post {
            try {
                val current = session ?: return@post
                if (closed) return@post
                try {
                    currentOp = "audio"
                    current.callFunction("audio", arrayOf(buildAudioCtx(pcmBase64)), EVENT_BUDGET_MS)
                } catch (e: ScriptBudgetExceeded) {
                    onBudgetExceeded("audio")
                } catch (e: Exception) {
                    Timber.w(
                        "cloudVoice session failed plugin=%s op=audio err=%s",
                        plugin.meta.id, sanitizeScriptError(e).kind,
                    )
                    failSession("audio_failed")
                }
            } finally {
                audioBacklog.decrementAndGet()
            }
        }
        if (!posted) {
            // 投递失败（已关闭或线程池已停）：任务不会跑，计数须还回去；
            // killInternal 另会把计数清零（会话已死，计数不再被读）。
            audioBacklog.decrementAndGet()
        }
    }

    /** 拥塞日志（节流 1 秒，避免慢网络期间每块都刷一条日志）。 */
    private fun logCongestion(dropped: Long) {
        val now = System.nanoTime()
        val last = lastCongestionLogNanos.get()
        if (dropped == 1L || now - last > CONGESTION_LOG_INTERVAL_NANOS) {
            if (lastCongestionLogNanos.compareAndSet(last, now)) {
                Timber.w(
                    "cloudVoice audio congested plugin=%s backlog=%d dropped=%d",
                    plugin.meta.id, MAX_AUDIO_BACKLOG, dropped,
                )
            }
        }
    }

    /**
     * 正常结束：调 stop（500ms 预算），随后释放会话与工作线程。
     * 先取消在途 HTTP：会话将结束，挂起的网络不再有意义，工作线程
     * 不应再被慢网络拖住才跑得到 stop 任务。
     */
    fun stop() {
        cancelInflightNetwork()
        post {
            if (closed) return@post
            try {
                currentOp = "stop"
                session?.callFunction("stop", arrayOf(buildSimpleCtx()), BACKGROUND_BUDGET_MS)
            } catch (e: ScriptBudgetExceeded) {
                onBudgetExceeded("stop")
            } catch (e: Exception) {
                Timber.w(
                    "cloudVoice session stop failed plugin=%s err=%s",
                    plugin.meta.id, sanitizeScriptError(e).kind,
                )
            } finally {
                killInternal()
            }
        }
    }

    /**
     * 立即杀实例（不调 stop）：停用、卸载、异常收尾走此口。
     * 先从调用线程取消在途 HTTP：工作线程若正堵在慢网络上，
     * 取消 socket 后它立即解堵，随后排队的 killInternal 才能跑到。
     */
    fun kill() {
        cancelInflightNetwork()
        post { killInternal() }
    }

    /** 从调用线程取消在途 HTTP（Call.cancel 线程安全；假传输无此能力时空操作）。 */
    private fun cancelInflightNetwork() {
        runCatching { (transport as? CancellableTransport)?.cancelInflight() }
    }

    /**
     * 工作线程投递：已关闭或线程池已停时静默丢弃（会话已死，
     * 后续投递皆无意义；绝不向调用方抛 RejectedExecutionException）。
     * 返回是否成功投递（audio 计数用：投递失败的任务不会跑，
     * 调用方须把先增的计数还回去）。
     */
    private fun post(task: () -> Unit): Boolean {
        if (closed) return false
        return runCatching { worker.execute(task) }.isSuccess
    }

    private fun killInternal() {
        if (closed) return
        closed = true
        for (handle in openSockets) {
            runCatching { handle.close() }
        }
        openSockets.clear()
        runCatching { session?.close() }
        session = null
        // 取消时清空：工作队列里排队的音频任务随 shutdownNow 丢弃，
        // 停止后绝不回放陈旧 PCM；计数清零（被丢弃的任务不会再减计数）。
        val dropped = audioDropped.get()
        if (dropped > 0L) {
            Timber.w("cloudVoice audio dropped total plugin=%s dropped=%d", plugin.meta.id, dropped)
        }
        audioBacklog.set(0)
        runCatching { networkExecutor.shutdownNow() }
        worker.shutdownNow()
    }

    private fun failSession(reason: String) {
        Timber.w("cloudVoice session failed plugin=%s op=%s", plugin.meta.id, reason)
        listener.onError()
        killInternal()
    }

    private fun onBudgetExceeded(op: String) {
        Timber.w("cloudVoice budget exceeded plugin=%s op=%s", plugin.meta.id, op)
        if (!budgetNotified) {
            budgetNotified = true
            listener.onBudgetDisabled()
        }
        killInternal()
    }

    private fun installBridges(session: RhinoSession) {
        session.putBridge("net", buildNetBridge())
        session.putBridge("cb", buildCbBridge())
        session.putBridge("files", buildFilesBridge())
        session.putBridge("crypto", buildCryptoBridge())
    }

    private fun buildNetBridge(): NativeObject {
        val net = NativeObject()
        net.put("httpPost", net, BridgeFunction { args ->
            runCatching { netHttpPost(args) }.getOrElse { errorResult("internal") }
        })
        net.put("websocketConnect", net, BridgeFunction { args ->
            runCatching { netWebsocketConnect(args) }.getOrElse { errorResult("internal") }
        })
        net.put("audioFrame", net, BridgeFunction { args ->
            val pcmBase64 = asJsString(args.getOrNull(0)) ?: return@BridgeFunction null
            val last = (args.getOrNull(1) as? Boolean) == true
            runCatching {
                base64.encodeToString(buildAudioFrameBytes(base64.decode(pcmBase64), last))
            }.getOrNull()
        })
        net.put("configFrame", net, BridgeFunction { args ->
            val jsonBase64 = asJsString(args.getOrNull(0)) ?: return@BridgeFunction null
            runCatching {
                base64.encodeToString(buildConfigFrameBytes(base64.decode(jsonBase64)))
            }.getOrNull()
        })
        net.put("gunzip", net, BridgeFunction { args ->
            val payloadBase64 = asJsString(args.getOrNull(0)) ?: return@BridgeFunction null
            runCatching {
                base64.encodeToString(gunzipBytes(base64.decode(payloadBase64)))
            }.getOrNull()
        })
        net.put("doubaoResult", net, BridgeFunction { args ->
            val frameBase64 = asJsString(args.getOrNull(0)) ?: return@BridgeFunction null
            val frame = runCatching { base64.decode(frameBase64) }.getOrNull()
                ?: return@BridgeFunction null
            val parsed = parseDoubaoResponse(frame) ?: return@BridgeFunction null
            val out = NativeObject()
            out.put("text", out, parsed.text)
            out.put("final", out, parsed.final)
            out.put("error", out, parsed.error)
            out
        })
        return net
    }

    /**
     * net.httpPost 桥接：同步返回契约不变（{ok, status?, bodyBase64?, error?}），
     * 但 HTTP 调用本身跑在独立网络执行器上，不占音频工作线程做 socket I/O。
     * 工作线程只等 Future 结果；执行器满了直接返 network_busy，不排队。
     * 注意：Rhino 墙钟预算只在执行 JS 指令时触发，阻塞中的桥接调用
     * 它拦不住；真正的取消靠 kill／stop 从调用线程取消在途 Call
     * （见 [cancelInflightNetwork]），预算超时自动取消需把 httpPost
     * 改为异步回调（改脚本契约），本批不做。
     */
    private fun netHttpPost(args: Array<Any?>): NativeObject {
        val url = asJsString(args.getOrNull(0)) ?: return errorResult("bad_args")
        val headers = readHeaders(args.getOrNull(1)) ?: return errorResult("bad_args")
        val bodyBase64 = asJsString(args.getOrNull(2)) ?: return errorResult("bad_args")
        val body = runCatching {
            base64.decode(bodyBase64)
        }.getOrNull() ?: return errorResult("bad_body")
        val contentType = asJsString(args.getOrNull(3))
        val shim = NetworkShim(
            allowedHosts = plugin.meta.hosts.map { it.lowercase() }.toSet(),
            authHeaders = plugin.meta.authHeaders,
            secretResolver = { field -> synchronized(secretsLock) { secrets.get(plugin.meta.id, field) } },
            transport = transport,
        )
        val future = try {
            networkExecutor.submit(Callable { shim.httpPost(url, headers, body, contentType) })
        } catch (e: RejectedExecutionException) {
            return errorResult("network_busy")
        }
        return try {
            val response = future.get()
            val out = NativeObject()
            out.put("ok", out, true)
            out.put("status", out, response.status)
            out.put(
                "bodyBase64", out,
                base64.encodeToString(response.body),
            )
            out
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is NetworkException -> errorResult(cause.reason)
                else -> errorResult("internal")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            errorResult("internal")
        }
    }

    private fun netWebsocketConnect(args: Array<Any?>): NativeObject {
        val url = asJsString(args.getOrNull(0)) ?: return errorResult("bad_args")
        val headers = readHeaders(args.getOrNull(1)) ?: return errorResult("bad_args")
        val shim = NetworkShim(
            allowedHosts = plugin.meta.hosts.map { it.lowercase() }.toSet(),
            authHeaders = plugin.meta.authHeaders,
            secretResolver = { field -> secrets.get(plugin.meta.id, field) },
            transport = transport,
        )
        val handle = try {
            shim.websocket(url, headers, object : NetworkTransport.WsListener {
                override fun onMessage(text: String?, bytes: ByteArray?) {
                    post { dispatchWsMessage(text, bytes) }
                }

                override fun onClosed() {
                    post { dispatchSocketClosed() }
                }

                override fun onFailure() {
                    post { dispatchSocketClosed() }
                }
            })
        } catch (e: NetworkException) {
            return errorResult(e.reason)
        }
        openSockets.add(handle)
        val ws = NativeObject()
        ws.put("send", ws, BridgeFunction { sendArgs ->
            val text = asJsString(sendArgs.getOrNull(0)) ?: return@BridgeFunction false
            shim.wsSend(handle, text)
        })
        ws.put("sendBytes", ws, BridgeFunction { sendArgs ->
            val encoded = asJsString(sendArgs.getOrNull(0)) ?: return@BridgeFunction false
            val bytes = runCatching {
                base64.decode(encoded)
            }.getOrNull() ?: return@BridgeFunction false
            shim.wsSendBytes(handle, bytes)
        })
        ws.put("close", ws, BridgeFunction {
            runCatching { handle.close() }
            openSockets.remove(handle)
            null
        })
        val out = NativeObject()
        out.put("ok", out, true)
        out.put("ws", out, ws)
        return out
    }

    private fun dispatchWsMessage(text: String?, bytes: ByteArray?) {
        val current = session ?: return
        if (closed) return
        // 二进制入站先按字节数拦截，再做 Base64：编码前拦住，绝不为超限帧
        // 分配 1.33 倍字符串；字节上限由字符上限换算（每 3 字节变 4 字符），
        // 接受集与原来完全一致（字符数超限的帧原来也在编码后被丢弃）。
        // 说明：OkHttp 公共 API 无入站帧上限设置，帧本身由 OkHttp 物化
        // 不可避免；此处是物化后、放大前的拦截，帧层硬上限需换实现，另议。
        if (bytes != null && bytes.size > MAX_WS_INBOUND_BYTES) {
            Timber.w("cloudVoice ws message dropped plugin=%s bytes=%d", plugin.meta.id, bytes.size)
            return
        }
        val data = text ?: bytes?.let { base64.encodeToString(it) } ?: return
        if (data.length > MAX_WS_INBOUND_CHARS) {
            Timber.w("cloudVoice ws message dropped plugin=%s len=%d", plugin.meta.id, data.length)
            return
        }
        val ctx = NativeObject()
        ctx.put("data", ctx, data)
        ctx.put("isBinary", ctx, text == null)
        ctx.put("settings", ctx, settingsObject())
        try {
            val fn = current.getFunction("onMessage")
            if (fn == null) {
                if (!wsNoHandlerLogged) {
                    wsNoHandlerLogged = true
                    Timber.w("cloudVoice ws no handler plugin=%s", plugin.meta.id)
                }
                return
            }
            currentOp = "onMessage"
            current.callFunction(fn, arrayOf(ctx), EVENT_BUDGET_MS)
        } catch (e: ScriptBudgetExceeded) {
            onBudgetExceeded("onMessage")
        } catch (e: Exception) {
            Timber.w(
                "cloudVoice session failed plugin=%s op=onMessage err=%s",
                plugin.meta.id, sanitizeScriptError(e).kind,
            )
            failSession("onMessage_failed")
        }
    }

    private fun dispatchSocketClosed() {
        val current = session ?: return
        if (closed) return
        try {
            currentOp = "socket_closed"
            current.callFunction("onSocketClosed", arrayOf(buildSimpleCtx()), EVENT_BUDGET_MS)
        } catch (e: ScriptBudgetExceeded) {
            onBudgetExceeded("onSocketClosed")
        } catch (e: Exception) {
            Timber.w(
                "cloudVoice socket closed handler failed plugin=%s err=%s",
                plugin.meta.id, sanitizeScriptError(e).kind,
            )
        }
    }

    private fun buildCbBridge(): NativeObject {
        val cb = NativeObject()
        cb.put("partial", cb, BridgeFunction { args ->
            val text = asJsString(args.getOrNull(0)) ?: return@BridgeFunction null
            if (text.length > MAX_PARTIAL_CHARS) {
                Timber.w("cloudVoice partial dropped plugin=%s len=%d", plugin.meta.id, text.length)
                return@BridgeFunction null
            }
            listener.onPartial(text)
            null
        })
        cb.put("final", cb, BridgeFunction { args ->
            val text = asJsString(args.getOrNull(0)) ?: return@BridgeFunction null
            if (text.length > MAX_FINAL_CHARS) {
                Timber.w("cloudVoice final dropped plugin=%s len=%d", plugin.meta.id, text.length)
                return@BridgeFunction null
            }
            listener.onFinal(text)
            null
        })
        cb.put("error", cb, BridgeFunction {
            // 只记阶段＋原因类（script_error），不记脚本报错文本、不记任何
            // 内容；失败语义不变，仍走通用失败。
            Timber.w("cloudVoice script error plugin=%s op=%s", plugin.meta.id, currentOp)
            // 杀实例必须递延到当前脚本调用结束之后（工作线程任务队列），
            // 绝不能在桥接调用中途 exit 引擎 Context。
            listener.onError()
            post { killInternal() }
            null
        })
        cb.put("ended", cb, BridgeFunction {
            listener.onEnded()
            post { killInternal() }
            null
        })
        return cb
    }

    /**
     * 签名桥接（本轮新增，唯一新增桥接）：脚本给字段名＋待签文本，
     * 宿主用该插件自有 SECRET 字段的密钥算 HMAC-SHA1 并只返 base64
     * 签名。字段必须属于本插件的 SECRET 设置，否则返 null；文本超
     * 限、密钥未填、算签失败一律返 null。密钥值永不进入脚本空间，
     * 与 authHeaders 代附同信任模型；除本函数外不暴露任何密码学原语。
     */
    private fun buildCryptoBridge(): NativeObject {
        val crypto = NativeObject()
        crypto.put("hmacSha1Base64", crypto, BridgeFunction { args ->
            val field = asJsString(args.getOrNull(0)) ?: return@BridgeFunction null
            val message = asJsString(args.getOrNull(1)) ?: return@BridgeFunction null
            val secretKeys = plugin.meta.settings
                .filter { it.type == SettingType.SECRET }
                .map { it.key }
                .toSet()
            if (field !in secretKeys) return@BridgeFunction null
            if (message.length > MAX_CRYPTO_MESSAGE_CHARS) return@BridgeFunction null
            val secret = synchronized(secretsLock) { secrets.get(plugin.meta.id, field) } ?: return@BridgeFunction null
            runCatching {
                val mac = javax.crypto.Mac.getInstance("HmacSHA1")
                mac.init(
                    javax.crypto.spec.SecretKeySpec(
                        secret.toByteArray(Charsets.UTF_8),
                        "HmacSHA1",
                    ),
                )
                base64.encodeToString(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
            }.getOrNull()
        })
        return crypto
    }

    private fun buildFilesBridge(): NativeObject {
        val files = NativeObject()
        files.put("read", files, BridgeFunction { args ->
            val name = asJsString(args.getOrNull(0)) ?: return@BridgeFunction null
            val file = resolveSandboxFile(name) ?: return@BridgeFunction null
            if (!file.isFile || file.length() > MAX_FILE_BYTES) return@BridgeFunction null
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        })
        files.put("write", files, BridgeFunction { args ->
            val name = asJsString(args.getOrNull(0)) ?: return@BridgeFunction false
            val text = asJsString(args.getOrNull(1)) ?: return@BridgeFunction false
            val file = resolveSandboxFile(name) ?: return@BridgeFunction false
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_FILE_BYTES) return@BridgeFunction false
            val dir = plugin.sandboxDir
            val currentTotal = dir.listFiles()?.sumOf { it.length() } ?: 0L
            val existing = if (file.exists()) file.length() else 0L
            if (currentTotal - existing + bytes.size > MAX_SANDBOX_BYTES) {
                return@BridgeFunction false
            }
            runCatching {
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                true
            }.getOrDefault(false)
        })
        files.put("list", files, BridgeFunction {
            val names = plugin.sandboxDir.listFiles()
                ?.map { it.name }
                ?.sorted()
                ?.take(MAX_LIST_ENTRIES)
                ?: emptyList()
            val array = NativeArray(names.size.toLong())
            names.forEachIndexed { index, name -> array.put(index, array, name) }
            array
        })
        return files
    }

    /** 沙箱路径解析：名必须干净，且规范路径必须落在沙箱目录内。 */
    private fun resolveSandboxFile(name: String): File? {
        if (!FILE_NAME_REGEX.matches(name)) return null
        if (name == "." || name == "..") return null
        val sandbox = plugin.sandboxDir.canonicalFile
        val file = File(sandbox, name).canonicalFile
        if (!file.path.startsWith(sandbox.path + File.separator) && file != sandbox) return null
        return file
    }

    private fun buildStartCtx(): NativeObject {
        val ctx = NativeObject()
        ctx.put("settings", ctx, settingsObject())
        ctx.put("sampleRate", ctx, SUPPORTED_SAMPLE_RATE)
        return ctx
    }

    private fun buildAudioCtx(pcmBase64: String): NativeObject {
        val ctx = NativeObject()
        ctx.put("pcmBase64", ctx, pcmBase64)
        ctx.put("sampleRate", ctx, SUPPORTED_SAMPLE_RATE)
        ctx.put("settings", ctx, settingsObject())
        return ctx
    }

    private fun buildSimpleCtx(): NativeObject {
        val ctx = NativeObject()
        ctx.put("settings", ctx, settingsObject())
        return ctx
    }

    private fun settingsObject(): NativeObject {
        val obj = NativeObject()
        for ((k, v) in safeSettings) {
            obj.put(k, obj, v)
        }
        return obj
    }

    private fun readHeaders(arg: Any?): Map<String, String>? {
        if (arg == null || arg === org.mozilla.javascript.Undefined.instance) {
            return emptyMap()
        }
        val obj = arg as? NativeObject ?: return null
        val out = LinkedHashMap<String, String>()
        for (id in obj.ids) {
            val key = id as? String ?: continue
            val value = asJsString(ScriptableObject.getProperty(obj, key)) ?: return null
            out[key] = value
        }
        return out
    }

    /**
     * 脚本字符串读取：Rhino 的字符串拼接结果是 ConsString（CharSequence，
     * 不是 java.lang.String），一律按 CharSequence 取，否则拼接出的
     * 字符串会被误判为非字符串而丢弃。
     */
    private fun asJsString(value: Any?): String? =
        (value as? CharSequence)?.toString()

    private fun errorResult(reason: String): NativeObject {
        val out = NativeObject()
        out.put("ok", out, false)
        out.put("error", out, reason)
        return out
    }

    companion object {
        /** 流式协议帧组装（纯函数）：头 4 字节 [0x11, byte1, byte2, 0x00]
         * ＋int32BE(压缩后载荷长度)＋gzip 载荷。音频帧 byte1＝0x20（续）／
         * 0x22（末），byte2＝0x01（无序列化、gzip 压缩）；配置帧 byte1＝
         * 0x10、byte2＝0x11（JSON 序列化、gzip 压缩）。供 net.audioFrame／
         * net.configFrame 桥接使用。 */
        private fun buildVolcFrame(byte1: Int, byte2: Int, gzipped: ByteArray): ByteArray {
            val out = ByteArray(8 + gzipped.size)
            out[0] = 0x11
            out[1] = byte1.toByte()
            out[2] = byte2.toByte()
            out[3] = 0x00
            out[4] = (gzipped.size ushr 24).toByte()
            out[5] = (gzipped.size ushr 16).toByte()
            out[6] = (gzipped.size ushr 8).toByte()
            out[7] = gzipped.size.toByte()
            gzipped.copyInto(out, 8)
            return out
        }

        private fun gzip(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(data) }
            return out.toByteArray()
        }

        /** 音频帧组装（纯函数）：[0x11, 0x20/0x22, 0x01, 0x00]＋长度＋
         * gzip(PCM)。供 net.audioFrame 桥接使用。 */
        internal fun buildAudioFrameBytes(pcm: ByteArray, last: Boolean = false): ByteArray {
            return buildVolcFrame(if (last) 0x22 else 0x20, 0x01, gzip(pcm))
        }

        /** 配置帧组装（纯函数）：[0x11, 0x10, 0x11, 0x00]＋长度＋
         * gzip(JSON)。供 net.configFrame 桥接使用。 */
        internal fun buildConfigFrameBytes(json: ByteArray): ByteArray {
            return buildVolcFrame(0x10, 0x11, gzip(json))
        }

        /** 豆包流式响应解析结果（纯函数结果）：text 为识别文本（可空串），
         * final 为末帧标志，error 为服务端错误帧（脚本侧走 cb.error）。 */
        internal data class DoubaoResponse(
            val text: String,
            val final: Boolean,
            val error: Boolean,
        )

        /** 豆包流式响应帧解析（纯函数，供 net.doubaoResult 桥接使用）：
         * 帧结构与脚本旧 onMessage 逐字节一致——头 4 字节外加 flag&4 的
         * id 段与 flag&3 的 sequence 段、type 15 即错误帧、byte2 低 4 位
         * 为 1 时载荷 gzip；取文本回退顺序与旧 extractText 一致（result
         * 字符串 → result.text → utterances[].text 连接 → results[].text
         * 连接 → 顶层 text）。任何结构／解压／JSON 失败返回 null（丢帧，
         * 与旧路径的静默 return 一致）。2026-10-30 响应解析轮：旧路径
         * 在脚本内对整帧做 base64 解码＋（压缩时）重编码＋解码＋UTF-8
         * 解码四遍循环，载荷随 show_utterances 累积增大，Rhino 解释执行
         * 下超 50ms 事件预算，真机 partial 之后即因此被杀。 */
        internal fun parseDoubaoResponse(frame: ByteArray): DoubaoResponse? {
            return runCatching { parseDoubaoResponseInner(frame) }.getOrNull()
        }

        private fun parseDoubaoResponseInner(frame: ByteArray): DoubaoResponse? {
            if (frame.size < 8) return null
            val headerLen = (frame[0].toInt() and 15) * 4
            val type = (frame[1].toInt() ushr 4) and 15
            val flag = frame[1].toInt() and 15
            var pos = headerLen
            if (flag and 4 != 0) {
                if (pos + 4 > frame.size) return null
                pos += 4
                if (pos + 4 > frame.size) return null
                val idLen = int32BE(frame, pos)
                pos += 4 + idLen
            }
            if (flag and 3 == 1 || flag and 3 == 3) {
                pos += 4
            }
            if (type == 15) return DoubaoResponse("", false, true)
            if (pos + 4 > frame.size) return null
            val plen = int32BE(frame, pos)
            pos += 4
            if (plen < 0 || pos + plen > frame.size) return null
            var payload = frame.copyOfRange(pos, pos + plen)
            if (frame[2].toInt() and 15 == 1) {
                payload = runCatching { gunzipBytes(payload) }.getOrNull() ?: return null
            }
            val text = String(payload, Charsets.UTF_8)
            // kotlinx 解析（不用 org.json：android.jar 的 org.json 在 JVM
            // 单测里是 stub，解析器须在测试里真实跑通）。解析失败＝丢帧；
            // 解析成功但顶层非对象，与旧 extractText 的空串回退等效。
            val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
            val obj = element as? JsonObject
            return DoubaoResponse(if (obj == null) "" else extractDoubaoText(obj), flag and 3 == 3, false)
        }

        private fun JsonObject.stringField(key: String): String? {
            val primitive = get(key) as? JsonPrimitive ?: return null
            return if (primitive.isString) primitive.content else null
        }

        private fun extractDoubaoText(obj: JsonObject): String {
            val result = obj["result"] ?: return obj.stringField("text") ?: ""
            if (result is JsonPrimitive) {
                // 字符串 result 直接取；非字符串 result 与旧路径一致，
                // 落到末尾的顶层 text 回退（不直接回空串）。
                if (result.isString) return result.content
            } else if (result is JsonObject) {
                val text = result.stringField("text") ?: ""
                if (text.isNotEmpty()) return text
                val utterances = result["utterances"] as? JsonArray
                if (utterances != null) {
                    val parts = StringBuilder()
                    for (u in utterances) {
                        parts.append((u as? JsonObject)?.stringField("text") ?: "")
                    }
                    if (parts.isNotEmpty()) return parts.toString()
                }
                val results = result["results"] as? JsonArray
                if (results != null) {
                    val joined = StringBuilder()
                    for (r in results) {
                        joined.append((r as? JsonObject)?.stringField("text") ?: "")
                    }
                    if (joined.isNotEmpty()) return joined.toString()
                }
            }
            return obj.stringField("text") ?: ""
        }

        private fun int32BE(bytes: ByteArray, pos: Int): Int {
            return (bytes[pos].toInt() shl 24) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
        }

        /** gzip 解压（纯函数，供 net.gunzip 桥接使用；失败抛异常，由桥接
         * 转 null）。 */
        internal fun gunzipBytes(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPInputStream(data.inputStream()).use { it.copyTo(out) }
            return out.toByteArray()
        }

        /** 事件回调预算 50ms（设计值）。 */
        const val EVENT_BUDGET_MS = 50L

        /** 后台任务预算 500ms（设计值）。 */
        const val BACKGROUND_BUDGET_MS = 500L

        const val MAX_PARTIAL_CHARS = 4000
        const val MAX_FINAL_CHARS = 100_000
        const val MAX_FILE_BYTES = 1024 * 1024
        const val MAX_SANDBOX_BYTES = 10L * 1024 * 1024
        const val MAX_LIST_ENTRIES = 256
        const val MAX_WS_INBOUND_CHARS = 1024 * 1024

        /** WS 二进制入站字节上限：由字符上限换算（每 3 字节变 4 字符），与原来接受集一致。 */
        const val MAX_WS_INBOUND_BYTES = MAX_WS_INBOUND_CHARS * 3 / 4

        /** 音频积压上限（块数）：超了直接丢弃，防慢网络期间队列无限增长。 */
        const val MAX_AUDIO_BACKLOG = 32

        /** 网络执行器队列容量（任务数）：满了直接拒，桥接返 network_busy。 */
        const val NETWORK_QUEUE_CAPACITY = 4

        const val MAX_CRYPTO_MESSAGE_CHARS = 4096

        /** 拥塞日志节流间隔（纳秒，1 秒）。 */
        private const val CONGESTION_LOG_INTERVAL_NANOS = 1_000_000_000L

        private val FILE_NAME_REGEX = Regex("^[A-Za-z0-9._-]{1,64}$")
    }
}
