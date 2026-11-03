package cn.xxstudy.asr.data.engine

import android.content.res.AssetManager
import cn.xxstudy.asr.data.asset.ModelAssetResolver
import cn.xxstudy.asr.domain.engine.DecodeSnapshot
import cn.xxstudy.asr.domain.engine.StreamingAsrEngine
import cn.xxstudy.asr.domain.engine.StreamingSession
import cn.xxstudy.asr.domain.model.AsrModelSpec
import cn.xxstudy.asr.domain.model.DecoderGraphOptions
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * 基于 `sherpa-onnx` 原生库的流式语音识别引擎实现。
 *
 * 工程健壮性与设计原则保障：
 * 1. **单线程协程限流与互斥锁保护**：所有涉及 JNI 指针（[OnlineRecognizer] 与 [OnlineStream]）的调用
 *    全部约束在单并行度调度器 [engineDispatcher] 与 [mutex] 内串行执行，杜绝多线程并发访问导致 Native Crash。
 * 2. **严格的低内存峰值控制**：切换新模型或更新解码图（WFST ITN / 热词束搜索）前必定先销毁并置空旧识别器实例，
 *    确保物理内存中绝不同时驻留两份权重。
 * 3. **跨词表热词自适应注入**：结合 [HotwordTokenizer] 将自然语言热词按当前加载模型的 `tokens.txt` 动态编码注入会话流。
 *
 * @property assetManager Android 资产管理器，供原生层直接零拷贝映射 APK 内的非压缩 ONNX 与 FST 资源
 * @property assetResolver 模型资产路径解析器
 * @property configFactory 识别器配置构造工厂
 * @property engineDispatcher 专用于串行执行原生推理任务的单并行度协程调度器
 */
