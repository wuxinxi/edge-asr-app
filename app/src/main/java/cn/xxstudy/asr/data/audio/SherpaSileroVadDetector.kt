package cn.xxstudy.asr.data.audio

import android.content.res.AssetManager
import cn.xxstudy.asr.domain.audio.StreamingVoiceDetector
import cn.xxstudy.asr.domain.audio.VadFrameDecision
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 基于 `sherpa-onnx` 原生 `Silero VAD INT8`（约 0.20MB）的流式神经网络人声活动检测器实现。
 *
 * 设计规范与工程保障：
 * - **真神经网络语音门控**：以 512 采样点（32ms@16kHz）窗口逐帧评估人声置信度，精准过滤敲键盘、咳嗽、翻书等非人声脉冲杂音，
 *   防止声学解码器在静音或噪声区间产生幻觉文本，同时降低无人说话时的后端算力消耗。
 * - **JNI 线程安全隔离**：所有对原生 [Vad] 句柄的访问均约束在单并行度调度器 [vadDispatcher] 与互斥锁 [mutex] 内串行执行。
 *
 * @property assetManager Android 资产管理器，零拷贝读取 `assets/models/vad/silero_vad.int8.onnx`
 * @property vadDispatcher 专用于串行执行原生 VAD 推理的单并行度调度器
 */
class SherpaSileroVadDetector @OptIn(ExperimentalCoroutinesApi::class) constructor(
    private val assetManager: AssetManager,
    private val vadDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : StreamingVoiceDetector {

    private val mutex = Mutex()

    private var nativeVad: Vad? = null

    override val isAvailable: Boolean
        get() = runCatching {
            assetManager.list(VAD_ASSET_DIR)?.contains(VAD_MODEL_FILE) == true
        }.getOrDefault(false)

    override suspend fun detect(samples: FloatArray, sampleRate: Int): VadFrameDecision =
        withContext(vadDispatcher) {
            mutex.withLock {
                if (samples.isEmpty()) {
                    return@withLock VadFrameDecision(isSpeech = false, costNanos = 0L)
                }
                val vad = ensureInitializedLocked(sampleRate)
                    ?: return@withLock VadFrameDecision(isSpeech = true, costNanos = 0L)

                val startNanos = System.nanoTime()
                vad.acceptWaveform(samples)
                val speechActive = vad.isSpeechDetected() || !vad.empty()
                // 及时弹出内部已闭合语音段缓存，防止长时间录音过程中原生队列累积内存
                while (!vad.empty()) {
                    vad.pop()
                }
                val costNanos = (System.nanoTime() - startNanos).coerceAtLeast(0L)
                VadFrameDecision(isSpeech = speechActive, costNanos = costNanos)
            }
        }

    override suspend fun reset() {
        withContext(vadDispatcher) {
            mutex.withLock {
                nativeVad?.reset()
                nativeVad?.clear()
            }
        }
    }

    override fun release() {
        val old = nativeVad
        nativeVad = null
        old?.release()
    }

    private fun ensureInitializedLocked(sampleRate: Int): Vad? {
        nativeVad?.let { return it }
        if (!isAvailable) return null

        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "$VAD_ASSET_DIR/$VAD_MODEL_FILE",
                threshold = SPEECH_PROBABILITY_THRESHOLD,
                minSilenceDuration = MIN_SILENCE_SECONDS,
                minSpeechDuration = MIN_SPEECH_SECONDS,
                windowSize = SILERO_WINDOW_SAMPLES_16K,
                maxSpeechDuration = MAX_SPEECH_SECONDS,
            ),
            sampleRate = sampleRate,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        return Vad(assetManager, config).also {
            nativeVad = it
        }
    }

    companion object {
        /** Silero VAD 模型在 `assets` 中的相对目录。 */
        const val VAD_ASSET_DIR = "models/vad"

        /** 官方 INT8 量化 Silero VAD 模型文件名（208KB）。 */
        const val VAD_MODEL_FILE = "silero_vad.int8.onnx"

        /** 16kHz 下 Silero VAD 标准分析窗长（512 采样点 = 32ms）。 */
        private const val SILERO_WINDOW_SAMPLES_16K = 512

        /** 判定存在人声活动的神经网络概率阈值。 */
        private const val SPEECH_PROBABILITY_THRESHOLD = 0.42f

        /** 最短有效人声持续时长（秒），过滤瞬态敲击杂音。 */
        private const val MIN_SPEECH_SECONDS = 0.15f

        /** 最短静音闭合时长（秒）。 */
        private const val MIN_SILENCE_SECONDS = 0.25f

        /** 最长连续语音切分上限（秒）。 */
        private const val MAX_SPEECH_SECONDS = 20.0f
    }
}
