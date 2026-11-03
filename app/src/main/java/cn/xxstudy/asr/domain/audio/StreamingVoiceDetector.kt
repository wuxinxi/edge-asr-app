package cn.xxstudy.asr.domain.audio

import kotlin.math.sqrt

/**
 * 单帧人声活动检测（VAD）判定结果。
 *
 * @property isSpeech 当前音频帧是否包含有效人声活动
 * @property costNanos 本帧 VAD 检测所耗费的纯 CPU 纳秒时间
 */
data class VadFrameDecision(
    val isSpeech: Boolean,
    val costNanos: Long,
)

/**
 * 流式人声活动检测器契约接口（策略模式 Strategy Pattern + 依赖倒置原则 DIP）。
 *
 * 抽象音频流中的人声门控与开口起点检测逻辑：
 * - 开启神经网络 VAD 时注入基于 `Silero VAD INT8`（0.20MB）的实现，精准拦截敲击、咳嗽等非人声脉冲杂音；
 * - 关闭神经网络 VAD 或在纯 JVM 单测中可无缝回退至零模型开销的 [RmsEnergyVoiceDetector]。
 */
interface StreamingVoiceDetector {

    /** 本地 VAD 模型资产是否已就绪。 */
    val isAvailable: Boolean

    /**
     * 对输入的一帧实时归一化音频执行人声活动判定。
     *
     * @param samples 范围在 `[-1.0f, 1.0f]` 的单声道浮点音频帧
     * @param sampleRate 音频采样率（默认 16000Hz）
     * @return 是否检测到人声及本步推理耗时 [VadFrameDecision]
     */
    suspend fun detect(samples: FloatArray, sampleRate: Int): VadFrameDecision

    /**
     * 重置跨帧隐藏层状态（新开识别会话前调用）。
     */
    suspend fun reset()

    /**
     * 释放底层原生 VAD 实例资源。
     */
    fun release()
}

/**
 * 基于均方根（RMS）振幅阈值的轻量能量起点检测器（回退策略实现）。
 *
 * 当用户关闭神经网络 VAD 开关时用于判定首次开口计算首字延迟，不做帧丢弃门控。
 *
 * @property onsetThreshold 判定有效人声的最小 RMS 能量阈值（默认 `0.012f`，约 -38 dBFS）
 */
class RmsEnergyVoiceDetector(
    private val onsetThreshold: Float = DEFAULT_RMS_THRESHOLD,
) : StreamingVoiceDetector {

    override val isAvailable: Boolean = true

    override suspend fun detect(samples: FloatArray, sampleRate: Int): VadFrameDecision {
        if (samples.isEmpty()) return VadFrameDecision(isSpeech = false, costNanos = 0L)
        var energySum = 0.0
        for (sample in samples) {
            energySum += sample * sample
        }
        val rms = sqrt(energySum / samples.size).toFloat()
        return VadFrameDecision(isSpeech = rms >= onsetThreshold, costNanos = 0L)
    }

    override suspend fun reset() = Unit

    override fun release() = Unit

    companion object {
        /** 默认有效人声 RMS 阈值。 */
        const val DEFAULT_RMS_THRESHOLD = 0.012f
    }
}
