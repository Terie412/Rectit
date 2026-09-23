package com.example.explaindot.knowledge

/**
 * 一处命中的位置。[start, end) 是**显示文本**里的下标，不是原始 markdown 的
 * —— 调用方拿它直接切 AnnotatedString，所以必须是被 `**` 剥掉之后的那套坐标。
 *
 * [known] 区分两种链接，界面用不同颜色画：
 *   · true —— 本地库里已经有解释，点下去立刻出结果，不花钱也不等
 *   · false —— 模型在解释里提到、但库里没有的词，点下去要现问一次模型
 *
 * 这个区分不能留给渲染层去猜：两种链接来自两套词表，谁盖住谁得在
 * [buildStyledText] 里定，渲染层只负责照着 [known] 上色。
 */
data class LinkSpan(
    val start: Int,
    val end: Int,
    val term: String,
    val known: Boolean = true
)

/**
 * 在解释正文里找出「本地已经解释过的概念」，标出可以跳转的位置。
 *
 * **为什么不用 Aho-Corasick。** 自动机是为「词典大到装不进内存」准备的方案，
 * 它的价值在于 O(文本长度) 且不依赖词表大小。但这个 App 的预算里，
 * 一万个概念名的 HashSet 只有大约 1MB —— 词表本来就能全量常驻。
 * 于是滑窗查表就够了：600 字的解释、最长词 8 字，约 5000 次查表，亚毫秒。
 * 代码量是自动机的十分之一，而且没有一处需要凭记忆写对。
 *
 * **这个类不做归一化。** 传进来的词表必须已经是 [TermNormalizer] 处理过的，
 * 被扫的文本则是原样的显示文本 —— 两边各自管好自己的事，
 * 免得归一化规则散在两个地方、日后只改了一边。
 */
class ConceptMatcher(terms: Collection<String>) {

    private val dictionary: Set<String> = terms.filterTo(HashSet()) { it.isNotEmpty() }

    /** 词表里实际出现过的长度，用来跳过不可能命中的长度。降序 → 天然先试长词 */
    private val lengths: List<Int> =
        dictionary.map { it.length }.distinct().sortedDescending()

    val isEmpty: Boolean get() = dictionary.isEmpty()

    /**
     * 这个词是不是已经在库里。
     *
     * 概念列表用它给「已经解释过」的条目打标记 —— 用户一眼能看出点哪个
     * 不用再等模型，这正是知识库省 token 的那部分价值，得让人看见。
     */
    operator fun contains(term: String): Boolean = term in dictionary

    /**
     * 扫一遍，给出所有可跳转的位置。
     *
     * 策略是「从左往右，每个位置取能匹配上的最长词，命中后跳过整个词」。
     * 最长优先正是「词相交时优先显示更长的概念」这条要求；
     * 命中即跳过则保证同一段文字不会被两个词重叠标注。
     *
     * [exclude] 用来排掉一个词，**只比完全相同的**。它的用途是「正在看某个概念的
     * 解释时，不要把解释正文里出现的这个词本身标成链接」—— 点进去还是同一页，
     * 是个死路。（如果正文里出现的是更长的、以它开头的另一个概念，比如在看「场」
     * 而正文提到「场论」，那仍然要连，所以不能按前缀排除。）
     */
    fun matchesIn(text: String, exclude: String? = null): List<LinkSpan> {
        if (dictionary.isEmpty() || text.isEmpty()) return emptyList()

        val out = ArrayList<LinkSpan>()
        var cursor = 0

        while (cursor < text.length) {
            val hit = longestAt(text, cursor)
            if (hit != null) {
                if (hit.term != exclude) out += hit
                cursor = hit.end
            } else {
                cursor++
            }
        }
        return out
    }

    private fun longestAt(text: String, start: Int): LinkSpan? {
        val remaining = text.length - start
        for (len in lengths) {
            if (len > remaining) continue
            val candidate = text.substring(start, start + len)
            if (candidate in dictionary && boundaryOk(text, start, len)) {
                return LinkSpan(start, start + len, candidate)
            }
        }
        return null
    }

    /**
     * 单字概念的边界判据。
     *
     * 中文没有词边界，所以单字概念最容易误连：「场」在库里，
     * 读到「市场」就会长出一个指向「物理学中的场」的链接 —— 误连比不连更糟。
     *
     * 这里用的规则是**左边界成立**才算：
     *   · 「熵是描述混乱程度的量」  → 熵 在句首，左边无字符 → 连（正确）
     *   · 「市场的作用」           → 场 左边是「市」→ 不连（正确，躲开误连）
     *   · 「一场梦」               → 场 左边是「一」→ 不连（正确）
     *
     * 为什么只看左边、不看右边：中文的词大多「头在前」，所以一个单字概念
     * 后面紧跟别的字是很正常的（熵+是、场+指）。要求右边也断开的话，
     * 「熵是…」这种最常见的情形反而全被挡掉，单字概念就等于废了。
     * 左边断开则恰好把「场」被包在「市场」里那类误连筛掉。
     *
     * 这是启发式，不是词法分析 —— 中文分词要么带词典（体积、维护都吃不消），
     * 要么准确率不如这条规则。以后真被咬到了，改这一个函数就够了。
     *
     * 多字概念一律放行：两字以上的局部命中（「市场」命中在「市场经济」里）
     * 语义上仍然相关，不是误连。
     */
    private fun boundaryOk(text: String, start: Int, len: Int): Boolean {
        if (len > 1) return true
        if (start == 0) return true
        // isLetterOrDigit 对汉字同样返回 true，所以这一条同时挡住了汉字、英文、数字
        return !text[start - 1].isLetterOrDigit()
    }
}
