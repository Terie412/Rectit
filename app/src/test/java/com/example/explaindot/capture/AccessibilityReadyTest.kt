package com.example.explaindot.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「屏幕取图」到底能不能用。
 *
 * 这份测试围着真机上**连续踩到的两次误判**写。两次的界面都是错的，而且错的方式不同：
 *
 *   1. `accessibility_enabled=0`，但白名单里还留着本服务 → 只看白名单会得出「已就绪」
 *   2. 两个 setting 都对，系统却从未 bind（`Bound services:{}`）→ 连设置都查不出问题
 *
 * 所以判据被定成**服务实例**：`takeScreenshot` 是实例方法，实例为 null 就是真的取不到图，
 * 系统设置里显示成什么样都不改变这一点。设置只用来解释"为什么没连上"。
 *
 * 这类 bug 的形态是**判据用错了来源** —— 不会抛异常、不会有日志，只会让界面说谎，
 * 所以值得用测试把「连着才算数」这件事钉住。
 */
class AccessibilityReadyTest {

    private val pkg = "com.example.explaindot"
    private val full = "$pkg/$pkg.capture.AccessibilityShotService"
    private val relative = "$pkg/.capture.AccessibilityShotService"

    @Test
    fun `服务连着就是就绪`() {
        val state = accessibilityState(connected = true, enabled = 1, services = full, expected = full)

        assertEquals(A11yState.Ready, state)
        assertTrue(accessibilityReady(connected = true, enabled = 1, services = full, expected = full))
    }

    /**
     * **这条是第二次误判。**
     *
     * 设置全对（总开关 1、白名单里有本服务），但系统从没真正绑定 —— 白名单条目
     * 是直接写 setting 留下的。这时界面只能说"没连上"，不能说"已就绪"。
     */
    @Test
    fun `设置全对但服务没连上，不算就绪`() {
        assertEquals(
            "设置是对的，但服务没绑定 —— 那是另一种状态",
            A11yState.NotConnected,
            accessibilityState(connected = false, enabled = 1, services = full, expected = full)
        )
        assertFalse(
            accessibilityReady(connected = false, enabled = 1, services = full, expected = full)
        )
    }

    /**
     * **这条是第一次误判。**
     *
     * 总开关关掉时系统不会清空白名单，所以"白名单里有它"完全可能发生在
     * 总开关关着的时候。
     */
    @Test
    fun `总开关关着时，白名单里有也不算就绪`() {
        assertEquals(
            A11yState.SwitchOff,
            accessibilityState(connected = false, enabled = 0, services = full, expected = full)
        )
        assertFalse(
            accessibilityReady(connected = false, enabled = 0, services = full, expected = full)
        )
    }

    /**
     * 三种"不能取图"要分得开 —— 它们要求用户去动的东西不是同一个。
     *
     * 合成一句"去开启"的话，后两种情况用户到了系统设置会发现开关本来就是开着的，
     * 然后不知道该做什么。
     */
    @Test
    fun `三种不能取图的原因各自分开`() {
        val off = accessibilityState(connected = false, enabled = 0, services = full, expected = full)
        val notSelected =
            accessibilityState(connected = false, enabled = 1, services = "", expected = full)
        val notConnected =
            accessibilityState(connected = false, enabled = 1, services = full, expected = full)

        assertEquals("该去开总开关", A11yState.SwitchOff, off)
        assertEquals("该去勾本应用", A11yState.NotSelected, notSelected)
        assertEquals("该把本应用关掉再打开一次", A11yState.NotConnected, notConnected)

        // 三者互不相同 —— 这是它们存在的全部意义
        assertEquals(3, setOf(off, notSelected, notConnected).size)
    }

    @Test
    fun `总开关关着时，白名单里没有也算总开关那一种`() {
        // 两个都缺的时候，让用户先开总开关 —— 那是页面上的第一道
        assertEquals(
            A11yState.SwitchOff,
            accessibilityState(
                connected = false, enabled = 0,
                services = "别的应用/x.y", expected = full
            )
        )
    }

