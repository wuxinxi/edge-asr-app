package cn.xxstudy.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android 设备端基础集成测试。
 *
 * 验证应用包名与全局手动依赖注入容器 [EdgeAsrApplication.container] 初始化完整性。
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    /**
     * 校验目标应用上下文包名及全局仓库注入可用性。
     */
    @Test
    fun verifyAppContextAndContainer() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("cn.xxstudy.asr", appContext.packageName)

        val app = appContext.applicationContext as EdgeAsrApplication
        assertNotNull(app.container.asrRepository)
    }
}
