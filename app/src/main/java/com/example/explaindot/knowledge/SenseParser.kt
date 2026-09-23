package com.example.explaindot.knowledge

/** 一个概念的一种含义：标签（适用场景）+ 正文 */
data class Sense(val tag: String, val body: String)

/**
 * 把 [com.example.explaindot.ai.Prompts.EXPLAIN_SYSTEM] 产出的 Markdown
 * 切成一条条「标签 + 正文」，好存进知识库。
 *
 * 为什么在本地切而不是让模型直接吐 JSON：解释要流式显示（三五百字，
 * 让用户干等五秒体验很差），而 JSON 在写完之前没法渲染。Markdown 标题
 * 既能流式显示、又能在写完之后本地切开 —— 两种好处都要。
 *
 * **默认标签的存在是有意的。** 模型偶尔会不守格式（忘了写 `## `、
 * 或者整篇都是段落）。这时候不能把整段丢掉，也不能存一个没有标签的条目
 * —— 标签是「重新解释」时定位要改哪一条的唯一凭据。所以退化成单条、
 * 给一个 [FALLBACK_TAG]，内容一个字不丢。
 *
 * 切分只看 `#` 开头的行，正文里的 `-` 列表和 `**粗体**` 原样保留，
 * 它们由渲染层处理。这样两件事各归各的，不需要在这里做 Markdown 解析。
 */
object SenseParser {

    /**
     * 模型没按格式输出时用的标签。
     *
     * 取「通用」而不是「其他」：它要显示在用户眼前，而且要在
     * 「重新解释」时作为 targetTag 被引用 —— 一个自己能看懂的说法比编号强。
     */
    const val FALLBACK_TAG = "通用"

    /**
     * 模型把 prompt 里的模板词照抄成标题时会产生的标签。
     *
     * **这不是假想的。** prompt 最早的写法是「## 标签 / 正文……」这种占位符模板，
     * 模型真就把「标签」当标题抄了下来 —— 库里存了一条名叫「标签」的解释，
     * 真正的标签「物理学的场」被挤到了正文第一行，正文里还混着一行「正文」。
     *
     * prompt 已经改成带具体示例的写法（大幅降低概率），但「模型偶尔照抄模板」
     * 这件事本身没法根治 —— 换个模型、换个语种都可能复现。所以在解析层再兜一道：
     * 这是最后一道拦得住的地方，放过去就会变成一条显示给用户的脏数据。
     */
    private val PLACEHOLDER_TAGS = setOf(
        "标签", "正文", "标题", "内容", "解释", "说明",
        "标签一", "标签二", "标签三", "标签名",
        "示例", "例子",
        "tag", "body", "title", "content"
    )

    /** 一到六级标题都认。模型偶尔会把 `##` 写成 `#` 或 `###`，不值得为此丢内容 */
    private val HEADING = Regex("^#{1,6}\\s+(.+)$")

    /** 标签的兜底长度。标签本身要求 4-10 字，超了说明模型把整段话当标题写了 */
    private const val MAX_TAG_LENGTH = 24

    fun parse(markdown: String): List<Sense> {
        // 先在标记处切断：那行「相关概念」不属于任何义项，
        // 留着的话会被当成最后一条解释的正文尾巴存进库
        val text = explanationOnly(markdown)

        val senses = ArrayList<Sense>()
        var currentTag: String? = null
        val body = StringBuilder()

        fun flush() {
            val content = body.toString().trim()
            val tag = currentTag
            if (tag != null && content.isNotEmpty()) {
                senses += Sense(tag, content)
            }
            body.setLength(0)
        }

        text.lines().forEach { rawLine ->
            val heading = HEADING.matchEntire(rawLine.trim())
            if (heading != null) {
                flush()
                currentTag = normalizeTag(heading.groupValues[1])
            } else {
                if (body.isNotEmpty()) body.append('\n')
                body.append(rawLine)
            }
        }
        flush()

        // 一个标题都没有（或标题下面全是空行）→ 整篇按一条存，内容不丢
        if (senses.isEmpty()) {
            val whole = text.trim()
            return if (whole.isEmpty()) emptyList() else listOf(Sense(FALLBACK_TAG, whole))
        }
        return rescueTemplateEcho(senses)
    }

