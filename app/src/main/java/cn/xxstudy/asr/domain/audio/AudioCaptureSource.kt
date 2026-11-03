package cn.xxstudy.asr.domain.audio

import kotlinx.coroutines.flow.Flow

/**
 * 音频采集源抽象接口。
 *
 * 架构设计原则体现：
 * - **接口隔离原则（ISP）**：仅暴露流式语音识别所需的采样率与归一化浮点帧流，不泄漏底层硬件句柄。
 * - **依赖倒置原则（DIP）**：领域层与仓库层仅依赖该抽象，具体实现可替换为系统麦克风、本地 WAV 回放或测试桩。
 * - **里氏替换原则（LSP）**：任意实现均承诺输出范围在 `[-1.0f, 1.0f]` 的单声道 PCM 浮点样本帧。
 */
interface AudioCaptureSource {

    /**
     * 音频采样率（单位：Hz），端侧流式识别标准采样率为 16000Hz。
     */
    val sampleRate: Int

    /**
     * 开启音频持续采集并返回冷流。
     *
     * 当收集协程取消时，实现类必须保证在 `finally` 块中安全停止并释放底层硬件录音资源。
     *
     * @return 持续发射归一化 PCM 浮点数组（取值范围 `-1.0f..1.0f`）的协程流
     */
    fun captureFrames(): Flow<FloatArray>
}
