package com.jobeen.ime.ui.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.jobeen.ime.R
import com.jobeen.ime.input.stats.InputStats
import java.io.File
import java.io.FileOutputStream

/**
 * 输入统计分享图（2026-10-29 item-1 C）：Canvas 手绘摘要位图，
 * 经 FileProvider 分享；图上只有聚合数字（段位、提交、字数、
 * 速度、准确率、类别热力、键盘热力），绝无输入内容。
 */
object InputStatsImage {

    private const val WIDTH = 1080
    private const val HEIGHT = 2100

    fun renderToFile(
        context: Context,
        period: InputStats.Period,
        stats: InputStats.PeriodStats,
        rank: InputStats.Rank,
    ): File? = runCatching {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 64f
            isFakeBoldText = true
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 44f
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 34f
        }
        val barBgPaint = Paint().apply { color = Color.LTGRAY }
        val barPaint = Paint().apply { color = Color.rgb(46, 125, 50) }
        var y = 120f
        canvas.drawText(context.getString(R.string.input_stats_title), 80f, y, titlePaint)
        y += 80f
        val periodLabel = when (period) {
            InputStats.Period.DAY -> context.getString(R.string.stats_img_period_day)
            InputStats.Period.WEEK -> context.getString(R.string.stats_img_period_week)
            InputStats.Period.MONTH -> context.getString(R.string.stats_img_period_month)
            InputStats.Period.YEAR -> context.getString(R.string.stats_img_period_year)
        }
        canvas.drawText(periodLabel, 80f, y, textPaint)
        y += 110f
        canvas.drawText(context.getString(R.string.stats_img_rank, rank.name, rank.xp), 80f, y, textPaint)
        y += 70f
        canvas.drawRect(RectF(80f, y, WIDTH - 80f, y + 24f), barBgPaint)
        canvas.drawRect(
            RectF(80f, y, 80f + (WIDTH - 160f) * rank.progress.toFloat(), y + 24f),
            barPaint,
        )
        y += 100f
        val t = stats.total
        canvas.drawText(context.getString(R.string.stats_img_totals, t.commits, t.chars, t.keypresses), 80f, y, textPaint)
        y += 70f
        canvas.drawText(
            context.getString(R.string.stats_img_avg_speed, stats.avgSpeed?.let { context.getString(R.string.stats_speed_format, it) } ?: "—"),
            80f, y, textPaint,
        )
        y += 70f
        canvas.drawText(
            context.getString(R.string.stats_img_accuracy, stats.accuracy?.let { "%.1f%%".format(it * 100) } ?: "—"),
            80f, y, textPaint,
        )
        y += 110f
        canvas.drawText(context.getString(R.string.stats_img_category_heat), 80f, y, titlePaint)
        y += 80f
        val max = stats.categories.values.maxOrNull() ?: 0L
        if (max > 0L) {
            for (cat in InputStats.Category.entries) {
                val n = stats.categories[cat.name] ?: 0L
                if (n <= 0L) continue
                canvas.drawText("${cat.label} $n", 80f, y, textPaint)
                y += 20f
                canvas.drawRect(RectF(80f, y, WIDTH - 80f, y + 28f), barBgPaint)
                canvas.drawRect(
                    RectF(80f, y, 80f + (WIDTH - 160f) * n.toFloat() / max.toFloat(), y + 28f),
                    barPaint,
                )
                y += 80f
            }
        }
        y += 30f
        canvas.drawText(context.getString(R.string.stats_keyboard_heat), 80f, y, titlePaint)
        y += 80f
        val heatTotal = stats.heat.values.sum()
        for (rowKeys in InputStats.HEAT_GRID) {
            val rowText = rowKeys.joinToString(" · ") { key ->
                val n = stats.heat[key] ?: 0L
                val pct = if (heatTotal > 0L) "%.0f%%".format(n * 100.0 / heatTotal) else "0%"
                "$key $pct"
            }
            canvas.drawText(rowText, 80f, y, textPaint)
            y += 70f
        }
        y += 30f
        canvas.drawText(context.getString(R.string.stats_img_by_scheme), 80f, y, titlePaint)
        y += 80f
        for ((id, c) in stats.schemes) {
            canvas.drawText(
                context.getString(R.string.stats_img_scheme_line, id.ifBlank { context.getString(R.string.stats_unknown_scheme) }, c.commits, c.chars),
                80f, y, textPaint,
            )
            y += 70f
        }
        canvas.drawText(
            context.getString(R.string.stats_img_privacy),
            80f, HEIGHT - 80f, smallPaint,
        )
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "input-stats.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        file
    }.getOrNull()
}
