package cn.xxstudy.asr.data.engine

import cn.xxstudy.asr.data.asset.ResolvedModelAssets
import cn.xxstudy.asr.domain.model.DecoderGraphOptions
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig

/**
 * `sherpa-onnx` 流式识别器配置工厂（工厂模式 Factory Pattern）。
 *
 * 集中封装声学特征参数、端点判停规则、CPU 线程数、WFST 数字逆文本规整（`ruleFsts`）
 * 以及热词束搜索（`modified_beam_search`）等配置映射逻辑。
 */
class RecognizerConfigFactory {

    /**
     * 根据解析后的模型资产与解码增强选项构建流式识别配置对象 [OnlineRecognizerConfig]。
     *
     * @param assets 已校验存在的模型资产路径集合
     * @param graphOptions 解码图增强配置（WFST 数字规整与热词束搜索）
     * @param itnFstAssetPath `itn_zh_number.fst` 在 `assets` 中的相对路径；不可用时传 `null`
     * @param sampleRate 音频输入采样率（默认 16000Hz）
     * @param cpuThreads ONNX Runtime CPU 推理线程数（默认 2 线程）
     * @return 可直接传入 `OnlineRecognizer` 的完整配置实例
     */
    fun create(
        assets: ResolvedModelAssets,
        graphOptions: DecoderGraphOptions = DecoderGraphOptions(),
        itnFstAssetPath: String? = null,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        cpuThreads: Int = DEFAULT_CPU_THREADS,
    ): OnlineRecognizerConfig {
        val featConfig = FeatureConfig().apply {
            this.sampleRate = sampleRate
            this.featureDim = MEL_FEATURE_DIM
        }

        val modelConfig = OnlineModelConfig().apply {
            this.tokens = assets.tokensPath
            this.numThreads = cpuThreads
            this.provider = CPU_PROVIDER
            this.debug = false

            when (assets) {
                is ResolvedModelAssets.Transducer -> {
                    this.modelType = assets.modelType
                    this.transducer = OnlineTransducerModelConfig().apply {
                        this.encoder = assets.encoderPath
                        this.decoder = assets.decoderPath
                        this.joiner = assets.joinerPath
                    }
                }

                is ResolvedModelAssets.Zipformer2Ctc -> {
                    this.zipformer2Ctc = OnlineZipformer2CtcModelConfig().apply {
                        this.model = assets.modelPath
                    }
                }
            }
        }

        val endpointConfig = EndpointConfig().apply {
            this.rule1 = EndpointRule(false, 2.4f, 0.0f)
            this.rule2 = EndpointRule(true, 1.2f, 0.0f)
            this.rule3 = EndpointRule(false, 0.0f, 20.0f)
        }

        val useHotwordsBeamSearch = graphOptions.enableHotwords && assets is ResolvedModelAssets.Transducer
        val resolvedRuleFsts = if (graphOptions.enableItn && !itnFstAssetPath.isNullOrBlank()) {
            itnFstAssetPath
        } else {
            ""
        }

        return OnlineRecognizerConfig().apply {
            this.featConfig = featConfig
            this.modelConfig = modelConfig
            this.endpointConfig = endpointConfig
            this.enableEndpoint = true
            this.decodingMethod = if (useHotwordsBeamSearch) MODIFIED_BEAM_SEARCH else GREEDY_DECODING
            this.maxActivePaths = DEFAULT_MAX_ACTIVE_PATHS
            this.hotwordsScore = graphOptions.hotwordsScore
            this.ruleFsts = resolvedRuleFsts
        }
    }

    companion object {
        /** 标准远场/近讲语音识别采样率（Hz）。 */
        const val DEFAULT_SAMPLE_RATE = 16000

        /** Fbank 梅尔频谱特征维度。 */
        private const val MEL_FEATURE_DIM = 80

        /** 面向旧安卓机调优的默认 CPU 线程数。 */
        private const val DEFAULT_CPU_THREADS = 2

        /** 纯 CPU 推理后端标识。 */
        private const val CPU_PROVIDER = "cpu"

        /** 贪心搜索解码策略，算力开销最低。 */
        private const val GREEDY_DECODING = "greedy_search"

        /** 支持热词上下文图偏置的改进型束搜索解码策略。 */
        private const val MODIFIED_BEAM_SEARCH = "modified_beam_search"

        /** 束搜索激活路径数上限（官方推荐 4，兼顾热词召回率与端侧实时率）。 */
        private const val DEFAULT_MAX_ACTIVE_PATHS = 4
    }
}
