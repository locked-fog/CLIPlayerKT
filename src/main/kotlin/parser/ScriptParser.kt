package com.lockedfog.clip.parser

import com.lockedfog.clip.model.Command
import com.lockedfog.clip.model.ScriptElement
import com.lockedfog.clip.model.Timestamp

class ScriptParser {

    companion object {
        private val RESERVED_KEYWORDS = setOf(
            "bpm", "newline", "mv", "color", "clearcolor",
            "background", "clearbackground", "style", "clearstyle",
            "clear", "clearn", "space"
        )
    }

    // ==========================================
    // 1. 正则表达式定义 (Regex Definitions)
    // ==========================================

    private val regexAbsMs = Regex("""^(\d{2}):(\d{2})\.(\d{3})$""")
    private val regexAbsBeatPlusFrac = Regex("""^(\d+(?:\.\d+)?)b\+(\d+)b(\d+)$""")
    private val regexAbsBeatPlusMs = Regex("""^(\d+(?:\.\d+)?)b\+(\d+)$""")
    private val regexAbsBeat = Regex("""^(\d+(?:\.\d+)?)b$""")

    private val regexRelFrac = Regex("""^\+(\d+)b(\d+)$""")
    private val regexRelBeat = Regex("""^\+(\d+(?:\.\d+)?)b$""")
    private val regexRelMs = Regex("""^\+(\d+)$""")

    private val regexBpm = Regex("""^bpm\s+(\d+(?:\.\d+)?)$""")
    private val regexKeywords = Regex("""^(newline|clear|clearn|clearcolor|clearbackground|clearstyle)$""")

    // [space] or [space 5]
    private val regexSpace = Regex("""^space(?:\s+(\d+))?$""")

    private val regexMvRel = Regex("""^mv\s+([+-]\d+)\s+([+-]\d+)$""")
    private val regexMvAbs = Regex("""^mv\s+(\d+)\s+(\d+)$""")

    // --- 变更：Hex 颜色正则 ---
    // 匹配 [color FFFFFF] 或 [color #FFFFFF]
    private val regexColor = Regex("""^color\s+(?:#?)([0-9a-fA-F]{6})$""")

    // 匹配 [background FFFFFFFF] 或 [background #FFFFFFFF] (包含Alpha)
    private val regexBackground = Regex("""^background\s+(?:#?)([0-9a-fA-F]{8})$""")

    private val regexStyle = Regex("""^style\s+([^]]+)$""")

    private val regexDefineAlias = Regex("""^@(\S+)\s+(.+?)$""")
    private val regexDefineFunc = Regex("""^#(\w+)(?:\s+([\w,]+))?$""")
    private val regexCallFunc = Regex("""^(\w+)(?:\s+([^]]+))?$""")

    // ==========================================
    // 2. 解析逻辑 (Parsing Logic)
    // ==========================================

    fun parse(lines: List<String>): List<ScriptElement> {
        val elements = mutableListOf<ScriptElement>()
        var expectingContinuation = false

        lines.forEachIndexed { index, rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//")) return@forEachIndexed

            var lineContent = rawLine
            val isContinuation = expectingContinuation
            var nextWillContinue = false

            if (trimmed.endsWith("[>]") && !trimmed.endsWith("\\[>]")) {
                nextWillContinue = true
                lineContent = lineContent.substring(0, lineContent.lastIndexOf("[>]"))
            }

            expectingContinuation = nextWillContinue

            val isMetaCommand = trimmed.startsWith("[@") || trimmed.startsWith("[#")
            val isBpmCommand = trimmed.startsWith("[bpm")

            val firstToken = extractFirstToken(lineContent)
            val startsWithTimestamp = firstToken != null && isTimestamp(firstToken)

            if (!isContinuation && !isBpmCommand && !isMetaCommand && !startsWithTimestamp) {
                throw IllegalArgumentException("Line ${index + 1}: MUST start with a timestamp (Absolute or Relative), or use [>] for continuation.")
            }

            try {
                if (isContinuation) {
                    elements.add(Timestamp.Continuation)
                }
                elements.addAll(parseLineContent(lineContent))
            } catch (e: Exception) {
                throw IllegalArgumentException("Error parsing line ${index + 1}: ${e.message}", e)
            }
        }
        return elements
    }

    private fun parseLineContent(line: String): List<ScriptElement> {
        val result = mutableListOf<ScriptElement>()
        val sb = StringBuilder()
        var i = 0
        val len = line.length

        while (i < len) {
            val c = line[i]

            if (c == '\\') {
                if (i + 1 < len) {
                    val nextC = line[i + 1]
                    when (nextC) {
                        '[' -> sb.append('[')
                        ']' -> sb.append(']')
                        '\\' -> sb.append('\\')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        '>' -> sb.append('>')
                        else -> {
                            sb.append('\\').append(nextC)
                        }
                    }
                    i += 2
                } else {
                    sb.append('\\')
                    i++
                }
            } else if (c == '[') {
                if (sb.isNotEmpty()) {
                    result.add(Command.PrintText(sb.toString()))
                    sb.clear()
                }

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
                    sb.append('[')
                    i++
                }
            } else {
                sb.append(c)
                i++
            }
        }

        if (sb.isNotEmpty()) {
            result.add(Command.PrintText(sb.toString()))
        }

