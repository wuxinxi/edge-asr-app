package cn.xxstudy.asr.domain.audio

/**
 * 单帧流式语音降噪结果快照。
 *
 * @property samples 经过神经网络降噪后的归一化浮点波形（若内部 STFT 帧移尚未凑齐则可能为空数组）
 * @property sampleRate 降噪输出波形采样率（Hz）
 * @property costNanos 本帧神经网络降噪推理耗时（纳秒）
 */
data class DenoiseFrameResult(
    val samples: FloatArray,
    val sampleRate: Int,
    val costNanos: Long,
)

/**
 * 端侧流式神经网络语音降噪器契约接口（策略模式 Strategy Pattern + 依赖倒置原则 DIP）。
 *
 * 抽象音频前端增强能力，使业务仓库层不直接耦合底层 `sherpa-onnx` 原生 `OnlineSpeechDenoiser` 句柄，
 * 同时支持在关闭降噪或纯 JVM 单元测试时无缝切换至零开销直通实现 [PassthroughSpeechDenoiser]。
 */
interface StreamingSpeechDenoiser {

    /** 本地降噪神经网络模型资产是否已就绪可用。 */
    val isAvailable: Boolean

    /**
     * 对输入的一帧实时 PCM 音频执行流式神经网络降噪。
     *
     * @param samples 范围在 `[-1.0f, 1.0f]` 的输入浮点音频帧
     * @param sampleRate 输入音频采样率（默认 16000Hz）
     * @return 降噪后的音频帧与本步推理耗时 [DenoiseFrameResult]
     */
    suspend fun denoise(samples: FloatArray, sampleRate: Int): DenoiseFrameResult

    /**
     * 在录音会话结束时冲刷 STFT 滑窗内的残余降噪采样点。
     *
     * @param defaultSampleRate 默认采样率（当无内部状态时回退使用）
     * @return 尾部残余降噪音频帧结果
     */
    suspend fun flush(defaultSampleRate: Int = 16000): DenoiseFrameResult

    /**
     * 重置流式降噪器的跨帧隐藏层状态（新开录音会话前调用）。
     */
    suspend fun reset()

    /**
     * 释放底层原生推理实例。
     */
    fun release()
}

/**
 * 零开销直通降噪实现（空对象模式 Null Object Pattern）。
 *
 * 当用户关闭神经网络降噪开关或本地未安装降噪权重时使用，原样透传麦克风采样帧。
 */
object PassthroughSpeechDenoiser : StreamingSpeechDenoiser {

    override val isAvailable: Boolean = true

    override suspend fun denoise(samples: FloatArray, sampleRate: Int): DenoiseFrameResult {
        return DenoiseFrameResult(samples = samples, sampleRate = sampleRate, costNanos = 0L)
    }

    override suspend fun flush(defaultSampleRate: Int): DenoiseFrameResult {
        return DenoiseFrameResult(samples = FloatArray(0), sampleRate = defaultSampleRate, costNanos = 0L)
    }

    override suspend fun reset() = Unit

    override fun release() = Unit
}
