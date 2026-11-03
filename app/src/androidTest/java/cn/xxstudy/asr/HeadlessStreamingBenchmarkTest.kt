package cn.xxstudy.asr

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.xxstudy.asr.data.asset.ModelAssetResolver
import cn.xxstudy.asr.data.audio.SherpaGtcrnDenoiser
import cn.xxstudy.asr.data.audio.SherpaSileroVadDetector
import cn.xxstudy.asr.data.engine.RecognizerConfigFactory
import cn.xxstudy.asr.data.engine.SherpaStreamingEngine
import cn.xxstudy.asr.data.monitor.AndroidProcessMemoryMonitor
import cn.xxstudy.asr.domain.audio.PassthroughSpeechDenoiser
import cn.xxstudy.asr.domain.audio.StreamingSpeechDenoiser
import cn.xxstudy.asr.domain.audio.StreamingVoiceDetector
import cn.xxstudy.asr.domain.model.DecoderGraphOptions
import cn.xxstudy.asr.domain.model.ModelCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.TimeSource

/**
 * 纯推理无头基准测试：不依赖任何 ComposeRule / Activity 生命周期，无论真机是否息屏锁屏均可秒级跑完。
 */
@RunWith(AndroidJUnit4::class)
class HeadlessStreamingBenchmarkTest {

