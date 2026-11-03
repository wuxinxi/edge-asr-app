package cn.xxstudy.asr.domain.engine

import cn.xxstudy.asr.domain.model.AsrModelSpec
import cn.xxstudy.asr.domain.model.DecoderGraphOptions

/**
 * 单次音频帧送入引擎后的解码快照。
 *
 * @property text 当前流式解码缓冲区中的最新文本
 * @property isEndpoint 是否检测到说话停顿端点（Endpoint）
 * @property decodeCostNanos 本轮调用底层引擎解码所消耗的纯 CPU 纳秒耗时
 */
data class DecodeSnapshot(
    val text: String,
    val isEndpoint: Boolean,
    val decodeCostNanos: Long,
)

/**
 * 单次流式识别会话抽象接口。
 *
 * 架构设计原则体现：
 * - **迪米特法则（LoD）**：仓库层无需感知底层 `OnlineRecognizer` 与 `OnlineStream` 的配对细节及端点重置步骤，
 *   只需向会话喂入音频帧并读取 [DecodeSnapshot]。
 * - **接口隔离原则（ISP）**：将会话级流操作与引擎级模型生命周期拆分为独立接口。
 */
interface StreamingSession {

    /**
     * 向当前识别流送入一帧归一化音频并推进增量解码。
     *
     * 若检测到说话停顿端点，实现类应在提取本句结果后自动重置底层流状态，确保下一句无缝衔接。
     *
     * @param samples 范围在 `[-1.0f, 1.0f]` 的单声道浮点音频样本
     * @param sampleRate 音频采样率（Hz）
     * @return 当前帧推进解码后的最新状态快照 [DecodeSnapshot]
     */
    suspend fun acceptWaveform(samples: FloatArray, sampleRate: Int): DecodeSnapshot

    /**
     * 标记音频输入结束并冲刷尾部残余特征帧，提取最后一句未闭合的识别文本。
     *
     * @return 尾部冲刷后剩余的未定稿文本，若无残余文本则返回空字符串
     */
    suspend fun finishInput(): String

    /**
     * 关闭当前会话并释放底层原生流指针。幂等操作，重复调用安全。
     */
    suspend fun close()
}

/**
 * 流式语音识别引擎抽象接口。
 *
 * 架构设计原则体现：
 * - **依赖倒置原则（DIP）**：隔离 `sherpa-onnx` JNI 实现，便于在 JVM 单元测试中注入替身引擎。
 * - **单一职责原则（SRP）**：仅负责原生模型的加载、解码图配置（ITN / 热词束搜索）、会话创建与内存释放。
 */
interface StreamingAsrEngine {

    /**
     * 当前已加载就绪的模型规格；未加载或已释放时为 `null`。
     */
    val currentModel: AsrModelSpec?

    /**
     * 当前已加载识别器生效的解码图增强配置。
     */
    val currentGraphOptions: DecoderGraphOptions

    /**
     * 本地 `itn_zh_number.fst` 逆文本规整状态图资产是否可用。
     */
    val isItnAvailable: Boolean

    /**
     * 加载指定规格的流式模型及解码图增强选项。若此前已加载其他模型或不同解码配置，须先安全释放旧实例以锁死内存峰值。
     *
     * @param spec 目标流式模型规格描述
     * @param graphOptions 解码图增强配置（WFST 数字规整与热词束搜索开关）
     * @return 本次模型完成初始化的实际耗时（毫秒）
     */
    suspend fun loadModel(
        spec: AsrModelSpec,
        graphOptions: DecoderGraphOptions = DecoderGraphOptions(),
    ): Long

    /**
     * 基于当前已加载的模型创建一条新的流式识别会话，并在启用热词偏置时注入当前词表的热词编码。
     *
     * @param hotwordsText 用户输入的原始热词文本（逗号或换行分隔；为空则不注入会话级热词）
     * @param hotwordsScore 热词加权得分
     * @return 独立的流式识别会话实例 [StreamingSession]
     */
    suspend fun createSession(
        hotwordsText: String = "",
        hotwordsScore: Float = DecoderGraphOptions.DEFAULT_HOTWORDS_SCORE,
    ): StreamingSession

    /**
     * 释放底层推理引擎持有的全部原生内存资源。
     */
    fun release()
}
