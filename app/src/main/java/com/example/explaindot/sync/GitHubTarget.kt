package com.example.explaindot.sync

import com.example.explaindot.ai.AiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * GitHub 上的一份文件。走官方 Contents API。
 *
 * ## 配置只有两项
 *
 * 用户只填**访问令牌**和**仓库名**。账号名和分支由 [resolve] 在保存时问出来，
 * 文件路径是 [GitHubFields.SYNC_PATH] 写死的。这不是为了少几个框 ——
 * 每一项都是他能填错的，而他填错时得到的报错指不出是哪里错。
 * 「文件路径」那种框更糟：他没有任何依据去判断该填什么。
 *
 * ## 两个动作分别发什么请求
 *
 * **上传**要两次：先 GET 拿现有文件的 `sha`，再 PUT 写。这不是绕远路 ——
 * 更新已有文件时**必须**带上当前 sha（乐观锁）。不带的话 GitHub 会直接拒绝
 * （422，"sha wasn't supplied"），因为那等于声明"我不在乎现在是什么，覆盖掉"，
 * 而它不允许这种默认行为。
 *
 * GET 顺带把远端内容也取回来了，于是能做一件便宜但值钱的事：
 * **内容一样就跳过 PUT**。不然每点一次上传都会在仓库里多一条空提交，
 * 而选择"仓库里的一个文件"本来就是为了让 diff 有意义。
 *
 * **下载**故意用 `Accept: application/vnd.github.raw` 而不是默认的 JSON。
 * JSON 那份把内容塞在 `content` 字段里做 base64，而**超过 1MB 的文件它会把
 * content 留空**（还标 `encoding: "none"`）—— 那就成了一个静默的
 * "下载成功但内容为空"。raw 直接吐字节，没这个坑，也省一次 base64 解码。
 *
 * ## 关于"强制"
 *
 * 上传不问远端有没有别人的改动，下载也不看本地有什么 —— 就是覆盖。
 * 唯一多做的事：PUT 撞上 409（sha 变了，说明这一瞬有人写过）时重取一次 sha
 * 再写一遍。那是把这次写入做成功，不是"发现冲突就拦住用户"。
 * 422 也会重试，但**先分清是哪一种 422** —— 见 [write]。那边有个坑，
 * 混着处理的后果是给用户一句完全指向错误方向的报错。
 *
 * ## 体积：库文件更大，但主要大在结构上
 *
 * 同一批 34 条，实测（都做过 checkpoint + VACUUM）：
 *
 * ```
 * 纯内容（term+tag+body）     12387 字节
 * JSON（带缩进，就是原来传的那份） 17037 字节
 * JSON（紧凑）                15324 字节
 * 库文件                      36864 字节
 * ```
 *
 * 那 36864 = 9 页 × 4096，拆开是：**5 页装数据**，另外 4 页分别属于表结构、
 * UNIQUE 索引、`AUTOINCREMENT` 产生的 `sqlite_sequence`、以及 Android 遗留的
 * `android_metadata`。**一个只存 8 字节的表照样占满一整页**，所以小数据量下
 * 固定开销占了大头（4 页 = 16384 字节，44%）。
 *
 * JSON 那边也确实浪费（字段名 1496 字节 + 标点空白 3153 字节），
 * 但绝对值比库文件的固定开销小得多。**这是小数据量下的现象**：长到 8000 条时
 * 两者只差 3%，格局基本抹平。所以选哪种格式不该由体积决定。
 *
 * ## 一个上限，但不是 1MB
 *
 * 这里的注释早先写着"上限 1MB"，那是错的。官方的口径是：
 * **读取**时 1MB 以内所有媒体类型都支持，1MB 到 100MB 只能用 raw 或 object
 * 媒体类型（`content` 字段会是空的）—— 而下载走的正是 raw，所以不受这条限制。
 * **写入**没有找到官方给出的数字，社区实测约 50MB 可用。
 *
 * 一条记录的边际成本实测约 470 字节（库文件，1000→8000 条之间量的），
 * 就算按最保守的 1MB 算也是两千多条。容量不是现在要操心的事。
 * 真撞上了 GitHub 会回一个错误，我们会把它的原话摆给用户看 —— 那时要换的是
 * 存储方式（Git Data API 一次提交多棵树，或者 Git LFS），不是调整这里某个参数。
 */
