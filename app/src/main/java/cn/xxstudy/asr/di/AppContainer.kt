package cn.xxstudy.asr.di

import android.content.Context
import cn.xxstudy.asr.data.asset.ModelAssetResolver
import cn.xxstudy.asr.data.audio.MicrophoneAudioSource
import cn.xxstudy.asr.data.audio.SherpaGtcrnDenoiser
import cn.xxstudy.asr.data.audio.SherpaSileroVadDetector
import cn.xxstudy.asr.data.engine.RecognizerConfigFactory
import cn.xxstudy.asr.data.engine.SherpaStreamingEngine
import cn.xxstudy.asr.data.monitor.AndroidProcessMemoryMonitor
import cn.xxstudy.asr.data.repository.AsrRepositoryImpl
import cn.xxstudy.asr.domain.repository.AsrRepository

/**
 * 应用级依赖注入容器契约接口（手动依赖注入 Manual DI Pattern）。
 */
interface AppContainer {

    /**
     * 全局唯一的端侧流式语音识别仓库实例。
     */
    val asrRepository: AsrRepository

    /**
     * 释放容器托管的全部底层原生资源。
     */
    fun release()
}

/**
 * 默认生产环境依赖容器实现。
 *
 * @param appContext 应用级上下文，仅用于访问 `AssetManager`，绝不持有 `Activity` 引用以杜绝内存泄漏
 */
class DefaultAppContainer(
    appContext: Context,
) : AppContainer {

    private val assetManager = appContext.applicationContext.assets

    private val assetResolver by lazy {
        ModelAssetResolver(assetManager = assetManager)
    }

    private val configFactory by lazy {
        RecognizerConfigFactory()
    }

    private val streamingEngine by lazy {
        SherpaStreamingEngine(
            assetManager = assetManager,
            assetResolver = assetResolver,
            configFactory = configFactory,
        )
    }

    private val speechDenoiser by lazy {
        SherpaGtcrnDenoiser(assetManager = assetManager)
    }

    private val voiceDetector by lazy {
        SherpaSileroVadDetector(assetManager = assetManager)
    }

    private val audioSource by lazy {
        MicrophoneAudioSource()
    }

    private val memoryMonitor by lazy {
        AndroidProcessMemoryMonitor()
    }

    override val asrRepository by lazy {
        AsrRepositoryImpl(
            audioSource = audioSource,
            engine = streamingEngine,
            readinessChecker = assetResolver,
            memoryMonitor = memoryMonitor,
            speechDenoiser = speechDenoiser,
            voiceDetector = voiceDetector,
        )
    }

    override fun release() {
        asrRepository.release()
    }
}
