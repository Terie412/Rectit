package com.example.explaindot.knowledge

/** 一个字母分组：字母本身，加上这个字母下的概念名（已排好序） */
data class LetterGroup(val letter: String, val terms: List<String>)

/**
 * 概念名的排列规则。
 *
 * **抽成接口只有一个理由：让分组逻辑能在 JVM 上单测。** 真正的实现
 * （[PinyinOrdering]）要用 Android 的 ICU 排序器，那东西在 JVM 单测里
 * 跑不起来，构造出来的行为也和真机不一样（JDK 的 Collator 不认拼音）。
 * 把「怎么算首字母」和「怎么比大小」隔离出来之后，分组、去重、排序这些
 * 真正容易写错的地方就能被测试盖住。
 */
interface TermOrdering {

    /** 这个概念该归到哪个字母下。返回 [ConceptIndex.OTHER_GROUP] 表示归入「其他」 */
    fun letterOf(term: String): String

    /** 同一组内的先后 */
    fun compare(a: String, b: String): Int
}

/**
 * 概念列表的整理：过滤、分组、排序。全是纯函数，所以全部有单测。
 *
 * 界面层不自己拼这些逻辑 —— 分组排序是那种「看着简单、边界一堆」的东西
 * （空输入、只有「其他」组、重复项、大小写混排），集中在这里才好测。
 */
object ConceptIndex {

    /**
     * 归不进去的（数字开头、符号开头、非汉字也非拉丁字母）都放这个组。
     *
     * 用 `#` 而不是「其他」：它要跟着 A-Z 一起排，一个字符的宽度在列表左边
     * 那一列里更整齐，而且和字母一样是「索引标记」的性质。
     */
    const val OTHER_GROUP = "#"

    /**
     * 按查询串过滤概念名。
     *
     * 子串匹配而不是前缀匹配：找一个概念时想得起来的多半是中间那几个字
     * （搜「场」要能搜出「市场机制」），前缀匹配会把这类结果全挡掉。
     * 大小写不敏感是为了英文概念 —— 用户不会记得库里存的是「GDP」还是「gdp」。
     *
     * 汉字没有大小写概念，`ignoreCase` 对它们不产生任何影响，所以两种语言
     * 可以走同一条路径。
     */
    fun filter(terms: Collection<String>, query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return terms.toList()
        return terms.filter { it.contains(q, ignoreCase = true) }
    }

    /**
     * 分组 + 排序。
     *
     * 结果里只有**非空**的组：字母表上摆一堆没有内容的字母，除了让用户多滑几屏
     * 没有任何用处。「其他」组永远排在最后 —— 它是兜底，不是索引的一部分。
     *
     * 输入里的空串和重复项在这里被清掉。空串是真实存在的：概念名经
     * [TermNormalizer] 处理之后理论上不会是空的，但库里可能留着历史数据，
     * 一个空名字出现在列表里既没法显示也没法点。
     */
    fun group(terms: Collection<String>, ordering: TermOrdering): List<LetterGroup> {
        val cleaned = terms.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

        if (cleaned.isEmpty()) return emptyList()

        return cleaned
            .groupBy { ordering.letterOf(it) }
            .map { (letter, group) ->
                LetterGroup(
                    letter = letter,
                    terms = group.sortedWith { a, b -> ordering.compare(a, b) }
                )
            }
            // 「其他」沉底，其余按字母升序
            .sortedWith(compareBy({ it.letter == OTHER_GROUP }, { it.letter }))
    }
}
