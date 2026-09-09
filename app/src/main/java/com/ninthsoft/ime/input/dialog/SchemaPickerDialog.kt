package com.ninthsoft.ime.input.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.schemaLayoutTag
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.ui.theme.OnPrimaryContainerDark
import com.ninthsoft.ime.ui.theme.OnPrimaryContainerLight
import com.ninthsoft.ime.ui.theme.OnSecondaryContainerDark
import com.ninthsoft.ime.ui.theme.OnSecondaryContainerLight
import com.ninthsoft.ime.ui.theme.OnTertiaryContainerDark
import com.ninthsoft.ime.ui.theme.OnTertiaryContainerLight
import com.ninthsoft.ime.ui.theme.PrimaryContainerDark
import com.ninthsoft.ime.ui.theme.PrimaryContainerLight
import com.ninthsoft.ime.ui.theme.SecondaryContainerDark
import com.ninthsoft.ime.ui.theme.SecondaryContainerLight
import com.ninthsoft.ime.ui.theme.TertiaryContainerDark
import com.ninthsoft.ime.ui.theme.TertiaryContainerLight
import androidx.compose.ui.graphics.toArgb
import splitties.dimensions.dp
import androidx.core.graphics.drawable.toDrawable
import timber.log.Timber

object SchemaPickerDialog {

    private var currentDialog: Dialog? = null

    /**
     * 将可能带有透明度的颜色，与基准底色（默认黑色/深色输入法背景）进行混合，
     * 计算出视觉效果完全一致但 Alpha 为 255（完全不透明）的新颜色。
     */
    private fun getOpaqueColor(color: Int, fallbackBgColor: Int = Color.BLACK): Int {
        val alpha = Color.alpha(color)
        if (alpha == 255) return color
        if (alpha == 0) return fallbackBgColor

        val srcR = Color.red(color)
        val srcG = Color.green(color)
        val srcB = Color.blue(color)

        val bgR = Color.red(fallbackBgColor)
        val bgG = Color.green(fallbackBgColor)
        val bgB = Color.blue(fallbackBgColor)

        val a = alpha / 255.0f
        val r = (srcR * a + bgR * (1 - a)).toInt().coerceIn(0, 255)
        val g = (srcG * a + bgG * (1 - a)).toInt().coerceIn(0, 255)
        val b = (srcB * a + bgB * (1 - a)).toInt().coerceIn(0, 255)

        return Color.rgb(r, g, b)
    }

    private fun isDarkMode(context: Context): Boolean {
        val nightModeFlags =
            context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun createTagBadge(
        context: Context,
        text: String,
        bgColor: Int,
        fgColor: Int,
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 11.7f
        setTextColor(fgColor)
        setPadding(context.dp(3), context.dp(1), context.dp(3), context.dp(1))
        includeFontPadding = false
        background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = context.dp(2f)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    fun build(
        context: Context,
        schemas: List<EngineMessage.Schema>,
        currentSchemaId: String?,
        colors: KeyboardColors.ColorScheme,
        onSchemaSelected: (String) -> Unit,
        onDismiss: () -> Unit = {},
    ): Dialog {
        val selectedIndex = schemas.indexOfFirst { it.id == currentSchemaId }
        val opaqueBackgroundColor = getOpaqueColor(colors.specialKeyBackground, colors.background)
        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL

            addView(TextView(context).apply {
                text = context.getString(R.string.choose_schema)
                textSize = 17f
                setTextColor(colors.keyText)
                setPadding(context.dp(28), context.dp(22), context.dp(28), context.dp(22))
            })

            addView(android.view.View(context).apply {
                setBackgroundColor(colors.specialKeyText)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1
                )
            })

            val optionsContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, context.dp(4), 0, context.dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            schemas.forEachIndexed { index, schema ->
                val isChecked = (index == selectedIndex)

                val itemLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(context.dp(18), context.dp(8), context.dp(18), context.dp(8))
                    isClickable = true
                    isFocusable = true

                    val radioSize = context.dp(18)
                    val slotWidth = context.dp(36)
                    val radioSlot = FrameLayout(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            slotWidth, ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    val radioView = android.view.View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.TRANSPARENT)
                            setStroke(context.dp(1.5f).toInt(), colors.accentKeyBackground)
                        }
                        layoutParams = FrameLayout.LayoutParams(radioSize, radioSize).apply {
                            gravity = Gravity.CENTER
                        }
                    }
                    radioSlot.addView(radioView)
                    if (isChecked) {
                        val innerView = android.view.View(context).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(colors.accentKeyBackground)
                            }
                            layoutParams =
                                FrameLayout.LayoutParams(context.dp(10), context.dp(10)).apply {
                                    gravity = Gravity.CENTER
                                }
                        }
                        radioSlot.addView(innerView)
                    }
                    addView(radioSlot)

                    val textColumn = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                        ).apply {
                            marginStart = context.dp(12)
                        }
                    }

                    textColumn.addView(TextView(context).apply {
                        text = schema.name.ifBlank { schema.id }
                        textSize = 16f
                        setTextColor(colors.keyText)
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    })

                    if (schema.name.isNotBlank()) {
                        val dark = isDarkMode(context)
                        val tagsRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                topMargin = context.dp(4)
                            }
                        }
                        val layoutText = schemaLayoutTag(context, schema.layout)
                        val layoutBg = if (dark) PrimaryContainerDark.toArgb()
                        else PrimaryContainerLight.toArgb()
                        val layoutFg = if (dark) OnPrimaryContainerDark.toArgb()
                        else OnPrimaryContainerLight.toArgb()
                        tagsRow.addView(createTagBadge(context, layoutText, layoutBg, layoutFg))
                        tagsRow.addView(android.view.View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(context.dp(4), 1)
                        })
                        val punctText =
                            if (schema.punctuation == "full-width") context.getString(R.string.tag_punctuation_full)
                            else context.getString(R.string.tag_punctuation_half)
                        val punctBg = if (dark) TertiaryContainerDark.toArgb()
                        else TertiaryContainerLight.toArgb()
                        val punctFg = if (dark) OnTertiaryContainerDark.toArgb()
                        else OnTertiaryContainerLight.toArgb()
                        tagsRow.addView(createTagBadge(context, punctText, punctBg, punctFg))

                        textColumn.addView(tagsRow)
                    }

                    addView(textColumn)

                    setOnClickListener {
                        onSchemaSelected(schema.id)
                        dismiss()
                    }
                }

                optionsContainer.addView(itemLayout)
            }

            addView(optionsContainer)

            addView(android.view.View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, context.dp(22)
                )
            })
        }

        val cornerRadius = context.dp(16f)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val dialogWidth = (screenWidth * 0.84f).toInt()

        val contentView = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(opaqueBackgroundColor)
                setCornerRadius(cornerRadius)
            }
            clipToOutline = true
            addView(innerLayout)
        }

        return Dialog(context).apply {
            setContentView(contentView)
            setOnDismissListener {
                currentDialog = null
                onDismiss()
            }
            currentDialog = this
        }.also { dialog ->
            val w = dialog.window ?: return@also
            w.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())

            // 去掉非弹层区域变灰
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0f)

            val attrs = w.attributes
            attrs.width = dialogWidth
            attrs.height = ViewGroup.LayoutParams.WRAP_CONTENT
            w.attributes = attrs
        }
    }

    fun dismiss() {
        currentDialog?.dismiss()
        currentDialog = null
    }
}