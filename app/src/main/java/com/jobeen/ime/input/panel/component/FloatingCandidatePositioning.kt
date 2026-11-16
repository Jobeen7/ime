package com.jobeen.ime.input.panel.component

/**
 * 浮动候选窗位置（WO52 1.4）：纯函数，只用 Int 坐标，不碰 Android
 * 类（PopupOverflow 同款写法，普通 JUnit 可测）。
 *
 * 三档（皮肤键 floatingCandidatePosition，缺省跟随光标）：
 * - CURSOR：跟随光标——光标上方够放放上方（光标顶 − gap − 窗高），
 *   否则放下方（光标底 ＋ gap）；上下都放不下回退。
 * - BOTTOM_LEFT / BOTTOM_RIGHT：左下／右下——键盘上方、贴键盘
 *   左右边（y = 键盘顶 − gap − 窗高），放不下回退。
 * 回退位置（皮肤键 floating_fallback_position，缺省左下）：
 * 光标取不到／被键盘盖住／窗口放不下时用；回退也放不下时贴屏边夹取。
 *
 * 全部坐标均为相对键盘窗口的窗口坐标（调用方把 CursorAnchorInfo
 * 经 getMatrix() 换算、再减键盘屏上位置后传入）；键盘占窗口底部，
 * 故“不与键盘相交”等价于窗底边 ≤ 键盘顶。
 */
enum class FloatingCandidatePosition {
    CURSOR,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    ;

    companion object {
        /** 皮肤值解析：未知／空／缺省一律跟随光标（单子定）。 */
        fun parse(raw: String?): FloatingCandidatePosition = when (raw?.trim()) {
            "bottomLeft", "bottom_left", "leftBottom", "bottom-left" -> BOTTOM_LEFT
            "bottomRight", "bottom_right", "rightBottom", "bottom-right" -> BOTTOM_RIGHT
            else -> CURSOR
        }
    }
}

object FloatingCandidatePositioning {

    data class Placement(
        val x: Int,
        val y: Int,
        /** true = 在光标上方，false = 在光标下方／键盘上方贴边 */
        val aboveCursor: Boolean,
        /** true = 走了回退位置（首选放不下或光标不可用） */
        val usedFallback: Boolean,
    )

    /**
     * @param cursorLeft/cursorTop/cursorRight/cursorBottom 光标矩形
     *   （组字起始字符框或插入标记矩形，键盘窗口坐标）；全 null
     *   = 光标取不到，直接走回退。
     * @param keyboardLeft/keyboardTop/keyboardRight 键盘矩形（窗口坐标）。
     * @param windowWidth/windowHeight 浮动窗尺寸（px）。
     * @param screenWidth/screenHeight 屏内可用范围（窗口坐标）。
     * @param gapPx 窗与光标／键盘间的间隙（4dp）。
     */
    fun resolve(
        preference: FloatingCandidatePosition,
        fallback: FloatingCandidatePosition,
        cursorLeft: Int?,
        cursorTop: Int?,
        cursorRight: Int?,
        cursorBottom: Int?,
        keyboardLeft: Int,
        keyboardTop: Int,
        keyboardRight: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        gapPx: Int,
    ): Placement {
        val cursor = if (cursorLeft != null && cursorTop != null &&
            cursorRight != null && cursorBottom != null
        ) {
            CursorRect(cursorLeft, cursorTop, cursorRight, cursorBottom)
        } else {
            null
        }
        // 首选档依次试放。
        tryPlacements(
            preference, cursor, keyboardLeft, keyboardTop, keyboardRight,
            windowWidth, windowHeight, screenWidth, screenHeight, gapPx,
        )?.let { return it.copy(usedFallback = false) }
        // 首选全失败 → 回退位置再试（与首选同一档时只试一次）。
        if (fallback != preference) {
            tryPlacements(
                fallback, cursor, keyboardLeft, keyboardTop, keyboardRight,
                windowWidth, windowHeight, screenWidth, screenHeight, gapPx,
            )?.let { return it.copy(usedFallback = true) }
        }
        // 回退也放不下：贴回退档夹取（恒有解：x/y 夹进屏内）。
        return clampPlacement(
            fallback, cursor, keyboardLeft, keyboardTop, keyboardRight,
            windowWidth, windowHeight, screenWidth, screenHeight, gapPx,
        ).copy(usedFallback = true)
    }

