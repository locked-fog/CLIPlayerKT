package com.lockedfog.clip.core

import com.lockedfog.clip.utils.CharUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 虚拟屏幕单元格 (Cell)
 * 存储每个格子的字符内容、样式和元数据
 */
data class Cell(
    var char: Char = ' ',
    var fgColor: Int? = null, // RGB Int, null 表示默认
    var bgColor: Int? = null,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var strikethrough: Boolean = false,

    // 宽字符处理
    var isWideHead: Boolean = false, // 是否为全角字符的左半部分
    var isWidePlaceholder: Boolean = false, // 是否为全角字符的右半部分（占位符）

    // 权限管理
    var lockedByMain: Boolean = false // 是否由主线程写入（受保护）
) {
    // 深度复制，用于缓冲交换
    fun copyFrom(other: Cell) {
        this.char = other.char
        this.fgColor = other.fgColor
        this.bgColor = other.bgColor
        this.bold = other.bold
        this.italic = other.italic
        this.underline = other.underline
        this.strikethrough = other.strikethrough
        this.isWideHead = other.isWideHead
        this.isWidePlaceholder = other.isWidePlaceholder
        this.lockedByMain = other.lockedByMain
    }

    // 检查是否与另一个 Cell 在视觉上完全一致
    fun visualEquals(other: Cell): Boolean {
        return char == other.char &&
                fgColor == other.fgColor &&
                bgColor == other.bgColor &&
                bold == other.bold &&
                italic == other.italic &&
                underline == other.underline &&
                strikethrough == other.strikethrough
    }
}

/**
 * 虚拟屏幕管理器 (Virtual Screen Buffer)
 * 实现双缓冲机制和线程安全的写入
 */
class VirtualScreen(val height: Int, val width: Int) {

    // 当前帧缓冲区 (正在被脚本写入)
    private val buffer: Array<Array<Cell>> = Array(height) { Array(width) { Cell() } }

    // 上一帧渲染缓冲区 (用于 Diff 计算)
    private val lastRenderedBuffer: Array<Array<Cell>> = Array(height) { Array(width) { Cell() } }

    private val lock = Mutex()

    /**
     * 写入单个字符到虚拟显存
     * @param r 行号 (0-based)
     * @param c 列号 (0-based)
     * @param char 字符
     * @param fg 前景色
     * @param bg 背景色
     * @param bold 粗体
     * @param italic 斜体
     * @param underline 下划线
     * @param strikethrough 删除线
     * @param isMainThread 是否为主线程操作
     * @param canOverride 是否强制覆盖
     * @return 实际写入后光标应该移动的距离 (1 或 2)，写入失败返回 0
     */
    suspend fun write(
        r: Int, c: Int,
        char: Char,
        fg: Int?, bg: Int?,
        bold: Boolean, italic: Boolean, underline: Boolean, strikethrough: Boolean,
        isMainThread: Boolean,
        canOverride: Boolean
    ): Int {
        // 1. 越界检查
        if (r !in 0 until height || c !in 0 until width) return 0

        val charWidth = CharUtils.getCharWidth(char)
        if (charWidth == 0) return 0 // 忽略零宽字符
        if (c + charWidth > width) return 0 // 宽度不足，不换行直接截断

        lock.withLock {
            val targetCell = buffer[r][c]

            // 2. 权限校验
            // 如果目标格子被主线程锁定，且当前写入者不是主线程，且没有强制覆盖权，则放弃
            if (targetCell.lockedByMain && !isMainThread && !canOverride) {
                return charWidth // 视为写入了，光标照常移动，但内容不生效
            }

            // 如果是写入全角字符，还需要检查右半边位置的权限
            if (charWidth == 2) {
                val rightCell = buffer[r][c + 1]
                if (rightCell.lockedByMain && !isMainThread && !canOverride) {
                    return charWidth
                }
            }

            // 3. 清理旧数据逻辑
            // 写入位置如果是某个旧全角字符的右半部分，需要把那个旧字符的左半部分清除，防止断裂
            if (targetCell.isWidePlaceholder && c > 0) {
                val leftCell = buffer[r][c - 1]
                if (leftCell.isWideHead) clearCell(leftCell)
            }
            // 写入位置如果是某个旧全角字符的左半部分，需要把那个旧字符的右半部分清除
            if (targetCell.isWideHead && c + 1 < width) {
                val rightCell = buffer[r][c + 1]
                if (rightCell.isWidePlaceholder) clearCell(rightCell)
            }
            // 针对全角写入：如果占用了 c+1，要清理 c+1 可能存在的旧连接
            if (charWidth == 2) {
                val nextCell = buffer[r][c + 1]
                // 如果 c+1 是某字的左半边，清掉 c+2
                if (nextCell.isWideHead && c + 2 < width) {
                    val nextNext = buffer[r][c + 2]
                    if (nextNext.isWidePlaceholder) clearCell(nextNext)
                }
                // 如果 c+1 是某字的右半边，清掉 c
                // (这种情况在上面 targetCell 检查时已经涵盖了，因为 c+1 的左边就是 c)
            }

            // 4. 执行写入
            updateCell(targetCell, char, fg, bg, bold, italic, underline, strikethrough, isMainThread, isWideHead = (charWidth == 2), isPlaceholder = false)

            if (charWidth == 2) {
                // 写入右侧占位符
                val placeholder = buffer[r][c + 1]
                updateCell(placeholder, ' ', fg, bg, bold, italic, underline, strikethrough, isMainThread, isWideHead = false, isPlaceholder = true)
            }
        }

        return charWidth
    }

