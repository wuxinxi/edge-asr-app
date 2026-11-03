package cn.xxstudy.asr.domain.repository

import cn.xxstudy.asr.domain.model.AsrModelSpec
import cn.xxstudy.asr.domain.model.EnhancementAvailability
import cn.xxstudy.asr.domain.model.EnhancementOptions
import cn.xxstudy.asr.domain.model.ModelAvailability
import cn.xxstudy.asr.domain.model.RecognitionEvent
import kotlinx.coroutines.flow.Flow

/**
 * 端侧流式语音识别仓库接口（仓库模式 Repository Pattern）。
 *
 * 作为展示层（ViewModel）与数据层（音频源、GTCRN 降噪器、Silero VAD 检测器、流式识别引擎、内存探针）之间的唯一契约边界。
 */
interface AsrRepository {

    /**
     * 当前已激活加载的模型规格；尚未加载时为 `null`。
     */
    val activeModel: AsrModelSpec?

    /**
     * 查询端侧各项原生增强组件（GTCRN 降噪、Silero VAD、WFST ITN）的本地资产可用性。
     */
    val enhancementAvailability: EnhancementAvailability

    /**
     * 查询内置目录中全部流式模型的本地就绪状态。
     *
     * @return 按展示顺序排列的模型可用性列表 [ModelAvailability]
     */
    suspend fun queryModelAvailabilities(): List<ModelAvailability>

    /**
     * 切换并按指定增强选项加载流式语音识别模型。
     *
     * @param spec 目标流式模型规格
     * @param options 当前启用的全链路增强选项（用于同步配置 WFST ITN 与热词束搜索图）
     * @return 成功时返回模型加载耗时（毫秒），失败时封装具体异常原因
     */
    suspend fun switchModel(
        spec: AsrModelSpec,
        options: EnhancementOptions = EnhancementOptions(),
    ): Result<Long>

    /**
     * 启动一次实时麦克风流式语音识别会话。
     *
     * 调用方收集返回的 [Flow] 即开始录音与全链路增强解码；取消收集协程即自动停止录音并释放流资源。
     *
     * @param options 本次会话启用的原生增强插件组合（GTCRN 降噪、Silero VAD 门控、WFST 数字规整、热词偏置）
     * @return 持续发射中间识别结果、端点定稿结果与实时性能指标的事件流 [RecognitionEvent]
     */
    fun startRecognitionSession(options: EnhancementOptions): Flow<RecognitionEvent>

    /**
     * 释放仓库底层持有的推理引擎、VAD、降噪网络与原生资源。
     */
    fun release()
}
