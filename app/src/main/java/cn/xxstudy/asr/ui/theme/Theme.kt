package cn.xxstudy.asr.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** 暗色模式默认配色方案。 */
private val DarkColorScheme = darkColorScheme(
    primary = ConsoleBlue80,
    secondary = SlateBlue80,
    tertiary = TealAccent80,
)

/** 亮色模式默认配色方案。 */
private val LightColorScheme = lightColorScheme(
    primary = ConsoleBlue40,
    secondary = SlateBlue40,
    tertiary = TealAccent40,
)

/**
 * 端侧流式语音识别应用 Material 3 主题包装组件。
 *
 * 在 Android 12（API 31）及以上设备支持系统动态壁纸取色，在 Android 7.0–11 旧款设备上使用定制科技蓝配色。
 *
 * @param darkTheme 是否启用暗黑模式，默认跟随系统设置
 * @param dynamicColor 是否启用 Android 12+ 动态配色，默认关闭以保持横评仪表盘视觉一致性
 * @param content 主题包裹的子级 Compose 界面内容
 */
@Composable
fun EdgeasrappTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
