package com.example.explaindot.ai

import android.util.Log
import com.example.explaindot.chat.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Proxy
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 调 DeepSeek 出问题时抛这个，message 已经是可以直接摆给用户看的中文。
 *
 * 单独定义一个异常类型，是为了让界面能把「我们知道原因的失败」
 * （key 没填、余额不足、模型名写错）和「意料之外的崩」区分开。
 */
class DeepSeekException(message: String) : Exception(message)

/**
 * 「重新解释」让模型做的判断。
 *
 * 用密封接口而不是几个可空字段：三种结果互斥，而且各自需要的字段不同。
 * 摆成 `tag: String?` + `targetTag: String?` + `body: String?` 那种形状的话，
 * 「Add 却只有 targetTag」这种非法组合就变得可以表达了 ——
 * 而消费方还得自己记得判断该读哪几个字段。
 */
sealed interface RefineDecision {

    /** 已有解释够用，什么都不用改 */
    data object Keep : RefineDecision

    /** 补一条新的：当前上下文的用法是已有解释没覆盖的 */
    data class Add(val tag: String, val body: String) : RefineDecision

    /**
     * 换掉某一条：已有解释里那条讲错了，或者和当前上下文矛盾。
     *
     * 标签沿用被替换的那个（[targetTag]），只换正文 —— 对应需求里的
     * 「覆盖一个现有的标签的解释」。这样用户的标签体系不会因为一次修正而漂移。
     */
    data class Replace(val targetTag: String, val body: String) : RefineDecision
}

/**
 * 从一个 SSE 分片里取出正文增量。返回 null 表示这一片没有正文。
 *
 * 单独一个函数是为了能单测 —— 它的错误方式很隐蔽：不会抛异常、不会报错，
 * 只会在回答前面多出一串垃圾字符。
 *
 * ## 为什么是 `opt` + `as? String` 而不是 `optString`
 *
 * 思考模式下，思考内容在 `delta.reasoning_content` 里，而同一片的
 * `delta.content` 是 JSON 的 null。Android 的 `JSONObject.optString` 对 JSON null
 * 返回的是**字符串 "null"**（`JSONObject.NULL.toString()` 的结果），
 * 于是每个思考分片都会吐出一个 "null" 到回答里。
 *
 * `as? String` 的语义在两个平台上一致：JSON null 不是 String，得到 null。
 *
 * ## 注意这个函数在单测里测不出完整的问题
 *
 * JVM 那版 org.json 的 `optString` 对 JSON null 返回**空串**，所以在单测里
 * 即使用 `optString` 写，这一条测试也会通过 —— 平台差异让它在测试机上蒙对了。
 * 也就是说：**这条测试守的是这里的实现意图，真正的验证在真机日志和界面上。**
 * 那个 "nullnullnull…" 是真机上看出来的，不是测出来的。
 */
internal fun sseContentDelta(chunk: JSONObject): String? =
    chunk.optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("delta")
        ?.opt("content") as? String

/**
 * DeepSeek Chat Completions 客户端。对外只有两个方法：
 *
 *   [listConcepts]  —— 带图问一轮，拿回结构化的话题候选（非流式，因为要解析 JSON）
 *   [explainStream] —— 带图问一轮，把解释逐段吐出来（流式，因为要等它写几百字）
 *
 * 两轮都带图。原因是解释必须贴合原文语境：同一个词在经济学书和哲学书里
 * 意思差很远，只发一个孤立的词，模型只能给词典释义，对正在读这本书的人没用。
 *
 * 这个包刻意不碰任何需要 Context 的 Android 组件 —— base64 用 java.util.Base64
 * 而不是 android.util.Base64，读文件用 java.io.File。唯一的例外是
 * android.util.Log，那只是个静态的日志门面，不涉及框架生命周期。
 * 守这条边界成本为零，将来要搬到别的形态时这一层能整块带走。
 */