    private data class CursorRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** 一档的候选摆放（按偏好顺序）；返回 null = 该档放不下。 */
    private fun tryPlacements(
        position: FloatingCandidatePosition,
        cursor: CursorRect?,
        keyboardLeft: Int,
        keyboardTop: Int,
        keyboardRight: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        gapPx: Int,
    ): Placement? {
        val xRange = 0..(screenWidth - windowWidth).coerceAtLeast(0)
        return when (position) {
            FloatingCandidatePosition.CURSOR -> {
                if (cursor == null) return null
                val x = cursor.left.coerceIn(xRange)
                // 上方：光标顶 − gap − 窗高；须整窗在屏内且不与键盘相交。
                val aboveY = cursor.top - gapPx - windowHeight
                if (aboveY >= 0 && aboveY + windowHeight <= keyboardTop) {
                    return Placement(x, aboveY, aboveCursor = true, usedFallback = false)
                }
                // 下方：光标底 ＋ gap；整窗须在屏内且完全位于键盘上方，
                // 否则会被键盘盖住 → 不试，回退。
                val belowY = cursor.bottom + gapPx
                if (belowY + windowHeight <= screenHeight &&
                    belowY + windowHeight <= keyboardTop
                ) {
                    return Placement(x, belowY, aboveCursor = false, usedFallback = false)
                }
                null
            }
            FloatingCandidatePosition.BOTTOM_LEFT -> {
                val y = keyboardTop - gapPx - windowHeight
                if (y >= 0) {
                    Placement(
                        keyboardLeft.coerceIn(xRange), y,
                        aboveCursor = false, usedFallback = false,
                    )
                } else {
                    null
                }
            }
            FloatingCandidatePosition.BOTTOM_RIGHT -> {
                val y = keyboardTop - gapPx - windowHeight
                if (y >= 0) {
                    Placement(
                        (keyboardRight - windowWidth).coerceIn(xRange), y,
                        aboveCursor = false, usedFallback = false,
                    )
                } else {
                    null
                }
            }
        }
    }

    /** 回退档也放不下时的夹取：按回退档算出位置再夹进屏内。 */
    private fun clampPlacement(
        fallback: FloatingCandidatePosition,
        cursor: CursorRect?,
        keyboardLeft: Int,
        keyboardTop: Int,
        keyboardRight: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        gapPx: Int,
    ): Placement {
        val xRange = 0..(screenWidth - windowWidth).coerceAtLeast(0)
        // .2：夹取亦不得让窗底越过键盘顶（光标 stale 时 CURSOR 档的 y
        // 会落到键盘下方）；窗高出键盘上方空间时贴屏顶（y=0）。
        val maxY = (keyboardTop - gapPx - windowHeight).coerceAtLeast(0)
        return when (fallback) {
            FloatingCandidatePosition.CURSOR -> {
                val y = if (cursor != null) {
                    cursor.top - gapPx - windowHeight
                } else {
                    keyboardTop - gapPx - windowHeight
                }
                val x = (cursor?.left ?: keyboardLeft).coerceIn(xRange)
                Placement(x, y.coerceIn(0, maxY), aboveCursor = cursor != null, usedFallback = true)
            }
            FloatingCandidatePosition.BOTTOM_LEFT -> Placement(
                keyboardLeft.coerceIn(xRange),
                (keyboardTop - gapPx - windowHeight).coerceIn(0, maxY),
                aboveCursor = false,
                usedFallback = true,
            )
            FloatingCandidatePosition.BOTTOM_RIGHT -> Placement(
                (keyboardRight - windowWidth).coerceIn(xRange),
                (keyboardTop - gapPx - windowHeight).coerceIn(0, maxY),
                aboveCursor = false,
                usedFallback = true,
            )
        }
    }
}
