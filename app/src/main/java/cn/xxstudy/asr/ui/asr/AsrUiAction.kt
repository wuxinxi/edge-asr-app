package cn.xxstudy.asr.ui.asr

import cn.xxstudy.asr.domain.model.AsrModelSpec

/**
 * 主界面用户交互意图密封接口（单向数据流 Action / Intent）。
 *
 * 所有界面事件统一封装为 [AsrUiAction] 交由 `AsrViewModel` 集中分发处理，
 * 使无状态 Compose 组件只需持有单一事件回调签名，符合**接口隔离原则（ISP）**。
 */
sealed interface AsrUiAction {

    /**
     * 切换目标流式语音识别模型。
     *
     * @property spec 用户点击选中的模型规格
     */
    data class SelectModel(val spec: AsrModelSpec) : AsrUiAction

    /**
     * 请求启动麦克风流式识别（需在已获取 `RECORD_AUDIO` 权限后触发）。
     */
    data object StartRecording : AsrUiAction

    /**
     * 请求停止当前流式录音与识别会话。
     */
    data object StopRecording : AsrUiAction

    /**
     * 切换 `GTCRN`（0.51MB）神经网络实时语音降噪开关。
     */
    data class SetNeuralDenoise(val enabled: Boolean) : AsrUiAction

    /**
     * 切换 `Silero VAD`（0.20MB）神经网络人声活动检测门控开关。
     */
    data class SetNeuralVad(val enabled: Boolean) : AsrUiAction

    /**
     * 切换 `WFST`（0.02MB `itn_zh_number.fst`）中文口语数字逆文本规整开关。
     */
    data class SetItn(val enabled: Boolean) : AsrUiAction

    /**
     * 切换流式热词偏置（`modified_beam_search`）开关。
     */
    data class SetHotwords(val enabled: Boolean) : AsrUiAction

    /**
     * 更新自定义热词列表文本（逗号或换行分隔）。
     */
    data class UpdateHotwordsText(val text: String) : AsrUiAction

    /**
     * 清空已定稿的历史转写记录与当前中间文本。
     */
    data object ClearTranscripts : AsrUiAction

    /**
     * 重新扫描本地 `assets/models` 目录下的模型文件就绪状态。
     */
    data object RefreshModels : AsrUiAction

    /**
     * 关闭顶部错误提示横幅。
     */
    data object DismissError : AsrUiAction

    /**
     * 上报录音权限被拒绝提示。
     *
     * @property message 面向用户的权限引导说明
     */
    data class ReportPermissionDenied(val message: String) : AsrUiAction
}
