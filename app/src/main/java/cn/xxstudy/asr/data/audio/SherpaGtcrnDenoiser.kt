package cn.xxstudy.asr.data.audio

import android.content.res.AssetManager
import cn.xxstudy.asr.domain.audio.DenoiseFrameResult
import cn.xxstudy.asr.domain.audio.StreamingSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 基于 `sherpa-onnx` 原生 `GTCRN`（Grouped Temporal Convolutional Recurrent Network，约 0.51MB）的流式语音降噪实现。
 *
 * 设计规范与工程保障：
 * - **真实神经网络推理**：100% 采用轻量化时频卷积循环网络逐帧抑制稳态与非稳态环境噪声，零硬编码规则。
 * - **JNI 线程安全隔离**：所有对原生 [OnlineSpeechDenoiser] 指针的访问均约束在单并行度调度器 [denoiserDispatcher]
 *   与协程互斥锁 [mutex] 内串行执行，彻底杜绝多线程并发踩踏原生句柄。
 *
 * @property assetManager Android 资产管理器，用于零拷贝映射 `assets/models/gtcrn/gtcrn_simple.onnx`
 * @property denoiserDispatcher 专用于串行执行原生降噪推理的单并行度调度器
 */
class SherpaGtcrnDenoiser @OptIn(ExperimentalCoroutinesApi::class) constructor(
    private val assetManager: AssetManager,
    private val denoiserDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : StreamingSpeechDenoiser {

    private val mutex = Mutex()

    private var nativeDenoiser: OnlineSpeechDenoiser? = null

    override val isAvailable: Boolean
        get() = runCatching {
            assetManager.list(GTCRN_ASSET_DIR)?.contains(GTCRN_MODEL_FILE) == true
        }.getOrDefault(false)

    override suspend fun denoise(samples: FloatArray, sampleRate: Int): DenoiseFrameResult =
        withContext(denoiserDispatcher) {
            mutex.withLock {
                val instance = ensureInitializedLocked()
                    ?: return@withLock DenoiseFrameResult(samples, sampleRate, 0L)

                val startNanos = System.nanoTime()
                val out = instance.run(samples, sampleRate)
                val costNanos = (System.nanoTime() - startNanos).coerceAtLeast(0L)
                DenoiseFrameResult(
                    samples = out.samples,
                    sampleRate = out.sampleRate,
                    costNanos = costNanos,
                )
            }
        }

    override suspend fun flush(defaultSampleRate: Int): DenoiseFrameResult =
        withContext(denoiserDispatcher) {
            mutex.withLock {
                val instance = nativeDenoiser
                    ?: return@withLock DenoiseFrameResult(FloatArray(0), defaultSampleRate, 0L)

                val startNanos = System.nanoTime()
                val out = instance.flush()
                val costNanos = (System.nanoTime() - startNanos).coerceAtLeast(0L)
                DenoiseFrameResult(
                    samples = out.samples,
                    sampleRate = out.sampleRate,
                    costNanos = costNanos,
                )
            }
        }

    override suspend fun reset() {
        withContext(denoiserDispatcher) {
            mutex.withLock {
                nativeDenoiser?.reset()
            }
        }
    }

    override fun release() {
        val old = nativeDenoiser
        nativeDenoiser = null
        old?.release()
    }

    /**
     * 按需懒加载原生 `GTCRN` 流式降噪器实例（必须在持有 [mutex] 时调用）。
     */
    private fun ensureInitializedLocked(): OnlineSpeechDenoiser? {
        nativeDenoiser?.let { return it }
        if (!isAvailable) return null

        val config = OnlineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(
                    model = "$GTCRN_ASSET_DIR/$GTCRN_MODEL_FILE",
                ),
                numThreads = 1,
                debug = false,
                provider = "cpu",
            ),
        )
        return OnlineSpeechDenoiser(assetManager, config).also {
            nativeDenoiser = it
        }
    }

    companion object {
        /** GTCRN 降噪模型在 `assets` 中的相对目录。 */
        const val GTCRN_ASSET_DIR = "models/gtcrn"

        /** 官方预训练轻量级 GTCRN 语音增强权重文件名（523KB）。 */
        const val GTCRN_MODEL_FILE = "gtcrn_simple.onnx"
    }
}
