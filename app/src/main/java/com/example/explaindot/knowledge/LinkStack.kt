package com.example.explaindot.knowledge

/**
 * 跳转历史，就是一个栈。元素是什么由调用方定 ——
 * 实际用的是「概念名 + 进来时正在读的那段文字」，因为「重新解释」要把
 * 用户当时读到的内容发回给模型。
 *
 * **允许同一个元素在栈里出现多次。** 这不是疏忽：从 A 看到 B、从 B 看到 C、
 * 又从 C 看到 A，栈就该是 A→B→C→A。去重听起来"更干净"，但会让返回行为
 * 变得不可预测 —— 用户在 C 里按返回，期望回到他刚才看的那个 A，
 * 而不是被弹到某个"上一次出现 A 之前"的位置。
 * 栈语义就是「他怎么走过来的」，把他走过的路改掉才是错的。
 *
 * 用 ArrayList 而不是 ArrayDeque：需要按下标读取任意一层
 * （取上一层的内容当上下文），而 ArrayDeque 只能从两头操作。
 *
 * 不做任何归一化或去重 —— 进来的原样存，出去的原样给。
 */
class LinkStack<T> {

    private val items = ArrayList<T>()

    /** 栈顶，也就是当前这一层。空栈表示停在概念列表 */
    val current: T? get() = items.lastOrNull()

    val size: Int get() = items.size

    val isEmpty: Boolean get() = items.isEmpty()

    /** 从栈底到栈顶的完整路径。只读快照，改它不影响栈 */
    val trail: List<T> get() = items.toList()

    /**
     * 上一层 —— 用户是从哪里跳过来的。
     *
     * 这个是「重新解释」要用作上下文的那一层：用户在读 A 的解释时
     * 点进了 B，发现 B 的解释对不上，那么要发给模型判断的上下文
     * 就是 A。所以需要能往上取一层。
     */
    val parent: T? get() = if (items.size >= 2) items[items.size - 2] else null

    fun push(item: T) {
        items += item
    }

    /**
     * 返回上一层。已经在栈底时返回 false，调用方据此决定是否退出到概念列表。
     *
     * 返回 Boolean 而不是让调用方先查 size：查完再弹是两步，
     * 中间隔着一次重组的话就可能弹空。一步做完，判断和动作在同一个时刻。
     */
    fun pop(): Boolean {
        if (items.isEmpty()) return false
        items.removeAt(items.lastIndex)
        return true
    }

    /** 直接清空，回到概念列表。深栈时用的「回到概念列表」按钮走这里 */
    fun clear() {
        items.clear()
    }
}
