package com.example.explaindot.knowledge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * 本地知识库：攒下用户解释过的每一个概念，供以后复用和链式跳转。
 *
 * **为什么是 SQLite 而不是自己排一个 offset/length 索引文件。**
 * 一开始的设计是「索引常驻内存 + 值文件按偏移量随机读 + 自写 Deflate 压缩」，
 * 目的是压低内存占用。但那个方案要自己实现的东西太多，而且每一件都是能出错的地方：
 * 事务性、崩溃一致性、碎片回收、同一概念重新解释后旧值的孤儿处理。
 * SQLite 把这四件事全部兜住，而且它本身就是按页惰性读盘的 ——
 * 「值不整份加载」这个诉求它天生满足。
 *
 * 内存账：SQLite 默认页缓存只有 2MB 量级，实测即使库里一万条也不过几 MB。
 * 预算 100MB，完全放得下。而唯一必须常驻的「词表」（见 [ConceptMatcher]）
 * 是另外单独取一次、放在内存里的，与数据库的页缓存互不相干。
 *
 * **线程。** 这个类的所有方法都是阻塞的，必须在 IO 线程调用。
 * 它自己不加锁：SQLite 的连接在 WAL 模式下支持一写多读，
 * 而调用方（AnalysisController）本来就串行地访问它，多加一层锁只是噪音。
 *
 * 表结构里的 `UNIQUE(term, tag)` 是这套设计的支点：
 * 一个概念可以有多个标签（每个标签一种适用场景），但**同一个标签只能有一份解释**。
 * 「重新解释」时模型选择覆盖某个标签，落到 SQL 上就是一次 UPDATE —— 天然幂等，
 * 不会因为重试攒出重复记录。
 */
class KnowledgeStore(context: Context) {

    private val app = context.applicationContext

    private val helper = Helper(app)

    /**
     * 这个库的文件在哪。
     *
     * 只是算一下路径，**不打开数据库** —— 要它的人（导出、测试）通常在
     * 打开之前就得知道位置。注意它不包括 WAL 的附属文件（`-wal` / `-shm`），
     * 那三个要一起看才完整（见 [exportTo]）。
     */
    val databaseFile: File get() = app.getDatabasePath(DB_NAME)

    /**
     * 一个概念在某一种适用场景下的解释。
     *
     * [createdAt] / [updatedAt] 是给同步用的：远端拿回来的那份要连时间戳一起
     * 落库，否则"同一个概念的多个义项按改动时间倒序"这条行为在拉取之后
     * 会变成随机顺序（所有行的 updated_at 都相同）。
     *
     * 默认 0 表示**时间未知** —— 现有的显示逻辑不读它们，而分析控制器在
     * "入库失败、只显示不缓存"那条退路上构造的 [Entry] 确实没有时间戳。
     * 写库时遇到 0 会当成"用当下时间"。
     */
    data class Entry(
        val term: String,
        val tag: String,
        val body: String,
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L
    )

    /** 设置页要显示的东西。bytes 是数据库文件实际占的磁盘字节数 */
    data class Stats(val concepts: Int, val tags: Int, val bytes: Long)

    /**
     * 全部概念名，给 [ConceptMatcher] 用。
     *
     * 只要去重后的名字，不碰 body —— 一万条也就一百来 KB，而且只取一次、常驻内存。
     */
    fun allTerms(): Set<String> = allConcepts().toHashSet()