    @Test
    fun `总开关认不出时按没开处理`() {
        // getInt 读不到会给默认值 0，但万一哪天传进来别的值也别当成就绪
        listOf(-1, 2).forEach { weird ->
            assertEquals(
                "enabled=$weird 应被当成没开",
                A11yState.SwitchOff,
                accessibilityState(connected = false, enabled = weird, services = full, expected = full)
            )
        }
    }

    @Test
    fun `总开关开着但白名单里没有本服务`() {
        assertEquals(
            A11yState.NotSelected,
            accessibilityState(
                connected = false,
                enabled = 1,
                services = "$pkg/$pkg.capture.OtherService",
                expected = full
            )
        )
    }

    @Test
    fun `白名单读不到时算没勾选`() {
        listOf(null, "").forEach { empty ->
            assertEquals(
                "白名单是「$empty」时应算没勾选",
                A11yState.NotSelected,
                accessibilityState(connected = false, enabled = 1, services = empty, expected = full)
            )
        }
    }

    @Test
    fun `白名单里排在中间或末尾也算`() {
        listOf(
            "$pkg/$pkg.capture.OtherService:$full",
            "$full:$pkg/$pkg.capture.OtherService"
        ).forEach { list ->
            assertEquals(
                "应认出本服务在列表里：$list",
                A11yState.NotConnected,
                accessibilityState(connected = false, enabled = 1, services = list, expected = full)
            )
        }
    }

    /**
     * 白名单里的写法不统一 —— 目标是相对写法时也得认出来。
     *
     * 这个差异是真的存在：无障碍设置页里手动开关过之后，系统存进去的形式
     * 可能和用 adb 写的不同。不展开的话同一个服务会被认成两个。
     */
    @Test
    fun `相对写法和全类名互相认得`() {
        assertEquals(
            "白名单里是相对写法，目标是全类名",
            A11yState.NotConnected,
            accessibilityState(connected = false, enabled = 1, services = relative, expected = full)
        )
        assertEquals(
            "反过来也要认",
            A11yState.NotConnected,
            accessibilityState(connected = false, enabled = 1, services = full, expected = relative)
        )
    }

    @Test
    fun `畸形条目不会让判断崩，也不会误判成勾上了`() {
        // 真实的白名单里完全可能出现这种残渣（服务被卸载、包名变了之后留下的）
        listOf("::", "只有包名没有斜杠", "$pkg/", "/$pkg.capture.X").forEach { junk ->
            assertEquals(
                "残渣「$junk」应被当成没勾选",
                A11yState.NotSelected,
                accessibilityState(connected = false, enabled = 1, services = junk, expected = full)
            )
        }
    }

    @Test
    fun `前导空白不算差别`() {
        // setting 是文本，手工写进去的条目带个空格很正常
        assertEquals(
            A11yState.NotConnected,
            accessibilityState(connected = false, enabled = 1, services = " $full ", expected = full)
        )
    }

    /**
     * 连着的时候，设置里写什么都无所谓 —— 实例才是权威。
     *
     * 这条防的是一种很自然的"优化"：有人可能觉得既然设置不对就该报错，
     * 于是把 connected 的判断挪到设置检查后面。那等于又回到"设置说了算"，
     * 而设置正是两次误判的来源。
     */
    @Test
    fun `连着就是能用，不管设置里写的是什么`() {
        listOf(
            accessibilityState(connected = true, enabled = 0, services = "", expected = full),
            accessibilityState(connected = true, enabled = 1, services = "", expected = full),
            accessibilityState(connected = true, enabled = 0, services = full, expected = full)
        ).forEach { state ->
            assertEquals(
                "实例活着就说明真的能截，设置不一致是系统自己的事",
                A11yState.Ready,
                state
            )
        }
    }
}
