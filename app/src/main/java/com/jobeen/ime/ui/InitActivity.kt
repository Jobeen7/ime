package com.jobeen.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jobeen.ime.ImeApplication
import com.jobeen.ime.R
import com.jobeen.ime.ui.theme.ImeTheme

class InitActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = ImeApplication.getInstance()

        setContent {
            val initState by app.state.collectAsState()

            // 应-O8：初始化失败/超时态。AppState 只有
            // Starting/ResourcePreparing/EngineStarting/Finished 四态、
            // 且 AppStartup 各步失败只记日志不置失败态（失败与挂起在
            // 本页都只表现为 Finished 永不到达），故本页内唯一可观测
            // 的失败信号就是超时：Finished 在时限内未到达即进超时态，
            // 不再永久转圈。超时只改本页显示，绝不取消后台初始化：
            // 超时后后台若完成，Finished 分支仍会 finish 本页；超时
            // 本身不杀部署、不动引擎状态。
            var timedOut by remember { mutableStateOf(false) }
            var timeoutAttempt by remember { mutableIntStateOf(0) }
            LaunchedEffect(timeoutAttempt) {
                delay(INIT_TIMEOUT_MS)
                if (app.state.value != ImeApplication.AppState.Finished) {
                    timedOut = true
                }
            }

            if (initState == ImeApplication.AppState.Finished) {
                LaunchedEffect(Unit) {
                    setResult(RESULT_OK)
                    finish()
                }
            }

            ImeTheme(themeMode = 0) {
                val (titleRes, descRes) = when (initState) {
                    ImeApplication.AppState.ResourcePreparing -> R.string.initializing to R.string.initializing_desc
                    ImeApplication.AppState.EngineStarting -> R.string.starting_engine to R.string.starting_engine_desc
                    else -> R.string.initializing to R.string.initializing_desc
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (timedOut && initState != ImeApplication.AppState.Finished) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 32.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.init_timeout_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                )

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    text = stringResource(R.string.init_timeout_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center,
                                )

                                Spacer(Modifier.height(24.dp))

                                // 重试 = 重新计时再等一轮：进程内重跑初始化
                                // 不安全（AppStartup 步骤失败被吞、引擎切换
                                // 不可重入），故不重启引擎；若后台仍在跑，
                                // 本轮内完成仍会走 Finished 分支正常退出。
                                Button(
                                    onClick = {
                                        timedOut = false
                                        timeoutAttempt++
                                    },
                                ) {
                                    Text(stringResource(R.string.schema_switch_retry))
                                }

                                Spacer(Modifier.height(8.dp))

                                // 退出 = 关本页回主页面（RESULT_CANCELED）：
                                // 不杀后台初始化、不重启应用；要全新重跑
                                // 初始化需退出应用后重新打开（进程重建时
                                // AppStartup 自然重走全程）。
                                TextButton(
                                    onClick = {
                                        setResult(RESULT_CANCELED)
                                        finish()
                                    },
                                ) {
                                    Text(stringResource(R.string.schema_banner_close))
                                }
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                LoadingIndicator(
                                    modifier = Modifier.size(72.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                )

                                Spacer(Modifier.height(24.dp))

                                Text(
                                    text = stringResource(titleRes),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                )

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    text = stringResource(descRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        /**
         * 初始化超时：取部署超时口径的上限，不发明更短的时限。
         * SchemePackageImport.deployTimeoutMs 为 180 秒基线＋每
         * 8MiB 加 30 秒、上限 1800 秒；初始化 = 资源解压＋引擎
         * 首次部署，单部署本身就可合法地走到该上限，故本页时限
         * 直接取上限 1800 秒，绝不短于任何一次合法部署。
         */
        private const val INIT_TIMEOUT_MS = 1_800_000L
    }
}

@Composable
private fun LoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color,
) {
    val transition = rememberInfiniteTransition(label = "loading")

    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    val headAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 290f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "headAngle",
    )

    val tailAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 290f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tailAngle",
    )

    Canvas(modifier = modifier) {
        val strokeWidth = 5.dp.toPx()
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)

        drawArc(
            color = color.copy(alpha = 0.15f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )

        drawArc(
            color = color,
            startAngle = rotation + tailAngle,
            sweepAngle = headAngle,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}
