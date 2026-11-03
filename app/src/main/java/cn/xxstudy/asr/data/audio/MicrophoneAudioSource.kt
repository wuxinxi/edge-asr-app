package cn.xxstudy.asr.data.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import cn.xxstudy.asr.domain.audio.AudioCaptureSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * 基于 Android 原生 [AudioRecord] 的麦克风流式音频采集实现。
 *
 * 采用 `VOICE_RECOGNITION` 音频源以获得针对语音识别优化的底噪控制（避免音乐录音模式下的激进自动增益失真），
 * 每帧稳定输出约 100ms（1600 个采样点）的归一化单声道浮点波形，兼顾流式首字响应速度与协程调度开销。
 *
 * @property sampleRate 采集采样率，默认 16000Hz
 * @property frameSamples 单次向下游发射的样本点数，默认 1600 点（对应 16kHz 下 100ms 窗口）
 */
class MicrophoneAudioSource(
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val frameSamples: Int = DEFAULT_FRAME_SAMPLES,
) : AudioCaptureSource {

    /**
     * 启动麦克风硬件采集并持续发射归一化 PCM 浮点帧。
     *
     * 调用本方法前必须已由界面层完成 `android.permission.RECORD_AUDIO` 运行时权限授权校验。
     * 当外部收集协程取消时，`finally` 代码块保证立即停止并释放 [AudioRecord] 硬件句柄。
     *
     * @return 运行在 [Dispatchers.IO] 上的归一化浮点音频帧冷流
     * @throws IllegalStateException 当设备不支持目标采样率、权限未授予或麦克风被其他应用独占时抛出
     */
    @SuppressLint("MissingPermission")
    override fun captureFrames(): Flow<FloatArray> = flow {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferBytes > 0) {
            "当前设备不支持 ${sampleRate}Hz 单声道 16-bit PCM 录音（错误码: $minBufferBytes）"
        }

        // 环形缓冲区取最小缓冲的 2 倍且不低于两帧大小，防止低配机 CPU 瞬时繁忙时丢帧
        val bufferSizeInBytes = maxOf(minBufferBytes * 2, frameSamples * BYTES_PER_SHORT * 2)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeInBytes,
        )

        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            "麦克风初始化失败，请确认已授予录音权限且未被其他应用占用"
        }

        val pcmBuffer = ShortArray(frameSamples)
        try {
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "麦克风未能进入录音状态，可能被系统通话或其他录音应用占用"
            }

            while (currentCoroutineContext().isActive) {
                val readCount = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                if (readCount > 0) {
                    // 每次发射独立分配的 FloatArray，防止下游异步消费时产生共享数组覆写竞态
                    val floatFrame = FloatArray(readCount) { index ->
                        pcmBuffer[index] / PCM16_NORMALIZATION_DIVISOR
                    }
                    emit(floatFrame)
                } else if (readCount < 0) {
                    error("读取麦克风音频数据失败（错误码: $readCount）")
                }
            }
        } finally {
            runCatching {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            }
            audioRecord.release()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** 默认 16kHz 语音识别标准采样率。 */
        const val DEFAULT_SAMPLE_RATE = 16000

        /** 默认单帧采样点数：1600 点 = 100ms 语音切片。 */
        const val DEFAULT_FRAME_SAMPLES = 1600

        /** 16-bit 有符号短整型占用的字节数。 */
        private const val BYTES_PER_SHORT = 2

        /** 16-bit PCM 转 `[-1.0f, 1.0f]` 浮点数的归一化除数。 */
        private const val PCM16_NORMALIZATION_DIVISOR = 32768.0f
    }
}
