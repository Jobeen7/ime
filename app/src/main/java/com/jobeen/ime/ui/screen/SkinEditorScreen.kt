// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.skin.library.ActiveSkinFiles
import com.jobeen.ime.skin.library.EditorFile
import com.jobeen.ime.skin.library.SkinEditorSaveResult
import com.jobeen.ime.skin.library.SkinEditorSaver
import kotlinx.coroutines.launch

/**
 * App 内置皮肤文件编辑器页（W19-7）：
 *
 * 编辑当前生效皮肤的文件文本（skin.json＋布局文件），保存后
 * 校验＋重载快照即刻生效。校验未通过不写文件、不丢文本（文本
 * 留在编辑器里）、旧快照继续生效、原因与问题页内明示。
 * 换主题就是直接改文件里的 6 个基础色，不做主题选择器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinEditorScreen(
    initialFilePath: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val saver = remember { SkinEditorSaver(context) }
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf<ActiveSkinFiles?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<EditorFile?>(null) }
    var text by remember { mutableStateOf("") }
    var originalText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SkinEditorSaveResult?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<EditorFile?>(null) }
    val dirty = text != originalText
    val editorScroll = rememberScrollState()
    val resultScroll = rememberScrollState()

    fun loadFile(file: EditorFile) {
        busy = true
        result = null
        scope.launch {
            val loaded = saver.loadText(file.file)
            busy = false
            if (loaded == null) {
                loadError = "读取失败：${file.displayName}"
                return@launch
            }
            loadError = null
            selected = file
            text = loaded
            originalText = loaded
        }
    }

    LaunchedEffect(Unit) {
        val resolved = saver.resolveActiveFiles()
        loading = false
        if (resolved == null) {
            loadError = "无生效皮肤"
            return@LaunchedEffect
        }
        active = resolved
        val initial = resolved.files.firstOrNull { it.file.absolutePath == initialFilePath }
            ?: resolved.files.firstOrNull { it.kind == EditorFile.Kind.SKIN_JSON }
            ?: resolved.files.firstOrNull()
        if (initial != null) loadFile(initial)
    }

    fun requestBack() {
        if (dirty) confirmLeave = true else onBack()
    }

    fun requestSwitch(file: EditorFile) {
        if (file == selected) return
        if (dirty) {
            pendingFile = file
            confirmLeave = true
        } else {
            loadFile(file)
        }
    }

    fun save() {
        val file = selected ?: return
        val current = active ?: return
        busy = true
        result = null
        scope.launch {
            val saved = when (file.kind) {
                EditorFile.Kind.SKIN_JSON -> saver.saveSkinJson(current, text)
                EditorFile.Kind.LAYOUT -> saver.saveLayout(current, file, text)
            }
            busy = false
            result = saved
            if (saved is SkinEditorSaveResult.Saved) {
                originalText = text
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false; pendingFile = null },
            title = { Text("未保存的修改") },
            text = { Text("离开将丢失未保存的修改，确定离开？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    val next = pendingFile
                    pendingFile = null
                    if (next != null) loadFile(next) else onBack()
                }) { Text("离开") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false; pendingFile = null }) {
                    Text("继续编辑")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.displayName ?: "皮肤文件编辑器") },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = ::save, enabled = !busy && selected != null) {
                        Text("保存")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (loading) {
                CircularProgressIndicator()
                return@Column
            }
            if (active == null) {
                Text(loadError ?: "无生效皮肤")
                return@Column
            }
            // 文件切换：skin.json＋该皮肤的布局文件。
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(active!!.files) { file ->
                    if (file == selected) {
                        Button(onClick = { requestSwitch(file) }, enabled = !busy) {
                            Text(file.displayName)
                        }
                    } else {
                        OutlinedButton(onClick = { requestSwitch(file) }, enabled = !busy) {
                            Text(file.displayName)
                        }
                    }
                }
            }
            if (loadError != null) {
                Text(loadError!!, color = MaterialTheme.colorScheme.error)
            }
            // 保存结果页内明示（问题／原因逐条列出，不用 Toast）。
            val currentResult = result
            if (currentResult != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when (currentResult) {
                            is SkinEditorSaveResult.Saved ->
                                MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.errorContainer
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(resultScroll)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when (currentResult) {
                            is SkinEditorSaveResult.Saved -> {
                                Text("已保存并生效")
                                currentResult.issues.forEach { Text(it, fontSize = 13.sp) }
                            }
                            is SkinEditorSaveResult.Blocked -> {
                                Text("校验未通过，未保存：${currentResult.reason}")
                                currentResult.issues.forEach { Text(it, fontSize = 13.sp) }
                            }
                            is SkinEditorSaveResult.Failed -> {
                                Text("保存失败：${currentResult.reason}")
                            }
                        }
                    }
                }
            }
            // 文本编辑区（等宽字体，整页滚动）。
            BasicTextField(
                value = text,
                onValueChange = { text = it; result = null },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(editorScroll),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                enabled = !busy && selected != null,
            )
        }
    }
}
