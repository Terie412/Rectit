package com.example.explaindot.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * GitHub 适配器的测试。
 *
 * **在本地起一个真的 HTTP 服务**（JDK 自带的 `com.sun.net.httpserver`，不用
 * 新加 mockwebserver），把适配器指过去。所以这里验的是真实的请求构造与响应解析：
 * URL 怎么拼、请求头带没带、base64 对不对、状态码怎么翻成人话。
 *
 * 唯一没盖住的是"GitHub 真的会这么回吗" —— 那要有令牌和网络，等真机上验。
 */
class GitHubTargetTest {

    private lateinit var server: FakeGitHub

    private val config = GitHubConfig(
        token = "ghp_test_token",
        owner = "octocat",
        repo = "my-notes",
        branch = "main",
        path = "knowledge.json"
    )

    private fun target(cfg: GitHubConfig = config) =
        GitHubTarget(base = server.base, config = { cfg })

    @Before
    fun setUp() {
        server = FakeGitHub()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ------------------------------------------------------------------ 下载

    @Test
    fun `下载：请求打到 contents 接口，带 raw 媒体类型和令牌`() = runBlocking {
        server.reply = { Reply(200, "{}") }

        target().pull()

        val req = server.requests.single()
        assertEquals("GET", req.method)
        assertEquals("/repos/octocat/my-notes/contents/knowledge.json", req.decodedPath)
        assertEquals("main", req.ref)
        assertEquals("Bearer ghp_test_token", req.header("authorization"))
        // raw 而不是默认的 JSON：JSON 那份对超过 1MB 的文件会把内容留空，
        // 那会变成「下载成功但内容为空」
        assertEquals("application/vnd.github.raw", req.header("accept"))
    }

    @Test
    fun `下载：把远端字节原样返回，一个字节都不动`() = runBlocking {
        // 用真的二进制测，不是可读字符串 —— 现在搬的是数据库文件，
        // 而这段代码最该被证明的就是"不碰字节"。早先按 UTF-8 解成字符串的写法
        // 会把不合法的字节序列换成 U+FFFD，静默毁掉一个库
        val raw = ByteArray(256) { it.toByte() }
        server.reply = { Reply(200, bytes = raw) }

        assertArrayEquals(raw, target().pull())
    }

    @Test
    fun `下载：远端还没有这份文件时返回 null，不报错`() = runBlocking {
        server.reply = { Reply(404, """{"message":"Not Found"}""") }
        assertNull(target().pull())
    }

    @Test
    fun `下载：401 给出能照着做的提示`() = runBlocking {
        server.reply = { Reply(401, """{"message":"Bad credentials"}""") }
        val e = runCatching { target().pull() }.exceptionOrNull()
        assertTrue(e is SyncException)
        assertTrue(e!!.message!!.contains("401"))
        // 服务端原话要带上，用户拿这两句就能自己判断
        assertTrue(e.message!!.contains("Bad credentials"))
    }

    @Test
    fun `下载：服务端出错时说清是谁的问题`() = runBlocking {
        server.reply = { Reply(500, """{"message":"boom"}""") }
        val e = runCatching { target().pull() }.exceptionOrNull()
        assertTrue(e is SyncException)
        assertTrue(e!!.message!!.contains("500"))
        // 这是 GitHub 自己的问题，不该让用户去查自己的配置
        assertTrue(e.message!!.contains("GitHub"))
    }

    // ------------------------------------------------------------------ 上传

    @Test
    fun `上传：远端没有文件时新建，不带 sha`() = runBlocking {
        val snapshot = blob("场")
        server.reply = { req ->
            if (req.method == "GET") Reply(404, """{"message":"Not Found"}""")
            else Reply(201, """{"content":{"sha":"new"}}""")
        }

        val message = target().push(snapshot)

        assertTrue("结果里该说明传到哪儿了", message.contains("octocat/my-notes"))

        val put = server.requests.last()
        assertEquals("PUT", put.method)
        // 新建时不能带 sha —— 带了 GitHub 会因为"文件不存在"而拒绝
        assertFalse("新建请求里不该有 sha", put.body.contains("\"sha\""))

        val body = org.json.JSONObject(put.body)
        assertEquals("框选解释 · 同步知识库", body.getString("message"))
        assertEquals("main", body.getString("branch"))
        // 内容要 base64 编好，而且要和原始字节逐字节对得上 ——
        // 这是搬二进制之后唯一能接受的等价性标准
        val decoded = Base64.getDecoder().decode(body.getString("content"))
        assertArrayEquals(blob("场"), decoded)
    }

    @Test
    fun `上传：远端已有文件时带上它的 sha`() = runBlocking {
        val snapshot = blob("场")
        val old = blob("熵")
        server.reply = { req ->
            if (req.method == "GET") Reply(200, contentsJson("sha_1", old))
            else Reply(200, """{"content":{"sha":"sha_2"}}""")
        }

        target().push(snapshot)

        val payload = org.json.JSONObject(server.requests.last().body)
        // 更新已有文件必须带上当前 sha，否则 GitHub 直接 422
        assertEquals("sha_1", payload.getString("sha"))
        assertEquals("main", payload.getString("branch"))
        assertArrayEquals(snapshot, Base64.getDecoder().decode(payload.getString("content")))
    }

    @Test
    fun `上传：内容已经一致时一个 PUT 都不发`() = runBlocking {
        val snapshot = blob("场")
        server.reply = { Reply(200, contentsJson("sha_1", snapshot)) }

        val message = target().push(snapshot)

        assertEquals("只该有一次 GET", 1, server.requests.size)
        assertEquals("GET", server.requests.single().method)
        assertTrue("该明说没改动", message.contains("一致"))
    }

    @Test
    fun `上传：撞上 409 会重取 sha 再写一次`() = runBlocking {
        val snapshot = blob("场")
        val old = blob("熵")
        var getCount = 0
        server.reply = { req ->
            when (req.method) {
                "GET" -> {
                    getCount++
                    val sha = if (getCount == 1) "sha_1" else "sha_2"
                    Reply(200, contentsJson(sha, old))
                }
                // 第一次写撞车（有人在这一瞬间推过），第二次成功
                else -> if (server.putCount() == 1) {
                    Reply(409, """{"message":"does not match"}""")
                } else {
                    Reply(200, """{"content":{"sha":"sha_3"}}""")
                }
            }
        }

        target().push(snapshot)

        val puts = server.requests.filter { it.method == "PUT" }
        assertEquals("该写了两次", 2, puts.size)
        assertTrue("第一次带旧 sha", puts[0].body.contains("sha_1"))
        assertTrue("重试要用新取的 sha", puts[1].body.contains("sha_2"))
    }

    @Test
    fun `上传：重试还是撞车就明说，不假装成功`() = runBlocking {
        val snapshot = blob("场")
        val old = blob("熵")
        server.reply = { req ->
            if (req.method == "GET") Reply(200, contentsJson("sha_1", old))
            else Reply(409, """{"message":"does not match"}""")
        }

        val e = runCatching { target().push(snapshot) }.exceptionOrNull()
        assertTrue(e is SyncException)
        assertTrue(e!!.message!!.contains("又被改掉"))
    }

    @Test
    fun `上传：422 但分支在，说明只是 sha 过期，重取一次就能写成功`() = runBlocking {
        val snapshot = blob("场")
        val old = blob("熵")
        var contentGets = 0
        server.reply = { req ->
            when {
                // 分支存在性查询也要先于通用的 GET 判断 —— 它本身就是个 GET
                req.decodedPath.endsWith("/branches/main") -> Reply(200, """{"name":"main"}""")
                req.method == "GET" -> {
                    contentGets++
                    Reply(200, contentsJson(if (contentGets == 1) "sha_1" else "sha_2", old))
                }
                else -> if (server.putCount() == 1) {
                    Reply(422, """{"message":"Invalid request."}""")
                } else {
                    Reply(200, """{"content":{"sha":"sha_3"}}""")
                }
            }
        }

        target().push(snapshot)

        val puts = server.requests.filter { it.method == "PUT" }
        assertEquals("该写了两次", 2, puts.size)
        assertTrue("重试要用新取的 sha", puts[1].body.contains("sha_2"))
    }

    @Test
    fun `上传：422 且分支不存在时，说的是分支而不是「远端被改了」`() = runBlocking {
        server.reply = { req ->
            when {
                req.decodedPath.endsWith("/branches/main") ->
                    Reply(404, """{"message":"Branch not found"}""")
                req.method == "GET" -> Reply(404, "{}")
                else -> Reply(422, """{"message":"Validation Failed"}""")
            }
        }

        val e = runCatching { target().push(blob("场")) }
            .exceptionOrNull()

        assertTrue(e is SyncException)
        val msg = e!!.message.orEmpty()
        // 要点出真正的原因，并且给一条能自己修好的路
        assertTrue("该点明是哪个分支：$msg", msg.contains("main"))
        assertTrue("该说明这是分支的问题：$msg", msg.contains("分支"))
        // 关键：不能再说成"远端又被改掉了"。那句话会让人一直重试，
        // 而重试一百次也不会成功，真正的原因（仓库是空的）还完全没露面
        assertFalse("不该再给那句误导的话：$msg", msg.contains("又被改掉"))
        // 已经问清楚是分支的问题了，不该再白写一次
        assertEquals("分支不存在时不该重试 PUT", 1, server.putCount())
    }

    @Test
    fun `上传：路径里的中文和空格被正确编码`() = runBlocking {
        val snapshot = blob("场")
        server.reply = { req ->
            if (req.method == "GET") Reply(404, "{}") else Reply(201, "{}")
        }

        target(config.copy(path = "资料/我的 笔记.json")).push(snapshot)

        val get = server.requests.first()
        // 原始 URI 必须是编码过的（空格是 %20），否则请求根本不合法
        assertFalse("原始路径里不该有裸空格", get.rawPath.contains(' '))
        assertTrue(get.rawPath.contains("%20"))
        // 而服务端解出来要是原来那个路径
        assertEquals("/repos/octocat/my-notes/contents/资料/我的 笔记.json", get.decodedPath)
    }

    @Test
    fun `上传：分支名带斜杠时被编码进查询串`() = runBlocking {
        server.reply = { req ->
            if (req.method == "GET") Reply(404, "{}") else Reply(201, "{}")
        }
        target(config.copy(branch = "feature/x")).push(blob("场"))

        val get = server.requests.first()
        // 斜杠必须编码进查询串，否则 `ref=feature/x` 里的斜杠会让服务端
        // 把它当成路径的一部分
        assertEquals("ref=feature%2Fx", get.rawQuery)
        assertEquals("feature/x", get.ref)
    }

    @Test
    fun `上传：403 的提示会带上服务端原话`() = runBlocking {
        server.reply = { Reply(403, """{"message":"Resource not accessible by personal access token"}""") }
        val e = runCatching { target().push(blob("场")) }.exceptionOrNull()
        assertTrue(e is SyncException)
        assertTrue(e!!.message!!.contains("403"))
        assertTrue(e.message!!.contains("Resource not accessible"))
    }

    @Test
    fun `没配置好时当场抛错，一个请求都不发`() = runBlocking {
        val e = runCatching {
            target(config.copy(token = "")).push(blob("场"))
        }.exceptionOrNull()
        assertTrue(e is SyncException)
        assertTrue("该告诉用户去填哪一项", e!!.message!!.contains("GitHub"))
        assertTrue(server.requests.isEmpty())
    }

    // ------------------------------------------------------------------ 展示

    @Test
    fun `摘要显示东西存在哪，但不显示用户改不了的东西`() {
        // 只有账号/仓库和分支 —— 这两项用户能改、因此可能配错，值得让他核对。
        // 文件路径是常量（永远 knowledge.json），摆在摘要里只是噪音；
        // 用户看到它之后的反应就是"为什么还有个 knowledge.json"
        assertEquals("octocat/my-notes · main", target().summary)
        assertFalse("常量不该出现在摘要里", target().summary.contains("knowledge.json"))

        assertEquals("还没配置", target(config.copy(token = "")).summary)
        assertTrue(target().isConfigured)
        assertFalse(target(config.copy(repo = "")).isConfigured)
    }

    @Test
    fun `上传成功那句话带上文件名`() = runBlocking {
        server.reply = { req ->
            if (req.method == "GET") Reply(404, "{}") else Reply(201, "{}")
        }

        val message = target().push(blob("场"))

        // 摘要里不显示了，但"这次写的是哪个文件"在这里有用：刚写完可能想点去看
        assertTrue("该点出写的是哪个文件：$message", message.contains("knowledge.json"))
    }

    @Test
    fun `账号名或分支还空着就算没配置好`() {
        // 这两个值现在由保存时补全（以前是用户手填）。还没补全就没有依据去拼地址，
        // 所以卡片该老实说"还没配置"，而不是显示一个缺了一段的摘要
        assertFalse(target(config.copy(owner = "")).isConfigured)
        assertFalse(target(config.copy(branch = "")).isConfigured)
        assertEquals("还没配置", target(config.copy(branch = "")).summary)
    }

    // ------------------------------------------------------------------ 配置规范化

    @Test
    fun `令牌：剥掉粘贴时常见的包装`() {
        assertEquals("ghp_x", GitHubConfig.normalizeToken("  ghp_x  "))
        assertEquals("ghp_x", GitHubConfig.normalizeToken("Bearer ghp_x"))
        assertEquals("ghp_x", GitHubConfig.normalizeToken("\"ghp_x\""))
        assertEquals("ghp_x", GitHubConfig.normalizeToken("`ghp_x`"))
    }

    @Test
    fun `仓库名：三种写法都认`() {
        assertEquals("my-notes", GitHubConfig.normalizeRepo("my-notes"))
        assertEquals("my-notes", GitHubConfig.normalizeRepo("my-notes/"))
        assertEquals("my-notes", GitHubConfig.normalizeRepo("my-notes.git"))
        assertEquals("my-notes", GitHubConfig.normalizeRepo(" my-notes "))
        // 浏览器地址栏里粘来的整条
        assertEquals(
            "octocat/my-notes",
            GitHubConfig.normalizeRepo("https://github.com/octocat/my-notes")
        )
        assertEquals(
            "octocat/my-notes",
            GitHubConfig.normalizeRepo("github.com/octocat/my-notes.git")
        )
    }

    @Test
    fun `仓库名：只取仓库那一段，斜杠绝不能漏进 URL`() {
        assertEquals("my-notes", GitHubConfig.repoNameOnly("my-notes"))
        assertEquals("my-notes", GitHubConfig.repoNameOnly("octocat/my-notes"))
        assertEquals("my-notes", GitHubConfig.repoNameOnly("https://github.com/octocat/my-notes"))
        // 从浏览器里粘来的深链接：只有前两段有意义
        assertEquals(
            "my-notes",
            GitHubConfig.repoNameOnly("https://github.com/octocat/my-notes/tree/main")
        )
    }

    @Test
    fun `仓库名：框里自己写了组织名就认组织名`() {
        assertEquals("", GitHubConfig.explicitOwner("my-notes"))
        assertEquals("octocat", GitHubConfig.explicitOwner("octocat/my-notes"))
        assertEquals("my-org", GitHubConfig.explicitOwner("https://github.com/my-org/my-notes"))
    }

    // ------------------------------------------------------------------ 保存时补全配置

    /** `resolve` 的入参：用户真的只填这两项 */
    private fun typed(token: String = "ghp_x", repo: String = "my-notes") =
        mapOf(GitHubFields.TOKEN.key to token, GitHubFields.REPO.key to repo)

    /** 一个"正常的 GitHub"：/user 给账号名，/repos/... 给默认分支 */
    private fun healthyGitHub(login: String = "octocat", branch: String = "main") {
        server.reply = { req ->
            when {
                req.decodedPath == "/user" -> Reply(200, """{"login":"$login"}""")
                req.decodedPath.startsWith("/repos/") ->
                    Reply(200, """{"default_branch":"$branch"}""")
                else -> Reply(404, "{}")
            }
        }
    }

    @Test
    fun `补全：问出账号名和默认分支，并带上要存的仓库名`() = runBlocking {
        healthyGitHub()

        val derived = target().resolve(typed())

        assertEquals("账号名从 GET /user 来", "octocat", derived[GitHubFields.CACHED_OWNER])
        assertEquals(
            "分支从仓库的默认分支来",
            "main",
            derived[GitHubFields.CACHED_BRANCH]
        )
        assertEquals("仓库名一并回写", "my-notes", derived[GitHubFields.REPO.key])
        // 请求头带上了令牌 —— 这两个接口都要鉴权
        assertTrue(server.requests.all { it.header("authorization") == "Bearer ghp_x" })
    }

    @Test
    fun `补全：框里粘了整条地址也会被规整成裸仓库名`() = runBlocking {
        healthyGitHub()

        val derived = target()
            .resolve(typed(repo = "https://github.com/octocat/my-notes"))

        // 这一条是拼 URL 的前提：带斜杠的仓库名会被当成路径分隔符编码成 %2F，
        // 那个地址在 GitHub 上根本不存在
        assertEquals("my-notes", derived[GitHubFields.REPO.key])
        assertEquals("octocat", derived[GitHubFields.CACHED_OWNER])
    }

    @Test
    fun `补全：写了组织名就不去问账号名`() = runBlocking {
        healthyGitHub(login = "应该没被用到")

        val derived = target().resolve(typed(repo = "my-org/my-notes"))

        // 组织仓库的 owner 是组织，而 GET /user 只会给出令牌主人 —— 两者不同，
        // 所以用户自己写了组织名时，自动取的那个值是错的，不能覆盖他
        assertEquals("my-org", derived[GitHubFields.CACHED_OWNER])
        assertFalse(
            "不该白问一次 /user",
            server.requests.any { it.decodedPath == "/user" }
        )
    }

    @Test
    fun `补全：令牌不认时当场说清楚`() = runBlocking {
        server.reply = { Reply(401, """{"message":"Bad credentials"}""") }

        val e = runCatching { target().resolve(typed(token = "bad")) }.exceptionOrNull()

        assertTrue(e is SyncException)
        val msg = e!!.message.orEmpty()
        assertTrue("该点明是令牌的问题：$msg", msg.contains("令牌"))
        // 令牌都不认，就没必要再去问仓库了
        assertEquals("只该发一个请求", 1, server.requests.size)
    }

    @Test
    fun `补全：看不到仓库时说仓库或授权，不能说成令牌过期`() = runBlocking {
        server.reply = { req ->
            if (req.decodedPath == "/user") Reply(200, """{"login":"octocat"}""")
            else Reply(404, """{"message":"Not Found"}""")
        }

        val e = runCatching { target().resolve(typed(repo = "no-such-repo")) }
            .exceptionOrNull()

        assertTrue(e is SyncException)
        val msg = e!!.message.orEmpty()
        // 账号名是刚问出来的，所以嫌疑只剩仓库名和授权范围
        assertTrue("该点出仓库全名：$msg", msg.contains("octocat/no-such-repo"))
        assertTrue("该提到授权范围：$msg", msg.contains("Repository access"))
        assertFalse("令牌是好的，不该说它过期：$msg", msg.contains("过期"))
    }

    @Test
    fun `补全：必填项空着就当场拦住，一个请求都不发`() = runBlocking {
        healthyGitHub()

        val noToken = runCatching { target().resolve(typed(token = "")) }.exceptionOrNull()
        val noRepo = runCatching { target().resolve(typed(repo = "  ")) }.exceptionOrNull()

        assertTrue(noToken is SyncException)
        assertTrue(noRepo is SyncException)
        assertTrue("空着就别发请求", server.requests.isEmpty())
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 一段用来当载体内容的假字节。
     *
     * 同步现在搬的是整个数据库文件，所以内容是二进制。这里用普通字符串转字节
     * 就够了 —— 适配器这一层**不该知道里面是什么**，用任何字节序列测它都一样，
     * 而且用可读的字节更容易看出断言失败时差在哪。
     */
    private fun blob(mark: String): ByteArray = "$mark 的内容".toByteArray(Charsets.UTF_8)

    /** GitHub 的 contents 响应长这样：内容 base64、外面套一层元信息 */
    private fun contentsJson(sha: String, content: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(content)
        return """{"sha":"$sha","encoding":"base64","content":"$b64"}"""
    }
}

/** 请求被收到时的样子 */
private class Recorded(
    val method: String,
    /** 原始（已编码）的路径与查询串 */
    val uri: String,
    val headers: Map<String, String>,
    val body: String
) {
    private val parsed get() = java.net.URI(uri)

    val rawPath: String get() = parsed.rawPath
    val decodedPath: String get() = parsed.path

    /** 完整的原始查询串，例如 `ref=main` */
    val rawQuery: String get() = parsed.rawQuery.orEmpty()

    /**
     * `ref` 参数解码后的值。
     *
     * 用 [java.net.URLDecoder] 是为了把 `%2F` 还原成 `/`，好验证
     * "分支名带斜杠时编解码能对上"。它对 `+` 的处理不是 URL 规范里
     * query 那一套 —— 但这里要验的值里没有 `+`，够用。
     */
    val ref: String get() = runCatching {
        java.net.URLDecoder.decode(rawQuery.substringAfter("ref=", ""), "UTF-8")
    }.getOrDefault("")

    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * 一条假回复。
 *
 * [bytes] 优先于 [body]：默认那条路会把字符串按 UTF-8 编成字节，而那**不是
 * 保字节的**（拿 ISO-8859-1 解出来的字符串再编回 UTF-8，高位字节会变形）。
 * 要测"搬二进制不动一个字节"就必须能直接给字节 —— 真实的 GitHub 也是直接吐字节。
 */
private class Reply(
    val code: Int,
    val body: String = "",
    val bytes: ByteArray? = null
)

/**
 * 本地假 GitHub。
 *
 * 用 JDK 自带的 `HttpServer`，所以不用为测试引一个新依赖。
 * 每个请求都记下来，测试断言的是"适配器到底发了什么"。
 */
private class FakeGitHub {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val requests = CopyOnWriteArrayList<Recorded>()

    /** 由每个测试自己决定怎么回 */
    @Volatile
    var reply: (Recorded) -> Reply = { Reply(404) }

    init {
        server.createContext("/") { exchange: HttpExchange ->
            val body = exchange.requestBody.readBytes()
            val recorded = Recorded(
                method = exchange.requestMethod,
                uri = exchange.requestURI.toString(),
                headers = exchange.requestHeaders.entries
                    .associate { it.key.lowercase() to it.value.joinToString(",") },
                body = String(body, Charsets.UTF_8)
            )
            requests += recorded

            val answer = reply(recorded)
            val bytes = answer.bytes ?: answer.body.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) {
                exchange.sendResponseHeaders(answer.code, -1)
                exchange.responseBody.close()
            } else {
                exchange.sendResponseHeaders(answer.code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
    }

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    fun putCount(): Int = requests.count { it.method == "PUT" }

    fun stop() = server.stop(0)
}