class SherpaStreamingEngine @OptIn(ExperimentalCoroutinesApi::class) constructor(
    private val assetManager: AssetManager,
    private val assetResolver: ModelAssetResolver,
    private val configFactory: RecognizerConfigFactory,
    private val engineDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : StreamingAsrEngine {

    /** 保护底层 JNI 句柄生命周期与流解码顺序的协程互斥锁。 */
    private val mutex = Mutex()

    /** 当前存活的原生流式识别器实例。 */
    private var recognizer: OnlineRecognizer? = null

    /** 与当前已加载模型词表匹配的自适应热词分词器。 */
    private var hotwordTokenizer: HotwordTokenizer? = null

    @Volatile
    private var _currentModel: AsrModelSpec? = null

    @Volatile
    private var _currentGraphOptions: DecoderGraphOptions = DecoderGraphOptions()

    override val currentModel: AsrModelSpec?
        get() = _currentModel

    override val currentGraphOptions: DecoderGraphOptions
        get() = _currentGraphOptions

    override val isItnAvailable: Boolean
        get() = runCatching {
            assetManager.list(ITN_ASSET_DIR)?.contains(ITN_FST_FILE) == true
        }.getOrDefault(false)

    override suspend fun loadModel(
        spec: AsrModelSpec,
        graphOptions: DecoderGraphOptions,
    ): Long = withContext(engineDispatcher) {
        mutex.withLock {
            // 先释放已有原生实例，避免新旧两个模型在物理内存中重叠导致低配机 OOM
            recognizer?.release()
            recognizer = null
            hotwordTokenizer = null
            _currentModel = null

            val resolvedAssets = assetResolver.resolve(spec)
            val itnPath = if (isItnAvailable) "$ITN_ASSET_DIR/$ITN_FST_FILE" else null
            val config = configFactory.create(
                assets = resolvedAssets,
                graphOptions = graphOptions,
                itnFstAssetPath = itnPath,
            )

            val tokenizer = runCatching {
                assetManager.open(resolvedAssets.tokensPath).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    HotwordTokenizer.fromTokensLines(lines)
                }
            }.getOrNull()

            val mark = TimeSource.Monotonic.markNow()
            val newRecognizer = OnlineRecognizer(
                assetManager = assetManager,
                config = config,
            )
            val loadCostMs = mark.elapsedNow().inWholeMilliseconds

            recognizer = newRecognizer
            hotwordTokenizer = tokenizer
            _currentModel = spec
            _currentGraphOptions = graphOptions
            loadCostMs
        }
    }

    override suspend fun createSession(
        hotwordsText: String,
        hotwordsScore: Float,
    ): StreamingSession = withContext(engineDispatcher) {
        mutex.withLock {
            val activeRecognizer = checkNotNull(recognizer) {
                "流式识别引擎尚未加载模型，请先选择并加载可用模型"
            }
            val encodedHotwords = if (_currentGraphOptions.enableHotwords && hotwordsText.isNotBlank()) {
                hotwordTokenizer?.encodeHotwords(hotwordsText, hotwordsScore).orEmpty()
            } else {
                ""
            }
            val nativeStream = if (encodedHotwords.isNotEmpty()) {
                activeRecognizer.createStream(encodedHotwords)
            } else {
                activeRecognizer.createStream()
            }
            SherpaStreamingSession(
                boundRecognizer = activeRecognizer,
                stream = nativeStream,
            )
        }
    }

    override fun release() {
        val oldRecognizer = recognizer
        recognizer = null
        hotwordTokenizer = null
        _currentModel = null
        oldRecognizer?.release()
    }

    /**
     * 绑定至特定 [OnlineRecognizer] 与 [OnlineStream] 的会话实现类。
     */
    private inner class SherpaStreamingSession(
        private val boundRecognizer: OnlineRecognizer,
        private val stream: OnlineStream,
    ) : StreamingSession {

        private var isClosed: Boolean = false

        override suspend fun acceptWaveform(
            samples: FloatArray,
            sampleRate: Int,
        ): DecodeSnapshot = withContext(engineDispatcher) {
            mutex.withLock {
                if (isClosed || recognizer !== boundRecognizer) {
                    return@withLock DecodeSnapshot(text = "", isEndpoint = false, decodeCostNanos = 0L)
                }

                val startNanos = System.nanoTime()
                stream.acceptWaveform(samples, sampleRate)
                while (boundRecognizer.isReady(stream)) {
                    boundRecognizer.decode(stream)
                }
                val text = normalizeDecodedText(boundRecognizer.getResult(stream).text)
                val endpoint = boundRecognizer.isEndpoint(stream)
                if (endpoint) {
                    boundRecognizer.reset(stream)
                }
                val costNanos = (System.nanoTime() - startNanos).coerceAtLeast(0L)

                DecodeSnapshot(
                    text = text,
                    isEndpoint = endpoint,
                    decodeCostNanos = costNanos,
                )
            }
        }

        override suspend fun finishInput(): String = withContext(engineDispatcher) {
            mutex.withLock {
                if (isClosed || recognizer !== boundRecognizer) {
                    return@withLock ""
                }

                // 补入 0.5 秒静音尾帧，使分块流式编码器完整吐出句末最后一个音节
                stream.acceptWaveform(
                    FloatArray(TAIL_PADDING_SAMPLES),
                    RecognizerConfigFactory.DEFAULT_SAMPLE_RATE,
                )
                stream.inputFinished()
                while (boundRecognizer.isReady(stream)) {
                    boundRecognizer.decode(stream)
                }
                val tailText = normalizeDecodedText(boundRecognizer.getResult(stream).text)
                boundRecognizer.reset(stream)
                tailText
            }
        }

        override suspend fun close() = withContext(engineDispatcher) {
            mutex.withLock {
                if (!isClosed) {
                    isClosed = true
                    stream.release()
                }
            }
        }
    }

    /**
     * 规整原生解码器输出的文本空白与未知词标记：
     * 针对采用 SentencePiece 词表的双语标点模型（如 X-ASR），剔除 `<unk>` 占位符及汉字之间、中文标点两侧由 `▁` 转换产生的冗余空格，
     * 同时完整保留英文单词之间的自然空格。
     */
    private fun normalizeDecodedText(raw: String): String {
        val cleaned = raw.replace(UNK_TOKEN_REGEX, "").trim()
        if (cleaned.isEmpty() || ' ' !in cleaned) return cleaned
        return cleaned
            .replace(CJK_INTER_SPACE_REGEX, "")
            .replace(CJK_DIGIT_SPACE_REGEX, "")
            .replace(SPACE_AROUND_CJK_PUNCT_REGEX, "$1")
            .replace(SPACE_BEFORE_ASCII_PUNCT_REGEX, "$1")
            .trim()
    }

    companion object {
        /** WFST 中文数字逆文本规整状态图所在 `assets` 目录。 */
        const val ITN_ASSET_DIR = "models/itn"

        /** 官方中文数字逆文本规整 FST 文件名（25KB）。 */
        const val ITN_FST_FILE = "itn_zh_number.fst"

        /** 流结束冲刷时补齐的静音采样点数（16kHz 下 8000 点对应 0.5 秒右上下文窗口）。 */
        private const val TAIL_PADDING_SAMPLES = 8000

        /** 匹配词表未登录词占位标记 `<unk>`（不区分大小写）。 */
        private val UNK_TOKEN_REGEX = Regex("<unk>", RegexOption.IGNORE_CASE)

        /** 匹配相邻汉字之间的冗余分词空格（保留中英、英英之间的空格）。 */
        private val CJK_INTER_SPACE_REGEX = Regex("(?<=[\\u4e00-\\u9fa5])\\s+(?=[\\u4e00-\\u9fa5])")

        /** 匹配汉字与阿拉伯数字之间的冗余分词空格（如“下午 3 点” -> “下午3点”）。 */
        private val CJK_DIGIT_SPACE_REGEX =
            Regex("(?<=[\\u4e00-\\u9fa5])\\s+(?=[0-9])|(?<=[0-9])\\s+(?=[\\u4e00-\\u9fa5])")

        /** 匹配全角中文标点前后的冗余空格。 */
        private val SPACE_AROUND_CJK_PUNCT_REGEX = Regex("\\s*([，。！？：；、])\\s*")

        /** 匹配半角标点符号前的冗余空格（保留半角逗号/句号后跟英文单词的自然空格）。 */
        private val SPACE_BEFORE_ASCII_PUNCT_REGEX = Regex("(?<=[\\u4e00-\\u9fa5A-Za-z0-9])\\s+([,.!?:;])")
    }
}
