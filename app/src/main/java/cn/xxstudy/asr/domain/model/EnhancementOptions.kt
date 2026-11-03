package cn.xxstudy.asr.domain.model

/**
 * 影响声学识别器底层解码图构建的增强参数子集。
 *
 * 当 [enableItn] 或 [enableHotwords] 发生变化时，需要重新构建 `OnlineRecognizer` 解码配置。
 *
 * @property enableItn 是否挂载 `itn_zh_number.fst`（0.02MB）加权有限状态转换器执行中文口语数字规整
 * @property enableHotwords 是否切换至 `modified_beam_search` 启用声学热词上下文偏置
 * @property hotwordsScore 热词路径加权得分（默认 2.0f）
 */
data class DecoderGraphOptions(
    val enableItn: Boolean = true,
    val enableHotwords: Boolean = false,
    val hotwordsScore: Float = DEFAULT_HOTWORDS_SCORE,
) {
    companion object {
        /** 默认热词束搜索加权分值。 */
        const val DEFAULT_HOTWORDS_SCORE = 2.0f
    }
}

/**
 * 端侧流式语音识别全链路原生增强开关配置。
 *
 * 涵盖“音频前端 — 声学解码器”四项总体积不足 1MB 的官方原生增强插件：
 * 1. [enableNeuralDenoise]：`0.51MB GTCRN` 时频卷积循环网络流式降噪；
 * 2. [enableNeuralVad]：`0.20MB Silero VAD` 神经网络人声门控（过滤非人声敲击/杂音防幻觉）；
 * 3. [enableItn]：`0.02MB WFST` 中文数字逆文本规整（自动将口语中文数字转为阿拉伯数字）；
 * 4. [enableHotwords]：`modified_beam_search` 流式热词上下文偏置（救回垂直领域同音专有名词）。
 *
 * @property enableNeuralDenoise 是否启用 `GTCRN` 实时神经网络语音降噪
 * @property enableNeuralVad 是否启用 `Silero VAD` 神经网络人声活动检测与静音门控
 * @property enableItn 是否启用 `WFST` 中文数字逆文本规整
 * @property enableHotwords 是否启用流式热词偏置增强
 * @property hotwordsText 用户自定义热词文本（逗号、顿号或换行分隔）
 * @property hotwordsScore 热词束搜索加权分值
 */
data class EnhancementOptions(
    val enableNeuralDenoise: Boolean = true,
    val enableNeuralVad: Boolean = true,
    val enableItn: Boolean = true,
    val enableHotwords: Boolean = false,
    val hotwordsText: String = DEFAULT_HOTWORDS_TEXT,
    val hotwordsScore: Float = DecoderGraphOptions.DEFAULT_HOTWORDS_SCORE,
) {
    /** 提取与底层声学解码图相关的参数快照。 */
    fun toDecoderGraphOptions(isItnAvailable: Boolean): DecoderGraphOptions = DecoderGraphOptions(
        enableItn = enableItn && isItnAvailable,
        enableHotwords = enableHotwords && hotwordsText.isNotBlank(),
        hotwordsScore = hotwordsScore,
    )

    companion object {
        /** 开箱默认演示热词示例（支持界面实时编辑）。 */
        const val DEFAULT_HOTWORDS_TEXT = "唐人,鸿蒙,sherpa,Kotlin"
    }
}

/**
 * 本地原生增强模型资产就绪状态快照。
 *
 * @property isDenoiseAvailable `models/gtcrn/gtcrn_simple.onnx` 是否已就绪
 * @property isVadAvailable `models/vad/silero_vad.int8.onnx` 是否已就绪
 * @property isItnAvailable `models/itn/itn_zh_number.fst` 是否已就绪
 */
data class EnhancementAvailability(
    val isDenoiseAvailable: Boolean = true,
    val isVadAvailable: Boolean = true,
    val isItnAvailable: Boolean = true,
)
