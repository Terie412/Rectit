package com.example.explaindot.knowledge

import android.icu.text.Collator
import android.icu.util.ULocale

/**
 * 拼音排序。**只在真机上可靠**（见 [TermOrdering] 的注释）。
 *
 * 实现思路值得说清楚，因为它绕开了「没有拼音库」这个难题：
 *
 * Android 没有公开的「汉字转拼音」API，自己带一张拼音表又笨又难维护
 * （常用字表几千行，罕见字照样漏）。但 ICU 的**排序器本身就按拼音排** ——
 * 中文 locale 的默认排序规则就是拼音序。所以：
 *
 *   · 排序：直接用 ICU 比较两个词，天然就是拼音序，不用任何码表
 *   · 首字母：准备 23 个「锚点字」，各是一个声母下拼音最小的那个字
 *     （阿/芭/擦/搭/蛾/发/噶/哈/击/喀/垃/妈/拿/哦/啪/期/然/撒/塌/挖/昔/压/匝）。
 *     想知道某个字属于哪个字母，就在这些锚点里找**最后一个不大于它的**——
 *     因为锚点自己就是按拼音排好的，这个查找等价于「它落在哪个声母区间」。
 *
 * 23 个锚点的对错一眼能看出来，比几百行码表好审得多。漏掉 i/u/v 三个字母
 * 是对的：汉语拼音里没有以它们开头的音节，所以不存在对应的汉字。
 */
class PinyinOrdering : TermOrdering {

    /**
     * strength 设成 PRIMARY：让带声调的同一个字排在一起。
     *
     * 不设的话 ICU 会先比声调，「ā」和「á」会被拆到不同的位置 ——
     * 对分组毫无意义，用户也不会在意「妈」排在「麻」前面还是后面。
     */
    private val collator: Collator = Collator.getInstance(ULocale.forLanguageTag(LOCALE))
        .apply { strength = Collator.PRIMARY }

    /**
     * 每个字母的锚点字，**必须按拼音顺序声明**（也就是 A→Z 的顺序）。
     *
     * 故意不在这里再 sort 一遍：万一哪个锚点放错了位置，
     * 重新排序会把它藏起来，而顺序错的结果只是某个字母下的概念悄悄跑到隔壁组，
     * 极难发现。
     */
    private val anchors: List<Pair<String, String>> = listOf(
        "A" to "阿", "B" to "芭", "C" to "擦", "D" to "搭", "E" to "蛾",
        "F" to "发", "G" to "噶", "H" to "哈", "J" to "击", "K" to "喀",
        "L" to "垃", "M" to "妈", "N" to "拿", "O" to "哦", "P" to "啪",
        "Q" to "期", "R" to "然", "S" to "撒", "T" to "塌", "W" to "挖",
        "X" to "昔", "Y" to "压", "Z" to "匝"
    )

    /**
     * 首字母。
     *
     * 先按字符种类分流，再决定怎么算 —— 顺序不能反：ICU 的拼音排序会把汉字
     * 按它的读音插到拉丁字母中间（阿排在 a 附近），所以拿汉字直接去和
     * 拉丁字母比大小是没有意义的。拉丁字母自己报自己的字母，汉字才走锚点查找。
     */
    override fun letterOf(term: String): String {
        val first = term.firstOrNull() ?: return ConceptIndex.OTHER_GROUP

        if (first in 'A'..'Z' || first in 'a'..'z') {
            return first.uppercaseChar().toString()
        }

        // 只处理汉字。日文假名、西里尔字母之类走锚点查找只会得到随机结果，
        // 让它们落到「其他」组比塞进某个字母组诚实
        if (Character.UnicodeScript.of(first.code) != Character.UnicodeScript.HAN) {
            return ConceptIndex.OTHER_GROUP
        }

        return anchors.lastOrNull { (_, anchor) -> collator.compare(anchor, first.toString()) <= 0 }
            ?.first
            ?: ConceptIndex.OTHER_GROUP
    }

    /**
     * 同组内的先后。
     *
     * PRIMARY 强度下「妈」和「麻」是相等的，而相等的元素在排序里的相对顺序
     * 是不确定的 —— 列表每次重建都可能换个位置，看着像随机跳动。
     * 所以相等时再按字面比一次，把顺序钉死。
     */
    override fun compare(a: String, b: String): Int {
        val byPinyin = collator.compare(a, b)
        return if (byPinyin != 0) byPinyin else a.compareTo(b)
    }

    companion object {
        /**
         * `zh-u-co-pinyin` 是 BCP 47 里「中文 + 拼音排序」的写法。
         * 明写出来而不是靠 zh 的默认值：默认值虽然在 ICU 里就是拼音，
         * 但那是实现细节，写明了才不依赖它。
         */
        private const val LOCALE = "zh-u-co-pinyin"

        /**
         * 全进程一个。构造 Collator 不便宜（要加载排序规则表），
         * 而这个对象会被用在每次列表重组上，不能每次新建。
         */
        val shared: PinyinOrdering by lazy { PinyinOrdering() }
    }
}
