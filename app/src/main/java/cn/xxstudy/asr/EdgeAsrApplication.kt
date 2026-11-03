package cn.xxstudy.asr

import android.app.Application
import cn.xxstudy.asr.di.AppContainer
import cn.xxstudy.asr.di.DefaultAppContainer

/**
 * 端侧流式语音识别应用全局入口。
 *
 * 负责在进程启动时初始化轻量级手动依赖注入容器 [AppContainer]，
 * 并在系统低内存回收终止时释放原生推理引擎资源。
 */
class EdgeAsrApplication : Application() {

    /**
     * 应用级依赖注入容器，供 `ViewModelProvider.Factory` 安全读取仓库实例。
     */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(appContext = this)
    }

    override fun onTerminate() {
        if (::container.isInitialized) {
            container.release()
        }
        super.onTerminate()
    }
}
