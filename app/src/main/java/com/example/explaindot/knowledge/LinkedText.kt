package com.example.explaindot.knowledge

/** 一段需要加粗的文字，坐标同样是**显示文本**的下标 */
data class BoldSpan(val start: Int, val end: Int)

/**
 * 一段排好版、算好可跳转位置的文本。
 *
 * [text] 是真正显示出来的字符（markdown 的记号已经剥掉），
 * [bold] 和 [links] 都指向它的下标 —— 三者共用同一套坐标，
 * 渲染时不需要再做任何换算，也就不存在「标注和文字错位」这类 bug。
 */
data class StyledText(
    val text: String,
    val bold: List<BoldSpan>,
    val links: List<LinkSpan>
)

/** 和 AnalysisScreen 里那个 BOLD_PATTERN 必须保持一致，否则加粗范围会和显示文字错位 */
private val BOLD_PATTERN = Regex("\\*\\*(.+?)\\*\\*")

/**
 * 把一段 markdown 记号剥成显示文本，同时算出加粗范围和可跳转范围。
 *
 * **为什么先剥记号再匹配链接。** 概念名是「用户看到的那个词」，
 * 所以必须拿显示文本去查库。如果直接在原始文本上匹配，一旦某个概念
 * 横跨了 `**` 记号（比如写成 `**熵**增`），位置就会偏移一格，
 * 链接会标到旁边的字上。
 *
 * [exclude] 往下传给匹配器，用来排掉「正在看的这个概念自己」——
 * 见 [ConceptMatcher.matchesIn] 的注释。
 *
 * [suggested] 是模型在解释里提到、本地却没有的第二套词表
 * （见 [SenseParser.suggestedTerms]）。它是可空的，含义是「有则多标一层」，
 * 没传就和以前完全一样。传进来之前就该把库里已有的词滤掉 ——
 * 一个词只该走其中一路。两路撞在一起时怎么取舍见 [linkSpans]。
 *
 * 流式输出时会遇到只有开头没有收尾的 `**`，正则的 `(.+?)` 配不上这类片段，
 * 于是那段记号会原样留在显示文本里。这是预期行为：# 记号的解析也在
 * AnalysisScreen 里做了同样处理 —— 半个记号渲染不出来也不会崩，下一帧补全就好。
 */
fun buildStyledText(
    raw: String,
    matcher: ConceptMatcher?,
    exclude: String? = null,
    suggested: ConceptMatcher? = null
): StyledText {
    val display = StringBuilder()
    val bold = ArrayList<BoldSpan>()
    var cursor = 0

    BOLD_PATTERN.findAll(raw).forEach { match ->
        if (match.range.first > cursor) {
            display.append(raw, cursor, match.range.first)
        }
        val start = display.length
        display.append(match.groupValues[1])
        bold += BoldSpan(start, display.length)
        cursor = match.range.last + 1
    }
    if (cursor < raw.length) display.append(raw, cursor, raw.length)

    val text = display.toString()
    return StyledText(
        text = text,
        bold = bold,
        links = linkSpans(text, matcher, exclude, suggested)
    )
}

/**
 * 把两套词表的命中结果合成一份不重叠的链接表。
 *
 * **重叠时保留「已知」的那一个。** 两个词表本来是分开的，但命中的区间可以交叠 ——
 * 比如库里存着「场论」，而模型在这段解释里建议了「场」。两条链接都盖住
 * 同一个「场」字的话，渲染出来就是叠在一起的两层链接，点中哪一层全看实现细节。
 * 这里把规则定死：留已知的。理由是它点下去立刻出结果、不用花钱也不用等，
 * 而建议的那个词多半会在用户读完「场论」之后再出现一次。
 *
 * 同一套词表内部的命中天然不重叠（[ConceptMatcher.matchesIn] 命中即跳过整个词），
 * 所以只需要按已知那一路的覆盖面，把建议那一路里撞上的筛掉。
 */
private fun linkSpans(
    text: String,
    matcher: ConceptMatcher?,
    exclude: String?,
    suggested: ConceptMatcher?
): List<LinkSpan> {
    val known = matcher?.matchesIn(text, exclude).orEmpty()
    if (suggested == null) return known

    val extra = suggested.matchesIn(text, exclude).map { it.copy(known = false) }
    if (extra.isEmpty()) return known

    val covered = BooleanArray(text.length)
    known.forEach { span ->
        for (i in span.start until span.end) covered[i] = true
    }

    val kept = extra.filter { span -> (span.start until span.end).none { covered[it] } }
    return (known + kept).sortedBy { it.start }
}
