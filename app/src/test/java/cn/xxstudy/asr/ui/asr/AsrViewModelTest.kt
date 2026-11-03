package cn.xxstudy.asr.ui.asr

import cn.xxstudy.asr.domain.model.AsrModelSpec
import cn.xxstudy.asr.domain.model.EnhancementAvailability
import cn.xxstudy.asr.domain.model.EnhancementOptions
import cn.xxstudy.asr.domain.model.ModelAvailability
import cn.xxstudy.asr.domain.model.ModelCatalog
import cn.xxstudy.asr.domain.model.PerformanceStats
import cn.xxstudy.asr.domain.model.RecognitionEvent
import cn.xxstudy.asr.domain.repository.AsrRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [AsrViewModel] 单向数据流与协程状态机单元测试。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AsrViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init preloads default model when downloaded and syncs enhancement availability`() =
        runTest(testDispatcher) {
            val fakeRepo = FakeAsrRepository(downloadedIds = setOf("zipformer", "x_asr_zh_en"))
            val viewModel = AsrViewModel(repository = fakeRepo)

            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("x_asr_zh_en", state.selectedModel.id)
            assertTrue(state.isModelLoaded)
            assertTrue(state.enhancementAvailability.isDenoiseAvailable)
            assertTrue(state.enhancementAvailability.isVadAvailable)
            assertTrue(state.enhancementAvailability.isItnAvailable)
            assertEquals(120L, state.stats.modelLoadMs)
        }

    @Test
    fun `recording collects streaming events and archives trailing partial with enhancement tags`() =
        runTest(testDispatcher) {
            val fakeRepo = FakeAsrRepository(downloadedIds = setOf("x_asr_zh_en"))
            val viewModel = AsrViewModel(repository = fakeRepo)
            advanceUntilIdle()

            viewModel.onAction(AsrUiAction.StartRecording)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isRecording)

            fakeRepo.emitEvent(
                RecognitionEvent.Final(
                    text = "帮我打开 App 查看今天的 API 调用日志。",
                    appliedEnhancements = listOf("GTCRN", "VAD", "ITN"),
                ),
            )
            fakeRepo.emitEvent(
                RecognitionEvent.Stats(
                    PerformanceStats(
                        modelLoadMs = 120L,
                        audioSeconds = 2.0f,
                        decodeSeconds = 0.24f,
                        firstTextLatencyMs = 180L,
                        pssMb = 210f,
                    ),
                ),
            )
            fakeRepo.emitEvent(RecognitionEvent.Partial("订单编号13800"))
            advanceUntilIdle()

            assertEquals("订单编号13800", viewModel.uiState.value.livePartialText)

            viewModel.onAction(AsrUiAction.StopRecording)
            advanceUntilIdle()

            val finalState = viewModel.uiState.value
            assertFalse(finalState.isRecording)
            assertEquals("", finalState.livePartialText)
            assertEquals(2, finalState.segments.size)
            assertEquals("订单编号13800", finalState.segments[1].text)
            assertEquals(listOf("GTCRN", "VAD", "ITN"), finalState.segments[1].appliedEnhancements)
        }

    @Test
    fun `toggling hotwords and editing text updates session enhancement options`() =
        runTest(testDispatcher) {
            val fakeRepo = FakeAsrRepository(downloadedIds = setOf("x_asr_zh_en"))
            val viewModel = AsrViewModel(repository = fakeRepo)
            advanceUntilIdle()

            viewModel.onAction(AsrUiAction.UpdateHotwordsText("唐人,鸿蒙"))
            viewModel.onAction(AsrUiAction.StartRecording)
            advanceUntilIdle()
            assertFalse(fakeRepo.lastOptions.enableHotwords)

            viewModel.onAction(AsrUiAction.SetHotwords(true))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isRecording)
            assertTrue(fakeRepo.lastOptions.enableHotwords)
            assertEquals("唐人,鸿蒙", fakeRepo.lastOptions.hotwordsText)
        }

    private class FakeAsrRepository(
        private val downloadedIds: Set<String>,
        override val enhancementAvailability: EnhancementAvailability = EnhancementAvailability(),
    ) : AsrRepository {

        private val eventStream = MutableSharedFlow<RecognitionEvent>(extraBufferCapacity = 16)

        var lastOptions: EnhancementOptions = EnhancementOptions()
            private set

        override var activeModel: AsrModelSpec? = null
            private set

        override suspend fun queryModelAvailabilities(): List<ModelAvailability> {
            return ModelCatalog.all.map { spec ->
                ModelAvailability(
                    spec = spec,
                    isDownloaded = spec.id in downloadedIds,
                )
            }
        }

        override suspend fun switchModel(
            spec: AsrModelSpec,
            options: EnhancementOptions,
        ): Result<Long> {
            activeModel = spec
            lastOptions = options
            return Result.success(120L)
        }

        override fun startRecognitionSession(options: EnhancementOptions): Flow<RecognitionEvent> {
            lastOptions = options
            return eventStream
        }

        override fun release() {
            activeModel = null
        }

        suspend fun emitEvent(event: RecognitionEvent) {
            eventStream.emit(event)
        }
    }
}
