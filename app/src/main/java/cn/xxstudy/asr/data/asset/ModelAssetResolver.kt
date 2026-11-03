package cn.xxstudy.asr.data.asset

import android.content.res.AssetManager
import cn.xxstudy.asr.domain.model.AsrModelSpec

/**
 * 模型本地资源就绪检测接口（接口隔离原则 ISP）。
 *
 * 将资源存在性探测从 Android [AssetManager] 中抽象出来，使仓库实现类可在纯 JVM 环境中独立单测。
 */
fun interface ModelReadinessChecker {

    /**
     * 检测指定规格的模型文件是否已在本地完整就绪。
     *
     * @param spec 待检测的模型规格
     * @return 若词表与全部必需 ONNX 权重文件均存在则返回 `true`
     */
    fun isModelReady(spec: AsrModelSpec): Boolean
}

/**
 * 运行时解析后的模型资产路径集合。
 *
 * 屏蔽不同官方模型包内具体文件命名（如 `epoch-99`、`chunk-16` 等后缀）的差异。
 */
sealed interface ResolvedModelAssets {

    /** 词表文件在 `assets` 中的完整相对路径。 */
    val tokensPath: String

    /**
     * Transducer 架构解析结果（Encoder + Decoder + Joiner 三组件）。
     *
     * @property encoderPath 编码器 ONNX 相对路径
     * @property decoderPath 解码器 ONNX 相对路径
     * @property joinerPath 联合网络 ONNX 相对路径
     * @property tokensPath 词表文件相对路径
     * @property modelType 传递给推理引擎的模型类型标识
     */
    data class Transducer(
        val encoderPath: String,
        val decoderPath: String,
        val joinerPath: String,
        override val tokensPath: String,
        val modelType: String,
    ) : ResolvedModelAssets

    /**
     * Zipformer2 CTC 架构解析结果（单一模型权重文件）。
     *
     * @property modelPath CTC 主模型 ONNX 相对路径
     * @property tokensPath 词表文件相对路径
     */
    data class Zipformer2Ctc(
        val modelPath: String,
        override val tokensPath: String,
    ) : ResolvedModelAssets
}

/**
 * 基于 Android [AssetManager] 的模型资产动态解析器。
 *
 * 遵循**单一职责原则（SRP）**与**开闭原则（OCP）**：
 * 按前缀关键字（`encoder`、`decoder`、`joiner`、`model`）动态匹配资产文件，并优先选用 `.int8.onnx` 量化权重，
 * 当官方更新模型迭代轮次命名时无需修改业务代码。
 *
 * @property assetManager Android 应用资产管理器
 */
class ModelAssetResolver(
    private val assetManager: AssetManager,
) : ModelReadinessChecker {

    /**
     * 判断指定模型目录内是否已包含推理所需的完整文件。
     *
     * @param spec 目标流式模型规格
     * @return 资源齐全返回 `true`，缺失或读取异常返回 `false`
     */
    override fun isModelReady(spec: AsrModelSpec): Boolean {
        return runCatching { resolve(spec) }.isSuccess
    }

    /**
     * 解析并校验指定模型的全部 `assets` 相对路径。
     *
     * @param spec 目标流式模型规格
     * @return 解析完成的 [ResolvedModelAssets]
     * @throws IllegalStateException 当目录为空、缺少 `tokens.txt` 或缺少必需 ONNX 文件时抛出
     */
    fun resolve(spec: AsrModelSpec): ResolvedModelAssets {
        val entries = assetManager.list(spec.assetDir)?.toList().orEmpty()
        check(entries.isNotEmpty()) {
            "未检测到模型 [${spec.displayName}] 目录，请先运行: bash scripts/download-models.sh ${spec.id}"
        }
        check(AsrModelSpec.TOKENS_FILE in entries) {
            "模型 [${spec.displayName}] 缺少词表文件 ${AsrModelSpec.TOKENS_FILE}"
        }

        return when (spec) {
            is AsrModelSpec.Transducer -> {
                val encoder = findPreferredOnnx(entries, "encoder")
                    ?: error("模型 [${spec.displayName}] 缺少 encoder*.onnx 文件")
                val decoder = findPreferredOnnx(entries, "decoder")
                    ?: error("模型 [${spec.displayName}] 缺少 decoder*.onnx 文件")
                val joiner = findPreferredOnnx(entries, "joiner")
                    ?: error("模型 [${spec.displayName}] 缺少 joiner*.onnx 文件")

                ResolvedModelAssets.Transducer(
                    encoderPath = "${spec.assetDir}/$encoder",
                    decoderPath = "${spec.assetDir}/$decoder",
                    joinerPath = "${spec.assetDir}/$joiner",
                    tokensPath = spec.tokensPath,
                    modelType = spec.modelType,
                )
            }

            is AsrModelSpec.Zipformer2Ctc -> {
                val ctcModel = findPreferredOnnx(entries, "model")
                    ?: entries.filter { it.endsWith(".onnx") }
                        .maxByOrNull { it.endsWith(".int8.onnx") }
                    ?: error("模型 [${spec.displayName}] 缺少 CTC ONNX 模型文件")

                ResolvedModelAssets.Zipformer2Ctc(
                    modelPath = "${spec.assetDir}/$ctcModel",
                    tokensPath = spec.tokensPath,
                )
            }
        }
    }

    /**
     * 按前缀查找 ONNX 模型文件名，若同时存在 FP32 与 INT8 权重，优先返回 `.int8.onnx` 以降低内存与算力开销。
     *
     * @param entries 目录下的文件名列表
     * @param prefix 目标组件前缀（如 `encoder`）
     * @return 匹配到的最佳文件名；未匹配时返回 `null`
     */
    private fun findPreferredOnnx(entries: List<String>, prefix: String): String? {
        val candidates = entries.filter { it.startsWith(prefix) && it.endsWith(".onnx") }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.endsWith(".int8.onnx") } ?: candidates.first()
    }
}
