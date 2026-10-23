package com.jobeen.ime.skin.model

/**
 * 低内存档位与降级口径（定稿二.4；详设六章，数值全部暂定/待真机校准）。
 *
 * 判定信号源为系统口径：isLowRamDevice / getMemoryClass（MB）。
 * 本文件只含纯判定与策略映射；Android 侧取值在 runtime 层适配。
 */
enum class MemoryTier {
    NORMAL,
    LOW,
    EXTREME,
    ;

    companion object {
        /** 详设 §6.1 暂定阈值：memoryClass ≤128MB 为极低档；≤192MB 或低内存设备为低内存档。 */
        const val EXTREME_MAX_MEMORY_CLASS_MB = 128
        const val LOW_MAX_MEMORY_CLASS_MB = 192

        fun of(isLowRamDevice: Boolean, memoryClassMb: Int): MemoryTier = when {
            memoryClassMb in 1..EXTREME_MAX_MEMORY_CLASS_MB -> EXTREME
            isLowRamDevice || memoryClassMb in 1..LOW_MAX_MEMORY_CLASS_MB -> LOW
            else -> NORMAL
        }
    }
}

/** 降级标记（快照携带，设置页明示；详设 §6.2）。 */
enum class Degradation {
    /** 第 1 步：关模糊（低内存档起）。 */
    BLUR_DISABLED,

    /** 第 2 步：背景解码目标尺寸 ×0.5（极低档，或运行期 trim 升档）。 */
    BACKGROUND_DOWNSAMPLED,
}

/** 某档位下的降级策略（详设 §6.2 阶梯的静态部分；第 3 步静音为 B2 挂接点）。 */
data class DegradationPolicy(
    val blurDisabled: Boolean,
    val backgroundScale: Float,
    /** 音效解码峰值预算（定稿十二.2；B2 接纳判定用，B1 仅定值）。 */
    val audioBudgetBytes: Long,
) {
    fun degradations(): Set<Degradation> = buildSet {
        if (blurDisabled) add(Degradation.BLUR_DISABLED)
        if (backgroundScale < 1f) add(Degradation.BACKGROUND_DOWNSAMPLED)
    }

    companion object {
        private const val MIB = 1024L * 1024L

        fun forTier(tier: MemoryTier): DegradationPolicy = when (tier) {
            MemoryTier.NORMAL -> DegradationPolicy(
                blurDisabled = false, backgroundScale = 1f, audioBudgetBytes = 32 * MIB,
            )
            MemoryTier.LOW -> DegradationPolicy(
                blurDisabled = true, backgroundScale = 1f, audioBudgetBytes = 16 * MIB,
            )
            MemoryTier.EXTREME -> DegradationPolicy(
                blurDisabled = true, backgroundScale = 0.5f, audioBudgetBytes = 16 * MIB,
            )
        }
    }
}
