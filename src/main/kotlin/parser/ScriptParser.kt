package com.lockedfog.clip.parser

import com.lockedfog.clip.model.Command
import com.lockedfog.clip.model.ScriptElement
import com.lockedfog.clip.model.Timestamp

class ScriptParser {

    companion object {
        private val RESERVED_KEYWORDS = setOf(
            "bpm", "newline", "mv", "color", "clearcolor",
            "background", "clearbackground", "style", "clearstyle",
            "clear", "clearn", "space", "override"
        )
    }

    // ==========================================
    // 1. 正则表达式定义 (Regex Definitions)
    // ==========================================

    // --- 时间戳 ---
    private val regexAbsMs = Regex("""^(\d{2}):(\d{2})\.(\d{3})$""")
    private val regexAbsBeatPlusFrac = Regex("""^(\d+(?:\.\d+)?)b\+(\d+)b(\d+)$""")
    private val regexAbsBeatPlusMs = Regex("""^(\d+(?:\.\d+)?)b\+(\d+)$""")
    private val regexAbsBeat = Regex("""^(\d+(?:\.\d+)?)b$""")

    private val regexRelFrac = Regex("""^\+(\d+)b(\d+)$""")
    private val regexRelBeat = Regex("""^\+(\d+(?:\.\d+)?)b$""")
    private val regexRelMs = Regex("""^\+(\d+)$""")

    // --- 指令 ---
    private val regexBpm = Regex("""^bpm\s+(\d+(?:\.\d+)?)$""")
    private val regexKeywords = Regex("""^(newline|clear|clearn|clearcolor|clearbackground|clearstyle)$""")

    // [space] or [space 5]
    private val regexSpace = Regex("""^space(?:\s+(\d+))?$""")

    // [mv x,y] - 支持逗号分隔
    // 绝对移动: mv 10, 5
    private val regexMvAbs = Regex("""^mv\s+(\d+)\s*,\s*(\d+)$""")
    // 相对移动: mv +1, -2
    private val regexMvRel = Regex("""^mv\s+([+-]\d+)\s*,\s*([+-]\d+)$""")

    // 颜色: [color FFFFFF] or [color #FFFFFF]
    private val regexColor = Regex("""^color\s+#?([0-9a-fA-F]{6})$""")
    // 背景: [background FFFFFFFF] (RGBA)
    private val regexBackground = Regex("""^background\s+#?([0-9a-fA-F]{8})$""")

    private val regexStyle = Regex("""^style\s+([^]]+)$""")

    // --- 元指令 ---

    // [@alias content]
    // content 将作为原始字符串保存，留待运行时解析
    private val regexDefineAlias = Regex("""^@(\S+)\s+(.+?)$""")

    // [#func] or [#func p1,p2]
    private val regexDefineFunc = Regex("""^#(\w+)(?:\s+([\w,]+))?$""")

    // [++func arg1,arg2] - 协程调用
    private val regexCallCoroutine = Regex("""^\+\+(\w+)(?:\s+([^]]+))?$""")

    // [func arg1,arg2] - 普通调用
    private val regexCallFunc = Regex("""^(\w+)(?:\s+([^]]+))?$""")

    // ==========================================
    // 2. 解析逻辑 (Parsing Logic)
    // ==========================================

    fun parse(lines: List<String>): List<ScriptElement> {
        return parseWithIndex(lines)
    }

