package com.lockedfog.clip.core

/**
 * 虚拟光标
 * 代表一个独立的写入上下文（位置、样式、权限）
 */
class VirtualCursor(
    private val screen: VirtualScreen,
    private val isMainThread: Boolean = false,
    private val canOverride: Boolean = false
) {
    // 坐标状态 (Row, Col) 0-based
    var row: Int = 0
    var col: Int = 0

    // 样式状态
    var fgColor: Int? = null
    var bgColor: Int? = null
    var bold: Boolean = false
    var italic: Boolean = false
    var underline: Boolean = false
    var strikethrough: Boolean = false

    /**
     * 写入文本并自动推进光标
     */
    suspend fun printText(text: String) {
        for (char in text) {
            // 处理换行符 (虽然脚本解析层可能已经处理了 NewLine 指令，但文本内仍可能含 \n)
            if (char == '\n') {
                newLine()
                continue
            }

            val moved = screen.write(
                r = row,
                c = col,
                char = char,
                fg = fgColor,
                bg = bgColor,
                bold = bold,
                italic = italic,
                underline = underline,
                strikethrough = strikethrough,
                isMainThread = isMainThread,
                canOverride = canOverride
            )

            // 只有写入成功或被屏蔽（返回宽度）时才移动
            // 如果是在行尾被截断，moved 可能为 0，此时光标停留在末尾
            col += moved
        }
    }

    /**
     * 换行
     */
    fun newLine() {
        row++
        col = 0
        if (row >= screen.height) {
            // 简单的防止越界，本引擎暂不支持滚动，到底部后停留在最后一行
            row = screen.height - 1
        }
    }

    /**
     * 绝对移动
     */
    fun moveTo(r: Int, c: Int) {
        // 允许设置到屏幕外，但在 write 时会被校验
        this.row = r
        this.col = c
    }

    /**
     * 相对移动
     */
    fun moveRelative(dr: Int, dc: Int) {
        this.row += dr
        this.col += dc
    }

    /**
     * 复制当前光标状态（用于协程启动）
     * 新的光标将继承位置和样式，但权限会改变
     * @param newIsMain 新光标是否为主线程 (通常协程为 false)
     * @param newCanOverride 新光标是否强制覆盖 (由函数属性决定)
     */
    fun copy(newIsMain: Boolean, newCanOverride: Boolean): VirtualCursor {
        val newCursor = VirtualCursor(screen, newIsMain, newCanOverride)
        newCursor.row = this.row
        newCursor.col = this.col
        newCursor.fgColor = this.fgColor
        newCursor.bgColor = this.bgColor
        newCursor.bold = this.bold
        newCursor.italic = this.italic
        newCursor.underline = this.underline
        newCursor.strikethrough = this.strikethrough
        return newCursor
    }

    fun resetStyle() {
        fgColor = null
        bgColor = null
        bold = false
        italic = false
        underline = false
        strikethrough = false
    }
}