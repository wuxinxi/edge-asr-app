package cn.xxstudy.asr.domain.model

/**
 * 端侧流式 ASR 模型描述。
 *
 * 采用密封类表达不同解码架构的差异：调用方只依赖抽象的 [AsrModelSpec]，
 * 新增架构时只需扩展子类并在工厂中支持，无需修改现有业务代码（开闭原则）。
 *
 * 模型文件名不在这里硬编码，而是由数据层在运行时按关键字从 [assetDir] 中解析，
 * 这样官方更新文件命名（例如 epoch、chunk 参数变化）时无需改代码。
 *
 * @property id 模型唯一标识，用于界面选择与日志
 * @property displayName 界面展示名称
 * @property assetDir 模型文件在 assets 中的目录，必须与下载脚本中的模型 ID 一致
 * @property approxSizeMb 推理所需文件的大致体积（MB），数据来自官方发布文件
 * @property description 一句话说明适用场景
 */
sealed class AsrModelSpec(
    val id: String,
    val displayName: String,
    val assetDir: String,
    val approxSizeMb: Int,
    val description: String,
) {

    /** tokens.txt 在 assets 中的相对路径，所有架构共用。 */
    val tokensPath: String get() = "$assetDir/$TOKENS_FILE"

    /**
     * Transducer 架构（Encoder + Decoder + Joiner 三件套）。
     *
     * @property modelType 传给 sherpa-onnx 的模型类型提示；默认传空串以由原生层从 ONNX 元数据自动识别 `zipformer` / `zipformer2`
     */
    class Transducer(
        id: String,
        displayName: String,
        assetDir: String,
        approxSizeMb: Int,
        description: String,
        val modelType: String = "",
    ) : AsrModelSpec(id, displayName, assetDir, approxSizeMb, description)

    /** Zipformer2 CTC 架构（单个模型文件，无预测网络与联合网络）。 */
    class Zipformer2Ctc(
        id: String,
        displayName: String,
        assetDir: String,
        approxSizeMb: Int,
        description: String,
    ) : AsrModelSpec(id, displayName, assetDir, approxSizeMb, description)

    companion object {
        /** 词表文件名，sherpa-onnx 所有官方模型包统一使用该名称。 */
        const val TOKENS_FILE = "tokens.txt"
    }
}