    private fun parseWithIndex(lines: List<String>): List<ScriptElement> {
        val elements = mutableListOf<ScriptElement>()
        var i = 0
        var expectingContinuation = false

        while (i < lines.size) {
            val rawLine = lines[i]
            val trimmed = rawLine.trim()

            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                i++
                continue
            }

            var lineContent = rawLine
            val isContinuation = expectingContinuation
            var nextWillContinue = false

            if (trimmed.endsWith("[>]") && !trimmed.endsWith("\\[>]")) {
                nextWillContinue = true
                lineContent = lineContent.substring(0, lineContent.lastIndexOf("[>]"))
            }
            expectingContinuation = nextWillContinue

            // 解析当前行
            val rowElements = try {
                parseLineContent(lineContent)
            } catch (e: Exception) {
                throw IllegalArgumentException("Error parsing line ${i + 1}: ${e.message}", e)
            }

            // 检查是否有函数定义
            val defineFuncCmd = rowElements.filterIsInstance<Command.DefineFunction>().firstOrNull()

            if (defineFuncCmd != null) {
                // --- 处理函数定义块 ---
                if (isContinuation) {
                    throw IllegalArgumentException("Line ${i + 1}: Function definition cannot be a continuation line.")
                }

                val funcName = defineFuncCmd.name
                val params = defineFuncCmd.params

                // 检查是否包含 [override] 标记
                // [override] 在 parseLineContent 中会被解析为 PrintText("[override]")
                // 因为它没有被识别为特定的 Command
                val allowOverride = rowElements.any {
                    it is Command.PrintText && it.text == "[override]"
                }

                // 开始读取函数体 (Raw Body Capture)
                // 关键修正：这里只读取原始文本，不进行 parseLineContent 解析
                // 以便支持 [x] 这样的参数占位符，留给 Engine 在运行时替换
                val rawBodyLines = mutableListOf<String>()
                i++ // 移动到下一行

                while (i < lines.size) {
                    val bodyLineRaw = lines[i].trim()
                    if (bodyLineRaw.startsWith("[<]")) {
                        // 这是一个函数体行，移除 [<] 标记，保存剩余部分
                        val actualContent = lines[i].substringAfter("[<]")
                        rawBodyLines.add(actualContent)
                        i++
                    } else if (bodyLineRaw.isEmpty() || bodyLineRaw.startsWith("//")) {
                        // 跳过空行和注释
                        i++
                    } else {
                        // 遇到非函数体行，函数定义结束
                        break
                    }
                }

                // 构造完整的 DefineFunction 并添加
                // 这里的 rawBodyLines 是 List<String>
                elements.add(Command.DefineFunction(funcName, params, rawBodyLines, allowOverride))

                // 注意：此时 i 已经指向了下一条非函数体指令，外层循环会处理它
                expectingContinuation = false

            } else {
                // --- 处理普通行 ---
                val isBpm = rowElements.any { it is Command.SetBpm }
                val isAlias = rowElements.any { it is Command.DefineAlias }

                // 简单的合法性检查：非接续行必须以时间戳开头（除非是 BPM/Alias/FunctionDef）
                if (!isContinuation && !isBpm && !isAlias && rowElements.isNotEmpty()) {
                    val first = rowElements.first()
                    if (first !is Timestamp) {
                        throw IllegalArgumentException("Line ${i + 1}: MUST start with a timestamp ($trimmed)")
                    }
                }

                if (isContinuation) {
                    elements.add(Timestamp.Continuation)
                }
                elements.addAll(rowElements)
                i++
            }
        }

