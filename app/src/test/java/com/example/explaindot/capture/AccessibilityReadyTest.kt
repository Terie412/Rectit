package com.example.explaindot.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「屏幕取图」到底开没开。
 *
 * 这份测试围着真机上踩到的一次误判写：无障碍的**总开关**关着，但白名单里
 * 还留着本服务，界面于是显示「已就绪」，而用户长按圆点根本截不到图。
 *
 * 之所以值得单测，是因为这类 bug 的形态是**少查一个条件** ——
 * 少查条件不会抛异常、不会有日志，只会让界面说谎。而"两个条件缺一不可"
 * 这件事用测试表达出来之后，将来有人觉得总开关那一行多余（它看起来确实啰嗦）
 * 时会先失败。
 */
class AccessibilityReadyTest {

    private val pkg = "com.example.explaindot"
    private val full = "$pkg/$pkg.capture.AccessibilityShotService"
    private val relative = "$pkg/.capture.AccessibilityShotService"

    @Test
    fun `总开关和本服务都开着才算就绪`() {
        assertTrue(accessibilityReady(enabled = 1, services = full, expected = full))
    }

    /**
     * **这条就是真机上那个 bug。**
     *
     * 总开关关掉时系统不会清空白名单，所以"白名单里有它"这件事本身
     * 完全可能发生在总开关关着的时候。
     */
    @Test
    fun `总开关关着时，白名单里有也不算就绪`() {
        assertFalse(
            "总开关关着，实际一个服务都不生效",
            accessibilityReady(enabled = 0, services = full, expected = full)
        )
    }

    @Test
    fun `总开关认不出时按没开处理`() {
        // getInt 读不到会给默认值 0，但万一哪天传进来别的值也别当成就绪
        assertFalse(accessibilityReady(enabled = -1, services = full, expected = full))
        assertFalse(accessibilityReady(enabled = 2, services = full, expected = full))
    }

    @Test
    fun `总开关开着但白名单里没有本服务`() {
        assertFalse(
            accessibilityReady(
                enabled = 1,
                services = "$pkg/$pkg.capture.OtherService",
                expected = full
            )
        )
    }

    @Test
    fun `白名单读不到时为否`() {
        assertFalse(accessibilityReady(enabled = 1, services = null, expected = full))
        assertFalse(accessibilityReady(enabled = 1, services = "", expected = full))
    }

    @Test
    fun `白名单里排在中间或末尾也算`() {
        assertTrue(
            accessibilityReady(
                enabled = 1,
                services = "$pkg/$pkg.capture.OtherService:$full",
                expected = full
            )
        )
        assertTrue(
            accessibilityReady(
                enabled = 1,
                services = "$full:$pkg/$pkg.capture.OtherService",
                expected = full
            )
        )
    }

    /**
     * 白名单里的写法不统一 —— 目标是相对写法时也得认出来。
     *
     * 这个差异是真的存在：无障碍设置页里手动开关过之后，系统存进去的形式
     * 可能和用 adb 写的不同。不展开的话同一个服务会被认成两个。
     */
    @Test
    fun `相对写法和全类名互相认得`() {
        assertTrue(
            "白名单里是相对写法，目标是全类名",
            accessibilityReady(enabled = 1, services = relative, expected = full)
        )
        assertTrue(
            "反过来也要认",
            accessibilityReady(enabled = 1, services = full, expected = relative)
        )
    }

    @Test
    fun `畸形条目不会让判断崩，也不会误判成开了`() {
        // 真实的白名单里完全可能出现这种残渣（服务被卸载、包名变了之后留下的）
        assertFalse(accessibilityReady(enabled = 1, services = "::", expected = full))
        assertFalse(accessibilityReady(enabled = 1, services = "只有包名没有斜杠", expected = full))
        assertFalse(accessibilityReady(enabled = 1, services = "$pkg/", expected = full))
        assertFalse(accessibilityReady(enabled = 1, services = "/$pkg.capture.X", expected = full))
    }

    @Test
    fun `前导空白不算差别`() {
        // setting 是文本，手工写进去的条目带个空格很正常
        assertTrue(accessibilityReady(enabled = 1, services = " $full ", expected = full))
    }
}
