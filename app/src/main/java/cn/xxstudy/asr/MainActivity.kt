package cn.xxstudy.asr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import cn.xxstudy.asr.ui.asr.AsrRoute
import cn.xxstudy.asr.ui.theme.EdgeasrappTheme

/**
 * 应用唯一主 Activity（单 Activity + Jetpack Compose 现代 Android 架构）。
 *
 * 仅负责开启沉浸式边缘到边缘布局并挂载顶层 Compose 路由组件 [AsrRoute]，
 * 不持有任何业务逻辑、协程任务或数据层对象（遵循**单一职责原则 SRP**）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EdgeasrappTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AsrRoute(
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
