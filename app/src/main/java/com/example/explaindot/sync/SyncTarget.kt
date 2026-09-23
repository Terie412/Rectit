package com.example.explaindot.sync

/**
 * 一个可以存放快照的远程位置。
 *
 * **所有平台都被当成同一件东西：某个位置上的一个字节串。** 适配器不解析内容、
 * 不知道里面是什么 —— 这条边界让"以后加 OneDrive"变成「声明配置项 + 实现三个方法」，
 * 而不是往一个共同基类里塞平台特例。
 *
 * 三个动作：
 *
 *   [resolve] 保存时把用户填的少量东西补全成一份可用配置
 *   [push]    本地整份覆盖远端
 *   [pull]    远端整份覆盖本地（远端还没有这份文件时返回 null）
 *
 * ## 载体是字节，不是文本
 *
 * 早先是 `String`：那时远端放的是 JSON 快照。现在放的是**整个 SQLite 数据库文件**，
 * 而它里面是二进制（页、B 树、变长整数编码），用文本装会坏。
 * 换成 [ByteArray] 之后适配器这一层反而更简单了 —— 它不需要知道里面是什么，
 * 字节就是字节。
 *
 * 都是**强制**的：不做合并、不看远端有没有别人的改动。代价是两台设备可能
 * 互相覆盖，这是明确接受的——换来的是任何时候都能一眼看出"现在两边到底一不一样"。
 */
interface SyncTarget {

    /** 稳定标识，将来多了用它记住用户选的是哪个 */
    val id: String

    /** 显示名，例如「GitHub」 */
    val label: String

    /**
     * 这个平台需要用户填哪些东西。
     *
     * 由适配器自己声明、界面照着渲染 —— 界面完全不知道"GitHub 要一个令牌和一个
     * 仓库名"，它只知道"这个适配器声明了两个框"。加平台只改这里和上面三个方法。
     *
     * **清单要短。** 用户不该看见任何他能不填的东西：能被推导出来的（账号名、
     * 默认分支）交给 [resolve]，是 App 自己决定的（文件放在哪个路径）直接写成常量。
     */
    val fields: List<ConfigField>

    /**
     * 一句话说清"东西存在哪儿"，给界面显示，例如「octocat/notes · main」。
     *
     * **只放用户能改变、因此可能配错的部分。** 适配器自己定的常量（比如文件在仓库里的
     * 路径）不进这里：一行用来核对配置的摘要，摆一个永远不可能错的项只是噪音。
     */
    val summary: String

    /** 必填项都填了没有 */
    val isConfigured: Boolean

    /**
     * 读回远端那份快照的原始字节。
     *
     * **远端还没有这份文件时返回 null**，而不是抛异常 —— "那边是空的"是正常状态
     * （第一次用），不是错误。调用方看到 null 就告诉用户"远端还没有内容"。
     *
     * **返回的字节不保证是好的。** 适配器只负责搬字节，内容对不对由上层校验 ——
     * 它要先把这份字节验明白，然后才准动本地数据。
     *
     * 失败（网络、鉴权）抛 [SyncException]，消息是直接给用户看的。
     */
    suspend fun pull(): ByteArray?

    /**
     * 把快照写到远端。
     *
     * 返回一句给用户看的结果说明。用 String 而不是布尔：平台可以说得更准，
     * 例如「远端已经和本地一致，没有改动」——那是成功，但和"刚推上去"不是同一件事。
     */
    suspend fun push(snapshot: ByteArray): String

    /**
     * 把用户填的那几项补全成一份可用配置，返回**要一并存下来的派生值**。
     *
     * 为什么需要它：有些值用户不该填、也填不好。「用户名」就是——它其实就是
     * 令牌主人的账号名，问一次 `GET /user` 就有，而且那个接口**不需要任何权限**。
     * 让用户去别处查了再抄进来，抄错了得到的报错是「找不到这个位置」，
     * 一句话里指了四个地方（用户名/仓库名/分支/路径），他没法判断是哪一个。
     * 分支同理：它是仓库的默认分支。
     *
     * 返回值会覆盖**同名的配置项** —— 适配器可以顺便把用户填的写法规整一下。
     *
     * **它只在「保存」时跑，不是每次同步。** 所以联网和失败都可以接受：
     * 令牌不对、仓库看不到，这些在这里就说清楚，比等到点上传才报错好得多。
     *
     * 默认空实现 —— 不需要派生值的平台完全不用管这个方法。
     * 失败抛 [SyncException]，消息直接给用户看。
     */
    suspend fun resolve(values: Map<String, String>): Map<String, String> = emptyMap()
}

/**
 * 一个配置项。刻意只支持字符串 —— 各种平台的配置项都是文本（令牌、地址），
 * 为它发明一套字段类型系统是提前设计。
 *
 * **没有"默认值"这个概念。** 早先有过：分支和文件路径留空就用 `main` 和
 * `knowledge.json`，所以 [ConfigField] 带了一个 `default`。现在那两个框都
 * 没了（分支由 [SyncTarget.resolve] 自动取，路径写死在适配器里），剩下的
 * 项都是必填，`default` 也就没有存在的理由了 —— 留着只会让人以为
 * "有些框可以不填"。
 */
data class ConfigField(
    /** 存进 [SyncSettings] 的键，各平台自己起名，不会撞 */
    val key: String,
    /** 界面上的名字，例如「访问令牌」 */
    val label: String,
    /** 输入框下方常显的说明，说清该填什么 */
    val hint: String,
    /** 是不是凭据。界面用密码样式显示、并给脱敏的展示 */
    val secret: Boolean = false
)

/** 同步过程中出的错。**消息直接给用户看**，所以每一句都要说清是什么、怎么办 */
class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 异常翻成一句给用户看的话。
 *
 * [SyncException] 的 message 本来就是照着这个目的写的（"令牌过期了"），直接用。
 * 其余的（磁盘满、SQLite 出错）加上类型名，至少让用户能报出个东西 ——
 * 报得出东西才有人能帮他。
 *
 * [prefix] 是必要的：同一个异常在「保存」和「同步」两个场合要说成不同的话，
 * 而对用户来说"哪一步失败了"比异常类型有用得多。
 */
internal fun Throwable.toUserMessage(prefix: String = "同步失败"): String = when (this) {
    is SyncException -> message.orEmpty().ifBlank { "$prefix。" }
    else -> "$prefix：${message ?: this::class.simpleName}"
}

/**
 * 装了哪些平台。
 *
 * 现在是 GitHub 一个。加平台的完整动作是：写一个 [SyncTarget] 实现、
 * 往 [all] 里加一项。**界面上那句「平台」到那时才需要变成可选项** ——
 * 现在只有一个，摆一个只有一项的下拉框是多余的。
 */
object SyncTargets {

    /** 懒加载：避免类加载时就 new 出一个 OkHttpClient */
    val all: List<SyncTarget> by lazy { listOf(GitHubTarget()) }

    /**
     * 当前用的那个。
     *
     * 只有一个平台时它就是唯一的选择，所以这里不做任何持久化 ——
     * 存一个恒为 "github" 的值只会多一个可能对不上的状态。
     */
    val primary: SyncTarget get() = all.first()
}
