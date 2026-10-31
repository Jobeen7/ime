package com.kyant.backdrop.internal

// 移植说明：Android 单模块无 expect/actual 机制，actual 关键字已去掉；实现即 Android 版原文。
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asAndroidRuntimeShader

@RequiresApi(Build.VERSION_CODES.S)
internal fun RenderEffect?.chain(other: RenderEffect): RenderEffect {
    return if (this != null) {
        android.graphics.RenderEffect.createChainEffect(
            other.asAndroidRenderEffect(),
            this.asAndroidRenderEffect()
        ).asComposeRenderEffect()
    } else {
        other
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun RuntimeShaderEffect(
    runtimeShader: RuntimeShader,
    uniformShaderName: String
): RenderEffect {
    return android.graphics.RenderEffect.createRuntimeShaderEffect(
        runtimeShader.asAndroidRuntimeShader(),
        uniformShaderName
    ).asComposeRenderEffect()
}

@RequiresApi(Build.VERSION_CODES.S)
internal fun ColorFilterEffect(
    renderEffect: RenderEffect?,
    colorFilter: ColorFilter
): RenderEffect {
    return if (renderEffect != null) {
        android.graphics.RenderEffect.createColorFilterEffect(
            colorFilter.asAndroidColorFilter(),
            renderEffect.asAndroidRenderEffect()
        ).asComposeRenderEffect()
    } else {
        android.graphics.RenderEffect.createColorFilterEffect(
            colorFilter.asAndroidColorFilter(),
        ).asComposeRenderEffect()
    }
}
