package com.example.explaindot.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SSE 分片的正文提取。
 *
 * ## 这份测试的边界，先写清楚
 *
 * 它**测不出那个真实的 bug**。原因是平台差异：Android 的 `JSONObject.optString`
 * 对 JSON null 返回字符串 `"null"`，而单元测试跑的 JVM 版 org.json 返回空串。
 * 所以就算有人把实现改回 `optString`，这里每一条也照样通过。
 *
 * 那为什么还留着？因为下面 [正文为 JSON null 的分片不产生任何正文] 会**立刻失败**
 * 一旦实现改成 `opt("content").toString()` 这类写法 —— 而且这几条测试把
 * "什么样的分片算没正文"这件事写成了可执行的规格：心跳、思考片、结尾片
 * 都不该产生字符。这个契约值得钉住，哪怕其中一半的保证要靠真机。
 *
 * 真机证据在对话页上：high 档思考下，开场白前面曾经挂着两百多个 "null"。
 */
class SseContentDeltaTest {

    /** 造一个真实形状的分片。字段名照官方文档抄，别自己发明 */
    private fun chunk(vararg deltaFields: Pair<String, Any?>): JSONObject {
        val delta = JSONObject()
        deltaFields.forEach { (key, value) -> delta.put(key, value) }
        return JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("index", 0).put("delta", delta))
        )
    }

    @Test
    fun `正常分片取出正文`() {
        assertEquals("你好", sseContentDelta(chunk("content" to "你好")))
        assertEquals(" ", sseContentDelta(chunk("content" to " ")))
    }

    /**
     * 这条是整份测试的重点：思考分片的 content 是 JSON null。
     *
     * `JSONObject.NULL` 而不是 Kotlin 的 null —— 前者才会真的写进 JSON 里变成 `null`，
     * 后者在 `put` 时会被当成"删掉这个键"。
     */
    @Test
    fun `正文为 JSON null 的分片不产生任何正文`() {
        val reasoningOnly = chunk(
            "reasoning_content" to "让我想想……",
            "content" to JSONObject.NULL
        )
        assertNull("思考片不该吐出任何字符", sseContentDelta(reasoningOnly))
    }

    @Test
    fun `缺 content 键的分片不产生正文`() {
        // 首片只有 role，末片可能只有 finish_reason
        assertNull(sseContentDelta(chunk("role" to "assistant")))
        assertNull(sseContentDelta(JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("index", 0).put("finish_reason", "stop"))
        )))
    }

    @Test
    fun `没有 choices 的分片不产生正文`() {
        // 心跳、usage 片（stream_options.include_usage）会长这样
        assertNull(sseContentDelta(JSONObject()))
        assertNull(sseContentDelta(JSONObject().put("choices", JSONArray())))
    }

    @Test
    fun `空串正文原样返回，交给调用方决定要不要丢`() {
        // 这里返回 ""，emitDeltas 那边用 isNullOrEmpty 一并挡掉。
        // 分成两道是有意的：提取的职责是"照实取出内容"，
        // "空的要不要发"是流量控制的事
        assertEquals("", sseContentDelta(chunk("content" to "")))
    }

    @Test
    fun `content 不是字符串时不崩也不误取`() {
        // 万一哪天文案里出现块数组形式（比如带图的 content），
        // as? String 会得到 null，而不是把整个数组 toString 出来
        val arrayContent = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "delta",
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text")))
                )
            )
        )
        assertNull(sseContentDelta(arrayContent))
    }
}
