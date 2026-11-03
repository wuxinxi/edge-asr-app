package cn.xxstudy.asr.domain.model

/**
 * 模型本地可用性状态描述。
 *
 * 将静态的模型规格定义 [AsrModelSpec] 与运行时的本地资源就绪状态解耦，
 * 供界面层展示下载状态与终端拉取提示。
 *
 * @property spec 模型基础规格描述
 * @property isDownloaded 模型所需的词表与 ONNX 权重文件是否已完整就绪
 * @property downloadCommand 当模型缺失时提示用户执行的脚本命令
 */
data class ModelAvailability(
    val spec: AsrModelSpec,
    val isDownloaded: Boolean,
    val downloadCommand: String = "bash scripts/download-models.sh ${spec.id}",
)
