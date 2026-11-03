package cn.xxstudy.asr.ui.asr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.xxstudy.asr.domain.model.AsrModelSpec
import cn.xxstudy.asr.domain.model.EnhancementAvailability
import cn.xxstudy.asr.domain.model.EnhancementOptions
import cn.xxstudy.asr.domain.model.ModelAvailability
import cn.xxstudy.asr.domain.model.ModelCatalog
import cn.xxstudy.asr.domain.model.PerformanceStats
import cn.xxstudy.asr.ui.theme.EdgeasrappTheme
import java.util.Locale

/**
 * 端侧流式语音识别有状态路由组件（Stateful Route Composable）。
 */
@Composable
fun AsrRoute(
    modifier: Modifier = Modifier,
    viewModel: AsrViewModel = viewModel(factory = AsrViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 仅当页面真正退至后台（锁屏或切出应用）时自动停录；横竖屏旋转等配置变更时保持收音与模型生命周期不断流
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        val isChangingConfig = context.findActivity()?.isChangingConfigurations == true
        if (uiState.isRecording && !isChangingConfig) {
            viewModel.onAction(AsrUiAction.StopRecording)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.onAction(AsrUiAction.StartRecording)
        } else {
            viewModel.onAction(
                AsrUiAction.ReportPermissionDenied("需要授予麦克风权限才能进行离线流式语音识别"),
            )
        }
    }

    AsrScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onRecordToggleClick = {
            if (uiState.isRecording) {
                viewModel.onAction(AsrUiAction.StopRecording)
            } else {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    viewModel.onAction(AsrUiAction.StartRecording)
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * 端侧流式语音识别主界面无状态组件（Stateless Screen Composable）。
 */
@Composable
fun AsrScreen(
    uiState: AsrUiState,
    onAction: (AsrUiAction) -> Unit,
    onRecordToggleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (isLandscape) {
        LandscapeAsrLayout(
            uiState = uiState,
            onAction = onAction,
            onRecordToggleClick = onRecordToggleClick,
            modifier = modifier,
        )
    } else {
        PortraitAsrLayout(
            uiState = uiState,
            onAction = onAction,
            onRecordToggleClick = onRecordToggleClick,
            modifier = modifier,
        )
    }
}

/**
 * 竖屏单列标准布局。
 */
@Composable
private fun PortraitAsrLayout(
    uiState: AsrUiState,
    onAction: (AsrUiAction) -> Unit,
    onRecordToggleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HeaderSection(isRecording = uiState.isRecording)

        AnimatedVisibility(visible = uiState.errorMessage != null) {
            ErrorBanner(
                message = uiState.errorMessage.orEmpty(),
                onDismiss = { onAction(AsrUiAction.DismissError) },
            )
        }

        ModelSelectorSection(
            models = uiState.models,
            selectedModel = uiState.selectedModel,
            isLoadingModel = uiState.isLoadingModel,
            onSelectModel = { onAction(AsrUiAction.SelectModel(it)) },
            onRefresh = { onAction(AsrUiAction.RefreshModels) },
        )

        TelemetryDashboard(stats = uiState.stats)

        EnhancementControlSection(
            options = uiState.enhancements,
            availability = uiState.enhancementAvailability,
            onAction = onAction,
        )

        TranscriptFeedSection(
            segments = uiState.segments,
            livePartialText = uiState.livePartialText,
            isRecording = uiState.isRecording,
            modifier = Modifier.weight(1f),
        )

        BottomControlBar(
            isRecording = uiState.isRecording,
            isLoadingModel = uiState.isLoadingModel,
            canStartRecording = uiState.canStartRecording,
            hasSegments = uiState.segments.isNotEmpty(),
            onRecordToggleClick = onRecordToggleClick,
            onClearClick = { onAction(AsrUiAction.ClearTranscripts) },
        )
    }
}

/**
 * 横屏双栏自适应布局：左栏放置模型选择、实时监控与增强插件箱（支持纵向滚动），右栏完整保留转写信息流与底部主控栏。
 */
@Composable
private fun LandscapeAsrLayout(
    uiState: AsrUiState,
    onAction: (AsrUiAction) -> Unit,
    onRecordToggleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.46f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeaderSection(isRecording = uiState.isRecording)

            AnimatedVisibility(visible = uiState.errorMessage != null) {
                ErrorBanner(
                    message = uiState.errorMessage.orEmpty(),
                    onDismiss = { onAction(AsrUiAction.DismissError) },
                )
            }

            ModelSelectorSection(
                models = uiState.models,
                selectedModel = uiState.selectedModel,
                isLoadingModel = uiState.isLoadingModel,
                onSelectModel = { onAction(AsrUiAction.SelectModel(it)) },
                onRefresh = { onAction(AsrUiAction.RefreshModels) },
            )

            TelemetryDashboard(stats = uiState.stats)

            EnhancementControlSection(
                options = uiState.enhancements,
                availability = uiState.enhancementAvailability,
                onAction = onAction,
            )
        }

        Column(
            modifier = Modifier
                .weight(0.54f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TranscriptFeedSection(
                segments = uiState.segments,
                livePartialText = uiState.livePartialText,
                isRecording = uiState.isRecording,
                modifier = Modifier.weight(1f),
            )

            BottomControlBar(
                isRecording = uiState.isRecording,
                isLoadingModel = uiState.isLoadingModel,
                canStartRecording = uiState.canStartRecording,
                hasSegments = uiState.segments.isNotEmpty(),
                onRecordToggleClick = onRecordToggleClick,
                onClearClick = { onAction(AsrUiAction.ClearTranscripts) },
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun HeaderSection(isRecording: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "端侧流式语音识别横评",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "sherpa-onnx · <228MB 流式模型 + 全链路原生增强",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(
            shape = RoundedCornerShape(50),
            color = if (isRecording) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isRecording) {
                    PulsingDot()
                    Text(
                        text = "实时收音中",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    Text(
                        text = "离线 · 零网络权限",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "recordingPulse")
    val alpha by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(color = MaterialTheme.colorScheme.error, shape = CircleShape),
    )
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        }
    }
}

@Composable
private fun ModelSelectorSection(
    models: List<ModelAvailability>,
    selectedModel: AsrModelSpec,
    isLoadingModel: Boolean,
    onSelectModel: (AsrModelSpec) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(
                items = models,
                key = { it.spec.id },
            ) { item ->
                val isSelected = item.spec.id == selectedModel.id
                ModelOptionCard(
                    availability = item,
                    isSelected = isSelected,
                    isLoading = isSelected && isLoadingModel,
                    onClick = { onSelectModel(item.spec) },
                )
            }
        }

        val currentAvailability = models.firstOrNull { it.spec.id == selectedModel.id }
        if (currentAvailability != null && !currentAvailability.isDownloaded) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "模型资产未安装（${selectedModel.displayName}）",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "刷新状态",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(onClick = onRefresh)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = currentAvailability.downloadCommand,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelOptionCard(
    availability: ModelAvailability,
    isSelected: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    val spec = availability.spec
    val archTag = when (spec) {
        is AsrModelSpec.Transducer -> "Transducer"
        is AsrModelSpec.Zipformer2Ctc -> "CTC"
    }

    val border = if (isSelected) {
        BorderStroke(1.8.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    OutlinedCard(
        modifier = Modifier
            .width(192.dp)
            .clickable(onClick = onClick),
        border = border,
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = spec.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$archTag · ~${spec.approxSizeMb}MB",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (availability.isDownloaded) "已就绪" else "未下载",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (availability.isDownloaded) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Text(
                text = spec.description,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TelemetryDashboard(stats: PerformanceStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricTile(
            label = "模型加载",
            value = if (stats.modelLoadMs > 0) "${stats.modelLoadMs} ms" else "--",
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "首字延迟",
            value = stats.firstTextLatencyMs?.let { "$it ms" } ?: "--",
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "实时率 RTF",
            value = if (stats.audioSeconds > 0f) {
                String.format(Locale.US, "%.3f", stats.rtf)
            } else {
                "--"
            },
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "内存 PSS",
            value = if (stats.pssMb > 0f) {
                String.format(Locale.US, "%.0f MB", stats.pssMb)
            } else {
                "--"
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/**
 * 端侧原生增强插件箱控制面板（涵盖 `GTCRN` 降噪、`Silero VAD` 人声门控、`WFST ITN` 数字规整与 `Hotwords` 热词偏置）。
 */
@Composable
private fun EnhancementControlSection(
    options: EnhancementOptions,
    availability: EnhancementAvailability,
    onAction: (AsrUiAction) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "端侧原生增强插件箱（总增量 0.73MB）",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "点击独立开关对比",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "chip_gtcrn") {
                    FilterChip(
                        selected = options.enableNeuralDenoise && availability.isDenoiseAvailable,
                        enabled = availability.isDenoiseAvailable,
                        onClick = {
                            onAction(AsrUiAction.SetNeuralDenoise(!options.enableNeuralDenoise))
                        },
                        label = { Text("GTCRN 降噪") },
                    )
                }
                item(key = "chip_vad") {
                    FilterChip(
                        selected = options.enableNeuralVad && availability.isVadAvailable,
                        enabled = availability.isVadAvailable,
                        onClick = {
                            onAction(AsrUiAction.SetNeuralVad(!options.enableNeuralVad))
                        },
                        label = { Text("Silero VAD") },
                    )
                }
                item(key = "chip_itn") {
                    FilterChip(
                        selected = options.enableItn && availability.isItnAvailable,
                        enabled = availability.isItnAvailable,
                        onClick = {
                            onAction(AsrUiAction.SetItn(!options.enableItn))
                        },
                        label = { Text("WFST 数字规整") },
                    )
                }
                item(key = "chip_hotwords") {
                    FilterChip(
                        selected = options.enableHotwords,
                        onClick = {
                            onAction(AsrUiAction.SetHotwords(!options.enableHotwords))
                        },
                        label = { Text("热词偏置") },
                    )
                }
            }

            AnimatedVisibility(visible = options.enableHotwords) {
                OutlinedTextField(
                    value = options.hotwordsText,
                    onValueChange = { onAction(AsrUiAction.UpdateHotwordsText(it)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    label = {
                        Text("声学束搜索热词（逗号分隔，如：唐人,鸿蒙,API）")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun TranscriptFeedSection(
    segments: List<TranscriptSegmentUi>,
    livePartialText: String,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(segments.size, livePartialText.isNotEmpty()) {
        val totalCount = segments.size + if (isRecording) 1 else 0
        if (totalCount > 0) {
            listState.animateScrollToItem(totalCount - 1)
        }
    }

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        if (segments.isEmpty() && !isRecording) {
            EmptyTranscriptGuide()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    items = segments,
                    key = { it.id },
                ) { segment ->
                    FinalSegmentCard(segment = segment)
                }

                if (isRecording) {
                    item(key = "live_partial_item") {
                        LivePartialCard(partialText = livePartialText)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTranscriptGuide() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "点击下方「开始流式识别」即可离线实时转写",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "推荐实测对比话术（双语标点 · 数字规整 · 热词纠偏）：\n" +
                "1. “帮我打开 App 查看今天的 API 调用日志，确认延迟是否正常”\n" +
                "2. “订单编号幺三八零零，金额一百二十八点五元”\n" +
                "3. 开启上方「热词偏置」输入专有名词，对比小模型同音字纠偏效果",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FinalSegmentCard(segment: TranscriptSegmentUi) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "#${segment.id} · ${segment.modelName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (segment.appliedEnhancements.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = segment.appliedEnhancements.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Text(
                text = segment.text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun LivePartialCard(partialText: String) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "流式解码中...",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = partialText.ifEmpty { "请说话，正在监听 16kHz 麦克风音频流..." },
                style = MaterialTheme.typography.bodyLarge,
                color = if (partialText.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun BottomControlBar(
    isRecording: Boolean,
    isLoadingModel: Boolean,
    canStartRecording: Boolean,
    hasSegments: Boolean,
    onRecordToggleClick: () -> Unit,
    onClearClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onClearClick,
            enabled = hasSegments,
            modifier = Modifier.height(48.dp),
        ) {
            Text("清空")
        }

        val buttonColors = if (isRecording) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        }

        Button(
            onClick = onRecordToggleClick,
            enabled = isRecording || canStartRecording,
            colors = buttonColors,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
        ) {
            val buttonLabel = when {
                isRecording -> "停止识别并定稿"
                isLoadingModel -> "正在加载模型权重..."
                !canStartRecording -> "请先运行脚本下载模型"
                else -> "开始流式识别"
            }
            Text(
                text = buttonLabel,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun AsrScreenPreview() {
    EdgeasrappTheme {
        AsrScreen(
            uiState = AsrUiState(
                models = ModelCatalog.all.map { spec ->
                    ModelAvailability(spec = spec, isDownloaded = true)
                },
                selectedModel = ModelCatalog.X_ASR_ZH_EN_PUNCT,
                isModelLoaded = true,
                isRecording = true,
                livePartialText = "轻量级流式语音识别模型在安卓低配手机上",
                segments = listOf(
                    TranscriptSegmentUi(
                        id = 1L,
                        modelName = "X-ASR 双语标点",
                        text = "请把这个 PR 合并到 main 分支，然后跑一下 build 测试。",
                        appliedEnhancements = listOf("GTCRN", "VAD", "ITN"),
                    ),
                ),
                stats = PerformanceStats(
                    modelLoadMs = 1320,
                    audioSeconds = 6.4f,
                    decodeSeconds = 0.72f,
                    firstTextLatencyMs = 210,
                    pssMb = 346f,
                ),
            ),
            onAction = {},
            onRecordToggleClick = {},
        )
    }
}