    /**
     * 全部概念名，给知识库浏览页用。
     *
     * 和 [allTerms] 读的是同一份数据，区别只在返回类型：那边要 Set（做成员查询），
     * 这边要 List（要排序、分组、按顺序显示）。**只有这一处写 SQL** ——
     * 两个方法各写一遍的话，「一个概念存在当且仅当它有至少一条解释」这条规则
     * 就有了两份实现，迟早有一份会飘。
     *
     * 不排序：排序规则在 [PinyinOrdering] 里，那是界面层的关注点，
     * 数据库层只负责把名字给全。
     */
    fun allConcepts(): List<String> {
        val out = ArrayList<String>()
        helper.readableDatabase.rawQuery(
            "SELECT DISTINCT term FROM $TABLE", null
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    /**
     * 某个概念的全部解释，最近改动过的排在前面。
     *
     * 按 updated_at 倒序而不是 created_at：刚「重新解释」完，用户想立刻看到
     * 新加的那条，让它出现在最上面就是最直接的反馈。
     */
    fun entries(term: String): List<Entry> {
        val out = ArrayList<Entry>()
        helper.readableDatabase.rawQuery(
            "SELECT term, tag, body FROM $TABLE WHERE term = ? ORDER BY updated_at DESC",
            arrayOf(term)
        ).use { c ->
            while (c.moveToNext()) {
                out += Entry(c.getString(0), c.getString(1), c.getString(2))
            }
        }
        return out
    }

    /** 这条概念解释过没有。渲染每一处链接前都要问一次，所以走索引 COUNT 而不是取全文 */
    fun has(term: String): Boolean {
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE WHERE term = ? LIMIT 1", arrayOf(term)
        ).use { c -> return c.moveToNext() }
    }

    /**
     * 写入一条解释。同一个 (term, tag) 已存在就覆盖正文。
     *
     * 用「先 UPDATE，没改到行再 INSERT」而不是 `INSERT OR REPLACE`：
     * 后者会把整行删掉重建，`created_at`（第一次见到这个概念的时间）就丢了。
     * 也不能用 `ON CONFLICT DO UPDATE` —— 那是 SQLite 3.24 起的语法，
     * 而本应用 minSdk 26 对应的系统 SQLite 是 3.18，会直接语法错误。
     *
     * 两条语句包在一个事务里：中途失败要么全成要么全不成，
     * 不会留下「更新时间改了但正文没改」这种半截状态。
     */
    fun put(term: String, tag: String, body: String) {
        val now = System.currentTimeMillis()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("body", body)
                put("updated_at", now)
            }
            val updated = db.update(
                TABLE, values, "term = ? AND tag = ?", arrayOf(term, tag)
            )
            if (updated == 0) {
                db.insert(TABLE, null, ContentValues().apply {
                    put("term", term)
                    put("tag", tag)
                    put("body", body)
                    put("created_at", now)
                    put("updated_at", now)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 删掉某个概念某个标签下的解释。
     *
     * 用户发现库里的解释和自己在读的东西对不上、重解释也救不回来时，
     * 需要有办法把它清掉 —— 否则那条错误解释会被之后的每一段文字反复链上。
     */
    fun deleteTag(term: String, tag: String) {
        helper.writableDatabase.delete(
            TABLE, "term = ? AND tag = ?", arrayOf(term, tag)
        )
    }

    /**
     * 全库读出来，给同步做快照。按 (term, tag) 排好序。
     *
     * 排序放在 SQL 里（走 UNIQUE(term, tag) 那个索引）而不是取回来再排：
     * 让 SQLite 按顺序吐，省掉一次内存排序，而且这个顺序和远端文件里的
     * 顺序一致 —— 两边是同一套规则，比对内容时不会因为顺序不同而误判。
     */
    fun allEntries(): List<Entry> {
        val out = ArrayList<Entry>()
        helper.readableDatabase.rawQuery(
            "SELECT term, tag, body, created_at, updated_at FROM $TABLE ORDER BY term, tag",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += Entry(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))
            }
        }
        return out
    }

    /**
     * 用一批记录**整表替换**本地内容。
     *
     * 这是"从远端强制同步到本地"的落点，但**不是现在走的那条路** ——
     * 同步改成搬整个库文件之后，远端那份是字节、不是一批记录，
     * 走的是 [inspect] + [replaceWith]。这个方法留着的原因只有一个：
     * 它是唯一一处「按记录写库」的实现，而重新解释、逐条删除那些地方
     * 早晚要用到同样的语义，那时候不必再写第二份。
     *
     * **全在一个事务里。** 中途失败就整体回滚，本地还是原来那份；
     * 分几次写的话，一次失败的拉取会留下一个既不是远端、也不是本地的库 ——
     * 那种状态用户没有任何办法辨认。
     *
     * 时间戳为 0 的记录（远端文件里没写）按当下时间落库，这样
     * "最近改动的排前面"至少还有个确定顺序，而不是全部并列。
     */
    fun replaceAll(entries: List<Entry>) {
        val now = System.currentTimeMillis()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE, null, null)
            entries.forEach { e ->
                db.insert(TABLE, null, ContentValues().apply {
                    put("term", e.term)
                    put("tag", e.tag)
                    put("body", e.body)
                    put("created_at", if (e.createdAt > 0L) e.createdAt else now)
                    put("updated_at", if (e.updatedAt > 0L) e.updatedAt else now)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 设置页要显示的一行统计。
     *
     * bytes 用 `page_count × page_size` 而不是查文件大小：
     * WAL 模式下数据可能还躺在 -wal 文件里，文件系统上的 knowledge.db 会偏小，
     * 而页计数是 SQLite 自己眼里真实占用的空间。
     */
    fun stats(): Stats {
        val db = helper.readableDatabase

        fun scalar(sql: String): Long = db.rawQuery(sql, null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

        return Stats(
            concepts = scalar("SELECT COUNT(DISTINCT term) FROM $TABLE").toInt(),
            tags = scalar("SELECT COUNT(*) FROM $TABLE").toInt(),
            bytes = scalar("PRAGMA page_count") * scalar("PRAGMA page_size")
        )
    }

    // ------------------------------------------------------------------ 整库搬运

    /**
     * 把整个库导出成一个**自包含**的文件，写到 [target]。给同步上传用。
     *
     * ## 为什么不能直接拷那个 .db
     *
     * 因为 WAL 模式下**开着连接时主库文件是残缺的**。SQLite 把最近的写入先追加到
     * `-wal`，主库要等一次 checkpoint 才会拿到它们。实测（200 条数据、连接开着）：
     *
     * ```
     * a.db       4096 字节   ← 拷它，读出来是 "no such table: t"
     * a.db-wal  12392 字节   ← 真正的数据在这儿
     * ```
     *
     * 拷出来的不是一个"旧一点的库"，是一个**连表都不存在**的文件 —— 而且它照样能
     * 打开，看不出坏了。
     *
     * ## 两步
     *
     * [CHECKPOINT] 把 wal 里的东西并回主库、把 wal 清零；[VACUUM] 再把碎片压掉 ——
     * 而碎片是真实存在的：[replaceAll] 每拉取一次就是一次全表删+全表插，
     * 那些空洞会一直留在文件里，不压的话传上去的文件会虚胖好几倍。
     *
     * 两步都是普通 SQL，不依赖 SQLite 版本（`VACUUM INTO` 要 3.27，那要 Android 11）。
     *
     * ## busy 那个返回值要检查
     *
     * checkpoint 在拿不到独占锁时**会失败但不会报错** —— 它返回的元组里第一个
     * 分量非零就表示"没干成"。那种情况下 wal 里的东西还在，接着拷主库就是上面
     * 那个残缺文件。所以这里宁可报错，也不导出一份不完整的库。
     */
    fun exportTo(target: File) {
        val db = helper.writableDatabase

        val checkpoint = db.rawQuery("PRAGMA wal_checkpoint($CHECKPOINT)", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else -1
        }
        if (checkpoint != 0) {
            // 加一条 retry？不加。它失败只可能是因为有别的连接正在读写，
            // 而那时重试也是白试 —— 让用户过一会儿再点，比这里转圈好
            throw IllegalStateException(
                "数据库正忙（checkpoint 返回 $checkpoint），没能导出完整的一份。"
            )
        }

        db.execSQL("VACUUM")

        // 此时主库是完整的，-wal 是空的。可以安全拷贝
        val source = databaseFile
        source.copyTo(target, overwrite = true)

        // 拷完再确认一次：文件头不对的话，上面那些推断就有问题
        if (!looksLikeSqlite(target)) {
            target.delete()
            throw IllegalStateException("导出的文件不是有效的数据库。")
        }
    }

    /**
     * 看一眼这个文件像不像本应用的库。返回里面的解释条数。
     *
     * **校验发生在动本地数据之前**，顺序不能反 —— 这是这套设计里唯一一处
     * "一个坏文件能毁掉全部数据"的地方。远端那份可能是任何东西：别人塞进去的
     * 文件、传到一半断了的、甚至是别的应用的库。
     *
     * 三道检查，从便宜到贵：
     *   一、文件头是 SQLite 的魔数（16 字节，纯字符串比对）
     *   二、`user_version` 等于本应用的表版本 —— SQLiteOpenHelper 会把它写成
     *      [VERSION]，所以这一条同时验了"是我们的库"和"表结构对得上"
     *   三、`concepts` 表真的能查
     */
    fun inspect(file: File): Int {
        if (!looksLikeSqlite(file)) {
            throw IllegalArgumentException("这不是一个 SQLite 数据库文件。")
        }

        // 只读打开：这个文件还没通过校验，绝不能给它写的机会
        val probe = SQLiteDatabase.openDatabase(
            file.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
        try {
            val version = probe.rawQuery("PRAGMA user_version", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else -1
            }
            if (version != VERSION) {
                throw IllegalArgumentException(
                    "这个库的表版本是 $version，本应用只认 $VERSION。" +
                        if (version > VERSION) "它可能来自更新版本的 App。" else ""
                )
            }
            return probe.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            // 文件头对、但里面是坏的（传到一半断了就是这样）
            throw IllegalArgumentException("这个数据库读不出来：${e.message}")
        } finally {
            probe.close()
        }
    }

    /**
     * 用 [file] 整份替换本地库。**调用方必须先 [inspect] 成功。**
     *
     * ## 为什么要关连接
     *
     * 换的是文件，而 [helper] 手里那个连接正开着这个文件。不关的话，SQLite 那边
     * 还拿着旧文件的句柄和页缓存 —— 换完了它继续按旧的写，两份数据混在一起，
     * 那是最糟糕的一种坏法：不是崩溃，是**慢慢写坏**。
     *
     * ## 为什么必须先删附属文件
     *
     * `-wal` 和 `-shm` 是**属于旧库**的：wal 里记的是"把旧主库的某一页改成什么"。
     * 留着它们再放进一个新主库，等于让 SQLite 拿旧库的补丁去改新库 ——
     * 结果既不是旧的也不是新的，而是损坏的。所以先删干净。
     *
     * ## 为什么先写临时文件再改名
     *
     * 直接往 `knowledge.db` 上拷，拷到一半失败（磁盘满、进程被杀）会留下一个
     * 半新半旧的文件 —— 那不是一个能读的库。先拷到同目录的临时文件、成功了
     * 再改名，改名是原子的，于是本地要么是旧库、要么是新库。
     */
    fun replaceWith(file: File) {
        val dbFile = databaseFile

        // 关掉连接。之后 helper 会在下次被访问时重新打开，拿到的就是新文件
        helper.close()

        // 旧库的附属文件必须先清掉，理由见上面
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()

        val staging = File(dbFile.parentFile, "$DB_NAME.incoming")
        file.copyTo(staging, overwrite = true)
        if (!staging.renameTo(dbFile)) {
            staging.delete()
            throw IllegalStateException("换入新库时改名失败，本地库没有被动过。")
        }
    }

    /** SQLite 的魔数，每个库文件的头 16 字节都是它 */
    private fun looksLikeSqlite(file: File): Boolean = try {
        if (file.length() < SQLITE_MAGIC.size) {
            false
        } else {
            val head = ByteArray(SQLITE_MAGIC.size)
            file.inputStream().use { it.read(head) }
            head.contentEquals(SQLITE_MAGIC)
        }
    } catch (e: java.io.IOException) {
        false
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, VERSION) {

        init {
            // WAL：写解释的同时还能读别的概念。默认的回滚日志模式下
            // 一次写入会挡住全部读，而写解释恰好是在用户来回跳转时发生的。
            // 注意这是 SQLiteOpenHelper 的方法（不是 SQLiteDatabase 的），
            // 只能在构造期设，打开之后再改会抛异常
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    term       TEXT    NOT NULL,
                    tag        TEXT    NOT NULL,
                    body       TEXT    NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(term, tag)
                )
                """.trimIndent()
            )
            // UNIQUE(term, tag) 自带的索引以 term 打头，按 term 查已经走它了，
            // 不需要再为 term 单建一个
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 知识库是用户攒出来的东西，升级时不能一删了事。
            // 目前只有 v1，真到要改表的时候在这里写迁移。
        }
    }

    private companion object {
        const val DB_NAME = "knowledge.db"
        const val TABLE = "concepts"

        /**
         * 改表结构时 +1，并在 [Helper.onUpgrade] 里补上迁移。
         *
         * 刻意不加「版本不对就重建」的兜底：那等于用户升级一次 App
         * 就把攒了几个月的知识库清空一次，而这件事不会有人立刻发现。
         *
         * **同步也用这个数**：远端那份库的 `user_version` 和它不相等就拒绝写入
         * （见 [inspect]）。于是改表结构这件事自动多了一道保护 ——
         * 老版本 App 不会把新版本的库拉下来覆盖自己。
         */
        const val VERSION = 1

        /**
         * checkpoint 的模式。
         *
         * `TRUNCATE` 而不是默认的 `PASSIVE`：我们要的是"主库拿到全部内容、
         * wal 被清空"，而 `PASSIVE` 是尽力而为 —— 它可能在还有读者时提前收工，
         * 留下一个非空的 wal，那样主库就不是完整的。
         *
         * 代价是它会等读者结束，所以可能返回 busy；那个返回值上面检查了。
         */
        const val CHECKPOINT = "TRUNCATE"

        /** 每个 SQLite 库文件开头的 16 个字节，固定是这个 */
        val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
