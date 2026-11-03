package cn.xxstudy.asr.data.monitor

import android.os.Debug

/**
 * 进程物理内存占用探针接口（接口隔离原则 ISP / 依赖倒置原则 DIP）。
 *
 * 抽象内存采集行为，避免仓库层直接耦合 `android.os.Debug` 静态调用，便于在 JVM 单元测试中注入模拟值。
 */
fun interface MemoryMonitor {

    /**
     * 获取当前应用进程的实际物理内存占用 PSS（Proportional Set Size，单位：MB）。
     *
     * 包含 Java/Kotlin 堆内存以及 ONNX Runtime 原生 C++ 层分配的权重与推理张量内存。
     *
     * @return 当前进程 PSS 内存占用（MB）
     */
    fun currentPssMb(): Float
}

/**
 * 基于 Android 系统 [Debug.getPss] 的真实进程内存探针实现。
 */
class AndroidProcessMemoryMonitor : MemoryMonitor {

    /**
     * 读取当前进程真实 PSS（原单位 KB）并换算为 MB。
     *
     * @return 当前进程真实 PSS 内存大小（MB）
     */
    override fun currentPssMb(): Float {
        val pssKb = Debug.getPss()
        return pssKb / KB_PER_MB
    }

    companion object {
        /** 千字节（KB）到兆字节（MB）的换算基数。 */
        private const val KB_PER_MB = 1024f
    }
}
