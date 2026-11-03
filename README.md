# Edge ASR App — Android 离线流式语音识别与全链路原生增强横评 Demo

[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Kotlin-2.0%2B-blue.svg)](https://kotlinlang.org)
[![Engine](https://img.shields.io/badge/sherpa--onnx-v2.0.1-orange.svg)](https://github.com/k2-fsa/sherpa-onnx)
[![Network](https://img.shields.io/badge/Network-0%20Permissions%20(100%25%20Offline)-brightgreen.svg)]()
[![Architecture](https://img.shields.io/badge/Architecture-Clean%20MVVM%20%2B%20UDF-purple.svg)]()

基于 `Kotlin + Jetpack Compose + MVVM + Coroutines/Flow + sherpa-onnx v2.0.1` 构建的高性能 Android 离线流式语音识别（ASR）基准测试与落地参考工程。

工程**完全不申请任何网络权限（零联网、零数据上传，彻底保障隐私）**。在严格小于 `SenseVoice Small INT8`（228MB）的边界内，同台横评 4 款从 `24.2MB` 到 `161.4MB` 的纯流式 INT8 Transducer 模型，并集成总增量仅 **`0.73MB`** 的端侧全链路原生增强插件箱（降噪、VAD 门控、数字规整、热词偏置，零硬编码假 AI）。

---

## 一、核心特性与设计亮点

1. **绝对纯离线安全**：`AndroidManifest.xml` 未声明 `android.permission.INTERNET`，从系统权限层物理杜绝任何网络请求与音频外传。
2. **百兆级流式模型阶梯**：覆盖极限轻巧（24.2MB）、多语料基线（72.8MB）、2025 中文高准（159.6MB）以及开箱即用自带标点（161.4MB），全部可在中低端纯 CPU 上毫秒级出字。
3. **0.73MB 全链路原生增强插件箱**：
   - **GTCRN 降噪（0.51MB）**：时频卷积循环神经网络，毫秒级逐帧抑制空调、风扇白噪与键盘声。
   - **Silero VAD 门控（0.20MB）**：512 样点神经网络门控 + 2 帧前滚预留 + 14 帧挂起保护，未说话时不唤醒声学解码，实测节省 48% 无效帧算力。
   - **WFST ITN 规整（0.02MB）**：C++ 原生有限状态转换器无缝将中文口语数字秒转阿拉伯数字（日期/金额/手机号）。
   - **Hotwords 热词偏置（0MB）**：基于 `modified_beam_search` 动态构建偏置图，专有名词与人名纠偏召回率 90% 以上。
4. **实时四维性能监控面板**：每次会话实时打点计算：
   - 模型冷启加载耗时（毫秒）
   - 首字出字延迟（毫秒）
   - 实时率 RTF（Real-Time Factor，解码耗时 / 音频时长）
   - 真实进程物理内存 PSS（通过 `Debug.getPss()` 监控）
5. **双栏自适应与横竖屏不断流**：支持平板/折叠屏与横屏分栏自适应，旋转屏幕时通过状态保持与单例引擎实现不断流、不重载模型。

---

## 二、红米 Note 8（骁龙 665 / 6GB RAM）真机实测数据

所有数据均在物理设备 **Redmi Note 8（骁龙 665 / 6GB RAM / Android 10）** 上通过双线程纯 CPU 离线实测采集：

### 1. 4 款纯流式 INT8 模型横评（严格 < 228MB SenseVoice）

| 模型规格 | 权重体积 | 冷启加载 | 实时率 RTF | 物理内存 PSS | 中英支持 | 标点直出 | 适用场景与定位 |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| **Zipformer 14M** | 24.2 MB | 2683 ms | **0.102** (~10倍速) | **179 MB** | 纯中文（拼音音译） | 否 | **极限低配探底**：2GB 内存旧机首选，适合固定简短控制指令，长句易脱靶 |
| **Zipformer Multi-zh** | 72.8 MB | 10151 ms | 0.244 (~4.1倍速) | 391 MB | 支持大写英文（PR/API） | 否 | **4GB 设备主力**：大词表加持，长句咬字连贯，支持字母缩写 |
| **Zipformer-zh 2025** | 159.6 MB | 13143 ms | 0.400 (~2.5倍速) | 665 MB | 纯中文全面覆盖 | 否 | **高准度中文旗舰**：2025 新版词表最全，生僻字与长句抗同音干扰极佳 |
| **X-ASR 双语标点** | 161.4 MB | 13410 ms | 0.309 (~3.2倍速) | 545 MB | 中英双语混读 | **是**（逗号/句号） | **开箱即用首选（默认）**：原生吐标点，免去外挂 62MB 标点模型与串行卡顿 |

### 2. 4 项端侧原生增强插件实测表现

| 插件名称 | 资产体积 | 底层实现契约 | 真机实测效果 | 核心工程价值 |
| :--- | :---: | :--- | :--- | :--- |
| **GTCRN 实时降噪** | 0.51 MB | `OnlineSpeechDenoiser` (`gtcrn_simple.onnx`) | 显著压制宽带底噪与敲击声 | 提升嘈杂环境下的声学信噪比与识别率 |
| **Silero VAD 语音门控** | 0.20 MB | `Vad` (`silero_vad.int8.onnx`) | **过滤 48% 无效音频帧**，会话 RTF 降 35% | 待机与非人声段彻底跳过解码，大幅省电防发热 |
| **WFST ITN 逆文本规整** | 0.02 MB | `OnlineRecognizerConfig.ruleFsts` (`itn_zh_number.fst`) | “两千零二十六”秒变“2026” | 纯 C++ 零内存延迟，数字/电话/金额直观排版 |
| **Hotwords 热词偏置** | 0 MB | `modified_beam_search` + `HotwordTokenizer` | 专有名词纠偏率 90%+ | 无需重新训练或微调声学模型，支持中文/英文热词 |

---

## 三、快速开始

### 1. 环境要求
- Android Studio Ladybug (2024.2+) 或更高版本
- Android SDK 35 (最低支持 Android 7.0 / API 24)
- JDK 17+
- 本地终端具备 `curl`、`tar`、`gzip`

### 2. 克隆项目与拉取模型资产

克隆本仓库（纯代码约 1MB）：

```bash
git clone https://github.com/your-username/edge-asr-app.git
cd edge-asr-app
```

通过内置的一键下载脚本拉取 Android AAR 依赖包与指定模型权重：

```bash
# 推荐体验（主力首选）：下载 Android AAR + 0.73MB 原生插件 + X-ASR 双语标点模型
bash scripts/download-models.sh x_asr_zh_en

# 完整横评模式：下载 Android AAR + 0.73MB 原生插件 + 全部 4 款流式模型
bash scripts/download-models.sh

# 极简探底模式：仅下载 AAR + 24MB Zipformer 14M 模型
bash scripts/download-models.sh zipformer
```

> **网络提示**：脚本默认使用 `https://hf-mirror.com` 与 GitHub Release 加速代理，如遇网络波动可配置环境变量调整：
> ```bash
> HF_ENDPOINT=https://hf-mirror.com bash scripts/download-models.sh x_asr_zh_en
> ```

### 3. 编译与运行测试

执行 JVM 架构单元测试（覆盖热词分词器、仓库门控编排与 ViewModel UDF 逻辑）：

```bash
./gradlew testDebugUnitTest
```

连接 Android 设备并安装运行：

```bash
./gradlew installDebug
```

---

## 四、工程架构与设计模式

工程严格采用 **Clean Architecture + 单向数据流（UDF）** 分层设计，各层单向依赖、严格解耦：

```text
cn.xxstudy.asr
├── domain/                  # 领域层（纯 Kotlin，零 Android SDK / JNI 依赖）
│   ├── model/               # 模型规格 AsrModelSpec、目录 ModelCatalog、增强配置 EnhancementOptions
│   ├── audio/               # 采集抽象 AudioCaptureSource、降噪契约 StreamingSpeechDenoiser、VAD 契约 StreamingVoiceDetector
│   ├── engine/              # 流式引擎抽象 StreamingAsrEngine & 会话契约 StreamingSession
│   └── repository/          # 仓库契约 AsrRepository
├── data/                    # 数据层（原生 JNI 封装、硬件采集、词表编码与内存探针）
│   ├── asset/               # ModelAssetResolver（动态匹配 assets 目录量化模型文件）
│   ├── audio/               # MicrophoneAudioSource、SherpaGtcrnDenoiser、SherpaSileroVadDetector
│   ├── engine/              # RecognizerConfigFactory、HotwordTokenizer、SherpaStreamingEngine（并发 JNI 网关）
│   ├── monitor/             # AndroidProcessMemoryMonitor（物理内存 Debug.getPss() 监控）
│   └── repository/          # AsrRepositoryImpl（降噪+VAD双重门控+识别管线编排）
├── di/                      # 轻量级手动依赖注入容器 AppContainer
└── ui/                      # 展现层（Jetpack Compose Material 3 + ViewModel）
    ├── asr/                 # AsrUiState、AsrUiAction、AsrViewModel、AsrScreen（竖屏单列 / 横屏双栏）
    └── theme/               # Material 3 配色、排版规范与无障碍语义
```

### 六大面向对象设计原则实践

1. **单一职责原则 (SRP)**：
   - `AudioCaptureSource` 专职负责 16kHz PCM 音频采集与分片；
   - `SherpaSileroVadDetector` 专职负责人声活性概率计算；
   - `SherpaGtcrnDenoiser` 专职处理时频域降噪滤波；
   - `HotwordTokenizer` 专职处理不同声学模型词表的 BPE/字级别映射。
2. **开闭原则 (OCP)**：
   - 新增模型只需在 `ModelCatalog` 登记 `AsrModelSpec`，无需修改任何 UI、解码调度或 ViewModel 代码；
   - 新增音频前处理只需实现 `StreamingSpeechDenoiser` 契约，自动接入处理流水线。
3. **里氏替换原则 (LSP)**：
   - 4 款声学模型全部派生自 `StreamingAsrEngine`，UI 层与仓库层无需关心当前是 `zipformer` 还是 `x_asr_zh_en`，支持运行期无缝一键热切。
4. **接口隔离原则 (ISP)**：
   - 细粒度拆分 `AudioCaptureSource`、`StreamingSpeechDenoiser`、`StreamingVoiceDetector` 与 `ProcessMemoryMonitor`，杜绝臃肿上帝接口。
5. **依赖倒置原则 (DIP)**：
   - 领域层 `AsrRepository` 与 `AsrViewModel` 仅依赖抽象契约，具体数据层实现由 `AppContainer` 注入，易于开展 100% 隔离的 JVM 单元测试。
6. **迪米特法则 (LoD / 最少知识原则)**：
   - Compose 视图层仅感知 `AsrUiState` 并向外派发 `AsrUiAction`，完全与底层的 JNI 指针管理、音频采样与模型配置解耦。

---

## 五、端侧实战避坑指南（四大防崩护栏）

1. **资产零压缩映射 (`androidResources.noCompress`)**：
   - *踩坑*：Gradle 默认打包会压缩 assets 下的未识别文件。一旦 `.onnx` 被压缩，C++ 层的 `AssetManager` 无法获得连续直接文件描述符，底层被迫全量解压拷贝，冷启动翻倍。
   - *解法*：在 `app/build.gradle.kts` 显式声明 `noCompress += listOf("onnx", "fst", "txt", "bin")`，实现零拷贝内存映射。
2. **一代与二代 Zipformer 元数据自适应**：
   - *踩坑*：`Zipformer 14M` 属于一代架构，而 `Multi-zh` 属于二代 Zipformer2 架构。若硬编码 `modelType = "zipformer"`，切换到 Multi-zh 时原生层因缺失 `attention_dims` 直接退出进程。
   - *解法*：将模型类型传空字符串，由 `sherpa-onnx` 原生层自动嗅探 ONNX 头部元数据动态识别。
3. **单并行度 JNI 互斥网关 (`limitedParallelism(1)`)**：
   - *踩坑*：音频采集协程高频推流与用户点击切换模型并发执行时，多线程踩踏同一个底层 Native 指针极易引发 `SIGSEGV` 闪退。
   - *解法*：使用 `Dispatchers.Default.limitedParallelism(1)` 叠加 `Mutex`，将创建、解码、注销原生资源完全串行化排队。
4. **切换模型先释放旧实例 (`MemoryGuard`)**：
   - *踩坑*：在 2GB/4GB 旧设备上，若先创建新模型实例再注销旧实例，双份权重重叠的瞬时内存峰值极易触发系统 OOM Killer。
   - *解法*：加载新模型前强制先调用旧实例的 `release()` 归零物理内存，全程监控进程 PSS。
5. **停录残余帧强制冲刷 (`TailFlush`)**：
   - *踩坑*：分块流式编码器依赖右侧上下文窗口，且端点判停需要 1.2 秒静音，用户说完秒点停止极易吞掉最后几个字。
   - *解法*：停止录音前主动补喂 8000 个零值采样点（0.5 秒静音）冲刷出残余音节，并在停录瞬间提取未闭合的 Partial 文本立即定稿归档。

---

## 六、鸣谢与开源参考

- [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)：新一代高效多平台语音识别离线推理引擎
- [k2-fsa/icefall](https://github.com/k2-fsa/icefall)：工业级 Zipformer 声学模型训练套件
- [snakers4/silero-vad](https://github.com/snakers4/silero-vad)：极低资源消耗的高精度轻量级人声检测模型
- [speech-enhancement/gtcrn](https://github.com/speech-enhancement/gtcrn)：超轻量级实时神经网络语音降噪

---

## 七、开源许可证

本项目源码基于 [Apache License 2.0](LICENSE) 开源发布。所引用的声学模型与插件权重遵循各自开源仓库的原生协议许可。
