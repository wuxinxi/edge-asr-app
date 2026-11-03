package cn.xxstudy.asr.data.repository

import cn.xxstudy.asr.data.asset.ModelReadinessChecker
import cn.xxstudy.asr.data.monitor.MemoryMonitor
import cn.xxstudy.asr.domain.audio.AudioCaptureSource
import cn.xxstudy.asr.domain.audio.DenoiseFrameResult
import cn.xxstudy.asr.domain.audio.StreamingSpeechDenoiser
import cn.xxstudy.asr.domain.audio.StreamingVoiceDetector
import cn.xxstudy.asr.domain.audio.VadFrameDecision
import cn.xxstudy.asr.domain.engine.DecodeSnapshot
import cn.xxstudy.asr.domain.engine.StreamingAsrEngine
import cn.xxstudy.asr.domain.engine.StreamingSession
import cn.xxstudy.asr.domain.model.AsrModelSpec
import cn.xxstudy.asr.domain.model.DecoderGraphOptions
import cn.xxstudy.asr.domain.model.EnhancementOptions
import cn.xxstudy.asr.domain.model.ModelCatalog
import cn.xxstudy.asr.domain.model.RecognitionEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AsrRepositoryImpl] 全链路增强编排单元测试。
 *
 * 验证 GTCRN 降噪、Silero VAD 前置滑窗与静音跳过门控、WFST ITN 与热词透传逻辑。
 */
class AsrRepositoryImplTest {

    @Test
    fun `neural vad gates out idle frames while preserving pre-roll before speech onset`() = runTest {
        // 5 帧静音 + 2 帧有效人声：前 3 帧应被 VAD 门控拦截，人声出现前保留最近 2 帧 pre-roll 一并送入识别器
        val frames = Array(7) { idx -> FloatArray(1600) { if (idx >= 5) 0.1f else 0.0f } }
        val fakeAudio = object : AudioCaptureSource {
            override val sampleRate: Int = 16000
            override fun captureFrames(): Flow<FloatArray> = flowOf(*frames)
        }

        var denoiseCalls = 0
        val fakeDenoiser = object : StreamingSpeechDenoiser {
            override val isAvailable: Boolean = true
            override suspend fun denoise(samples: FloatArray, sampleRate: Int): DenoiseFrameResult {
                denoiseCalls++
                return DenoiseFrameResult(samples = samples, sampleRate = sampleRate, costNanos = 1_000_000L)
            }

            override suspend fun flush(defaultSampleRate: Int): DenoiseFrameResult {
                return DenoiseFrameResult(samples = FloatArray(0), sampleRate = defaultSampleRate, costNanos = 0L)
            }

            override suspend fun reset() = Unit
            override fun release() = Unit
        }

        val fakeVad = object : StreamingVoiceDetector {
            override val isAvailable: Boolean = true
            override suspend fun detect(samples: FloatArray, sampleRate: Int): VadFrameDecision {
                val speech = samples.firstOrNull { it > 0.05f } != null
                return VadFrameDecision(isSpeech = speech, costNanos = 500_000L)
            }

            override suspend fun reset() = Unit
            override fun release() = Unit
        }

        var acceptedWaveforms = 0
        var receivedHotwords = ""
        val fakeEngine = object : StreamingAsrEngine {
            override var currentModel: AsrModelSpec? = ModelCatalog.X_ASR_ZH_EN_PUNCT
            override var currentGraphOptions: DecoderGraphOptions = DecoderGraphOptions()
            override val isItnAvailable: Boolean = true

            override suspend fun loadModel(spec: AsrModelSpec, graphOptions: DecoderGraphOptions): Long {
                currentModel = spec
                currentGraphOptions = graphOptions
                return 80L
            }

            override suspend fun createSession(hotwordsText: String, hotwordsScore: Float): StreamingSession {
                receivedHotwords = hotwordsText
                return object : StreamingSession {
                    override suspend fun acceptWaveform(samples: FloatArray, sampleRate: Int): DecodeSnapshot {
                        acceptedWaveforms++
                        return DecodeSnapshot(text = "唐人测试128元", isEndpoint = false, decodeCostNanos = 4_000_000L)
                    }

                    override suspend fun finishInput(): String = "唐人测试128元。"
                    override suspend fun close() = Unit
                }
            }

            override fun release() = Unit
        }

        val repo = AsrRepositoryImpl(
            audioSource = fakeAudio,
            engine = fakeEngine,
            readinessChecker = ModelReadinessChecker { true },
            memoryMonitor = MemoryMonitor { 180f },
            speechDenoiser = fakeDenoiser,
            voiceDetector = fakeVad,
        )

        val options = EnhancementOptions(
            enableNeuralDenoise = true,
            enableNeuralVad = true,
            enableItn = true,
            enableHotwords = true,
            hotwordsText = "唐人",
        )
        val events = repo.startRecognitionSession(options).toList()

        assertEquals(7, denoiseCalls)
        // 7 帧里前 3 帧纯静音被门控跳过，仅第 4、5 帧（2 帧 pre-roll）+ 第 6、7 帧（人声）共 4 帧送入后端解码器
        assertEquals(4, acceptedWaveforms)
        assertEquals("唐人", receivedHotwords)
        assertTrue(fakeEngine.currentGraphOptions.enableItn)
        assertTrue(fakeEngine.currentGraphOptions.enableHotwords)

        val finals = events.filterIsInstance<RecognitionEvent.Final>()
        assertEquals(1, finals.size)
        assertEquals(listOf("GTCRN", "VAD", "ITN", "热词"), finals.first().appliedEnhancements)
    }
}