        return result
    }

    private fun parseBracketContent(content: String): ScriptElement {
        // --- Timestamp Parsing ---
        if (regexAbsMs.matches(content)) {
            val (m, s, ms) = regexAbsMs.find(content)!!.destructured
            return Timestamp.AbsoluteMs(m.toLong() * 60000 + s.toLong() * 1000 + ms.toLong())
        }
        if (regexAbsBeatPlusFrac.matches(content)) {
            val (b, n, d) = regexAbsBeatPlusFrac.find(content)!!.destructured
            return Timestamp.AbsoluteBeatPlusFraction(b.toDouble(), n.toInt(), d.toInt())
        }
        if (regexAbsBeatPlusMs.matches(content)) {
            val (b, ms) = regexAbsBeatPlusMs.find(content)!!.destructured
            return Timestamp.AbsoluteBeatPlusMs(b.toDouble(), ms.toLong())
        }
        if (regexAbsBeat.matches(content)) {
            val (b) = regexAbsBeat.find(content)!!.destructured
            return Timestamp.AbsoluteBeat(b.toDouble())
        }
        if (regexRelFrac.matches(content)) {
            val (n, d) = regexRelFrac.find(content)!!.destructured
            return Timestamp.RelativeFractionBeat(n.toInt(), d.toInt())
        }
        if (regexRelBeat.matches(content)) {
            val (b) = regexRelBeat.find(content)!!.destructured
            return Timestamp.RelativeBeat(b.toDouble())
        }
        if (regexRelMs.matches(content)) {
            val (ms) = regexRelMs.find(content)!!.destructured
            return Timestamp.RelativeMs(ms.toLong())
        }

        // --- Command Parsing ---
        if (regexBpm.matches(content)) {
            return Command.SetBpm(regexBpm.find(content)!!.groupValues[1].toDouble())
        }

        // Space
        if (regexSpace.matches(content)) {
            val match = regexSpace.find(content)!!
            val countStr = match.groupValues[1]
            val count = if (countStr.isBlank()) 1 else countStr.toInt().coerceAtLeast(1)
            return Command.PrintSpace(count)
        }

        val keywordMatch = regexKeywords.find(content)
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

        if (regexMvRel.matches(content)) {
            val (dx, dy) = regexMvRel.find(content)!!.destructured
            return Command.MoveRelative(dx.toInt(), dy.toInt())
        }
        if (regexMvAbs.matches(content)) {
            val (x, y) = regexMvAbs.find(content)!!.destructured
            return Command.MoveAbsolute(x.toInt(), y.toInt())
        }

        // --- 变更：Hex 颜色解析 ---
        if (regexColor.matches(content)) {
            val hex = regexColor.find(content)!!.groupValues[1] // 获取 RRGGBB 字符串
            val r = hex.substring(0, 2).toInt(16)
            val g = hex.substring(2, 4).toInt(16)
            val b = hex.substring(4, 6).toInt(16)
            return Command.SetColor(r, g, b)
        }

        // --- 变更：Hex 背景解析 ---
        if (regexBackground.matches(content)) {
            val hex = regexBackground.find(content)!!.groupValues[1] // 获取 RRGGBBAA 字符串
            val r = hex.substring(0, 2).toInt(16)
            val g = hex.substring(2, 4).toInt(16)
            val b = hex.substring(4, 6).toInt(16)
            val a = hex.substring(6, 8).toInt(16)
            return Command.SetBackground(r, g, b, a)
        }

        if (regexStyle.matches(content)) {
            val styles = regexStyle.find(content)!!.groupValues[1].lowercase()
            return Command.SetStyle(
                bold = "bold" in styles,
                italic = "italic" in styles,
                underline = "underline" in styles,
                strikethrough = "strikethrough" in styles || "strike" in styles
            )
        }

        // --- Meta Instructions ---
        if (regexDefineAlias.matches(content)) {
            val (name, aliasContent) = regexDefineAlias.find(content)!!.destructured
            validateAliasName(name)
            return Command.DefineAlias(name, aliasContent)
        }
        if (regexDefineFunc.matches(content)) {
            val match = regexDefineFunc.find(content)!!
            val name = match.groupValues[1]
            val paramsStr = match.groupValues[2]
            val params = if (paramsStr.isBlank()) emptyList() else paramsStr.split(",").map { it.trim() }
            validateAliasName(name)
            return Command.DefineFunction(name, params)
        }
        if (regexCallFunc.matches(content)) {
            val match = regexCallFunc.find(content)!!
            val name = match.groupValues[1]
            val argsStr = match.groupValues[2]
            val args = if (argsStr.isBlank()) emptyList() else argsStr.split(",").map { it.trim() }
            return Command.CallFunction(name, args)
        }

        return Command.PrintText("[$content]")
    }

    private fun validateAliasName(name: String) {
        if (name in RESERVED_KEYWORDS) {
            throw IllegalArgumentException("Syntax Error: Name '$name' is reserved.")
        }
    }

    private fun isTimestamp(content: String): Boolean {
        return regexAbsMs.matches(content) ||
                regexAbsBeat.matches(content) ||
                regexAbsBeatPlusMs.matches(content) ||
                regexAbsBeatPlusFrac.matches(content) ||
                regexRelMs.matches(content) ||
                regexRelBeat.matches(content) ||
                regexRelFrac.matches(content)
    }

    private fun extractFirstToken(line: String): String? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("[")) return null
        val end = trimmed.indexOf(']')
        if (end == -1) return null
        return trimmed.substring(1, end)
    }
}