package cn.xxstudy.asr.ui.asr

import androidx.compose.runtime.Immutable
import cn.xxstudy.asr.domain.model.AsrModelSpec
import cn.xxstudy.asr.domain.model.EnhancementAvailability
import cn.xxstudy.asr.domain.model.EnhancementOptions
import cn.xxstudy.asr.domain.model.ModelAvailability
import cn.xxstudy.asr.domain.model.ModelCatalog
import cn.xxstudy.asr.domain.model.PerformanceStats

/**
 * 已定稿的单句转写历史条目界面模型。
 *
 * 使用 [@Immutable] 标记帮助 Compose 编译器在重组期间跳过未变更列表项的冗余绘制。
 *
 * @property id 条目自增唯一标识，用作 `LazyColumn` 稳定 `key`
 * @property modelName 产出本句结果的模型展示名称
 * @property text 识别引擎输出的定稿文本
 * @property appliedEnhancements 本句生效的原生增强插件标签列表（如 `["GTCRN", "VAD", "ITN"]`）
 */
@Immutable
data class TranscriptSegmentUi(
    val id: Long,
    val modelName: String,
    val text: String,
    val appliedEnhancements: List<String> = emptyList(),
)

/**
 * 端侧流式语音识别主页面单向数据流（UDF）不可变状态对象。
 *
 * @property models 内置 4 款流式模型的本地可用性列表
 * @property selectedModel 当前选中的模型规格
 * @property isModelLoaded 当前选中模型是否已完成原生层初始化并处于随时可录状态
 * @property isLoadingModel 是否正在后台加载模型权重
 * @property isRecording 是否正在进行麦克风流式录音与实时解码
 * @property enhancements 当前启用的端侧原生增强插件配置（GTCRN 降噪、Silero VAD、WFST 数字规整、热词偏置）
 * @property enhancementAvailability 本地原生增强权重资产的就绪状态
 * @property livePartialText 当前正在说话但尚未触发停顿端点的实时中间识别文本
 * @property segments 已定稿的历史句子列表（按时间正序排列）
 * @property stats 当前会话的真机实测性能统计指标
 * @property errorMessage 顶部错误或操作提示横幅文案，无异常时为 `null`
 */
@Immutable
data class AsrUiState(
    val models: List<ModelAvailability> = ModelCatalog.all.map {
        ModelAvailability(spec = it, isDownloaded = false)
    },
    val selectedModel: AsrModelSpec = ModelCatalog.default,
    val isModelLoaded: Boolean = false,
    val isLoadingModel: Boolean = false,
    val isRecording: Boolean = false,
    val enhancements: EnhancementOptions = EnhancementOptions(),
    val enhancementAvailability: EnhancementAvailability = EnhancementAvailability(),
    val livePartialText: String = "",
    val segments: List<TranscriptSegmentUi> = emptyList(),
    val stats: PerformanceStats = PerformanceStats(),
    val errorMessage: String? = null,
) {
    /** 当前选中模型的本地可用性详情。 */
    val selectedAvailability: ModelAvailability?
        get() = models.firstOrNull { it.spec.id == selectedModel.id }

    /** 当前选中模型文件是否已下载就绪。 */
    val isSelectedModelDownloaded: Boolean
        get() = selectedAvailability?.isDownloaded == true

    /** 当前是否允许点击“开始识别”按钮。 */
    val canStartRecording: Boolean
        get() = isSelectedModelDownloaded && !isLoadingModel
}
