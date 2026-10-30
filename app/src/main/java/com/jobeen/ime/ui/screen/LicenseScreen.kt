package com.jobeen.ime.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 开源许可查看页：读取构建时由 syncLicenses 从仓库根目录单源同步进
 * assets/licenses/ 的文件。首层是文件列表（索引 THIRD_PARTY_NOTICES.md
 * 在最前），点开看全文（纯文本原样展示，不做 Markdown 渲染）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var detailText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        files = withContext(Dispatchers.IO) {
            val names = context.assets.list("licenses")?.toList().orEmpty()
            // 索引排最前，其余按文件名排序
            names.sortedWith(compareBy({ it != INDEX_FILE }, { it }))
        }
    }
    LaunchedEffect(selected) {
        val name = selected ?: return@LaunchedEffect
        detailText = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
    }

    SettingsPageLazyScaffold(
        title = selected ?: stringResource(R.string.open_source_licenses),
        onBack = { if (selected != null) selected = null else onBack() },
    ) {
        if (selected == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {

                items(files) { name ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = name }
                            .padding(vertical = 14.dp),
                    ) {
                        Text(
                            text = if (name == INDEX_FILE) {
                                stringResource(R.string.licenses_index)
                            } else {
                                name.removeSuffix(".txt")
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
            ) {
                Text(
                    text = detailText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private const val INDEX_FILE = "THIRD_PARTY_NOTICES.md"
