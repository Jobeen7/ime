// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.data.manager.WordSegmenter
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * 分词页（WO52 1.9）：
 *
 * - 词格按 [WordSegmenter] 切分展示；点按选中单个词，长按或横向滑动
 *   进选择模式（锚点区间选择），距上下沿 16dp 内自动滚动（每帧至多
 *   12dp，见 [WordSegmenter.autoScrollDeltaDp]）。
 * - 底部显示选中文本（按原串取回，分隔符不丢），复制写系统剪贴板。
 *
 * 手势机（词区单指针三态）：落指后长按超时与首个移动／抬手竞速——
 * 抬手为点按，横滑超阈为选择拖动，竖滑超阈则放弃（不消费，留给滚动）。
 * 选择拖动中才消费后续移动；点按与竖滑一律不消费。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SplitWordsScreen(text: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val copiedText = stringResource(R.string.copied)
    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current
    val words = remember(text) { WordSegmenter.segment(text) }

    var anchor by remember { mutableStateOf<Int?>(null) }
    var current by remember { mutableStateOf<Int?>(null) }
    val selecting = anchor != null
    val a = anchor
    val c = current
    val range = if (a != null && c != null) {
        WordSegmenter.selectionRange(a, c, words.size)
    } else {
        IntRange.EMPTY
    }
    val selectedText = remember(text, words, range) {
        WordSegmenter.selectedText(text, words, range)
    }

    // 词格在窗口中的位置（滚动时随布局更新），供拖动手势命中换算。
    val chipBounds = remember { mutableStateMapOf<Int, Rect>() }
    var containerTopLeft by remember { mutableStateOf(Offset.Zero) }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }
    val scrollState = rememberScrollState()
    var dragPositionLocal by remember { mutableStateOf<Offset?>(null) }
    var dragActive by remember { mutableStateOf(false) }

    fun chipAt(positionInWindow: Offset): Int? {
        for ((index, rect) in chipBounds) {
            if (rect.contains(positionInWindow)) return index
        }
        // 落在格间空白时归最近格（拖动换端不断档）
        var best: Int? = null
        var bestDist = Float.MAX_VALUE
        for ((index, rect) in chipBounds) {
            val dx = positionInWindow.x - rect.center.x
            val dy = positionInWindow.y - rect.center.y
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                best = index
            }
        }
        return best
    }

    // 拖动中的边缘自动滚动：每帧按距沿距离算步长（至多 12dp），
    // 手指离开边缘区或抬手即停。
    LaunchedEffect(dragActive, dragPositionLocal) {
        if (!dragActive) return@LaunchedEffect
        while (dragActive) {
            val pos = dragPositionLocal
            if (pos != null && viewportHeightPx > 0f) {
                val yDp = with(density) { pos.y.toDp().value }
                val viewHDp = with(density) { viewportHeightPx.toDp().value }
                val deltaDp = WordSegmenter.autoScrollDeltaDp(yDp, viewHDp)
                if (deltaDp != 0f) {
                    scrollState.scrollBy(with(density) { deltaDp.dp.toPx() })
                    chipAt(containerTopLeft + pos)?.let { current = it }
                }
            }
            delay(16)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.split_words_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.split_words_done))
            }
        }
        if (words.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.split_words_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val touchSlop = viewConfig.touchSlop
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned {
                        viewportHeightPx = it.size.height.toFloat()
                        containerTopLeft = it.positionInWindow()
                    }
                    .verticalScroll(scrollState)
                    .pointerInput(words) {
                        awaitPointerEventScope {
                            while (true) {
                                val downEvent = awaitPointerEvent()
                                val down = downEvent.changes.firstOrNull() ?: continue
                                if (downEvent.type != PointerEventType.Press) continue
                                val downIndex = chipAt(
                                    containerTopLeft + down.position
                                ) ?: continue
                                val downPos = down.position
                                // 0 未定／1 点按／2 选择拖动／3 放弃（竖滑，留给滚动）
                                var outcome = 0
                                val first: PointerEvent? = withTimeoutOrNull(
                                    viewConfig.longPressTimeoutMillis.toLong()
                                ) {
                                    var ev: PointerEvent
                                    do {
                                        ev = awaitPointerEvent()
                                    } while (ev.type != PointerEventType.Move &&
                                        ev.type != PointerEventType.Release
                                    )
                                    ev
                                }
                                if (first == null) {
                                    outcome = 2 // 长按超时进选择模式
                                } else if (first.type == PointerEventType.Release) {
                                    outcome = 1
                                } else {
                                    var pos = first.changes.firstOrNull()?.position ?: downPos
                                    while (outcome == 0) {
                                        val dx = pos.x - downPos.x
                                        val dy = pos.y - downPos.y
                                        if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                                            outcome = 2
                                            break
                                        }
                                        if (abs(dy) > touchSlop && abs(dy) >= abs(dx)) {
                                            outcome = 3
                                            break
                                        }
                                        val ev = awaitPointerEvent()
                                        if (ev.type == PointerEventType.Release) {
                                            outcome = 1
                                            break
                                        }
                                        if (ev.type == PointerEventType.Move) {
                                            pos = ev.changes.firstOrNull()?.position ?: pos
                                        }
                                    }
                                }
                                when (outcome) {
                                    1 -> {
                                        // 点按：未选则单选该词；已在选择模式则改当前端
                                        if (selecting) {
                                            current = downIndex
                                        } else {
                                            anchor = downIndex
                                            current = downIndex
                                        }
                                    }
                                    2 -> {
                                        anchor = downIndex
                                        current = downIndex
                                        dragActive = true
                                        dragPositionLocal = downPos
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            for (change in ev.changes) {
                                                if (ev.type == PointerEventType.Move) {
                                                    dragPositionLocal = change.position
                                                    chipAt(containerTopLeft + change.position)
                                                        ?.let { current = it }
                                                    change.consume()
                                                }
                                            }
                                            if (ev.type == PointerEventType.Release) break
                                        }
                                        dragActive = false
                                        dragPositionLocal = null
                                    }
                                    else -> { /* 竖滑：不消费，交给滚动 */ }
                                }
                            }
                        }
                    }
                    .padding(12.dp),
            ) {
                FlowRow {
                    for ((index, word) in words.withIndex()) {
                        val selected = !range.isEmpty() && index in range
                        Surface(
                            modifier = Modifier
                                .padding(end = 6.dp, bottom = 8.dp)
                                .onGloballyPositioned { coords ->
                                    val pos = coords.positionInWindow()
                                    chipBounds[index] = Rect(
                                        pos.x, pos.y,
                                        pos.x + coords.size.width,
                                        pos.y + coords.size.height,
                                    )
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ) {
                            Text(
                                text = word.text,
                                modifier = Modifier.padding(
                                    horizontal = 10.dp, vertical = 8.dp
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedText,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    if (selectedText.isEmpty()) return@Button
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("split", selectedText))
                    Toast.makeText(
                        context,
                        copiedText,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                enabled = selectedText.isNotEmpty(),
            ) {
                Text(stringResource(R.string.split_words_copy))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