class DeepSeekClient {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(AiConfig.connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(AiConfig.readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(AiConfig.readTimeoutSeconds, TimeUnit.SECONDS)
        .apply {
            // 手机上的代理类 App 会往系统里塞 HTTP 代理设置，OkHttp 默认会捡起来用。
            // 那些设置经常是残留的（代理早关了、设置还在），表现就是
            // 「WiFi 明明通着，请求却一律超时」。所以默认让流量走系统路由，
            // 绕开系统代理这一层变量。
            if (!AiConfig.useSystemProxy) {
                proxy(Proxy.NO_PROXY)
            }
        }
        .build()

    // ------------------------------------------------------------------ 对外接口

    /** 第一轮：这张图里有哪些可能看不懂的概念 */
    suspend fun listConcepts(imageFile: File): List<Concept> = withContext(Dispatchers.IO) {
        requireConfigured()

        val payload = buildRequestBody(
            imageFile = imageFile,
            systemPrompt = Prompts.CONCEPT_SCAN_SYSTEM,
            userPrompt = Prompts.CONCEPT_SCAN_USER,
            jsonMode = true,
            stream = false
        )

        parseConcepts(executeChat(payload, LABEL_EXPLAIN, AiConfig.thinkingMode))
    }

    /**
     * 第二轮：解释某个概念，逐段吐出。
     *
     * 语境有两种载体：[context] 是从解释里的链接跳进来时带上的来源文字，
     * [imageFile] 是从概念列表进来时带的那张截图。两者至少有一个非空。
     *
     * **带图的目的和早先完全不同。** 早先是让模型「结合用户正在读的这段原文解释」——
     * 那样产出的解释绑死某一本书，存进本地库就没法复用。
     * 现在是让它「判断用户此刻要的是哪个义项」，而**解释内容仍然要求脱离语境**。
     * 这两件事必须分开，见 [Prompts.EXPLAIN_SYSTEM] 的注释。
     */
    fun explainStream(term: String, context: String, imageFile: File?): Flow<String> = flow {
        requireConfigured()

        val payload = buildRequestBody(
            imageFile = imageFile,
            systemPrompt = Prompts.EXPLAIN_SYSTEM,
            userPrompt = Prompts.explainUser(term, context, hasImage = imageFile != null),
            jsonMode = false,
            stream = true
        )

        client.newCall(newRequest(payload, LABEL_EXPLAIN, AiConfig.thinkingMode)).execute().use { response ->
            if (!response.isSuccessful) {
                throw DeepSeekException(describeHttpError(response.code, response.body.string()))
            }
            emitDeltas(response)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 重新解释：把已有解释和当前语境发给模型，让它决定补一条还是换一条。
     *
     * 这是唯一一个非流式的生成请求。理由见 [Prompts.REFINE_SYSTEM] 的注释：
     * 输出很短，而且要先解析完才能决定往库里写什么。
     *
     * [existing] 是库里已有的「标签 → 正文」，必须全发过去 ——
     * 只发「请重新解释」的话模型会重复一遍已经有的含义。
     *
     * 语境的载体和 [explainStream] 一样：来源文字或截图，至少一个非空。
     */
    suspend fun refine(
        term: String,
        context: String,
        existing: List<Pair<String, String>>,
        imageFile: File?
    ): RefineDecision = withContext(Dispatchers.IO) {
        requireConfigured()

        val payload = buildRequestBody(
            imageFile = imageFile,
            systemPrompt = Prompts.REFINE_SYSTEM,
            userPrompt = Prompts.refineUser(term, context, existing, hasImage = imageFile != null),
            jsonMode = true,
            stream = false
        )

        parseRefine(executeChat(payload, LABEL_EXPLAIN, AiConfig.thinkingMode))
    }

    /**
     * 对话：把整段消息历史发过去，逐段吐回回答。
     *
     * ## 图片挂在哪一条上
     *
     * [imageFile] 会被挂到**最后一条 user 消息**上，而不是每次都新造一条。
     * 调用方传进来的历史里，第一条 user 就是带图那一轮的开场指令 ——
     * 所以从第二轮开始，图实际上来自历史本身、跟着前缀一起被缓存，
     * 这里的参数只在首轮真正起作用。
     *
     * 这个设计不是省事，是有意的：**历史里那条带图的消息必须原样保留**。
     * 如果为了"省 token"在后续轮次里把它换成一句"[用户发来一张图]"，
     * 从那条消息起前缀就变了，DeepSeek 的自动前缀缓存全线失效 ——
     * 而图片那部分 token 本来是按命中价（约未命中的五十分之一）算的。
     * 图每轮都在请求体里，这一点不假；但成本不是线性增长的。
     *
     * ## 与 [explainStream] 的区别
     *
     * 那个是"一次问答"：system + 一条 user，问完就结束。
     * 这个是"多轮会话"：调用方维护历史，每一轮把全部历史重发一遍。
     * 后者贵在请求体大，靠前缀缓存摊平。
     */
    fun chatStream(
        history: List<ChatMessage>,
        imageFile: File?
    ): Flow<String> = flow {
        requireConfigured()
        if (history.isEmpty()) return@flow

        val payload = buildConversation(
            history = history,
            // 用户自己写的两段由 Prompts 拼进来。这里读 ChatProfile 而不是
            // AiConfig：那是提示词的素材，不是发给服务端的请求参数
            systemPrompt = Prompts.chatSystem(ChatProfile.background, ChatProfile.skill),
            imageFile = imageFile,
            stream = true
        )

        client.newCall(newRequest(payload, LABEL_CHAT, AiConfig.chatThinkingMode)).execute().use { response ->
            if (!response.isSuccessful) {
                throw DeepSeekException(describeHttpError(response.code, response.body.string()))
            }
            emitDeltas(response)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 把一批对话摘成一段摘要。给上下文压缩用。
     *
     * 非流式，因为它的产出不是给人看的 —— 它是下一次请求的输入。
     * 等它写完再发下一个请求是应该的，没有"边写边显示"的需求。
     */
    suspend fun summarize(text: String): String = withContext(Dispatchers.IO) {
        requireConfigured()

        val payload = JSONObject().apply {
            put("model", AiConfig.model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", Prompts.SUMMARY_SYSTEM)
                })
                // 摘要请求不带图：要摘的是对话内容本身，图帮不上忙，
                // 而带上它反而让这次请求重新走一遍图片的 token
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
            put("stream", false)
            put("max_tokens", SUMMARIZE_MAX_TOKENS)
            put("thinking", JSONObject().put("type", "disabled"))
            put("reasoning_effort", "none")
        }.toString()

        executeChat(payload, LABEL_SUMMARY, "off").trim()
    }

    // ------------------------------------------------------------------ 请求构造

    /**
     * 多轮对话的请求体。
     *
     * 只有**最后一条 user** 挂图。历史里较早的 user 消息（也就是带图那轮）
     * 在它自己那一轮就已经带过图了 —— 那时它是"最后一条"，所以图在历史里。
     * 现在它既不是最后一条、也不该被重新构造，原样发出去即可。
     */
    private fun buildConversation(
        history: List<ChatMessage>,
        systemPrompt: String,
        imageFile: File?,
        stream: Boolean
    ): String {
        val lastUserIndex = history.indexOfLast { it.role == ChatMessage.Role.User }

        val messages = JSONArray().apply {
            // system 不允许带图，带了直接 400
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })

            history.forEachIndexed { index, message ->
                put(JSONObject().apply {
                    put("role", message.role.wire)
                    put(
                        "content",
                        if (index == lastUserIndex) {
                            userContent(imageFile, message.text)
                        } else {
                            message.text
                        }
                    )
                })
            }
        }

        return JSONObject().apply {
            put("model", AiConfig.model)
            put("messages", messages)
            put("stream", stream)
            put("max_tokens", AiConfig.maxTokens)
            // 对话用自己那一档思考强度，和解释互不影响。见 AiConfig.chatThinkingMode
            put(
                "thinking",
                JSONObject().put("type", if (AiConfig.chatThinkingEnabled) "enabled" else "disabled")
            )
            put("reasoning_effort", AiConfig.chatReasoningEffort)
        }.toString()
    }

    /** 把 SSE 流里的 delta 逐个吐出来。两条流式路径共用，避免抄两遍漏掉某一种错误 */
    private suspend fun FlowCollector<String>.emitDeltas(response: Response) {
        val source = response.body.source()
        while (true) {
            val line = source.readUtf8Line() ?: break

            // SSE 流里除了 data: 还有 event:、id:、空行和注释行，一律跳过
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") break

            val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue

            // 流到一半出错时，服务端会把错误也塞进一条 data: 里
            chunk.optJSONObject("error")?.let { err ->
                throw DeepSeekException("生成中断：" + err.optString("message", "未知错误"))
            }

            // 取正文增量。**必须用 opt + as? String，不能用 optString。**
            //
            // 思考模式下每个分片是 `delta.content = null`（null 是 JSON 的 null，
            // 不是字符串），思考内容在 delta.reasoning_content 里、先流完才轮到正文。
            // 而 Android 的 `JSONObject.optString` 对 JSON null 返回的是**字符串 "null"**
            // —— 于是每个思考分片都会被当成一个正文增量吐出去。
            //
            // 真机上就是这么炸的：对话默认开了 high 档思考，开场白前面挂了
            // 两百多个 "null"。这个 bug 只在思考模式打开时出现，
            // 而在此之前解释和对话默认都是 off，所以一直没露过面。
            //
            // 实现挪到了 [sseContentDelta]，为的是能脱离网络单测它 ——
            // 这个函数错了不会有任何报错，只会让界面上多出一串垃圾字符。
            val delta = sseContentDelta(chunk)

            // 思考分片落在这一句上：content 是 null，什么都不 emit。
            // reasoning_content 我们有意不显示 —— 那是模型的草稿，
            // 用户在等的是答案，把草稿也流出来只会更乱
            if (!delta.isNullOrEmpty()) emit(delta)
        }
    }

    private fun buildRequestBody(
        imageFile: File?,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean,
        stream: Boolean
    ): String {
        val messages = JSONArray().apply {
            // system 消息不允许带图，带了直接 400。这里只放纯文本指令
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userContent(imageFile, userPrompt))
            })
        }

        return JSONObject().apply {
            put("model", AiConfig.model)
            put("messages", messages)
            put("stream", stream)
            put("max_tokens", AiConfig.maxTokens)

            // 思考模式。两个字段都发：thinking.type 是显式开关，
            // reasoning_effort=none 是官方另一条等价路径，任一生效都能关掉。
            // 不关的话，DeepSeek 默认按「开启 + high」跑，首字延迟从一两秒涨到十几秒。
            put(
                "thinking",
                JSONObject().put("type", if (AiConfig.thinkingEnabled) "enabled" else "disabled")
            )
            put("reasoning_effort", AiConfig.reasoningEffort)

            if (jsonMode) {
                put("response_format", JSONObject().put("type", "json_object"))
            }
        }.toString()
    }