    @Test
    fun runHeadlessBenchmark() = runBlocking {
        val testDir = File("/data/local/tmp/asr_test")
        if (!testDir.exists()) {
            Log.i(TAG, "未检测到 /data/local/tmp/asr_test 测试音频目录，跳过离线音频横评")
            return@runBlocking
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = ModelAssetResolver(context.assets)
        val configFactory = RecognizerConfigFactory()
        val engine = SherpaStreamingEngine(
            assetManager = context.assets,
            assetResolver = resolver,
            configFactory = configFactory,
        )
        val gtcrnDenoiser = SherpaGtcrnDenoiser(assetManager = context.assets)
        val sileroVad = SherpaSileroVadDetector(assetManager = context.assets)
        val memoryMonitor = AndroidProcessMemoryMonitor()

        val sampleFiles = listOf(
            "sample_mandarin_01.wav",
            "sample_mandarin_02.wav",
            "sample_hard_01.wav",
            "sample_cs_01.wav",
            "sample_noise_01.wav",
            "sample_numbers_01.wav",
        ).map { File(testDir, it) }.filter { it.exists() }

        val readySpecs = ModelCatalog.all.filter { resolver.isModelReady(it) }
        Log.i(
            TAG,
            "===== 检测到就绪模型=${readySpecs.size}, GTCRN=${gtcrnDenoiser.isAvailable}, " +
                "VAD=${sileroVad.isAvailable}, ITN=${engine.isItnAvailable} =====",
        )

        val pureGraph = DecoderGraphOptions(enableItn = false, enableHotwords = false)

        // 1. 4 款模型基础对比
        for (spec in readySpecs) {
            System.gc()
            delay(100)
            val pssBefore = memoryMonitor.currentPssMb()
            val loadMs = engine.loadModel(spec, pureGraph)
            val pssAfterLoad = memoryMonitor.currentPssMb()

            var totalAudioSec = 0.0
            var totalDecodeSec = 0.0
            val firstCharLatencies = mutableListOf<Long>()

            for (wavFile in sampleFiles) {
                val pcmFloats = readWav16kMono(wavFile)
                val res = decodeWavStreaming(
                    engine = engine,
                    denoiser = PassthroughSpeechDenoiser,
                    vad = null,
                    pcmFloats = pcmFloats,
                    hotwords = "",
                )
                val pssPeak = memoryMonitor.currentPssMb()

                totalAudioSec += res.audioSec
                totalDecodeSec += res.decodeSec
                if (res.firstLatencyMs != null) firstCharLatencies.add(res.firstLatencyMs)

                Log.i(
                    TAG,
                    "BENCH_ITEM|model=${spec.id}|mode=raw|file=${wavFile.name}|loadMs=$loadMs|" +
                        "audioSec=${"%.2f".format(res.audioSec)}|decodeMs=${"%.1f".format(res.decodeSec * 1000)}|" +
                        "rtf=${"%.4f".format(res.rtf)}|pssBefore=${"%.1f".format(pssBefore)}|" +
                        "pssLoaded=${"%.1f".format(pssAfterLoad)}|pssPeak=${"%.1f".format(pssPeak)}|" +
                        "text=${res.text}",
                )
            }

            val avgRtf = if (totalAudioSec > 0) totalDecodeSec / totalAudioSec else 0.0
            val avgFirstMs = if (firstCharLatencies.isNotEmpty()) firstCharLatencies.average() else 0.0
            Log.i(
                TAG,
                "BENCH_SUMMARY|model=${spec.id}|name=${spec.displayName}|loadMs=$loadMs|" +
                    "avgRtf=${"%.4f".format(avgRtf)}|avgFirstComputeMs=${"%.1f".format(avgFirstMs)}|" +
                    "pssLoadedMb=${"%.1f".format(pssAfterLoad)}",
            )
        }

        // 2. 4 项原生增强对比实测
        val mainSpec = ModelCatalog.X_ASR_ZH_EN_PUNCT.takeIf { resolver.isModelReady(it) }
            ?: readySpecs.last()

        // 2a. GTCRN 降噪
        val heavyNoiseFile = File(testDir, "sample_heavy_noise.wav").takeIf { it.exists() }
            ?: File(testDir, "sample_noise_01.wav")
        if (heavyNoiseFile.exists() && gtcrnDenoiser.isAvailable) {
            engine.loadModel(mainSpec, pureGraph)
            val noisyPcm = readWav16kMono(heavyNoiseFile)
            val rawNoisy = decodeWavStreaming(engine, PassthroughSpeechDenoiser, null, noisyPcm, "")
            val gtcrnNoisy = decodeWavStreaming(engine, gtcrnDenoiser, null, noisyPcm, "")
            Log.i(
                TAG,
                "ENHANCE_GTCRN|model=${mainSpec.id}|file=${heavyNoiseFile.name}|" +
                    "rawRtf=${"%.4f".format(rawNoisy.rtf)}|gtcrnRtf=${"%.4f".format(gtcrnNoisy.rtf)}|" +
                    "rawText=${rawNoisy.text}|gtcrnText=${gtcrnNoisy.text}",
            )
        }

        // 2b. Silero VAD 人声门控
        val vadGateFile = File(testDir, "sample_vad_gate.wav")
        if (vadGateFile.exists() && sileroVad.isAvailable) {
            engine.loadModel(mainSpec, pureGraph)
            val vadPcm = readWav16kMono(vadGateFile)
            val noVadRes = decodeWavStreaming(engine, PassthroughSpeechDenoiser, null, vadPcm, "")
            val withVadRes = decodeWavStreaming(engine, PassthroughSpeechDenoiser, sileroVad, vadPcm, "")
            Log.i(
                TAG,
                "ENHANCE_VAD|model=${mainSpec.id}|totalFrames=${noVadRes.fedFrames}|" +
                    "vadFedFrames=${withVadRes.fedFrames}|noVadRtf=${"%.4f".format(noVadRes.rtf)}|" +
                    "withVadRtf=${"%.4f".format(withVadRes.rtf)}|noVadText=${noVadRes.text}|withVadText=${withVadRes.text}",
            )
        }

        // 2c. WFST ITN 数字规整
        val numFile = File(testDir, "sample_numbers_01.wav")
        if (numFile.exists() && engine.isItnAvailable) {
            val numPcm = readWav16kMono(numFile)
            engine.loadModel(mainSpec, DecoderGraphOptions(enableItn = false, enableHotwords = false))
            val noItn = decodeWavStreaming(engine, PassthroughSpeechDenoiser, null, numPcm, "")
            engine.loadModel(mainSpec, DecoderGraphOptions(enableItn = true, enableHotwords = false))
            val withItn = decodeWavStreaming(engine, PassthroughSpeechDenoiser, null, numPcm, "")
            Log.i(
                TAG,
                "ENHANCE_ITN|model=${mainSpec.id}|noItnText=${noItn.text}|withItnText=${withItn.text}",
            )
        }

        // 2d. Hotwords 热词偏置
        val hotwordFile = File(testDir, "sample_hotword.wav")
        if (hotwordFile.exists()) {
            val hwPcm = readWav16kMono(hotwordFile)
            for (target in listOf(ModelCatalog.ZIPFORMER_ZH_14M, ModelCatalog.ZIPFORMER_MULTI_ZH_HANS, mainSpec)) {
                if (!resolver.isModelReady(target)) continue
                engine.loadModel(target, DecoderGraphOptions(enableItn = false, enableHotwords = false))
                val noHw = decodeWavStreaming(engine, PassthroughSpeechDenoiser, null, hwPcm, "")
                engine.loadModel(target, DecoderGraphOptions(enableItn = false, enableHotwords = true, hotwordsScore = 3.0f))
                val withHw = decodeWavStreaming(engine, PassthroughSpeechDenoiser, null, hwPcm, "唐人,鸿蒙")
                Log.i(
                    TAG,
                    "ENHANCE_HOTWORD|model=${target.id}|noHwRtf=${"%.4f".format(noHw.rtf)}|" +
                        "withHwRtf=${"%.4f".format(withHw.rtf)}|noHwText=${noHw.text}|withHwText=${withHw.text}",
                )
            }
        }

        engine.release()
        gtcrnDenoiser.release()
        sileroVad.release()
    }

    private data class SingleWavBenchResult(
        val audioSec: Double,
        val decodeSec: Double,
        val rtf: Double,
        val firstLatencyMs: Long?,
        val fedFrames: Int,
        val text: String,
    )

    private suspend fun decodeWavStreaming(
        engine: SherpaStreamingEngine,
        denoiser: StreamingSpeechDenoiser,
        vad: StreamingVoiceDetector?,
        pcmFloats: FloatArray,
        hotwords: String,
    ): SingleWavBenchResult {
        denoiser.reset()
        vad?.reset()
        val audioSec = pcmFloats.size / 16000.0
        val session = engine.createSession(hotwordsText = hotwords, hotwordsScore = 3.0f)
        val startMark = TimeSource.Monotonic.markNow()
        var firstLatencyMs: Long? = null
        var decodeNanos = 0L
        var fedFrames = 0
        val collected = StringBuilder()

        val preRoll = ArrayDeque<Pair<FloatArray, Int>>(2)
        var hangover = 0

        suspend fun pushToRecognizer(samples: FloatArray, rate: Int) {
            if (samples.isEmpty()) return
            fedFrames++
            val snap = session.acceptWaveform(samples, rate)
            decodeNanos += snap.decodeCostNanos
            if (firstLatencyMs == null && snap.text.isNotEmpty()) {
                firstLatencyMs = startMark.elapsedNow().inWholeMilliseconds
            }
            if (snap.isEndpoint && snap.text.isNotEmpty()) {
                hangover = 0
                collected.append(snap.text)
            }
        }

        var offset = 0
        while (offset < pcmFloats.size) {
            val end = minOf(offset + 1600, pcmFloats.size)
            val frame = pcmFloats.copyOfRange(offset, end)
            val denoised = denoiser.denoise(frame, 16000)
            decodeNanos += denoised.costNanos
            val candidate = if (denoised.samples.isNotEmpty()) denoised.samples else frame
            val rate = if (denoised.samples.isNotEmpty()) denoised.sampleRate else 16000

            if (vad == null) {
                if (denoised.samples.isNotEmpty()) {
                    pushToRecognizer(denoised.samples, denoised.sampleRate)
                }
            } else {
                val decision = vad.detect(candidate, rate)
                decodeNanos += decision.costNanos
                if (denoised.samples.isNotEmpty()) {
                    if (decision.isSpeech) {
                        while (preRoll.isNotEmpty()) {
                            val (ps, pr) = preRoll.removeFirst()
                            pushToRecognizer(ps, pr)
                        }
                        hangover = 14
                        pushToRecognizer(denoised.samples, denoised.sampleRate)
                    } else if (hangover > 0) {
                        hangover--
                        pushToRecognizer(denoised.samples, denoised.sampleRate)
                    } else {
                        if (preRoll.size >= 2) preRoll.removeFirst()
                        preRoll.addLast(denoised.samples to denoised.sampleRate)
                    }
                }
            }
            offset = end
        }
        val tailDenoised = denoiser.flush(16000)
        decodeNanos += tailDenoised.costNanos
        if (tailDenoised.samples.isNotEmpty()) {
            pushToRecognizer(tailDenoised.samples, tailDenoised.sampleRate)
        }
        val tail = session.finishInput()
        if (tail.isNotEmpty()) {
            collected.append(tail)
        }
        session.close()

        val decodeSec = decodeNanos / 1_000_000_000.0
        val rtf = if (audioSec > 0) decodeSec / audioSec else 0.0
        return SingleWavBenchResult(
            audioSec = audioSec,
            decodeSec = decodeSec,
            rtf = rtf,
            firstLatencyMs = firstLatencyMs,
            fedFrames = fedFrames,
            text = collected.toString(),
        )
    }

    private fun readWav16kMono(file: File): FloatArray {
        val bytes = file.readBytes()
        var dataOffset = 44
        for (i in 12 until minOf(bytes.size - 8, 256)) {
            if (bytes[i] == 'd'.code.toByte() &&
                bytes[i + 1] == 'a'.code.toByte() &&
                bytes[i + 2] == 't'.code.toByte() &&
                bytes[i + 3] == 'a'.code.toByte()
            ) {
                dataOffset = i + 8
                break
            }
        }
        val pcmBytes = bytes.size - dataOffset
        val sampleCount = pcmBytes / 2
        val shortBuf = ByteBuffer.wrap(bytes, dataOffset, sampleCount * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        return FloatArray(sampleCount) { shortBuf.get(it) / 32768.0f }
    }

    companion object {
        private const val TAG = "ASR_BENCH"
    }
}
