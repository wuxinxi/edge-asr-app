package cn.xxstudy.asr.data.repository

import cn.xxstudy.asr.data.asset.ModelReadinessChecker
import cn.xxstudy.asr.data.monitor.MemoryMonitor
import cn.xxstudy.asr.domain.audio.AudioCaptureSource
import cn.xxstudy.asr.domain.audio.PassthroughSpeechDenoiser
import cn.xxstudy.asr.domain.audio.RmsEnergyVoiceDetector
import cn.xxstudy.asr.domain.audio.StreamingSpeechDenoiser
import cn.xxstudy.asr.domain.audio.StreamingVoiceDetector
import cn.xxstudy.asr.domain.engine.StreamingAsrEngine
import cn.xxstudy.asr.domain.engine.StreamingSession
import cn.xxstudy.asr.domain.model.AsrModelSpec
import cn.xxstudy.asr.domain.model.EnhancementAvailability
import cn.xxstudy.asr.domain.model.EnhancementOptions
import cn.xxstudy.asr.domain.model.ModelAvailability
import cn.xxstudy.asr.domain.model.ModelCatalog
import cn.xxstudy.asr.domain.model.PerformanceStats
import cn.xxstudy.asr.domain.model.RecognitionEvent
import cn.xxstudy.asr.domain.repository.AsrRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * 端侧流式语音识别仓库默认实现。
 *
 * 协调音频采集源 [AudioCaptureSource]、`GTCRN` 神经网络语音降噪器 [StreamingSpeechDenoiser]、
 * `Silero VAD` 神经网络人声门控 [StreamingVoiceDetector]、流式推理引擎 [StreamingAsrEngine]（含 `WFST ITN` 与 `Hotwords` 偏置）
 * 以及进程内存探针 [MemoryMonitor]，向上层提供统一的响应式事件流。
 *
 * @property audioSource 音频帧采集源
 * @property engine 流式语音识别引擎
 * @property readinessChecker 模型本地资产完整性检查器
 * @property memoryMonitor 进程物理内存探针
 * @property speechDenoiser 流式神经网络语音降噪器（默认直通）
 * @property voiceDetector 流式人声活动检测器（默认 RMS 能量探针）
 * @property ioDispatcher 用于文件存在性扫描的 IO 协程调度器
 */