        return elements
    }

    /**
     * 解析单行内容
     * 改为 Public，以便 ScriptEngine 在运行时进行 JIT 解析
     */
    fun parseLineContent(line: String): List<ScriptElement> {
        val result = mutableListOf<ScriptElement>()
        val sb = StringBuilder()
        var i = 0
        val len = line.length

        while (i < len) {
            val c = line[i]

            if (c == '\\') {
                // 处理转义字符
                if (i + 1 < len) {
                    val nextC = line[i + 1]
                    when (nextC) {
                        '[' -> sb.append('[')
                        ']' -> sb.append(']')
                        '\\' -> sb.append('\\')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        '>' -> sb.append('>') // 转义接续符
                        else -> sb.append(nextC)
                    }
                    i += 2
                } else {
                    sb.append('\\')
                    i++
                }
            } else if (c == '[') {
                // 遇到左括号
                // 1. 先把之前累积的文本处理掉 (如果有)
                if (sb.isNotEmpty()) {
                    result.add(Command.PrintText(sb.toString()))
                    sb.clear()
                }

                // 2. 提取括号内容
                val start = i + 1
                var end = start
                var insideEscape = false
                var foundEnd = false

                while (end < len) {
                    if (line[end] == '\\') {
                        insideEscape = !insideEscape
                    } else if (line[end] == ']' && !insideEscape) {
                        foundEnd = true
                        break
                    } else {
                        insideEscape = false
                    }
                    end++
                }

                if (foundEnd) {
                    val content = line.substring(start, end)
                    val element = parseBracketContent(content)
                    result.add(element)
                    i = end + 1
                } else {
                    // 没找到闭合括号，视为普通文本 '['
                    sb.append('[')
                    i++
                }
            } else {
                // 普通字符：只有非空白字符才会被加入
                // 要求：输出的空格应当只来自于 [space] 指令，解析时要过滤掉用户在文本间留下的空格
                if (!c.isWhitespace()) {
                    sb.append(c)
                }
                i++
            }
        }

        if (sb.isNotEmpty()) {
            result.add(Command.PrintText(sb.toString()))
        }

        return result
    }

    private fun parseBracketContent(content: String): ScriptElement {
        val trimmed = content.trim()

        // --- Timestamp Parsing ---
        if (regexAbsMs.matches(trimmed)) {
            val (m, s, ms) = regexAbsMs.find(trimmed)!!.destructured
            return Timestamp.AbsoluteMs(m.toLong() * 60000 + s.toLong() * 1000 + ms.toLong())
        }
        if (regexAbsBeatPlusFrac.matches(trimmed)) {
            val (b, n, d) = regexAbsBeatPlusFrac.find(trimmed)!!.destructured
            return Timestamp.AbsoluteBeatPlusFraction(b.toDouble(), n.toInt(), d.toInt())
        }
        if (regexAbsBeatPlusMs.matches(trimmed)) {
            val (b, ms) = regexAbsBeatPlusMs.find(trimmed)!!.destructured
            return Timestamp.AbsoluteBeatPlusMs(b.toDouble(), ms.toLong())
        }
        if (regexAbsBeat.matches(trimmed)) {
            val (b) = regexAbsBeat.find(trimmed)!!.destructured
            return Timestamp.AbsoluteBeat(b.toDouble())
        }
        if (regexRelFrac.matches(trimmed)) {
            val (n, d) = regexRelFrac.find(trimmed)!!.destructured
            return Timestamp.RelativeFractionBeat(n.toInt(), d.toInt())
        }
        if (regexRelBeat.matches(trimmed)) {
            val (b) = regexRelBeat.find(trimmed)!!.destructured
            return Timestamp.RelativeBeat(b.toDouble())
        }
        if (regexRelMs.matches(trimmed)) {
            val (ms) = regexRelMs.find(trimmed)!!.destructured
            return Timestamp.RelativeMs(ms.toLong())
        }

        // --- Command Parsing ---
        if (regexBpm.matches(trimmed)) {
            return Command.SetBpm(regexBpm.find(trimmed)!!.groupValues[1].toDouble())
        }

        // Space
        if (regexSpace.matches(trimmed)) {
            val match = regexSpace.find(trimmed)!!
            val countStr = match.groupValues[1]
            val count = if (countStr.isBlank()) 1 else countStr.toInt().coerceAtLeast(1)
            return Command.PrintSpace(count)
        }

        // Keywords
        val keywordMatch = regexKeywords.find(trimmed)
        if (keywordMatch != null) {
            return when (keywordMatch.groupValues[1]) {
                "newline" -> Command.NewLine
                "clear" -> Command.ClearScreen
                "clearn" -> Command.ClearScreenNoReset
                "clearcolor" -> Command.ClearColor
                "clearbackground" -> Command.ClearBackground
                "clearstyle" -> Command.ClearStyle
                else -> Command.PrintText("[$content]")
            }
        }

        // Move
        if (regexMvRel.matches(trimmed)) {
            val (dRow, dCol) = regexMvRel.find(trimmed)!!.destructured
            return Command.MoveRelative(dRow.toInt(), dCol.toInt())
        }
        if (regexMvAbs.matches(trimmed)) {
            val (row, col) = regexMvAbs.find(trimmed)!!.destructured
            return Command.MoveAbsolute(row.toInt(), col.toInt())
        }

        // Color & Background
        if (regexColor.matches(trimmed)) {
            val hex = regexColor.find(trimmed)!!.groupValues[1]
            val r = hex.substring(0, 2).toInt(16)
            val g = hex.substring(2, 4).toInt(16)
            val b = hex.substring(4, 6).toInt(16)
            return Command.SetColor(r, g, b)
        }

        if (regexBackground.matches(trimmed)) {
            val hex = regexBackground.find(trimmed)!!.groupValues[1]
            val r = hex.substring(0, 2).toInt(16)
            val g = hex.substring(2, 4).toInt(16)
            val b = hex.substring(4, 6).toInt(16)
            val a = hex.substring(6, 8).toInt(16)
            return Command.SetBackground(r, g, b, a)
        }

        // Style
        if (regexStyle.matches(trimmed)) {
            val styles = regexStyle.find(trimmed)!!.groupValues[1].lowercase()
            return Command.SetStyle(
                bold = "bold" in styles,
                italic = "italic" in styles,
                underline = "underline" in styles,
                strikethrough = "strikethrough" in styles || "strike" in styles
            )
        }

        // --- Meta Instructions ---

        // Define Function Header: [#func p1,p2]
        // 这里仅解析 Header，Body 由 parseWithIndex 处理
        if (regexDefineFunc.matches(trimmed)) {
            val match = regexDefineFunc.find(trimmed)!!
            val name = match.groupValues[1]
            val paramsStr = match.groupValues[2]
            val params = if (paramsStr.isBlank()) emptyList() else paramsStr.split(",").map { it.trim() }
            validateAliasName(name)
            // 返回一个临时的 DefineFunction，rawBodyLines 为空，allowOverride 默认为 false (将在主循环修正)
            return Command.DefineFunction(name, params, emptyList(), false)
        }

        // Define Alias: [@alias content]
        // 内容将按原样字符串保存，不进行指令解析
        if (regexDefineAlias.matches(trimmed)) {
            val (name, aliasContent) = regexDefineAlias.find(trimmed)!!.destructured
            validateAliasName(name)
            return Command.DefineAlias(name, aliasContent)
        }

        // Call Coroutine: [++func arg1,arg2]
        if (regexCallCoroutine.matches(trimmed)) {
            val match = regexCallCoroutine.find(trimmed)!!
            val name = match.groupValues[1]
            val argsStr = match.groupValues[2]
            val args = if (argsStr.isBlank()) emptyList() else argsStr.split(",").map { it.trim() }
            return Command.CallCoroutine(name, args)
        }

        // Call Function: [func arg1,arg2]
        if (regexCallFunc.matches(trimmed)) {
            val match = regexCallFunc.find(trimmed)!!
            val name = match.groupValues[1]
            val argsStr = match.groupValues[2]
            val args = if (argsStr.isBlank()) emptyList() else argsStr.split(",").map { it.trim() }
            return Command.CallFunction(name, args)
        }

        // 如果是 [override] 标记，或者其他未识别指令，作为文本返回
        // 主循环会识别 PrintText("[override]") 并将其转换为元数据
        return Command.PrintText("[$content]")
    }

    private fun validateAliasName(name: String) {
        if (name in RESERVED_KEYWORDS) {
            throw IllegalArgumentException("Syntax Error: Name '$name' is reserved.")
        }
    }

}