    /**
     * user 消息的 content。
     *
     * 有图时必须用「块数组」格式（这是带图的固定协议），文字在前、图在后 ——
     * 先把要它干什么说清楚，再让它看图，比反过来稳。
     * 没有图时**必须用纯字符串**：块数组在无图请求里虽然不是错误，但
     * 会让部分模型把内容当成多模态输入去解析，平白多一层不确定性。
     */
    private fun userContent(imageFile: File?, prompt: String): Any {
        if (imageFile == null) return prompt

        val dataUrl = "data:image/jpeg;base64," +
            Base64.getEncoder().encodeToString(imageFile.readBytes())

        return JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", dataUrl)
                })
            })
        }
    }

    /**
     * 打一条出站日志再发出去。
     *
     * [label] 和 [effort] 由调用方给，因为这四路请求用的思考强度不是同一个值
     * （解释一档、对话一档、摘要固定关掉）。日志里不写清是哪一路、哪一档，
     * 出了问题就只能靠猜 —— 而"我明明调成了 off 怎么还这么慢"这类疑问，
     * 最常见的原因正是看错了是哪一路的档位。
     */
    private fun newRequest(jsonBody: String, label: String, effort: String): Request {
        Log.i(TAG, "→ $label · ${AiConfig.model} · thinking=$effort · ${jsonBody.length}B")

        return Request.Builder()
            .url(AiConfig.baseUrl + AiConfig.CHAT_COMPLETIONS_PATH)
            .header("Authorization", "Bearer ${AiConfig.apiKey}")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()
    }

    // ------------------------------------------------------------------ 响应处理

    private fun executeChat(jsonBody: String, label: String, effort: String): String {
        client.newCall(newRequest(jsonBody, label, effort)).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw DeepSeekException(describeHttpError(response.code, text))
            }

            val root = runCatching { JSONObject(text) }.getOrElse {
                throw DeepSeekException("服务端返回的不是 json，原文开头：\n${text.take(200)}")
            }

            // 同样用 opt + as? String：content 是 JSON null 时 optString 会给出
            // 字符串 "null"（见 emitDeltas 里那段注释），那会变成一个假的回答内容
            return root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.opt("content") as? String
                ?: ""
        }
    }

    private fun parseConcepts(raw: String): List<Concept> {
        if (raw.isBlank()) {
            // DeepSeek 文档自己承认 JSON 模式有概率返回空 content。
            // 让用户重试一次，比硬着头皮显示「没有概念」诚实
            throw DeepSeekException("模型这次返回了空内容。这是 JSON 模式的偶发问题，再框一次通常就好。")
        }

        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        val root = runCatching { JSONObject(cleaned) }.getOrElse {
            throw DeepSeekException("模型返回的不是合法 json，没法解析。原文开头：\n${raw.take(200)}")
        }

        val array = root.optJSONArray("concepts") ?: JSONArray()
        val result = ArrayList<Concept>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val term = item.optString("term").trim()
            if (term.isEmpty()) continue
            result += Concept(
                term = term,
                type = item.optString("type").trim(),
                hint = item.optString("hint").trim()
            )
        }
        return result
    }

    /**
     * 解析「重新解释」的决策。
     *
     * **解析失败时退化成 [RefineDecision.Keep]，不抛异常。** 这不是偷懒：
     * 这个请求的产出是一处"锦上添花"的修改，为了它把用户眼前的界面打成
     * 错误页，代价远大于收益。什么都不做，用户重试一次就行。
     * 唯一例外是空返回 —— 那通常意味着 JSON 模式没触发（模型直接说了句
     * 人话），说明这次的 prompt 组合有问题，值得让用户知道。
     */
    private fun parseRefine(raw: String): RefineDecision {
        if (raw.isBlank()) return RefineDecision.Keep

        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        val root = runCatching { JSONObject(cleaned) }.getOrNull()
            ?: return RefineDecision.Keep

        return when (root.optString("action").trim().lowercase()) {
            "add" -> {
                val tag = root.optString("tag").trim()
                val body = root.optString("body").trim()
                if (tag.isEmpty() || body.isEmpty()) RefineDecision.Keep
                else RefineDecision.Add(tag, body)
            }

            "replace" -> {
                val target = root.optString("targetTag").trim()
                val body = root.optString("body").trim()
                // targetTag 空的话没法定位要改哪一条，只能放弃这次修改
                if (target.isEmpty() || body.isEmpty()) RefineDecision.Keep
                else RefineDecision.Replace(target, body)
            }

            else -> RefineDecision.Keep
        }
    }

    /**
     * 把 HTTP 错误翻译成「用户照着做就能解决」的中文。
     *
     * 不做这一步的话，用户看到的是「HTTP 401」—— 他知道出错了，
     * 但不知道是 key 的问题、余额的问题，还是模型名写错了。
     */
    private fun describeHttpError(code: Int, body: String): String {
        val serverSaid = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty().trim()

        val explanation = when (code) {
            400 -> "请求被拒绝。如果服务端提示模型不支持图片，" +
                "去「设置 → AI 服务 → 模型」把它改回支持图片的模型（比如 deepseek-flash）。"
            401 -> "API Key 无效或已过期。\n" +
                "去「设置 → AI 服务 → API Key」换一个。" +
                "改完立刻生效，不用重新编译。"
            402 -> "账户余额不足，去 platform.deepseek.com 充值。"
            422 -> "请求参数有问题，通常是模型名写错了。" +
                "当前用的模型是「${AiConfig.model}」，去「设置 → AI 服务 → 模型」核对。"
            429 -> "请求太频繁，等几秒再试。"
            in 500..599 -> "DeepSeek 服务端出了点问题（$code），过一会儿再试。"
            else -> "请求失败，HTTP $code。"
        }

        return if (serverSaid.isNotEmpty()) "$explanation\n\n服务端原话：$serverSaid" else explanation
    }

    private fun requireConfigured() {
        if (!AiConfig.isConfigured) {
            throw DeepSeekException(
                "还没填 API Key。\n\n" +
                    "回到 App 首屏，点底部栏的「设置」→ AI 服务 → 填写 API Key。" +
                    "填完立刻生效，不用重新编译。"
            )
        }
    }

    private companion object {
        const val TAG = "DeepSeek"

        /**
         * 日志里的路径名。
         *
         * 四路请求分属三种用途，用的思考强度也不是同一个值 ——
         * 日志不写清是哪一路，慢的时候根本判断不了该去调哪个开关。
         */
        const val LABEL_EXPLAIN = "解释"
        const val LABEL_CHAT = "对话"
        const val LABEL_SUMMARY = "摘要"

        /**
         * 摘要的输出上限。
         *
         * 比正常回答小得多：摘要要的是「把问过什么、结论是什么、还留着什么
         * 没解决」压成一段，而不是复述每一句话。给它和正文一样长的额度，
         * 它会顺手把对话抄一遍 —— 那就完全失去压缩的意义了。
         */
        const val SUMMARIZE_MAX_TOKENS = 1024
    }
}