    /**
     * 抢救「模型把 prompt 模板照抄下来」的那一种输出。
     *
     * 正常的节原样留下；只有当**所有**标题都是占位符词时才动手 ——
     * 那时候说明整篇的标题都不是模型自己想的，真正的标签被写在了正文第一行。
     *
     * **把所有占位符节的正文拼起来再捞**，而不是只看第一条：模型抄模板时
     * 可能抄成一节（`## 标签` 后面紧跟 `正文` 两个字），也可能抄成两节
     * （`## 标签` 下写真标签、`## 正文` 下写正文）—— 后一种内容分散在两条里，
     * 只看第一条会捞到空。
     */
    private fun rescueTemplateEcho(senses: List<Sense>): List<Sense> {
        val real = senses.filterNot { isPlaceholder(it.tag) }
        if (real.isNotEmpty()) return real
        if (senses.isEmpty()) return senses

        val merged = senses.joinToString("\n\n") { it.body }
        return listOf(rescueBody(merged) ?: Sense(FALLBACK_TAG, merged))
    }

    /**
     * 从一个「标题是占位符」的节里捞出真正的标签和正文。
     *
     * 找正文里第一个「像标签」的行当标签（非空、不是占位符词、长度合理），
     * 它后面的是正文；顺手把模型照抄的那些占位符行丢掉，免得它们混在解释里。
     *
     * 返回 null 表示捞不出来 —— 那种情况交给调用方退化成 [FALLBACK_TAG]，
     * 至少内容一个字不丢。
     */
    private fun rescueBody(body: String): Sense? {
        val lines = body.lines()
        val tagIndex = lines.indexOfFirst(::looksLikeTag)
        if (tagIndex < 0) return null

        val tag = cleanLine(lines[tagIndex])
        val rest = lines.drop(tagIndex + 1)
            .filterNot { isPlaceholder(cleanLine(it)) }
            .joinToString("\n")
            .trim()

        if (rest.isEmpty()) return null
        return Sense(tag, rest)
    }

    private fun looksLikeTag(line: String): Boolean {
        val candidate = cleanLine(line)
        return candidate.isNotEmpty() &&
            !isPlaceholder(candidate) &&
            candidate.length <= MAX_TAG_LENGTH
    }

    /** 去掉行首的 `#`、行尾的冒号句读和空白 —— 判断占位符前先归一化 */
    private fun cleanLine(line: String): String =
        line.trim().trimStart('#').trim().trimEnd('：', ':', '。')

    private fun isPlaceholder(tag: String): Boolean =
        tag.trim().lowercase() in PLACEHOLDER_TAGS

    /**
     * 标题里可能带 `**`、结尾冒号、多余的空白，都清掉。
     *
     * 这些不是洁癖：标签会被当作 `targetTag` 发给模型去定位要替换哪一条，
     * 带一堆记号的话模型对不上，replace 就会落空。
     *
     * **占位符词原样保留，不在这里替换成 [FALLBACK_TAG]。** 这一条很关键：
     * 如果把「标签」就地改成「通用」，[rescueTemplateEcho] 就再也分不清
     * 「模型抄了模板」和「模型真的起了个叫通用的标签」，抢救逻辑等于废掉。
     * 判断和替换交给那一个地方做，这里只管清理。
     */
    private fun normalizeTag(raw: String): String {
        val cleaned = raw
            .replace("**", "")
            .trim()
            .trimEnd('：', ':', '。', '，', ',')
            .trim()

        return when {
            cleaned.isEmpty() -> FALLBACK_TAG
            cleaned.length > MAX_TAG_LENGTH -> cleaned.take(MAX_TAG_LENGTH)
            else -> cleaned
        }
    }

    /**
     * 从一段解释正文里取「标签」列表，供「重新解释」时发给模型。
     *
     * 单独一个函数而不是让调用方自己 map：这里要保证顺序和去重规则一致，
     * 散在外面写两遍迟早会不一致。
     */
    fun tagsOf(senses: List<Sense>): List<String> = senses.map { it.tag }

