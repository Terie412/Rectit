package com.example.explaindot.knowledge

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机上验整库搬运的三件事：导出、校验、替换。
 *
 * 对应 [KnowledgeStore.exportTo] / [inspect] / [replaceWith]。
 *
 * **为什么它们必须跑在真机上**：JVM 单测里的 `android.database.sqlite` 是一调用
 * 就抛的桩，而这三个方法干的全部是文件和 SQLite 的事 —— 在真机上跑之前，
 * 一行都没被执行过。而它们恰好是同步链路里最不能错的一段：
 * 上传导出错了会传上去一个残缺的库，下载替换错了会**把本地库换没**。
 *
 * 用的是隔离出来的库文件（见 [IsolatedContext]），不碰真机上的真实知识库。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeFileInstrumentedTest {

    private lateinit var ctx: IsolatedContext
    private lateinit var store: KnowledgeStore
    private lateinit var scratch: File

    /**
     * 造一份"远端来的"库。
     *
     * **必须用另一个 tag**，不能拿同一个 [ctx] 再 `new` 一个 store ——
     * 那样两个 store 会落在**同一个文件**上，一个的写入直接改掉另一个，
     * 于是"换入之后内容变了"这类断言会因为错误的原因通过。
     */
    private fun remoteLibrary(entries: List<KnowledgeStore.Entry>): File {
        val remote = KnowledgeStore(IsolatedContext.create("kb-file-remote"))
        remote.replaceAll(entries)
        val out = File(scratch, "incoming.db")
        remote.exportTo(out)
        return out
    }

    @Before
    fun setUp() {
        ctx = IsolatedContext.create("kb-file")
        store = KnowledgeStore(ctx)
        scratch = File(ctx.cacheDir, "kb-test-scratch").apply {
            deleteRecursively()
            mkdirs()
        }
        store.replaceAll(emptyList())
    }

    private fun entry(term: String, body: String = "正文", tag: String = "标签") =
        KnowledgeStore.Entry(term, tag, body, 1758000006000L, 1758000009000L)

    /** 拿一个**独立连接**去读那个文件，只有这样才验得出它自包含 */
    private fun readStandalone(file: File): List<Triple<String, String, String>> {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            it.rawQuery("SELECT term, tag, body FROM concepts ORDER BY term, tag", null).use { c ->
                buildList {
                    while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2)))
                }
            }
        }
    }

    // ------------------------------------------------------------------ 导出

    /**
     * **这是这组测试里最要紧的一条。**
     *
     * 刚写完还没 checkpoint 的时候，主库文件是残缺的 —— 数据在 `-wal` 里。
     * [KnowledgeStore.exportTo] 的全部意义就是先把它们并进主库再拷，
     * 所以这里让写入停在"未 checkpoint"的状态，然后要求导出的文件**能独立读出全部内容**。
     */
    @Test
    fun 导出必须在没checkpoint的情况下也是完整的() {
        val expected = (1..40).map { entry("概念$it", body = "第 $it 条的正文") }
        store.replaceAll(expected)

        // 此刻数据多半还在 wal 里（连接开着，SQLite 不会自动合并）
        val dbFile = store.databaseFile
        val wal = File(dbFile.path + "-wal")
        println("导出前：主库 ${dbFile.length()} 字节，wal ${wal.length()} 字节")

        val out = File(scratch, "exported.db")
        store.exportTo(out)

        val got = readStandalone(out)
        assertEquals("导出的文件必须包含全部记录", expected.size, got.size)
        assertEquals(
            "内容要一条不差",
            expected.map { Triple(it.term, it.tag, it.body) }.sortedBy { it.first },
            got
        )
    }

    @Test
    fun 导出的文件是自包含的_旁边没有wal也读得出来() {
        store.replaceAll(listOf(entry("场"), entry("熵")))
        val out = File(scratch, "self-contained.db")
        store.exportTo(out)

        // 拷出来就一个文件。没有 -wal 才算数 —— 有的话说明 checkpoint 没做干净
        assertFalse("导出目录里不该有 -wal", File(out.path + "-wal").exists())
        assertTrue("文件头必须是 SQLite 的魔数", out.readBytes().take(16).toByteArray()
            .contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
        assertEquals(2, readStandalone(out).size)
    }

    @Test
    fun 空库也导得出来() {
        val out = File(scratch, "empty.db")
        store.exportTo(out)
        assertTrue("空库也要产出一个合法文件，不能是零字节", out.length() > 0)
        assertEquals(0, readStandalone(out).size)
    }

    // ------------------------------------------------------------------ 校验

    @Test
    fun 校验通过时返回条数() {
        store.replaceAll(listOf(entry("甲"), entry("乙"), entry("丙")))
        val out = File(scratch, "ok.db")
        store.exportTo(out)

        assertEquals(3, store.inspect(out))
    }

    @Test
    fun 校验拒绝不是数据库的文件() {
        val fake = File(scratch, "not-a-db.bin")
        fake.writeBytes("这是一段随便什么文字，肯定不是数据库".toByteArray())

        val e = runCatching { store.inspect(fake) }.exceptionOrNull()
        assertTrue("该抛 IllegalArgumentException", e is IllegalArgumentException)
        assertTrue("该说清不是数据库：${e!!.message}", e.message!!.contains("SQLite"))
    }

    @Test
    fun 校验拒绝空文件() {
        val empty = File(scratch, "empty.bin")
        empty.writeBytes(ByteArray(0))
        val e = runCatching { store.inspect(empty) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    /**
     * 截断的文件：有些东西能通过文件头，但内容不完整。
     *
     * 这一条防的是"下载到一半断了"—— 那种文件有一模一样的头 16 字节，
     * 光凭文件头放它进来，本地库就毁了。
     */
    @Test
    fun 校验拒绝被截断的数据库() {
        store.replaceAll((1..60).map { entry("概念$it") })
        val good = File(scratch, "good.db")
        store.exportTo(good)

        val truncated = File(scratch, "truncated.db")
        truncated.writeBytes(good.readBytes().copyOf(good.length().toInt() / 2))

        val e = runCatching { store.inspect(truncated) }.exceptionOrNull()
        assertTrue("截断的文件必须被拒绝，实际是：${e?.message}", e is IllegalArgumentException)
    }

    @Test
    fun 校验拒绝表版本对不上的库() {
        // 造一个"别人的库"：文件头合法、但 user_version 不是本应用的表版本
        val alien = File(scratch, "alien.db")
        SQLiteDatabase.openOrCreateDatabase(alien, null).use {
            it.execSQL("CREATE TABLE something_else (x TEXT)")
            it.execSQL("PRAGMA user_version = 99")
        }

        val e = runCatching { store.inspect(alien) }.exceptionOrNull()
        assertTrue("版本对不上必须被拒绝", e is IllegalArgumentException)
        assertTrue("该报出这个版本号：${e!!.message}", e.message!!.contains("99"))
    }

    // ------------------------------------------------------------------ 替换

    /**
     * 换入之后本地就是那份库了，**而且换的时候连接是开着的** ——
     * 这正是真机上的情况（App 一直在用这个库）。
     */
    @Test
    fun 换入之后本地内容变成新的() {
        store.replaceAll(listOf(entry("旧的甲"), entry("旧的乙")))

        val incoming = remoteLibrary(listOf(entry("新的丙", body = "来自远端")))

        // 先验后换，跟真实流程一个顺序
        store.inspect(incoming)
        store.replaceWith(incoming)

        val got = store.allEntries()
        assertEquals("条数应该变成新的那份", 1, got.size)
        assertEquals("新的丙", got[0].term)
        assertEquals("来自远端", got[0].body)
    }

    /**
     * 换文件时必须清掉旧库的 `-wal` / `-shm`。
     *
     * 它们**属于旧库**：wal 记的是"把旧主库的某一页改成什么"。留着它们再放进
     * 一个新主库，等于让 SQLite 拿旧库的补丁去改新库 —— 结果既不是旧的、
     * 也不是新的，而是损坏的。这一条就是防那个。
     */
    @Test
    fun 换入之后旧库的附属文件不残留() {
        store.replaceAll((1..30).map { entry("旧概念$it", body = "旧内容$it") })
        store.exportTo(File(scratch, "warmup.db"))   // 让 wal 里有东西

        val incoming = remoteLibrary(listOf(entry("唯一的新概念")))

        store.inspect(incoming)
        store.replaceWith(incoming)

        // 读回来的必须**只有**新那条 —— 一旦旧 wal 还起作用，就会看到旧概念混进来
        val got = store.allEntries()
        assertEquals("旧库的内容一条都不该留下", 1, got.size)
        assertEquals("唯一的新概念", got[0].term)
    }

    @Test
    fun 换入之后再写入仍然正常() {
        val incoming = remoteLibrary(listOf(entry("换进来的")))
        store.inspect(incoming)
        store.replaceWith(incoming)

        // 换完之后连接是重新打开的，得能继续正常读写
        store.replaceAll(store.allEntries() + entry("换完之后加的"))
        val terms = store.allEntries().map { it.term }.sorted()
        assertEquals(listOf("换完之后加的", "换进来的"), terms)
    }

    /** 完整走一遍：导出 → 校验 → 换入，内容应当和原来逐字段一致 */
    @Test
    fun 往返一圈内容不变() {
        val original = listOf(
            entry("场", body = "一种物质属性。\n第二行。", tag = "物理学的场"),
            entry("特征", body = "从数据里挑出来的属性。", tag = "机器学习/深度学习中")
        )
        store.replaceAll(original)
        val before = store.allEntries().sortedBy { it.term }

        val out = File(scratch, "roundtrip.db")
        store.exportTo(out)
        assertEquals(2, store.inspect(out))

        store.replaceAll(emptyList())      // 先清空，确认换入真的起作用
        store.replaceWith(out)

        assertEquals("往返一圈必须一字不差", before, store.allEntries().sortedBy { it.term })
    }

    /** 换入失败时本地不该被破坏 —— staging 改名失败那条路 */
    @Test
    fun 换入一个坏文件被校验挡住时本地完好() {
        store.replaceAll(listOf(entry("要保住的")))
        val before = store.allEntries()

        val bad = File(scratch, "bad.db")
        bad.writeBytes("不是数据库".toByteArray())

        val e = runCatching {
            store.inspect(bad)          // 真流程里就是这一步先抛，replaceWith 根本不会被调到
            store.replaceWith(bad)
        }.exceptionOrNull()

        assertTrue(e is IllegalArgumentException)
        assertEquals("本地必须原样不动", before, store.allEntries())
    }

    /** 测一下统计：替换之后卡片上那三个数字要跟着对 */
    @Test
    fun 换入之后统计跟着变() {
        store.replaceAll((1..8).map { entry("概念$it") })
        val before = store.stats()

        val incoming = remoteLibrary((1..3).map { entry("另一个概念$it") })
        store.inspect(incoming)
        store.replaceWith(incoming)

        val after = store.stats()
        assertEquals("概念数", 3, after.concepts)
        assertEquals("解释数", 3, after.tags)
        assertNotEquals("旧统计不该还留着", before.concepts, after.concepts)
    }
}
