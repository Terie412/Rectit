package com.example.explaindot.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这两次拿到的还是不是同一张图」。
 *
 * 这份测试围着真机上那个 bug 写：对话历史要求**只跟当前图有关**（换了图就该清空），
 * 但它一直没清过。原因是判据只比路径，而图片槽位固定写在一个文件名上 ——
 * 截图、相册、相机拿到的图路径**永远是同一个值**：
 *
 *     cacheDir/pending/latest.jpg        ← 三种入口都写这儿
 *
 * 于是第二个图进来时被判成「还是那张图」，一整场旧对话留在界面上：
 * 用户对着新图看到的是关于上一张图的问答，而且没有任何线索指向原因。
 *
 * 之所以值得单测，是因为它的形态是**少比一个字段** ——
 * 少比字段不报错、不崩、不打日志，只会让旧对话静默留下。
 * 而「身份由路径 + 代号共同决定」这件事用测试表达出来之后，
 * 将来有人觉得代号那一项多余（它看起来确实啰嗦）时会先失败。
 */
class SameImageTest {

    /** 和 [com.example.explaindot.image.PendingImage] 里的槽位对齐 */
    private val slot = "/data/user/0/com.example.explaindot/cache/pending/latest.jpg"

    /**
     * **就是那个 bug。**
     *
     * 三张不同的图落在同一个路径上，能区分它们的只有代号。
     */
    @Test
    fun `路径相同但代号变了，是另一张图`() {
        assertFalse(
            "截图、相册、相机都写这一个路径，只比路径的话这里会答「还是那张图」",
            sameImage(slot, 1L, slot, 2L)
        )
    }

    @Test
    fun `路径和代号都一样才是同一张`() {
        assertTrue(sameImage(slot, 7L, slot, 7L))
    }

    /**
     * 反方向也要成立：代号没变但路径变了，仍然是另一张图。
     *
     * 现在三个入口都写同一个路径，这一条走不到；但把路径也从判据里去掉
     * 是过度简化 —— 将来多一个槽位（比如相机单独存一份）它就有用了。
     */
    @Test
    fun `路径变了也是另一张，哪怕代号恰好相同`() {
        assertFalse(sameImage(slot, 3L, "/data/user/0/x/cache/pending/other.jpg", 3L))
    }

    /** 从「还没有图」到「有图」当然是换图，否则第一段对话会被上一张图的残留污染 */
    @Test
    fun `从没有图到有图算换图`() {
        assertFalse(sameImage(null, NO_IMAGE_GENERATION, slot, 1L))
    }

    /** 代号每次递增，所以两个不同的代号永远不会被认成同一张 —— 连号也不连续 */
    @Test
    fun `代号相邻但不等，仍然算换图`() {
        assertFalse(sameImage(slot, 1L, slot, 2L))
        assertFalse(sameImage(slot, 41L, slot, 42L))
    }
}
