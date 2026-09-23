package com.example.explaindot.chat

/**
 * 对话里的一条消息。
 *
 * [hidden] 是**自动发起的那条开场指令**用的：进入对话页时我们要替用户问一句
 * 「这张图讲的是什么」，但不该在界面上摆出一条用户没打过的消息 ——
 * 那会让用户以为是自己发的。所以它照常进请求（API 需要至少一条 user 消息），
 * 只是不渲染。
 *
 * [role] 用枚举而不是裸字符串：这个值要拼进 JSON 的 `role` 字段，
 * 写错了服务端会回一个看不懂的 400，而枚举让拼错这件事根本无法发生。
 */
data class ChatMessage(
    val role: Role,
    val text: String,
    val hidden: Boolean = false
) {
    enum class Role(val wire: String) {
        User("user"),
        Assistant("assistant")
    }

    /** 用户看得见的消息 —— 界面上只摆这些 */
    val visible: Boolean get() = !hidden
}
