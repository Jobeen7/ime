package com.jobeen.ime.ui.screen

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jobeen.ime.R
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.export.JimeExporter
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.qr.SkinQrPayload
import com.jobeen.ime.skin.qr.SkinQrShare
import com.jobeen.ime.skin.store.SkinLibrary
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 库页卡片“分享二维码”对话框（2026-10-28 新增，App 侧二维码分享）。
 *
 * - 先导出 .jime 到缓存并读清单定方案（与编辑器同口径）：
 *   内联（仅 skin 且 ≤16KB）直接出码；含资产/布局或内联超限走
 *   URL 型（用户填已托管直链，App 附导出包 sha256 生成可验码）；
 *   导出/编码失败则明示只能用 .jime 文件分享。
 * - 生成的码必经 [SkinQrPayload.parse] 复核 Ok 才展示，与导入侧
 *   同一契约；导入侧不改。
 * - 日志只记方案种类与字节数等元数据，绝不记 URL、载荷原文、
 *   skin.json 内容。
 */
@Composable
fun QrShareDialog(
    skinId: String,
    onDismiss: () -> Unit,
    onShareJime: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<QrShareUiState>(QrShareUiState.Loading) }
    var urlInput by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var urlBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(skinId) {
        state = withContext(Dispatchers.IO) {
            runCatching {
                val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(sharedDir, "qr-share-$skinId.jime")
                var exported = false
                file.outputStream().use { out ->
                    exported = JimeExporter(SkinLibrary.layout, SkinLibrary.store)
                        .export(skinId, out) is JimeExporter.Result.Exported
                }
                if (!exported) return@runCatching QrShareUiState.Failed
                val bytes = file.readBytes()
                var manifest: PackageManifest? = null
                var skinJson: ByteArray? = null
                ZipInputStream(bytes.inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val out = ByteArrayOutputStream()
                        zip.copyTo(out)
                        when (entry.name) {
                            "manifest.json" -> manifest = PackageManifest.parse(out.toString(Charsets.UTF_8))
                            "skin.json" -> skinJson = out.toByteArray()
                        }
                    }
                }
                val contents = manifest?.contents
                val json = skinJson
                if (contents == null || json == null) return@runCatching QrShareUiState.Failed
                when (val plan = SkinQrShare.planForExport(contents, json)) {
                    is SkinQrShare.Plan.Inline -> {
                        val bitmap = SkinQrShare.encodeBitmap(plan.payload)
                        QrShareUiState.Inline(bitmap, json.size)
                    }
                    is SkinQrShare.Plan.NeedsUrl ->
                        QrShareUiState.NeedsUrl(DigestUtil.sha256Hex(bytes))
                }
            }.getOrElse { error ->
                DiagLog.w(
                    "library", "qr_share_prepare_failed",
                    mapOf("skinId" to skinId, "error" to (error::class.simpleName ?: "Unknown")),
                )
                QrShareUiState.Failed
            }
        }
    }

    fun shareBitmap(bitmap: Bitmap) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(sharedDir, "qr-$skinId.png")
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file,
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.skin_qr_share_title)))
            }.onFailure { error ->
                DiagLog.w(
                    "library", "qr_share_image_failed",
                    mapOf("skinId" to skinId, "error" to (error::class.simpleName ?: "Unknown")),
                )
            }
        }
    }

    SettingsDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.skin_qr_share_title),
        body = {
            Column {
                when (val current = state) {
                    is QrShareUiState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.skin_qr_preparing))
                    }
                    is QrShareUiState.Inline -> {
                        Image(
                            bitmap = current.bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.skin_qr_content_desc),
                            modifier = Modifier.size(256.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.skin_qr_inline, current.skinJsonBytes))
                        if (current.skinJsonBytes > 3072) {
                            Text(stringResource(R.string.skin_qr_large_note))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { shareBitmap(current.bitmap) }) { Text(stringResource(R.string.skin_qr_share_image)) }
                    }
                    is QrShareUiState.NeedsUrl -> {
                        Text(
stringResource(R.string.skin_qr_needs_url),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it; urlError = null; urlBitmap = null },
                            label = { Text(stringResource(R.string.skin_qr_url_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        urlError?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(it)
                        }
                        urlBitmap?.let { bitmap ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.skin_qr_content_desc),
                                modifier = Modifier.size(256.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { shareBitmap(bitmap) }) { Text(stringResource(R.string.skin_qr_share_image)) }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {
                            val payload = SkinQrShare.buildUrlPayload(
                                urlInput.trim(), current.packageSha256,
                            )
                            when (val parsed = SkinQrPayload.parse(payload)) {
                                is SkinQrPayload.ParseResult.Ok -> {
                                    urlError = null
                                    urlBitmap = runCatching {
                                        SkinQrShare.encodeBitmap(payload)
                                    }.getOrElse { error ->
                                        DiagLog.w(
                                            "library", "qr_share_encode_failed",
                                            mapOf(
                                                "skinId" to skinId,
                                                "error" to (error::class.simpleName ?: "Unknown"),
                                            ),
                                        )
                                        urlError = context.getString(R.string.skin_qr_generate_failed)
                                        null
                                    }
                                }
                                is SkinQrPayload.ParseResult.Rejected ->
                                    urlError = urlRejectionText(context, parsed.reason)
                            }
                        }) { Text(stringResource(R.string.skin_qr_generate)) }
                    }
                    is QrShareUiState.Failed -> {
                        Text(stringResource(R.string.skin_qr_generate_failed_text))
                    }
                }
            }
        },
        actions = listOf(
            SettingsDialogAction(text = stringResource(R.string.skin_qr_share_jime), onClick = onShareJime),
            SettingsDialogAction(text = stringResource(R.string.close), onClick = onDismiss),
        ),
    )
}

private sealed interface QrShareUiState {
    data object Loading : QrShareUiState
    data class Inline(val bitmap: Bitmap, val skinJsonBytes: Int) : QrShareUiState
    data class NeedsUrl(val packageSha256: String) : QrShareUiState
    data object Failed : QrShareUiState
}

private fun urlRejectionText(context: Context, reason: String): String = when (reason) {
    "qr_url_not_https" -> context.getString(R.string.skin_qr_url_not_https)
    "qr_url_userinfo_rejected" -> context.getString(R.string.skin_qr_url_userinfo)
    "qr_url_too_long" -> context.getString(R.string.skin_qr_url_too_long)
    "qr_url_invalid_chars" -> context.getString(R.string.skin_qr_url_invalid_chars)
    "qr_url_fragment_invalid" -> context.getString(R.string.skin_qr_url_fragment)
    else -> context.getString(R.string.skin_qr_url_invalid, reason)
}
