package com.example.explaindot.knowledge

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * 给仪器测试用的隔离环境：**用应用自己的 Context（uid 对得上、写得进去），
 * 但把数据库路径重定向到它私有的缓存目录。**
 *
 * ## 为什么不能用测试包的 Context
 *
 * 试过，在真机上走不通。仪器测试跑在**目标应用的进程**里（uid 是
 * `com.example.explaindot`），而测试包的目录属于另一个 uid ——
 * `getDatabasePath()` 给得出路径，`mkdirs()` 却建不出来，用例会一起挂在
 * `SQLITE_CANTOPEN` 上。顺带一个坑：那个 Context 的 `getApplicationContext()`
 * 是 null，而 [KnowledgeStore] 构造时正好取它。
 *
 * ## 为什么必须隔离
 *
 * 真机上有用户攒了很久的真库，而这些用例会整份删、整份换。
 * 所以路径必须换掉 —— 生产代码一行都不用改，这是 `ContextWrapper` 的用处。
 *
 * 真库的安全最终由两件事保证：建完之后那条断言，以及跑完用
 * `tools/compare_real_kb.py` 比对真库的内容指纹。
 */
internal class IsolatedContext private constructor(
    base: Context,
    private val dir: File
) : ContextWrapper(base) {

    /** 只需要动这个 —— `SQLiteOpenHelper` 就是拿它来定位文件的 */
    override fun getDatabasePath(name: String): File {
        dir.mkdirs()
        return File(dir, name)
    }

    /** 默认实现会委托给底层，而 [KnowledgeStore] 构造时取的正是它（传底层会 NPE） */
    override fun getApplicationContext(): Context = this

    companion object {

        /**
         * 建一个隔离环境，并**当场确认它没指着真库**。
         *
         * [tag] 决定用哪个子目录。**每个测试类、以及同一个测试里想造"另一份库"的地方，
         * 都要给不同的 tag** —— 文件路径是 `cacheDir/<tag>/knowledge.db`，
         * 同 tag 的两个 [KnowledgeStore] 会落在同一个文件上、互相覆盖数据，
         * 而那种错误看起来像"测试自己抽风"，很难查。
         *
         * 那条断言是整份测试的安全带：万一哪天重定向被改坏（比如有人为了"省事"
         * 把 `getDatabasePath` 的覆写删了），用例会在这里先失败，
         * 而不是先去删用户的数据。
         */
        fun create(tag: String): IsolatedContext {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(base.cacheDir, tag)
            val wrapped = IsolatedContext(base, dir)

            val redirected = wrapped.getDatabasePath("knowledge.db").absolutePath
            val real = base.getDatabasePath("knowledge.db").absolutePath
            check(redirected != real) {
                "测试库绝不能是用户那份：解析出来的路径是 $real"
            }
            check(redirected.startsWith(dir.absolutePath)) {
                "测试库应该落在缓存目录下，实际是 $redirected"
            }
            return wrapped
        }
    }
}