class GitHubTarget(
    /** 只为让测试指向本地起的服务；线上永远是 api.github.com */
    private val base: String = DEFAULT_API,
    private val client: OkHttpClient = defaultClient(),
    /**
     * 配置从哪儿来。默认读 [SyncSettings]；测试注入一份固定值，
     * 于是那些测试完全不碰 SharedPreferences。
     */
    private val config: () -> GitHubConfig = { GitHubConfig.fromSettings() }
) : SyncTarget {

    override val id = "github"

    override val label = "GitHub"

    override val fields: List<ConfigField> get() = GitHubFields.ALL

    override val isConfigured: Boolean get() = config().isComplete

    /**
     * 只显示账号/仓库和分支，**不带文件路径**。
     *
     * 路径曾经在这里，因为那时它是用户填的一项。现在它是常量（[GitHubFields.SYNC_PATH]），
     * 对每个用户、每一次都是同一个值 —— 一行用来核对"配得对不对"的摘要里，
     * 摆一个永远不可能错的项只是噪音。用户也确实这么问了：明明已经没有那个输入框了，
     * 为什么旁边还挂着 `knowledge.json`。
     *
     * 路径本身没消失，它仍然是真的。它换到了上传成功那句话里 ——
     * 那是"想知道文件叫什么"真正有用的时刻：你刚写完，可能想点进仓库去看。
     */
    override val summary: String
        get() = config().let {
            if (it.isComplete) {
                "${it.owner}/${it.repo} · ${it.branch}"
            } else {
                "还没配置"
            }
        }

    override suspend fun pull(): ByteArray? = withContext(Dispatchers.IO) {
        val cfg = requireComplete()
        val response = execute(request(cfg, accept = ACCEPT_RAW))
        response.use {
            when {
                it.isSuccessful -> it.body.bytes()
                // 远端还没有这份文件。这是正常状态（第一次用），不是错误
                it.code == 404 -> null
                else -> throw describeHttpError(it)
            }
        }
    }

    override suspend fun push(snapshot: ByteArray): String = withContext(Dispatchers.IO) {
        val cfg = requireComplete()

        val remote = fetchRemote(cfg)
        if (remote != null && remote.content != null && remote.content.contentEquals(snapshot)) {
            return@withContext "远端已经和本地一致，没有改动。"
        }

        if (write(cfg, snapshot, remote?.sha) == PutOutcome.Written) {
            return@withContext uploaded(cfg)
        }

        // sha 在这一瞬间过期了。重取一次再写 —— 强制语义下目标就是把内容写进去
        val fresh = fetchRemote(cfg)
        if (fresh != null && fresh.content != null && fresh.content.contentEquals(snapshot)) {
            return@withContext "远端已经和本地一致，没有改动。"
        }
        if (write(cfg, snapshot, fresh?.sha) == PutOutcome.Written) {
            return@withContext uploaded(cfg)
        }
        throw SyncException("远端在这一刻又被改掉了，两次都没写进去。稍后再试。")
    }

    /**
     * 上传成功那句话，**带上文件名**。
     *
     * 文件名在卡片上不再显示了（见 [summary]），因为它是常量、永远不变，
     * 摆在一行核对配置的摘要里没有意义。但"这次写的是哪个文件"在这里是有用的：
     * 刚写完的那一刻，你可能想点进仓库去看它。
     */
    private fun uploaded(cfg: GitHubConfig) =
        "已上传到 ${cfg.owner}/${cfg.repo} 的 ${cfg.path}。"

    // ------------------------------------------------------------------ 保存时补全配置

    /**
     * 把「令牌 + 仓库名」补全成一份可用配置。**只在保存时跑。**
     *
     * 两件事，每件都在消掉一个具体的坑：
     *
     *   一、**问出账号名**（`GET /user`）。这个接口不需要任何权限，返回体里的
     *      `login` 就是令牌主人的账号名。于是「用户名」那个框可以整个删掉，
     *      连带消掉"填错了报 404、但提示里指了四个地方不知道错在哪"那一类问题。
     *
     *   二、**问出默认分支并验证仓库**（`GET /repos/{owner}/{repo}`）。分支不该
     *      由用户填（他得先去别处查）。而这一次请求顺带回答了三件他更想早点知道
     *      的事：仓库名对不对、这个令牌看不看得见它、令牌本身有没有过期。
     *      在「保存」这一刻说出来，比等他点上传才报错有用得多。
     *
     * 组织名下的仓库照样能用：把 `组织名/仓库名` 填进「仓库名」框 ——
     * 自动取到的是令牌主人的账号名，而组织仓库的 owner 是组织，两者不同。
     */
    override suspend fun resolve(values: Map<String, String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val token = GitHubConfig.normalizeToken(values[GitHubFields.TOKEN.key].orEmpty())
            val typed = values[GitHubFields.REPO.key].orEmpty()

            if (token.isEmpty()) throw SyncException("访问令牌没填。")
            if (typed.isBlank()) throw SyncException("仓库名没填。")

            val repo = GitHubConfig.repoNameOnly(typed)
            if (repo.isEmpty()) throw SyncException("从「$typed」里读不出仓库名，直接填仓库名就行。")

            // 框里自己写了组织名就听他的，没写才去问账号名
            val owner = GitHubConfig.explicitOwner(typed).ifEmpty { fetchLogin(token) }
            val branch = fetchDefaultBranch(token, owner, repo)

            mapOf(
                GitHubFields.CACHED_OWNER to owner,
                GitHubFields.CACHED_BRANCH to branch,
                // 顺便把写法规整掉：`组织名/仓库名` 或整条地址都存成裸仓库名。
                // 不这么做的话那截斜杠会被当成路径分隔符拼进 URL
                GitHubFields.REPO.key to repo
            )
        }

    /** `GET /user` → 令牌主人的账号名。细粒度令牌访问这个接口**不需要任何权限** */
    private fun fetchLogin(token: String): String {
        val request = Request.Builder()
            .url(base.toHttpUrl().newBuilder().addPathSegment("user").build())
            .header("Authorization", "Bearer $token")
            .header("Accept", ACCEPT_JSON)
            .header("X-GitHub-Api-Version", API_VERSION)
            .get()
            .build()

        return execute(request).use {
            if (!it.isSuccessful) throw describeHttpError(it)
            val login = runCatching { JSONObject(it.body.string()).optString("login") }
                .getOrNull().orEmpty()
            if (login.isEmpty()) throw SyncException("GitHub 没有返回账号名，稍后再试一次。")
            login
        }
    }

    /**
     * `GET /repos/{owner}/{repo}` → 默认分支。
     * 顺带验证这个仓库存在、而且这个令牌看得见它。
     */
    private fun fetchDefaultBranch(token: String, owner: String, repo: String): String {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegment("repos")
            .addPathSegment(owner)
            .addPathSegment(repo)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", ACCEPT_JSON)
            .header("X-GitHub-Api-Version", API_VERSION)
            .get()
            .build()

        return execute(request).use {
            // 这里的 404 含义比别处窄：账号名要么是刚问出来的、要么是用户自己写的，
            // 所以嫌疑只剩仓库名，或者「令牌没被授权访问这个仓库」——
            // 后者在私有仓库上很常见，而且从界面上完全看不出来（令牌看起来好好的）
            if (it.code == 404) {
                throw SyncException(
                    "找不到「$owner/$repo」这个仓库，或者这个令牌看不到它。\n\n" +
                        "先核对仓库名。都写对了的话，检查建令牌时「Repository access」" +
                        "里有没有勾上它 —— 只勾了别的仓库、或者选了 All repositories" +
                        "以外又漏了它，都会是这个结果。"
                )
            }
            if (!it.isSuccessful) throw describeHttpError(it)

            val branch = runCatching {
                JSONObject(it.body.string()).optString("default_branch")
            }.getOrNull().orEmpty()
            if (branch.isEmpty()) {
                throw SyncException("GitHub 没有返回这个仓库的默认分支，稍后再试一次。")
            }
            branch
        }
    }

    // ------------------------------------------------------------------ 远端状态

    /** 远端那份文件现在长什么样。[content] 为 null 表示读不到内容（文件太大） */
    private class Remote(val sha: String, val content: ByteArray?)

    private fun fetchRemote(cfg: GitHubConfig): Remote? {
        val response = execute(request(cfg, accept = ACCEPT_JSON))
        response.use {
            when {
                it.isSuccessful -> Unit
                it.code == 404 -> return null
                else -> throw describeHttpError(it)
            }

            val body = runCatching { JSONObject(it.body.string()) }.getOrNull()
                ?: throw SyncException("GitHub 的回复读不懂，上传中止。")

            val sha = body.optString("sha")
            if (sha.isEmpty()) throw SyncException("GitHub 的回复里没有文件标识，上传中止。")

            // 文件超过 1MB 时 GitHub 不内联内容。那时拿不到内容、也就没法比对，
            // 直接走覆盖 —— 不假装"内容一样"
            val content = if (body.optString("encoding") == "base64") {
                runCatching {
                    // MIME 解码器会忽略换行 —— GitHub 返回的 base64 是折行的
                    Base64.getMimeDecoder().decode(body.optString("content"))
                }.getOrNull()
            } else {
                null
            }
            return Remote(sha, content)
        }
    }

    private enum class PutOutcome { Written, Conflict }

    /** @param sha 远端已有文件的标识；null 表示这次是新建 */
    private fun write(cfg: GitHubConfig, snapshot: ByteArray, sha: String?): PutOutcome {
        val payload = JSONObject().apply {
            put("message", COMMIT_MESSAGE)
            put("content", Base64.getEncoder().encodeToString(snapshot))
            put("branch", cfg.branch)
            if (sha != null) put("sha", sha)
        }.toString()

        val request = Request.Builder()
            .url(contentsUrl(cfg, withRef = false))
            .header("Authorization", "Bearer ${cfg.token}")
            .header("Accept", ACCEPT_JSON)
            .header("X-GitHub-Api-Version", API_VERSION)
            .put(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        execute(request).use {
            if (it.isSuccessful) return PutOutcome.Written
            // 409 = sha 对不上，重取一次就能写成功
            if (it.code == 409) return PutOutcome.Conflict
            if (it.code == 422) {
                // **422 有两个完全不同的原因，不能一律当成"信息过期"去重试。**
                //
                //   一、没带 sha，但远端已经有这个文件 —— 确实过期了，重取 sha 再写就行。
                //   二、branch 在这个仓库里根本不存在 —— 重试多少次都一样。
                //
                // 混在一起处理的后果很具体：第二种会退化成「远端在这一刻又被改掉了，
                // 两次都没写进去」—— 用户会一直重试、永远不成功，而且猜不到真正的原因。
                if (!branchExists(cfg)) throw branchMissing(cfg)
                return PutOutcome.Conflict
            }
            throw describeHttpError(it)
        }
    }

    /**
     * 仓库里有没有这个分支。
     *
     * 只在 PUT 撞上 422 之后才问 —— 那是唯一需要区分 422 两种含义的时刻，
     * 平时不该为此多花一次请求（上传是"点一下"的动作，多一个来回就多一点等待）。
     *
     * **只有明确的 404 才算"没有"。** 401/403 是令牌或权限的问题，
     * 把那些说成"分支不存在"会把用户引到完全错误的方向去排查。
     */
    private fun branchExists(cfg: GitHubConfig): Boolean {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegment("repos")
            .addPathSegment(cfg.owner)
            .addPathSegment(cfg.repo)
            .addPathSegment("branches")
            .apply {
                // 分支名可能带斜杠（feature/x），按段加才是对的
                cfg.branch.split('/').filter { it.isNotEmpty() }
                    .forEach { addPathSegment(it) }
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.token}")
            .header("Accept", ACCEPT_JSON)
            .header("X-GitHub-Api-Version", API_VERSION)
            .get()
            .build()

        return execute(request).use {
            when {
                it.isSuccessful -> true
                it.code == 404 -> false
                else -> true
            }
        }
    }

    /**
     * 配置里记着的分支，仓库里现在没有了。
     *
     * **措辞跟以前不一样了，因为原因变了。** 分支以前是用户填的，所以那时最可能
     * 是"名字抄错"（跟着别处的教程填了 `main`，而这仓库的默认分支叫 `master`）。
     * 现在分支由 [resolve] 从仓库的默认分支取，抄错这个原因不存在了 ——
     * 剩下的可能是**缓存过期**：保存之后那个分支被删了，或者仓库改了默认分支。
     * 所以该叫他做的是"重新保存一次"，而不是"去核对名字"。
     */
    private fun branchMissing(cfg: GitHubConfig): SyncException = SyncException(
        "「${cfg.owner}/${cfg.repo}」里现在没有「${cfg.branch}」这个分支了。\n\n" +
            "配置里记的是上次保存时取到的默认分支，看来它已经被删掉、" +
            "或者这个仓库把默认分支换成了别的名字。重新「配置」一次就会取到当前的。"
    )

    // ------------------------------------------------------------------ 请求构造

    private fun execute(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        throw SyncException(
            "连不上 GitHub。检查网络。\n\n如果网是通的，看一下手机上有没有装代理类应用 —— " +
                "它们的设置经常是残留的，会让请求一律超时。",
            e
        )
    }

    private fun request(cfg: GitHubConfig, accept: String): Request = Request.Builder()
        .url(contentsUrl(cfg, withRef = true))
        .header("Authorization", "Bearer ${cfg.token}")
        .header("Accept", accept)
        .header("X-GitHub-Api-Version", API_VERSION)
        .get()
        .build()

    /**
     * `{base}/repos/{owner}/{repo}/contents/{path}?ref={branch}`
     *
     * 用 [HttpUrl] 拼而不是字符串相加：路径里的空格、中文、`#` 都会被正确
     * 百分号编码 —— 路径是用户填的，而剪贴板里什么都可能有。分支名同理
     * （`feature/x` 这种带斜杠的一点都不稀奇）。
     */
    private fun contentsUrl(cfg: GitHubConfig, withRef: Boolean): HttpUrl {
        val builder = base.toHttpUrl().newBuilder()
            .addPathSegment("repos")
            .addPathSegment(cfg.owner)
            .addPathSegment(cfg.repo)
            .addPathSegment("contents")
        // 路径按段加，保留它的层级（斜杠是分隔符，不是要编码的字符）
        cfg.path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        if (withRef) builder.addQueryParameter("ref", cfg.branch)
        return builder.build()
    }

    private fun requireComplete(): GitHubConfig = config().let {
        if (it.isComplete) it else throw SyncException("同步还没配置好，把「$label」那几项填上。")
    }

    /**
     * 把 HTTP 状态码翻成人话。
     *
     * 和 [com.example.explaindot.ai.DeepSeekClient] 同一个做法：先给一句
     * 我们能判断的解释，再附服务端原话。用户拿着这两句就能自己修好。
     */
    private fun describeHttpError(response: Response): SyncException {
        val serverSaid = runCatching {
            JSONObject(response.body.string()).optString("message")
        }.getOrNull().orEmpty().trim()

        val explanation = when (response.code) {
            401 -> "GitHub 不认这个访问令牌（401）。检查有没有填错，或者它已经过期、被撤销了。"
            403 -> "GitHub 拒绝了这次请求（403）。多半是令牌没有这个仓库的 Contents 读写权限；" +
                "也可能是一小时内请求次数用完了，等一会儿再试。"
            404 -> "找不到这个位置（404）。检查用户名、仓库名、分支、文件路径有没有写错。" +
                "仓库是私有的话，还要确认令牌是给这个仓库发的。"
            in 500..599 -> "GitHub 自己出了点问题（${response.code}），过一会儿再试。"
            else -> "请求失败，HTTP ${response.code}。"
        }

        return SyncException(
            if (serverSaid.isNotEmpty()) "$explanation\n\n服务端原话：$serverSaid" else explanation
        )
    }

    companion object {
        private const val DEFAULT_API = "https://api.github.com"
        private const val API_VERSION = "2022-11-28"
        private const val ACCEPT_JSON = "application/vnd.github+json"
        private const val ACCEPT_RAW = "application/vnd.github.raw"

        /** 仓库里那条提交叫什么。固定一句就够，时间由 git 自己记 */
        private const val COMMIT_MESSAGE = "看图学 · 同步知识库"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .apply {
                // 和 DeepSeekClient 同一个理由（见那里的注释）：代理类 App 塞进系统的
                // HTTP 代理设置经常是残留的，表现成"网明明通着，请求一律超时"。
                // 这个开关是全应用共用的一个判断，所以直接读 AI 那边那一份，
                // 不另起一个可能对不上的设置
                if (!AiConfig.useSystemProxy) {
                    proxy(Proxy.NO_PROXY)
                }
            }
            .build()
    }
}

/**
 * GitHub 让用户填的东西，以及那些**不让他填**的值放在哪。
 *
 * 单独拎出来是为了**只有一处定义**：界面按 [ALL] 渲染输入框，[GitHubConfig]
 * 按同样的 key 取值。两边各写一遍的话，改了一个另一个会静默读不到值。
 */
internal object GitHubFields {

    val TOKEN = ConfigField(
        key = "github_token",
        label = "访问令牌",
        hint = "细粒度令牌，只给这个仓库的 Contents 读写权限",
        secret = true
    )

    val REPO = ConfigField(
        key = "github_repo",
        label = "仓库名",
        hint = "例如 my-notes。粘整条仓库地址也行；组织名下的仓库写成 组织名/仓库名"
    )

    /** **用户要填的就这两个。** 少一个框就少一类填错的可能 */
    val ALL = listOf(TOKEN, REPO)

    /**
     * 令牌主人的账号名。**不是配置项** —— 用户不填，由 [GitHubTarget.resolve]
     * 问 GitHub 要（`GET /user`，不需要任何权限）。
     *
     * 存下来而不是每次现问，是因为 [SyncTarget.summary] 和
     * [SyncTarget.isConfigured] 必须是**同步**可读的：界面在重组时调它们，
     * 那里不能发网络请求。所以它是一份缓存，写入时机只有一处 —— 保存。
     *
     * 键名沿用用户手填时代的那个，于是老配置里的值直接能用，不必重填。
     */
    const val CACHED_OWNER = "github_owner"

    /** 仓库的默认分支。同样不由用户填，见 [CACHED_OWNER] */
    const val CACHED_BRANCH = "github_branch"

    /**
     * 库文件在仓库里的什么位置。**写死在代码里。**
     *
     * 它曾经是一个输入框，后来发现没有存在的理由：值是 App 自己定的，
     * 用户没有任何依据去改它，摆在那里只会让人停下来想"这该填什么"——
     * 而"填错了会怎样"他还得先搞懂这整套东西才能判断。
     *
     * 要换位置（放进子目录、两台设备各存一份）时改这一行就够了，
     * 那是这个适配器的实现细节，不需要用户参与。
     */
    const val SYNC_PATH = "knowledge.db"
}

/**
 * GitHub 这一端需要的全部信息。
 *
 * **构造它不碰网络，规范化全是纯函数** —— 那几个函数是最容易出错的
 * （用户从浏览器地址栏、从 clone 命令里复制来的东西什么样都有），
 * 所以单独拎出来单测。
 */
data class GitHubConfig(
    val token: String,
    val owner: String,
    val repo: String,
    val branch: String,
    val path: String
) {

    val isComplete: Boolean
        get() = token.isNotEmpty() && owner.isNotEmpty() && repo.isNotEmpty() &&
            branch.isNotEmpty() && path.isNotEmpty()

    companion object {

        fun fromSettings(): GitHubConfig = GitHubConfig(
            token = normalizeToken(SyncSettings.get(GitHubFields.TOKEN.key)),
            // 这两项是缓存，由 resolve 写进来；没跑过 resolve 就是空的，
            // isComplete 因此为 false，卡片显示「还没配置」
            owner = SyncSettings.get(GitHubFields.CACHED_OWNER),
            branch = SyncSettings.get(GitHubFields.CACHED_BRANCH),
            repo = repoNameOnly(SyncSettings.get(GitHubFields.REPO.key)),
            path = GitHubFields.SYNC_PATH
        )

        /**
         * 令牌：剥掉粘贴时常见的包装。
         *
         * 复制令牌很容易连 `Bearer ` 前缀或者一对引号一起带进来 —— 那种错误
         * 从界面上看不出来（脱敏显示只留头尾），但请求一定 401。
         * 让用户对着一个"看起来没问题"的令牌查半天，不如在这里剥掉。
         */
        fun normalizeToken(raw: String): String = raw.trim()
            .removePrefix("Bearer ")
            .removePrefix("bearer ")
            .trim()
            .trim('"', '\'', '`')
            .trim()

        /**
         * 仓库名：把用户可能粘进来的包装剥掉。**三种写法都认**，因为它们都是
         * 真实会发生的事：
         *
         *     https://github.com/octocat/my-notes   ← 浏览器地址栏里就是这么一整条
         *     octocat/my-notes                      ← 从 clone 命令里复制来的
         *     my-notes                              ← 照着输入框的提示填的
         *
         * **结果保留了中间的 `owner/repo` 形态，不在这里切分** —— 组织名下的
         * 仓库需要它，切分是 [GitHubTarget.resolve] 的活。
         */
        fun normalizeRepo(raw: String): String {
            var s = raw.trim().removeSuffix("/").removeSuffix(".git").trim()
            s = s.substringAfter("github.com/", s)
            s = s.substringAfter("github.com", s)
            return s.trim('/').trim()
        }

        /**
         * 只要仓库名那一段，丢掉可能写在前面的 `组织名/`。
         *
         * 拼 URL 和存缓存都必须用它，**不能用 [normalizeRepo] 的结果**：
         * 里面的斜杠会被当成**路径分隔符**编码成 `%2F`，那个地址在 GitHub 上
         * 不存在。多一段（`组织名/仓库名/tree/main`）也只取前两段，
         * 于是从浏览器里粘来的深链接不会把 `tree`、`main` 当成仓库名。
         */
        fun repoNameOnly(raw: String): String {
            val s = normalizeRepo(raw)
            return if ('/' in s) s.substringAfter('/').substringBefore('/') else s
        }

        /**
         * 用户是不是自己写了组织名（`组织名/仓库名`）。
         *
         * 写了就听他的 —— 自动取到的是**令牌主人**的账号名，而组织仓库的 owner
         * 是组织，两者不一样。没写才去问 `GET /user`。
         */
        fun explicitOwner(raw: String): String {
            val s = normalizeRepo(raw)
            return if ('/' in s) s.substringBefore('/') else ""
        }
    }
}
