package com.example.explaindot.knowledge

/**
 * 概念名的归一化。入库的键、以及拿去做匹配的，都是归一化之后的形态。
 *
 * 为什么必须归一化：第一轮挑概念时，prompt 明确要求模型「截图里原样出现的那个词，
 * 带标点就照抄」。所以同一个概念可能带着「」、引号、句号、括号进来 ——
 * 不收拾的话，「熵」和「“熵”」会变成库里两条互不相干的记录，
 * 用户解释过的东西等于没解释过。
 *
 * **不做大小写折叠。** 看着像漏了一步，其实是有意的：真的折叠了，
 * 「GDP」和「gdp」会合并成一条，而匹配时就得在解释文本里做不区分大小写的搜索，
 * 那样又得处理「显示的大小写和库里不一样」这种麻烦。实测模型对同一概念
 * 的大小写是稳定的，不值得为它换一套复杂逻辑。
 *
 * 也不改写内部字符：只剥**首尾**的标点。中间的标点往往是有意义的
 * （比如「图灵测试/中文房间」这种并列写法），动了就是篡改概念名。
 */
object TermNormalizer {

    /**
     * 首尾要去掉的字符。
     *
     * 包含成对括号的两半（而不是整对删除）：实际数据里有只带半边的情况，
     * 比如模型抄了「（见下）而漏了后括号。成对剥的循环能同时处理两种。
     */
    private const val TRIM_CHARS =
        "「」『』“”‘’（）()〔〕【】[]《》〈〉<>\"'·•　 \t\r\n" +
            "，。、；：！？…—～,.;:!?~"

    /** 内部连续空白折成一个半角空格。中文里出现多空格多半是抄写误差 */
    private val WHITESPACE_RUN = Regex("\\s+")

    fun normalize(raw: String): String {
        var s = raw.trim()

        // 反复剥：模型可能套了两层，比如「“熵”」
        var changed = true
        while (changed) {
            changed = false
            if (s.isNotEmpty() && TRIM_CHARS.indexOf(s.first()) >= 0) {
                s = s.drop(1)
                changed = true
            }
            if (s.isNotEmpty() && TRIM_CHARS.indexOf(s.last()) >= 0) {
                s = s.dropLast(1)
                changed = true
            }
        }

        return s.replace(WHITESPACE_RUN, " ").trim()
    }
}
