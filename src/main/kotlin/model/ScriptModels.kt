package com.lockedfog.clip.model

/**
 * 脚本元素标记接口
 * 用于表示解析后的流中的任何对象（时间戳、指令或纯文本）
 */
sealed interface ScriptElement

/**
 * 时间戳定义 (Timeline)
 * 用于标记动作触发的时间点
 */
sealed class Timestamp : ScriptElement {
    // --- 绝对时间 (Absolute) ---

    // [mm:ss.xxx] -> 转换为总毫秒数
    data class AbsoluteMs(val ms: Long) : Timestamp()

    // [xxb] -> 指定第几拍 (例如: 10.5b)
    data class AbsoluteBeat(val beat: Double) : Timestamp()

    // [xxb+xxx] -> 指定第几拍 + 额外毫秒偏移 (例如: 10b+500)
    data class AbsoluteBeatPlusMs(val beat: Double, val offsetMs: Long) : Timestamp()

    // [xxb+xxbxx] -> 指定第几拍 + 分数拍偏移 (例如: 10b+1b3 表示 10拍 + 1/3拍)
    // 注意：numerator=分子, denominator=分母
    data class AbsoluteBeatPlusFraction(val beat: Double, val numerator: Int, val denominator: Int) : Timestamp()

    // --- 相对时间 (Relative) ---

    // [+xxx] -> 相对毫秒偏移
    data class RelativeMs(val ms: Long) : Timestamp()

    // [+xxb] -> 相对节拍偏移 (例如: +1.5b)
    data class RelativeBeat(val beat: Double) : Timestamp()

    // [+xxbxx] -> 相对分数拍偏移 (例如: +1b3 表示 +1/3拍)
    data class RelativeFractionBeat(val numerator: Int, val denominator: Int) : Timestamp()

    // --- 特殊标记 ---

    // [>] -> 接续上一行 (不重置时间锚点，属于同一时间帧)
    data object Continuation : Timestamp()
}

/**
 * 指令集定义 (Commands)
 * 对应脚本中 [] 包裹的操作
 */
sealed class Command : ScriptElement {

    // --- 全局控制 ---

    // [bpm xxx] 设置当前 BPM
    data class SetBpm(val value: Double) : Command()

    // --- 屏幕控制 ---

    // [newline] 换行
    data object NewLine : Command()

    // [clear] 清屏并复位光标
    data object ClearScreen : Command()

    // [clearn] 清屏但不复位光标
    data object ClearScreenNoReset : Command()

    // --- 光标移动 ---

    // [mv x y] 绝对移动 (1-based index)
    data class MoveAbsolute(val x: Int, val y: Int) : Command()

    // [mv +x -y] 相对移动
    data class MoveRelative(val dx: Int, val dy: Int) : Command()

    // --- 颜色与背景 ---

    // [color r g b] 前景色 (0-255)
    data class SetColor(val r: Int, val g: Int, val b: Int) : Command()

    // [clearcolor] 清除前景色
    data object ClearColor : Command()

    // [background r g b a] 背景色 (0-255)
    // 注：大多数终端对 Alpha 支持有限，通常只用 RGB，但模型保留 Alpha 字段以备将来支持
    data class SetBackground(val r: Int, val g: Int, val b: Int, val a: Int) : Command()

    // [clearbackground] 清除背景色
    data object ClearBackground : Command()

    // --- 字体样式 ---

    // [style bold/italic/...] 设置样式组合
    // 每次调用 [style] 会覆盖之前的样式设置，未指定的视为 false
    data class SetStyle(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false
    ) : Command()

    // [clearstyle] 清除所有字体样式
    data object ClearStyle : Command()

    // --- 函数与宏 (Meta Instructions) ---

    // [@name content] 定义别名
    data class DefineAlias(val name: String, val content: String) : Command()

    // [#name] 或 [#name p1,p2] 定义函数头
    // 实际脚本中，函数体是跟随在定义头之后的若干行
    data class DefineFunction(val name: String, val params: List<String>) : Command()

    // [name] 或 [name arg1,arg2] 调用函数
    data class CallFunction(val name: String, val args: List<String>) : Command()

    // --- 文本输出 ---

    //[space] or [space n] 输出指定数量的空格
    data class PrintSpace(val count: Int) : Command()

    // 脚本中的纯文本内容
    data class PrintText(val text: String) : Command()
}