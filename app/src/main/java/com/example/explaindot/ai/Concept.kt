package com.example.explaindot.ai

/**
 * 第一轮问答的产物：截图里一个「可能看不懂」的概念。
 *
 * 只有 [term] 是必需的。另外两个字段是给用户做判断用的 ——
 * 列表里摆着七八个词的时候，光看词本身很难决定该点哪个。
 */
data class Concept(
    /** 截图里原样出现的词。不做改写、不补全，方便和原文对照 */
    val term: String,
    /** 所属领域，比如「哲学术语」「拉丁语」「统计」 */
    val type: String = "",
    /** 一句话提示，告诉用户它大概讲什么 */
    val hint: String = ""
)
