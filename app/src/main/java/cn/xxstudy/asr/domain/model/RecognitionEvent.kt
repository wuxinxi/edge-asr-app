package cn.xxstudy.asr.domain.model

/**
 * 识别过程中向上层发送的事件。
 *
 * 使用密封接口保证 ViewModel 处理事件时 when 表达式穷尽，避免遗漏分支。
 */
sealed interface RecognitionEvent {

    /**
     * 中间结果：当前句子尚未结束，文本会随着语音持续刷新。
     *
     * @property text 当前句子的实时识别文本
     */
    data class Partial(val text: String) : RecognitionEvent

    /**
     * 最终结果：检测到端点（说话停顿）或音频流自然结束，当前句子定稿。
     *
     * @property text 识别引擎输出的定稿文本
     * @property appliedEnhancements 本句实际生效的原生增强插件短标签列表（如 `["GTCRN", "VAD", "ITN", "热词"]`）
     */
    data class Final(
        val text: String,
        val appliedEnhancements: List<String> = emptyList(),
    ) : RecognitionEvent

    /**
     * 性能统计：周期性上报，便于在真机上观察实际开销。
     *
     * @property stats 当前会话的累计统计
     */
    data class Stats(val stats: PerformanceStats) : RecognitionEvent
}

/**
 * 识别会话性能统计。全部为真机运行时实测值，不做任何估算。
 *
 * @property modelLoadMs 模型加载耗时（毫秒）
 * @property audioSeconds 已采集音频时长（秒）
 * @property decodeSeconds 端侧神经网络（GTCRN + Silero VAD + 流式 ASR）累计推理耗时（秒）
 * @property firstTextLatencyMs 从检测到首个有效人声帧起算至出现首个识别字的耗时（毫秒），未出字时为 null
 * @property pssMb 当前进程实际物理内存占用 PSS（MB）
 */
data class PerformanceStats(
    val modelLoadMs: Long = 0,
    val audioSeconds: Float = 0f,
    val decodeSeconds: Float = 0f,
    val firstTextLatencyMs: Long? = null,
    val pssMb: Float = 0f,
) {
    /** 实时率 RTF = 端侧神经网络总耗时 / 音频时长，小于 1 表示处理快于说话速度。 */
    val rtf: Float get() = if (audioSeconds > 0f) decodeSeconds / audioSeconds else 0f
}
