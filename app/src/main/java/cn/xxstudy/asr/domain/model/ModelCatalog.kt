package cn.xxstudy.asr.domain.model

/**
 * 内置流式模型目录（严格小于 `228MB SenseVoice Small INT8` 的端侧探底与主力梯队）。
 *
 * 集中维护 Demo 支持的 4 款 `k2-fsa/sherpa-onnx` 官方纯流式（`OnlineRecognizer`）INT8 模型，
 * 体积数据取自官方发布包中推理实际所需的 ONNX 权重与词表文件之和（不含压缩包内的测试音频与冗余 FP32 权重）：
 * 1. [ZIPFORMER_ZH_14M]（24MB）：极限下探参照，展示压至 25MB 级别在开放口语下的同音字边界；
 * 2. [ZIPFORMER_MULTI_ZH_HANS]（73MB）：约 SenseVoice 1/3 体积，纯中文日常听写可用起步线；
 * 3. [ZIPFORMER_ZH_2025]（160MB）：2025 多数据集中文流式主力，抗同音干扰能力大幅提升；
 * 4. [X_ASR_ZH_EN_PUNCT]（161MB）：2026 中英双语混说 + 原生流式标点主力，比 SenseVoice 小约 67MB。
 *
 * 新增模型时只需在 `scripts/download-models.sh` 与本对象的 [all] 中各追加一项，其余代码无需改动（开闭原则 OCP）。
 */
object ModelCatalog {

    /** 极限探底基线：14M 参数中文 Zipformer Transducer，INT8 三件套实测 24.2MB。 */
    val ZIPFORMER_ZH_14M = AsrModelSpec.Transducer(
        id = "zipformer",
        displayName = "Zipformer 14M",
        assetDir = "models/zipformer",
        approxSizeMb = 24,
        description = "探底参照：适合固定短指令，开放口语同音字较多",
    )

    /** 纯中文可用起步线：多数据集简体中文 Zipformer2 Transducer，INT8 实测 72.8MB。 */
    val ZIPFORMER_MULTI_ZH_HANS = AsrModelSpec.Transducer(
        id = "multi_zh",
        displayName = "Zipformer Multi-zh",
        assetDir = "models/multi_zh",
        approxSizeMb = 73,
        description = "起步可用：约 SenseVoice 1/3 体积，日常纯中文听写",
    )

    /** 2025 纯中文高准主力：新一代多数据集中文 Zipformer2 Transducer，INT8 实测 159.6MB。 */
    val ZIPFORMER_ZH_2025 = AsrModelSpec.Transducer(
        id = "zipformer_zh_2025",
        displayName = "Zipformer-zh 2025",
        assetDir = "models/zipformer_zh_2025",
        approxSizeMb = 160,
        description = "中文主力：2025 大规模语料训练，同音消歧显著增强",
    )

    /** 2026 全能双语标点主力：X-ASR 480ms 流式 Zipformer2 Transducer，INT8 实测 161.4MB。 */
    val X_ASR_ZH_EN_PUNCT = AsrModelSpec.Transducer(
        id = "x_asr_zh_en",
        displayName = "X-ASR 双语标点",
        assetDir = "models/x_asr_zh_en",
        approxSizeMb = 161,
        description = "全能主力：中英双语混说 + 原生流式自带标点",
    )

    /** 全部内置模型，按体积由小到大排列展示完整探底阶梯。 */
    val all: List<AsrModelSpec> = listOf(
        ZIPFORMER_ZH_14M,
        ZIPFORMER_MULTI_ZH_HANS,
        ZIPFORMER_ZH_2025,
        X_ASR_ZH_EN_PUNCT,
    )

    /** 默认选中的主力模型（开箱即获中英双语与实时标点体验）。 */
    val default: AsrModelSpec = X_ASR_ZH_EN_PUNCT
}
