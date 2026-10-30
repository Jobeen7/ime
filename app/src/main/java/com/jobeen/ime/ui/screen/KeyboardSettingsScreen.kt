package com.jobeen.ime.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import com.jobeen.ime.base.feedback.InputFeedbacks
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.ui.screen.ScreenComponent.ActionRow
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.SliderRow
import com.jobeen.ime.ui.screen.ScreenComponent.SwitchRow

/** 键盘设置内部分类（2026-10-29 批量第 3 项）：原“按键设置”大组按
 * 参考分类拆为 6 类，先列分类、点进分类页；偏好键与回调一律不动。 */
private enum class KeyboardCategory {
    SOUND, HAPTICS, GESTURE, DISPLAY, LAYOUT, BEHAVIOR,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var keySoundEnabled by remember {
        mutableStateOf(KeyboardManager.Keyboard.Feedback.getSoundEnabled(context))
    }
    var keyVibrationEnabled by remember {
        mutableStateOf(KeyboardManager.Keyboard.Feedback.getVibrationEnabled(context))
    }
    var vibrationFollowSystem by remember {
        mutableStateOf(KeyboardManager.Keyboard.Feedback.getVibrationFollowSystem(context))
    }
    var vibrationAmplitude by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Feedback.getVibrationAmplitude(context).toFloat())
    }
    var swipeThreshold by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.SwipeThreshold.getDp(context))
    }
    var symbolPairingEnabled by remember {
        mutableStateOf(KeyboardManager.Keyboard.SymbolPairing.isEnabled(context))
    }
    var mnemonicEnabled by remember {
        mutableStateOf(KeyboardManager.Keyboard.Mnemonic.isEnabled(context))
    }
    var decorationEnabled by remember {
        mutableStateOf(KeyboardManager.Keyboard.Decoration.isEnabled(context))
    }
    var keyBorderEnabled by remember {
        mutableStateOf(KeyboardManager.Keyboard.KeyBorderStroke.isEnabled(context))
    }
    var keyXGap by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Gap.getHorizontalDp(context).toFloat())
    }
    var keyYGap by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Gap.getVerticalDp(context).toFloat())
    }
    var keyboardHeight by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.getHeightPercent(context).toFloat())
    }
    var keyboardHeightLandscape by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.getHeightPercentLandscape(context).toFloat())
    }
    var horizontalPadding by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Padding.getHorizontalDp(context).toFloat())
    }
    var bottomPadding by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Padding.getBottomDp(context).toFloat())
    }
    var ignoreInsets by remember {
        mutableStateOf(KeyboardManager.Keyboard.getIgnoreInsets(context))
    }
    var keyRadius by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.KeyRadius.getDp(context).toFloat())
    }
    var rippleEnabled by remember {
        mutableStateOf(KeyboardManager.Keyboard.RippleEffect.isEnabled(context))
    }
    var expandBorder by remember {
        mutableStateOf(KeyboardManager.Keyboard.ExpandBorder.isEnabled(context))
    }
    var soundVolume by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Feedback.getSoundVolume(context).toFloat())
    }
    var customSoundEnabled by remember {
        mutableStateOf(KeyboardManager.Keyboard.Feedback.isCustomSoundEnabled(context))
    }
    var customSoundPath by remember {
        mutableStateOf(KeyboardManager.Keyboard.Feedback.getCustomSoundPath(context))
    }
    var customSoundError by remember { mutableStateOf(false) }
    var vibrationDurationMs by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Feedback.getVibrationDurationMs(context).toFloat())
    }
    var releaseVibrationEnabled by remember {
        mutableStateOf(KeyboardManager.Keyboard.Feedback.isReleaseVibrationEnabled(context))
    }
    var swipeVelocity by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Gesture.getMinSwipeVelocityPxPerSec(context).toFloat())
    }
    var longPressTimeoutMs by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Gesture.getLongPressTimeoutMs(context).toFloat())
    }
    var repeatIntervalMs by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Gesture.getRepeatIntervalMs(context).toFloat())
    }
    var doubleTapTimeoutMs by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Gesture.getDoubleTapTimeoutMs(context).toFloat())
    }
    var slideStepChars by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Gesture.getSlideStepChars(context).toFloat())
    }
    var hideSymbol by remember {
        mutableStateOf(KeyboardManager.Keyboard.Display.isHideSymbol(context))
    }
    var hideHint by remember {
        mutableStateOf(KeyboardManager.Keyboard.Display.isHideHint(context))
    }

    // 自定义音效文件选择：复制进应用私有目录（不持有外部 uri 权限），≤5MB
    val soundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        customSoundError = false
        val imported = runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
            input.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                var overflow = false
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 5L * 1024 * 1024) {
                        overflow = true
                        break
                    }
                    out.write(buf, 0, n)
                }
                if (overflow) return@runCatching null
                val mime = context.contentResolver.getType(uri)
                val ext = when (mime) {
                    "audio/mpeg" -> "mp3"
                    "audio/wav", "audio/x-wav" -> "wav"
                    "audio/ogg" -> "ogg"
                    else -> uri.lastPathSegment
                        ?.substringAfterLast('.', "")
                        ?.lowercase()
                        .orEmpty()
                        .ifEmpty { "bin" }
                }
                val file = java.io.File(context.filesDir, "custom_key_sound.$ext")
                file.writeBytes(out.toByteArray())
                file.absolutePath
            }
        }.getOrNull()
        if (imported != null) {
            val old = customSoundPath
            if (old.isNotEmpty() && old != imported) runCatching { java.io.File(old).delete() }
            customSoundPath = imported
            KeyboardManager.Keyboard.Feedback.setCustomSoundPath(context, imported)
        } else {
            customSoundError = true
        }
    }

    var category by remember { mutableStateOf<KeyboardCategory?>(null) }
    BackHandler(enabled = category != null) { category = null }

    SettingsPageScaffold(
        title = stringResource(R.string.keyboard_settings),
        onBack = { if (category != null) category = null else onBack() },
    ) {
        val open = category
        if (open == null) {
            GroupCard {
                KeyboardCategory.entries.forEach { cat ->
                    NavRow(
                        title = categoryTitle(cat),
                        subtitle = categorySubtitle(cat),
                        onClick = { category = cat },
                        icon = categoryIcon(cat),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        } else {
            SettingsGroup(title = categoryTitle(open)) {
                when (open) {
                        KeyboardCategory.SOUND -> {
                            SwitchRow(
                                title = stringResource(R.string.key_sound),
                                checked = keySoundEnabled,
                                onCheckedChange = {
                                    keySoundEnabled = it
                                    KeyboardManager.Keyboard.Feedback.setSoundEnabled(context, it)
                                },
                                showDivider = true,
                            )
                            SliderRow(
                                title = stringResource(R.string.key_sound_volume),
                                value = soundVolume,
                                valueLabel = "${soundVolume.toInt()}%",
                                range = 0f..100f,
                                enabled = keySoundEnabled,
                                onValueChange = {
                                    soundVolume = it
                                    KeyboardManager.Keyboard.Feedback.setSoundVolume(context, it.toInt())
                                },
                                showDivider = true,
                            )
                            SwitchRow(
                                title = stringResource(R.string.key_custom_sound),
                                checked = customSoundEnabled,
                                enabled = keySoundEnabled,
                                onCheckedChange = {
                                    customSoundEnabled = it
                                    KeyboardManager.Keyboard.Feedback.setCustomSoundEnabled(context, it)
                                },
                                showDivider = true,
                            )
                            ActionRow(
                                title = stringResource(R.string.key_custom_sound_pick),
                                subtitle = if (customSoundPath.isNotEmpty()) {
                                    java.io.File(customSoundPath).name
                                } else {
                                    stringResource(R.string.key_custom_sound_none)
                                },
                                trailing = {
                                    Button(
                                        onClick = { soundPicker.launch(arrayOf("audio/*")) },
                                        enabled = keySoundEnabled && customSoundEnabled,
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.key_custom_sound_pick),
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                        )
                                    }
                                },
                            )
                            if (customSoundPath.isNotEmpty()) {
                                ActionRow(
                                    title = stringResource(R.string.key_custom_sound_clear),
                                    trailing = {
                                        Button(
                                            onClick = {
                                                runCatching { java.io.File(customSoundPath).delete() }
                                                customSoundPath = ""
                                                KeyboardManager.Keyboard.Feedback.setCustomSoundPath(context, "")
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        ) {
                                            Text(
                                                stringResource(R.string.key_custom_sound_clear),
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                            )
                                        }
                                    },
                                )
                            }
                            ActionRow(
                                title = stringResource(R.string.key_sound_preview),
                                trailing = {
                                    Button(
                                        onClick = { InputFeedbacks.previewSoundEffect(context) },
                                        enabled = keySoundEnabled,
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.key_sound_preview),
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                        )
                                    }
                                },
                            )
                            if (customSoundError) {
                                Text(
                                    text = stringResource(R.string.key_custom_sound_error),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        KeyboardCategory.HAPTICS -> {
                            SwitchRow(
                                title = stringResource(R.string.key_vibration),
                                checked = keyVibrationEnabled,
                                onCheckedChange = {
                                    keyVibrationEnabled = it
                                    KeyboardManager.Keyboard.Feedback.setVibrationEnabled(context, it)
                                },
                                showDivider = true,
                            )
                            SwitchRow(
                                title = stringResource(R.string.key_vibration_follow_system),
                                checked = vibrationFollowSystem,
                                enabled = keyVibrationEnabled,
                                onCheckedChange = {
                                    vibrationFollowSystem = it
                                    KeyboardManager.Keyboard.Feedback.setVibrationFollowSystem(context, it)
                                },
                                showDivider = true,
                            )
                            SliderRow(
                                title = stringResource(R.string.key_vibration_amplitude),
                                value = vibrationAmplitude,
                                valueLabel = "${vibrationAmplitude.toInt()}%",
                                range = 1f..100f,
                                enabled = keyVibrationEnabled && !vibrationFollowSystem,
                                onValueChange = {
                                    vibrationAmplitude = it
                                    KeyboardManager.Keyboard.Feedback.setVibrationAmplitude(context, it.toInt())
                                },
                                showDivider = true,
                            )
                            SliderRow(
                                title = stringResource(R.string.key_vibration_duration),
                                value = vibrationDurationMs,
                                valueLabel = "${vibrationDurationMs.toInt()} ms",
                                range = 5f..100f,
                                enabled = keyVibrationEnabled,
                                onValueChange = {
                                    vibrationDurationMs = it
                                    KeyboardManager.Keyboard.Feedback.setVibrationDurationMs(context, it.toInt())
                                },
                                showDivider = true,
                            )
                            SwitchRow(
                                title = stringResource(R.string.key_release_vibration),
                                checked = releaseVibrationEnabled,
                                enabled = keyVibrationEnabled,
                                onCheckedChange = {
                                    releaseVibrationEnabled = it
                                    KeyboardManager.Keyboard.Feedback.setReleaseVibrationEnabled(context, it)
                                },
                            )
                        }
                        KeyboardCategory.GESTURE -> {
                            SliderRow(
                                title = stringResource(R.string.swipe_trigger_distance),
                                value = swipeThreshold,
                                valueLabel = "${swipeThreshold.toInt()}dp",
                                range = 16f..80f,
                                onValueChange = {
                                    swipeThreshold = it
                                    KeyboardManager.Keyboard.SwipeThreshold.setDp(context, it)
                                },
                                showDivider = true,
                            )
                            SliderRow(
                                title = stringResource(R.string.gesture_swipe_velocity),
                                value = swipeVelocity,
                                valueLabel = if (swipeVelocity.toInt() == 0) {
                                    stringResource(R.string.gesture_swipe_velocity_unlimited)
                                } else {
                                    "${swipeVelocity.toInt()} px/s"
                                },
                                range = 0f..5000f,
                                onValueChange = {
                                    swipeVelocity = it
                                    KeyboardManager.Keyboard.Gesture.setMinSwipeVelocityPxPerSec(context, it.toInt())
                                },
                                showDivider = true,
                            )
                            SliderRow(
                                title = stringResource(R.string.gesture_long_press_timeout),
                                value = longPressTimeoutMs,
                                valueLabel = "${longPressTimeoutMs.toInt()} ms",
                                range = 100f..1000f,
                                onValueChange = {
                                    longPressTimeoutMs = it
                                    KeyboardManager.Keyboard.Gesture.setLongPressTimeoutMs(context, it.toInt())
                                },
                                showDivider = true,
                            )
                            SliderRow(
                                title = stringResource(R.string.gesture_repeat_interval),
                                value = repeatIntervalMs,
                                valueLabel = "${repeatIntervalMs.toInt()} ms",
                                range = 50f..500f,
                                onValueChange = {
                                    repeatIntervalMs = it
                                    KeyboardManager.Keyboard.Gesture.setRepeatIntervalMs(context, it.toInt())
                                },
                                showDivider = true,
                            )
                            SliderRow(
                                title = stringResource(R.string.gesture_double_tap_timeout),
                                value = doubleTapTimeoutMs,
                                valueLabel = "${doubleTapTimeoutMs.toInt()} ms",
                                range = 100f..1000f,
                                onValueChange = {
                                    doubleTapTimeoutMs = it
                                    KeyboardManager.Keyboard.Gesture.setDoubleTapTimeoutMs(context, it.toInt())
                                },
                                showDivider = true,
                            )
                            SliderRow(
                                title = stringResource(R.string.gesture_slide_step),
                                value = slideStepChars,
                                valueLabel = "${slideStepChars.toInt()} 字/步",
                                range = 1f..5f,
                                onValueChange = {
                                    slideStepChars = it
                                    KeyboardManager.Keyboard.Gesture.setSlideStepChars(context, it.toInt())
                                },
                            )
                        }
                        KeyboardCategory.DISPLAY -> {
                            SwitchRow(
                                title = stringResource(R.string.mnemonic_hints),
                                checked = mnemonicEnabled,
                                onCheckedChange = {
                                    mnemonicEnabled = it
                                    KeyboardManager.Keyboard.Mnemonic.setEnabled(context, it)
                                },
                                showDivider = true,
                            )
                            SwitchRow(
                                title = stringResource(R.string.decoration_effects),
                                checked = decorationEnabled,
                                onCheckedChange = {
                                    decorationEnabled = it
                                    KeyboardManager.Keyboard.Decoration.setEnabled(context, it)
                                },
                                showDivider = true,
                            )
                            SwitchRow(
                                title = stringResource(R.string.key_ripple_effect),
                                checked = rippleEnabled,
                                onCheckedChange = {
                                    rippleEnabled = it
                                    KeyboardManager.Keyboard.RippleEffect.setEnabled(context, it)
                                },
                                showDivider = true,
                            )
                            SwitchRow(
                                title = stringResource(R.string.key_border_show),
                                checked = keyBorderEnabled,
                                onCheckedChange = {
                                    keyBorderEnabled = it
                                    KeyboardManager.Keyboard.KeyBorderStroke.setEnabled(context, it)
                                },
                                showDivider = true,
                            )
                            SwitchRow(
                                title = stringResource(R.string.expand_border),
                                checked = expandBorder,
                                onCheckedChange = {
                                    expandBorder = it
                                    KeyboardManager.Keyboard.ExpandBorder.setEnabled(context, it)
                                },
                                showDivider = true,
                            )
                            SwitchRow(
                                title = stringResource(R.string.display_hide_symbol),
                                checked = hideSymbol,
                                onCheckedChange = {
                                    hideSymbol = it
                                    KeyboardManager.Keyboard.Display.setHideSymbol(context, it)
                                },
                                showDivider = true,
                            )
                            SwitchRow(
                                title = stringResource(R.string.display_hide_hint),
                                checked = hideHint,
                                onCheckedChange = {
                                    hideHint = it
                                    KeyboardManager.Keyboard.Display.setHideHint(context, it)
                                },
                                showDivider = true,
                            )
                            Text(
                                text = stringResource(R.string.display_hide_symbol_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.display_hide_hint_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SliderRow(
                                title = stringResource(R.string.key_corner_radius),
                                value = keyRadius,
                                valueLabel = "${keyRadius.toInt()} dp",
                                range = 0f..32f,
                                onValueChange = {
                                    keyRadius = it
                                    KeyboardManager.Keyboard.KeyRadius.setDp(context, it.toInt())
                                },
                                showDivider = true,
                            )
                            SliderRow(
                                title = stringResource(R.string.key_x_gap),
                                value = keyXGap,
                                valueLabel = "${keyXGap.toInt()} dp",
                                range = 0f..16f,
                                onValueChange = {
                                    keyXGap = it
                                    KeyboardManager.Keyboard.Gap.setHorizontalDp(context, it.toInt())
                                },
                                showDivider = true,
                            )
                            SliderRow(
                                title = stringResource(R.string.key_y_gap),
                                value = keyYGap,
                                valueLabel = "${keyYGap.toInt()} dp",
                                range = 0f..16f,
                                onValueChange = {
                                    keyYGap = it
                                    KeyboardManager.Keyboard.Gap.setVerticalDp(context, it.toInt())
                                },
                            )
                        }
                        KeyboardCategory.LAYOUT -> {
                            SliderRow(
                                title = stringResource(R.string.keyboard_height),
                                value = keyboardHeight,
                                valueLabel = "${keyboardHeight.toInt()}%",
                                range = 20f..50f,
                                onValueChange = {
                                    keyboardHeight = it
                                    KeyboardManager.Keyboard.setHeightPercent(context, it.toInt())
                                },
                                showDivider = true,
                            )
                            SliderRow(
                                title = stringResource(R.string.keyboard_height_landscape),
                                value = keyboardHeightLandscape,
                                valueLabel = "${keyboardHeightLandscape.toInt()}%",
                                range = 30f..70f,
                                onValueChange = {
                                    keyboardHeightLandscape = it
                                    KeyboardManager.Keyboard.setHeightPercentLandscape(context, it.toInt())
                                },
                                showDivider = true,
                            )
                            SliderRow(
                                title = stringResource(R.string.horizontal_padding),
                                value = horizontalPadding,
                                valueLabel = "${horizontalPadding.toInt()} dp",
                                range = 0f..20f,
                                onValueChange = {
                                    horizontalPadding = it
                                    KeyboardManager.Keyboard.Padding.setHorizontalDp(context, it.toInt())
                                },
                                showDivider = true,
                            )
                            SliderRow(
                                title = stringResource(R.string.bottom_padding),
                                value = bottomPadding,
                                valueLabel = "${bottomPadding.toInt()} dp",
                                range = 0f..40f,
                                onValueChange = {
                                    bottomPadding = it
                                    KeyboardManager.Keyboard.Padding.setBottomDp(context, it.toInt())
                                },
                                showDivider = true,
                            )
                            SwitchRow(
                                title = stringResource(R.string.ignore_system_insets),
                                checked = ignoreInsets,
                                onCheckedChange = {
                                    ignoreInsets = it
                                    KeyboardManager.Keyboard.setIgnoreInsets(context, it)
                                },
                            )
                        }
                        KeyboardCategory.BEHAVIOR -> {
                            SwitchRow(
                                title = stringResource(R.string.symbol_pairing),
                                checked = symbolPairingEnabled,
                                onCheckedChange = {
                                    symbolPairingEnabled = it
                                    KeyboardManager.Keyboard.SymbolPairing.setEnabled(context, it)
                                },
                            )
                        }
                    }
                }
            }
    }
}

@Composable
private fun categoryTitle(category: KeyboardCategory): String = when (category) {
    KeyboardCategory.SOUND -> stringResource(R.string.kb_cat_sound)
    KeyboardCategory.HAPTICS -> stringResource(R.string.kb_cat_haptics)
    KeyboardCategory.GESTURE -> stringResource(R.string.kb_cat_gesture)
    KeyboardCategory.DISPLAY -> stringResource(R.string.kb_cat_display)
    KeyboardCategory.LAYOUT -> stringResource(R.string.keyboard_layout)
    KeyboardCategory.BEHAVIOR -> stringResource(R.string.kb_cat_behavior)
}

@Composable
private fun categorySubtitle(category: KeyboardCategory): String = when (category) {
    KeyboardCategory.SOUND -> stringResource(R.string.kb_cat_sound_desc)
    KeyboardCategory.HAPTICS -> stringResource(R.string.kb_cat_haptics_desc)
    KeyboardCategory.GESTURE -> stringResource(R.string.kb_cat_gesture_desc)
    KeyboardCategory.DISPLAY -> stringResource(R.string.kb_cat_display_desc)
    KeyboardCategory.LAYOUT -> stringResource(R.string.kb_cat_layout_desc)
    KeyboardCategory.BEHAVIOR -> stringResource(R.string.kb_cat_behavior_desc)
}

private fun categoryIcon(category: KeyboardCategory): ImageVector = when (category) {
    KeyboardCategory.SOUND -> Icons.Filled.VolumeUp
    KeyboardCategory.HAPTICS -> Icons.Filled.Vibration
    KeyboardCategory.GESTURE -> Icons.Filled.Gesture
    KeyboardCategory.DISPLAY -> Icons.Filled.Style
    KeyboardCategory.LAYOUT -> Icons.Filled.AspectRatio
    KeyboardCategory.BEHAVIOR -> Icons.Filled.Tune
}