    // ---------------------------------------------------------------- 相关概念的标记

    /**
     * 模型在正文末尾附上的那行标记的开头。
     *
     * 用 `<!--` 而不是一个正经标题（比如 `## 相关概念`）：标题会被 [parse]
     * 当成一个义项节，于是就得再加一条「哪些标题是特殊的」的规则，
     * 而那条规则和 [PLACEHOLDER_TAGS] 那套会互相打架。
     * `<!--` 在模型的正常输出里根本不会出现，是个干净的分界。
     *
     * **代价是「模型真写了一处 HTML 注释」时会把后面的内容截掉。**
     * 接受这个代价：我们从不要求它输出 HTML，注释在 Markdown 里本来也不显示，
     * 真出现的话截掉和显示一堆乱码相比并不更糟。
     */
    private const val MARKER = "<!--"

    /** 标记收尾。模型偶尔会漏写，所以它是可选的 */
    private const val MARKER_END = "-->"

    /** 标记里冒号之前的部分（「相关概念」「相关概念:」这类前缀），提取时整段丢掉 */
    private val LABEL = Regex("^[^:：]*[:：](.*)$", RegexOption.DOT_MATCHES_ALL)

    /** 词与词之间的分隔符。模型有时用逗号、有时用顿号，空格也算 */
    private val SEPARATORS = Regex("[、,，;；\\s]+")

    /**
     * 这一行上限。prompt 里要求最多 5 个，这里放宽到 8 —— 这是
     * 「模型没听话」的兜底，不是设计值：真被它列了二十个，正文会变成
     * 一片链接，反而看不出哪句是重点。
     */
    private const val MAX_SUGGESTED = 8

    /**
     * 取解释正文 —— 也就是标记之前的那部分。
     *
     * **流式显示也走这里**：一边收一边用它算要显示的文字，于是标记一旦开始出现，
     * 显示就停在正文末尾，用户看不到那行机器标记。
     * 这正是把分隔符选成 `<!--` 而非段落的另一个好处 —— 它可以被截断，
     * 而「半个标记」也被同一条规则处理：见到 `<!--` 就停，
     * 无论后面有没有写完。
     */
    fun explanationOnly(markdown: String): String {
        val at = markdown.indexOf(MARKER)
        return if (at < 0) markdown else markdown.substring(0, at)
    }

    /**
     * 取标记里那串「读者可能也不懂的其他概念」。
     *
     * 解析刻意宽松：模型可能写成 `<!--相关概念：A、B-->`，
     * 也可能漏掉收尾的 `-->`、把顿号写成逗号、或者多加几个空格。
     * 这些都不该让它白写一遍 —— 拿不准的形态一律尽量捞，捞不出来就返回空表，
     * 退化成「没有可延伸的概念」，而不是抛异常把整次解释搞砸。
     *
     * 词会过一遍 [TermNormalizer]：模型偶尔给词加上引号或书名号，
     * 而入库用的键、[com.example.explaindot.knowledge.ConceptMatcher] 查表用的
     * 也都是归一化之后的形态，两边必须一致。
     */
    fun suggestedTerms(markdown: String): List<String> {
        val at = markdown.indexOf(MARKER)
        if (at < 0) return emptyList()

        val payload = markdown.substring(at + MARKER.length)
            .substringBefore(MARKER_END)
            .trim()
        if (payload.isEmpty()) return emptyList()

        // 去掉「相关概念：」这层标签。没有冒号就当作整段都是词
        val terms = LABEL.find(payload)?.groupValues?.get(1) ?: payload

        return terms.split(SEPARATORS)
            .map { TermNormalizer.normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_SUGGESTED)
    }

    /**
     * 同一个概念已经存了某个标签时，新来的同名标签要能对得上。
     *
     * 用归一化后的标签做比较：模型两次可能写成「物理学的场」和「物理学的场 」
     * （多一个空格），直接字面比较会变成两条。
     */
    fun sameTag(a: String, b: String): Boolean =
        normalizeTag(a).equals(normalizeTag(b), ignoreCase = true)
}