class AsrRepositoryImpl(
    private val audioSource: AudioCaptureSource,
    private val engine: StreamingAsrEngine,
    private val readinessChecker: ModelReadinessChecker,
    private val memoryMonitor: MemoryMonitor,
    private val speechDenoiser: StreamingSpeechDenoiser = PassthroughSpeechDenoiser,
    private val voiceDetector: StreamingVoiceDetector = RmsEnergyVoiceDetector(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AsrRepository {

    private val fallbackEnergyDetector = RmsEnergyVoiceDetector()

    /** 最近一次模型成功加载的实测耗时（毫秒）。 */
    @Volatile
    private var lastModelLoadMs: Long = 0L

    override val activeModel: AsrModelSpec?
        get() = engine.currentModel

    override val enhancementAvailability: EnhancementAvailability
        get() = EnhancementAvailability(
            isDenoiseAvailable = speechDenoiser.isAvailable,
            isVadAvailable = voiceDetector.isAvailable,
            isItnAvailable = engine.isItnAvailable,
        )

    override suspend fun queryModelAvailabilities(): List<ModelAvailability> =
        withContext(ioDispatcher) {
            ModelCatalog.all.map { spec ->
                ModelAvailability(
                    spec = spec,
                    isDownloaded = readinessChecker.isModelReady(spec),
                )
            }
        }

    override suspend fun switchModel(
        spec: AsrModelSpec,
        options: EnhancementOptions,
    ): Result<Long> {
        return try {
            val graphOptions = options.toDecoderGraphOptions(engine.isItnAvailable)
            val loadMs = engine.loadModel(spec, graphOptions)
            lastModelLoadMs = loadMs
            Result.success(loadMs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override fun startRecognitionSession(options: EnhancementOptions): Flow<RecognitionEvent> = flow {
        val currentSpec = checkNotNull(engine.currentModel) {
            "流式识别引擎尚未加载模型，请先选择并加载可用模型"
        }
        val targetGraphOptions = options.toDecoderGraphOptions(engine.isItnAvailable)
        if (engine.currentGraphOptions != targetGraphOptions) {
            lastModelLoadMs = engine.loadModel(currentSpec, targetGraphOptions)
        }

        val useDenoiser = options.enableNeuralDenoise && speechDenoiser.isAvailable
        val activeDenoiser: StreamingSpeechDenoiser = if (useDenoiser) {
            speechDenoiser
        } else {
            PassthroughSpeechDenoiser
        }
        activeDenoiser.reset()

        val useNeuralVad = options.enableNeuralVad && voiceDetector.isAvailable
        val activeVad: StreamingVoiceDetector = if (useNeuralVad) {
            voiceDetector
        } else {
            fallbackEnergyDetector
        }
        activeVad.reset()

        val appliedTags = buildList {
            if (useDenoiser) add("GTCRN")
            if (useNeuralVad) add("VAD")
            if (targetGraphOptions.enableItn) add("ITN")
            if (targetGraphOptions.enableHotwords) add("热词")
        }

        val session = engine.createSession(
            hotwordsText = if (targetGraphOptions.enableHotwords) options.hotwordsText else "",
            hotwordsScore = options.hotwordsScore,
        )
        val sessionStartMark = TimeSource.Monotonic.markNow()
        var speechOnsetMark: TimeSource.Monotonic.ValueTimeMark? = null

        var processedSamples = 0L
        var totalDecodeNanos = 0L
        var firstTextLatencyMs: Long? = null
        var lastPartialText = ""
        var frameCounter = 0
        var cachedPssMb = memoryMonitor.currentPssMb()

        // 2 帧（200ms）前置滑窗缓冲 + 14 帧（1.4s）尾随保持门控：
        // 既能在静音/敲击杂音期间门控跳过声学解码，又能完整保留起音辅音并确保 1.2s 停顿 Endpoint 正常闭合
        val preRollBuffer = ArrayDeque<Pair<FloatArray, Int>>(VAD_PREROLL_FRAMES)
        var vadHangoverRemaining = 0

        suspend fun feedFrameToRecognizer(
            samples: FloatArray,
            rate: Int,
            sessionRef: StreamingSession,
        ) {
            if (samples.isEmpty()) return
            val snapshot = sessionRef.acceptWaveform(samples, rate)
            totalDecodeNanos += snapshot.decodeCostNanos

            if (firstTextLatencyMs == null && snapshot.text.isNotEmpty()) {
                val referenceMark = speechOnsetMark ?: sessionStartMark
                firstTextLatencyMs = referenceMark.elapsedNow().inWholeMilliseconds
            }

            if (snapshot.isEndpoint) {
                vadHangoverRemaining = 0
                if (snapshot.text.isNotEmpty()) {
                    emit(
                        RecognitionEvent.Final(
                            text = snapshot.text,
                            appliedEnhancements = appliedTags,
                        ),
                    )
                }
                if (lastPartialText.isNotEmpty()) {
                    lastPartialText = ""
                    emit(RecognitionEvent.Partial(""))
                }
            } else if (snapshot.text != lastPartialText) {
                lastPartialText = snapshot.text
                emit(RecognitionEvent.Partial(snapshot.text))
            }
        }

        emit(
            RecognitionEvent.Stats(
                PerformanceStats(
                    modelLoadMs = lastModelLoadMs,
                    pssMb = cachedPssMb,
                ),
            ),
        )

        try {
            audioSource.captureFrames().collect { frame ->
                val sampleRate = audioSource.sampleRate
                processedSamples += frame.size

                val denoised = activeDenoiser.denoise(frame, sampleRate)
                totalDecodeNanos += denoised.costNanos

                val candidateSamples = if (denoised.samples.isNotEmpty()) denoised.samples else frame
                val candidateRate = if (denoised.samples.isNotEmpty()) denoised.sampleRate else sampleRate

                val vadDecision = activeVad.detect(candidateSamples, candidateRate)
                totalDecodeNanos += vadDecision.costNanos

                if (speechOnsetMark == null && vadDecision.isSpeech) {
                    speechOnsetMark = TimeSource.Monotonic.markNow()
                }

                if (!useNeuralVad) {
                    if (denoised.samples.isNotEmpty()) {
                        feedFrameToRecognizer(denoised.samples, denoised.sampleRate, session)
                    }
                } else if (denoised.samples.isNotEmpty()) {
                    if (vadDecision.isSpeech) {
                        while (preRollBuffer.isNotEmpty()) {
                            val (preSamples, preRate) = preRollBuffer.removeFirst()
                            feedFrameToRecognizer(preSamples, preRate, session)
                        }
                        vadHangoverRemaining = VAD_HANGOVER_FRAMES
                        feedFrameToRecognizer(denoised.samples, denoised.sampleRate, session)
                    } else if (vadHangoverRemaining > 0) {
                        vadHangoverRemaining--
                        feedFrameToRecognizer(denoised.samples, denoised.sampleRate, session)
                    } else {
                        if (preRollBuffer.size >= VAD_PREROLL_FRAMES) {
                            preRollBuffer.removeFirst()
                        }
                        preRollBuffer.addLast(denoised.samples to denoised.sampleRate)
                    }
                }

                frameCounter++
                if (frameCounter % STATS_REPORT_FRAME_INTERVAL == 0) {
                    cachedPssMb = memoryMonitor.currentPssMb()
                    val audioSeconds = processedSamples.toFloat() / sampleRate.toFloat()
                    val decodeSeconds = totalDecodeNanos.toFloat() / NANOS_PER_SECOND
                    emit(
                        RecognitionEvent.Stats(
                            PerformanceStats(
                                modelLoadMs = lastModelLoadMs,
                                audioSeconds = audioSeconds,
                                decodeSeconds = decodeSeconds,
                                firstTextLatencyMs = firstTextLatencyMs,
                                pssMb = cachedPssMb,
                            ),
                        ),
                    )
                }
            }

            val tailDenoised = activeDenoiser.flush(audioSource.sampleRate)
            totalDecodeNanos += tailDenoised.costNanos
            if (tailDenoised.samples.isNotEmpty()) {
                feedFrameToRecognizer(tailDenoised.samples, tailDenoised.sampleRate, session)
            }

            val tailText = session.finishInput()
            if (tailText.isNotEmpty()) {
                emit(
                    RecognitionEvent.Final(
                        text = tailText,
                        appliedEnhancements = appliedTags,
                    ),
                )
                if (lastPartialText.isNotEmpty()) {
                    emit(RecognitionEvent.Partial(""))
                }
            }
        } finally {
            withContext(NonCancellable) {
                session.close()
            }
        }
    }

    override fun release() {
        engine.release()
        speechDenoiser.release()
        voiceDetector.release()
    }

    companion object {
        /** 每处理 5 帧（约 500ms）采样并上报一次内存与 RTF 统计。 */
        private const val STATS_REPORT_FRAME_INTERVAL = 5

        /** 秒与纳秒的换算系数。 */
        private const val NANOS_PER_SECOND = 1_000_000_000f

        /** VAD 触发说话前保留的前导音频帧数（2 帧 = 200ms，防止切掉句首声母）。 */
        private const val VAD_PREROLL_FRAMES = 2

        /** VAD 检测到人声结束后的尾随保持帧数（14 帧 = 1.4s，覆盖识别器 1.2s 停顿 Endpoint 规则）。 */
        private const val VAD_HANGOVER_FRAMES = 14
    }
}