    private fun clearCell(cell: Cell) {
        cell.char = ' '
        cell.fgColor = null
        cell.bgColor = null
        cell.bold = false
        cell.italic = false
        cell.underline = false
        cell.strikethrough = false
        cell.isWideHead = false
        cell.isWidePlaceholder = false
        cell.lockedByMain = false
    }

    private fun updateCell(
        cell: Cell,
        char: Char,
        fg: Int?, bg: Int?,
        bold: Boolean, italic: Boolean, underline: Boolean, strikethrough: Boolean,
        isMainThread: Boolean,
        isWideHead: Boolean,
        isPlaceholder: Boolean
    ) {
        cell.char = char
        cell.fgColor = fg
        cell.bgColor = bg
        cell.bold = bold
        cell.italic = italic
        cell.underline = underline
        cell.strikethrough = strikethrough
        cell.isWideHead = isWideHead
        cell.isWidePlaceholder = isPlaceholder
        // 只有主线程写入时才锁定；协程写入会解除锁定（除非后续逻辑改变，目前设计是协程覆盖后该格变为普通格）
        cell.lockedByMain = isMainThread
    }

    /**
     * 生成差异渲染指令 (Diff)
     * 对比 buffer 和 lastRenderedBuffer，生成最小 ANSI 序列
     */
    suspend fun generateDiffAndSwap(): String {
        val sb = StringBuilder()

        // 缓存当前正在应用的样式，避免重复指令
        var lastFg: Int? = -1
        var lastBg: Int? = -1
        var lastBold = false
        var lastItalic = false
        var lastUnderline = false
        var lastStrike = false

        // 记录光标位置优化
        var cursorR = -1
        var cursorC = -1

        lock.withLock {
            for (r in 0 until height) {
                for (c in 0 until width) {
                    val current = buffer[r][c]
                    val last = lastRenderedBuffer[r][c]

                    // 如果视觉上没有变化，跳过
                    if (current.visualEquals(last)) {
                        // 即使内容一样，last buffer 也要同步（主要是 lockedByMain 状态可能变了，虽然不影响显示）
                        last.copyFrom(current)
                        continue
                    }

                    // 移动光标 (优化：如果光标刚好在当前位置，不需要移动指令)
                    if (cursorR != r || cursorC != c) {
                        // ANSI Cursor Move: ESC [ <r+1> ; <c+1> H
                        sb.append("\u001b[${r + 1};${c + 1}H")
                        cursorR = r
                        cursorC = c
                    }

                    // 应用样式变化
                    if (current.fgColor != lastFg) {
                        if (current.fgColor == null) sb.append("\u001b[39m")
                        else {
                            val color = current.fgColor!!
                            val red = (color shr 16) and 0xFF
                            val green = (color shr 8) and 0xFF
                            val blue = color and 0xFF
                            sb.append("\u001b[38;2;$red;$green;${blue}m")
                        }
                        lastFg = current.fgColor
                    }

                    if (current.bgColor != lastBg) {
                        if (current.bgColor == null) sb.append("\u001b[49m")
                        else {
                            val color = current.bgColor!!
                            val red = (color shr 16) and 0xFF
                            val green = (color shr 8) and 0xFF
                            val blue = color and 0xFF
                            sb.append("\u001b[48;2;$red;$green;${blue}m")
                        }
                        lastBg = current.bgColor
                    }

                    // 样式标志位处理
                    if (current.bold != lastBold) {
                        sb.append(if (current.bold) "\u001b[1m" else "\u001b[22m")
                        lastBold = current.bold
                    }
                    if (current.italic != lastItalic) {
                        sb.append(if (current.italic) "\u001b[3m" else "\u001b[23m")
                        lastItalic = current.italic
                    }
                    if (current.underline != lastUnderline) {
                        sb.append(if (current.underline) "\u001b[4m" else "\u001b[24m")
                        lastUnderline = current.underline
                    }
                    if (current.strikethrough != lastStrike) {
                        sb.append(if (current.strikethrough) "\u001b[9m" else "\u001b[29m")
                        lastStrike = current.strikethrough
                    }

                    // 输出字符
                    if (!current.isWidePlaceholder) {
                        sb.append(current.char)
                    } else {
                        // 占位符不输出，光标位置由前一个宽字符自然推移，但我们需要更新逻辑坐标
                    }

                    // 更新上一帧缓存
                    last.copyFrom(current)

                    // 更新虚拟光标位置
                    val width = if (current.isWideHead) 2 else 1
                    cursorC += width
                }
            }
        }

        // 帧结束后重置样式，防止影响终端状态
        sb.append("\u001b[0m")
        // 隐藏光标到角落 (或根据需求保留)
        // sb.append("\u001b[${height};${width}H")

        return sb.toString()
    }

    suspend fun clearScreen() {
        lock.withLock {
            for (r in 0 until height) {
                for (c in 0 until width) {
                    clearCell(buffer[r][c])
                }
            }
        }
    }
}