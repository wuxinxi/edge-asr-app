package cn.xxstudy.asr.ui.asr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.xxstudy.asr.EdgeAsrApplication
import cn.xxstudy.asr.domain.model.AsrModelSpec
import cn.xxstudy.asr.domain.model.EnhancementOptions
import cn.xxstudy.asr.domain.model.PerformanceStats
import cn.xxstudy.asr.domain.model.RecognitionEvent
import cn.xxstudy.asr.domain.repository.AsrRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 端侧流式语音识别展示层 ViewModel（MVVM 架构核心状态持有者）。
 *
 * @property repository 端侧语音识别领域仓库契约
 */
class AsrViewModel(
    private val repository: AsrRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AsrUiState())

    /** 对外暴露的只读界面状态流（观察者模式 Observer Pattern）。 */
    val uiState: StateFlow<AsrUiState> = _uiState.asStateFlow()

    /** 当前活跃的流式录音收集任务句柄。 */
    private var recordingJob: Job? = null

    /** 当前活跃的模型切换加载任务句柄。 */
    private var modelLoadJob: Job? = null

    /** 转写历史条目自增主键计数器。 */
    private var nextSegmentId: Long = 1L

    init {
        initializeCatalogAndPreload()
    }

    /**
     * 统一接收并分发界面层上送的用户交互动作。
     */
    fun onAction(action: AsrUiAction) {
        when (action) {
            is AsrUiAction.SelectModel -> handleSelectModel(action.spec)
            AsrUiAction.StartRecording -> handleStartRecording()
            AsrUiAction.StopRecording -> handleStopRecording()
            is AsrUiAction.SetNeuralDenoise -> updateEnhancements(restartIfRecording = true) {
                it.copy(enableNeuralDenoise = action.enabled)
            }

            is AsrUiAction.SetNeuralVad -> updateEnhancements(restartIfRecording = true) {
                it.copy(enableNeuralVad = action.enabled)
            }

            is AsrUiAction.SetItn -> updateEnhancements(restartIfRecording = true) {
                it.copy(enableItn = action.enabled)
            }

            is AsrUiAction.SetHotwords -> updateEnhancements(restartIfRecording = true) {
                it.copy(enableHotwords = action.enabled)
            }

            is AsrUiAction.UpdateHotwordsText -> updateEnhancements(restartIfRecording = false) {
                it.copy(hotwordsText = action.text)
            }

            AsrUiAction.ClearTranscripts -> handleClearTranscripts()
            AsrUiAction.RefreshModels -> initializeCatalogAndPreload()
            AsrUiAction.DismissError -> _uiState.update { it.copy(errorMessage = null) }
            is AsrUiAction.ReportPermissionDenied -> _uiState.update {
                it.copy(errorMessage = action.message)
            }
        }
    }

    /**
     * 扫描本地模型与原生增强插件可用性，并自动预加载默认或首个已就绪模型。
     */
    private fun initializeCatalogAndPreload() {
        viewModelScope.launch {
            val availabilities = repository.queryModelAvailabilities()
            val pluginAvailability = repository.enhancementAvailability
            val currentSpec = _uiState.value.selectedModel
            val preferredAvailability = availabilities.firstOrNull {
                it.spec.id == currentSpec.id && it.isDownloaded
            } ?: availabilities.firstOrNull { it.isDownloaded }
                ?: availabilities.firstOrNull()

            val targetSpec = preferredAvailability?.spec ?: currentSpec
            _uiState.update { state ->
                state.copy(
                    models = availabilities,
                    selectedModel = targetSpec,
                    enhancementAvailability = pluginAvailability,
                    errorMessage = null,
                )
            }

            if (preferredAvailability?.isDownloaded == true &&
                repository.activeModel?.id != targetSpec.id
            ) {
                loadModelAsync(targetSpec)
            }
        }
    }

    private fun handleSelectModel(spec: AsrModelSpec) {
        if (_uiState.value.isLoadingModel && _uiState.value.selectedModel.id == spec.id) {
            return
        }
        if (_uiState.value.isRecording) {
            handleStopRecording()
        }

        val availability = _uiState.value.models.firstOrNull { it.spec.id == spec.id }
        val downloaded = availability?.isDownloaded == true

        if (!downloaded) {
            modelLoadJob?.cancel()
            _uiState.update { state ->
                state.copy(
                    selectedModel = spec,
                    isModelLoaded = false,
                    isLoadingModel = false,
                    errorMessage = null,
                )
            }
            return
        }

        if (repository.activeModel?.id == spec.id && _uiState.value.isModelLoaded) {
            _uiState.update { it.copy(selectedModel = spec, errorMessage = null) }
            return
        }

        loadModelAsync(spec)
    }

    private fun loadModelAsync(spec: AsrModelSpec) {
        modelLoadJob?.cancel()
        modelLoadJob = viewModelScope.launch {
            val currentOptions = _uiState.value.enhancements
            _uiState.update { state ->
                state.copy(
                    selectedModel = spec,
                    isModelLoaded = false,
                    isLoadingModel = true,
                    errorMessage = null,
                )
            }

            val result = repository.switchModel(spec, currentOptions)
            result.fold(
                onSuccess = { loadCostMs ->
                    _uiState.update { state ->
                        state.copy(
                            isModelLoaded = true,
                            isLoadingModel = false,
                            stats = PerformanceStats(
                                modelLoadMs = loadCostMs,
                                pssMb = state.stats.pssMb,
                            ),
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { state ->
                        state.copy(
                            isModelLoaded = false,
                            isLoadingModel = false,
                            errorMessage = error.message ?: "模型 [${spec.displayName}] 加载失败",
                        )
                    }
                },
            )
        }
    }

    private fun handleStartRecording() {
        val snapshot = _uiState.value
        if (snapshot.isRecording || snapshot.isLoadingModel) return

        if (!snapshot.isSelectedModelDownloaded) {
            _uiState.update {
                it.copy(
                    errorMessage = "当前模型尚未下载，请先在项目根目录执行: bash scripts/download-models.sh ${snapshot.selectedModel.id}",
                )
            }
            return
        }

        recordingJob?.cancel()
        recordingJob = viewModelScope.launch {
            val targetSpec = _uiState.value.selectedModel
            val activeOptions = _uiState.value.enhancements
            if (repository.activeModel?.id != targetSpec.id || !_uiState.value.isModelLoaded) {
                _uiState.update { it.copy(isLoadingModel = true, errorMessage = null) }
                val loadResult = repository.switchModel(targetSpec, activeOptions)
                val loadMs = loadResult.getOrElse { error ->
                    _uiState.update {
                        it.copy(
                            isLoadingModel = false,
                            isModelLoaded = false,
                            errorMessage = error.message ?: "模型加载失败",
                        )
                    }
                    return@launch
                }
                _uiState.update { state ->
                    state.copy(
                        isLoadingModel = false,
                        isModelLoaded = true,
                        stats = PerformanceStats(modelLoadMs = loadMs, pssMb = state.stats.pssMb),
                    )
                }
            }

            _uiState.update { state ->
                state.copy(
                    isRecording = true,
                    livePartialText = "",
                    errorMessage = null,
                    stats = PerformanceStats(
                        modelLoadMs = state.stats.modelLoadMs,
                        pssMb = state.stats.pssMb,
                    ),
                )
            }

            try {
                repository.startRecognitionSession(options = activeOptions)
                    .collect { event ->
                        onRecognitionEvent(event)
                    }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update { state ->
                    state.copy(
                        isRecording = false,
                        errorMessage = t.message ?: "流式语音识别异常中断",
                    )
                }
            } finally {
                _uiState.update { it.copy(isRecording = false) }
            }
        }
    }

    private fun onRecognitionEvent(event: RecognitionEvent) {
        when (event) {
            is RecognitionEvent.Partial -> {
                _uiState.update { it.copy(livePartialText = event.text) }
            }

            is RecognitionEvent.Final -> {
                appendFinalSegment(
                    text = event.text,
                    appliedEnhancements = event.appliedEnhancements,
                )
            }

            is RecognitionEvent.Stats -> {
                _uiState.update { it.copy(stats = event.stats) }
            }
        }
    }

    private fun handleStopRecording() {
        val stateSnapshot = _uiState.value
        val pendingPartial = stateSnapshot.livePartialText.trim()
        val activeTags = computeActiveTags(
            options = stateSnapshot.enhancements,
            availability = stateSnapshot.enhancementAvailability,
        )

        recordingJob?.cancel()
        recordingJob = null

        if (pendingPartial.isNotEmpty()) {
            appendFinalSegment(
                text = pendingPartial,
                appliedEnhancements = activeTags,
            )
        }

        _uiState.update { state ->
            state.copy(
                isRecording = false,
                livePartialText = "",
            )
        }
    }

    private fun updateEnhancements(
        restartIfRecording: Boolean,
        transform: (EnhancementOptions) -> EnhancementOptions,
    ) {
        val current = _uiState.value.enhancements
        val updated = transform(current)
        if (updated == current) return

        val shouldRestart = restartIfRecording && _uiState.value.isRecording
        if (shouldRestart) {
            handleStopRecording()
        }
        _uiState.update { it.copy(enhancements = updated) }
        if (shouldRestart) {
            handleStartRecording()
        }
    }

    private fun computeActiveTags(
        options: EnhancementOptions,
        availability: cn.xxstudy.asr.domain.model.EnhancementAvailability,
    ): List<String> = buildList {
        if (options.enableNeuralDenoise && availability.isDenoiseAvailable) add("GTCRN")
        if (options.enableNeuralVad && availability.isVadAvailable) add("VAD")
        if (options.enableItn && availability.isItnAvailable) add("ITN")
        if (options.enableHotwords && options.hotwordsText.isNotBlank()) add("热词")
    }

    private fun handleClearTranscripts() {
        _uiState.update { state ->
            state.copy(
                segments = emptyList(),
                livePartialText = if (state.isRecording) state.livePartialText else "",
            )
        }
    }

    private fun appendFinalSegment(text: String, appliedEnhancements: List<String>) {
        val segmentId = nextSegmentId++
        val activeModelName = _uiState.value.selectedModel.displayName
        val newSegment = TranscriptSegmentUi(
            id = segmentId,
            modelName = activeModelName,
            text = text,
            appliedEnhancements = appliedEnhancements,
        )
        _uiState.update { state ->
            state.copy(
                segments = state.segments + newSegment,
                livePartialText = "",
            )
        }
    }

    override fun onCleared() {
        recordingJob?.cancel()
        modelLoadJob?.cancel()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as EdgeAsrApplication
                AsrViewModel(repository = app.container.asrRepository)
            }
        }
    }
}
