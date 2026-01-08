package com.lockedfog.clip.engine

import com.github.ajalt.mordant.terminal.Terminal
import com.lockedfog.clip.core.VirtualCursor
import com.lockedfog.clip.core.VirtualScreen
import com.lockedfog.clip.model.Command
import com.lockedfog.clip.model.ScriptElement
import com.lockedfog.clip.model.Timestamp
import com.lockedfog.clip.parser.ScriptParser
import javazoom.jl.player.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import kotlin.math.roundToLong

class ScriptEngine(
    private val terminal: Terminal,
    private val scriptContent: List<ScriptElement>
) {

    // --- 全局状态 ---
    // 存储函数定义，包含原始文本行以便进行参数替换
    private val functions = mutableMapOf<String, Command.DefineFunction>()
    // 存储别名定义，Key=别名, Value=原始指令字符串
    private val aliases = mutableMapOf<String, String>()

    // JIT 解析器，用于在运行时解析替换参数后的函数体
    private val parser = ScriptParser()

    // 虚拟屏幕与显存
    private lateinit var screen: VirtualScreen

    // 渲染互斥锁，防止输出撕裂
    private val printLock = Mutex()

    init {
        // 预扫描：提取所有函数定义和别名，不放入主执行流
        scriptContent.forEach {
            when (it) {
                is Command.DefineFunction -> functions[it.name] = it
                is Command.DefineAlias -> aliases[it.name] = it.content
                else -> {}
            }
        }
    }

    suspend fun run(musicFile: File? = null) = coroutineScope {
        // 1. 初始化屏幕
        // 获取终端尺寸 (Mordant 3.0+ 使用 terminal.size)
        val size = terminal.size
        // 默认至少 24x80
        val height = size.height.coerceAtLeast(24)
        val width = size.width.coerceAtLeast(80)

        screen = VirtualScreen(height, width)
        val mainCursor = VirtualCursor(screen, isMainThread = true, canOverride = true)

        // 2. 启动渲染循环 (Render Loop) - 30 FPS
        val renderJob = launch(Dispatchers.IO) {
            // 隐藏光标
            terminal.cursor.hide(showOnExit = true)
            // 清屏
            terminal.rawPrint("\u001b[2J")

            while (isActive) {
                val frameStart = System.currentTimeMillis()

                // 计算差异并输出
                val diff = screen.generateDiffAndSwap()
                if (diff.isNotEmpty()) {
                    printLock.withLock {
                        terminal.rawPrint(diff)
                    }
                }

                // 简单的帧率控制 (33ms per frame)
                val frameTime = System.currentTimeMillis() - frameStart
                val delayTime = (33 - frameTime).coerceAtLeast(1)
                delay(delayTime)
            }
        }

        // 3. 准备音频
        val player = musicFile?.let { SimpleAudioPlayer(it) }

        terminal.println("Ready. Press ENTER to start...")
        withContext(Dispatchers.IO) {
            System.`in`.read()
        }

        // 再次清屏准备开始
        screen.clearScreen()

        // 4. 执行主时间轴
        val startTime = System.currentTimeMillis()
        player?.play()

        try {
            // 过滤掉定义语句，只执行主逻辑
            val mainElements = scriptContent.filter {
                it !is Command.DefineFunction && it !is Command.DefineAlias
            }

            // 启动主执行流
            executeScope(
                elements = mainElements,
                cursor = mainCursor,
                scopeStartTime = startTime,
                parentBpm = 120.0 // 默认 BPM
            )

            // 等待最后的延迟
            delay(1000)

        } catch (e: Exception) {
            // 错误处理，恢复终端状态
            renderJob.cancel()
            player?.stop()
            terminal.cursor.show()
            throw e
        }

        // 5. 结束清理
        player?.stop()
        renderJob.cancelAndJoin()

        // 恢复光标位置到屏幕下方
        terminal.cursor.move { setPosition(height, 0) }
        terminal.cursor.show()
        terminal.println("\nPlayback finished.")
    }

    /**
     * 执行一个指令作用域 (Scope)
     * 支持递归调用、参数替换和 JIT 解析
     */
    private suspend fun executeScope(
        elements: List<ScriptElement>,
        cursor: VirtualCursor,
        scopeStartTime: Long,
        parentBpm: Double
    ): Unit = coroutineScope {

        var currentBpm = parentBpm
        var lastEventOffsetMs = 0L

        for (element in elements) {
            if (!isActive) break

            when (element) {
                // --- 时间戳处理 ---
                is Timestamp -> {
                    val now = System.currentTimeMillis()

                    val targetOffsetMs = calculateTargetOffset(
                        ts = element,
                        currentBpm = currentBpm,
                        lastOffsetMs = lastEventOffsetMs
                    )

                    // 更新基准偏移 (Continuation 不更新基准)
                    if (element !is Timestamp.Continuation) {
                        lastEventOffsetMs = targetOffsetMs
                    }

                    // 漂移修正等待
                    val waitMs = (scopeStartTime + targetOffsetMs) - now
                    if (waitMs > 0) {
                        delay(waitMs)
                    }
                }

                // --- 指令处理 ---
                is Command -> {
                    when (element) {
                        is Command.SetBpm -> currentBpm = element.value

                        is Command.PrintText -> cursor.printText(element.text)
                        is Command.PrintSpace -> cursor.printText(" ".repeat(element.count))
                        is Command.NewLine -> cursor.newLine()

                        is Command.ClearScreen -> screen.clearScreen()
                        is Command.ClearScreenNoReset -> screen.clearScreen()

                        is Command.MoveAbsolute -> cursor.moveTo(element.row - 1, element.col - 1)
                        is Command.MoveRelative -> cursor.moveRelative(element.dRow, element.dCol)

                        is Command.SetColor -> cursor.fgColor = (element.r shl 16) or (element.g shl 8) or element.b
                        is Command.ClearColor -> cursor.fgColor = null

                        is Command.SetBackground -> cursor.bgColor = (element.r shl 16) or (element.g shl 8) or element.b
                        is Command.ClearBackground -> cursor.bgColor = null

                        is Command.SetStyle -> {
                            if (element.bold) cursor.bold = true
                            if (element.italic) cursor.italic = true
                            if (element.underline) cursor.underline = true
                            if (element.strikethrough) cursor.strikethrough = true
                        }
                        is Command.ClearStyle -> cursor.resetStyle()

                        // --- 核心：函数/协程/别名调用 (宏展开 & JIT) ---

                        is Command.CallFunction -> {
                            // 1. 优先检查是否为 Alias
                            val aliasContent = aliases[element.name]
                            if (aliasContent != null) {
                                // JIT 解析别名内容
                                val resolvedElements = parser.parseLineContent(aliasContent)
                                // 立即在当前上下文执行 (不重置时间，视为宏插入)
                                executeScope(resolvedElements, cursor, scopeStartTime + lastEventOffsetMs, currentBpm)
                            } else {
                                // 2. 检查是否为 Function
                                val funcDef = functions[element.name]
                                if (funcDef != null) {
                                    // 2.1 参数替换
                                    val expandedLines = substituteParams(funcDef.rawBodyLines, funcDef.params, element.args)
                                    // 2.2 JIT 解析整个函数体
                                    val resolvedElements = parser.parse(expandedLines)
                                    // 2.3 递归执行 (更新 theoreticalNow)
                                    val theoreticalNow = scopeStartTime + lastEventOffsetMs
                                    executeScope(resolvedElements, cursor, theoreticalNow, currentBpm)
                                } else {
                                    // 未找到函数或别名，作为普通文本输出 (Fallback)
                                    cursor.printText("[${element.name}]")
                                }
                            }
                        }

                        is Command.CallCoroutine -> {
                            // 协程暂不支持 Alias，只支持 Function
                            val funcDef = functions[element.name]
                            if (funcDef != null) {
                                // 1. 复制光标
                                val subCursor = cursor.copy(
                                    newIsMain = false,
                                    newCanOverride = funcDef.allowOverride
                                )
                                // 2. 参数替换
                                val expandedLines = substituteParams(funcDef.rawBodyLines, funcDef.params, element.args)
                                // 3. JIT 解析
                                val resolvedElements = parser.parse(expandedLines)

                                val theoreticalNow = scopeStartTime + lastEventOffsetMs

                                // 4. 启动新协程
                                launch {
                                    executeScope(resolvedElements, subCursor, theoreticalNow, currentBpm)
                                }
                            }
                        }

                        // 定义语句在 Init 阶段处理，运行时忽略
                        is Command.DefineFunction -> {}
                        is Command.DefineAlias -> {}
                    }
                }
            }
        }
    }

    /**
     * 参数替换逻辑
     * 将函数体中的 [paramName] 替换为 argValue
     */
    private fun substituteParams(
        rawLines: List<String>,
        params: List<String>,
        args: List<String>
    ): List<String> {
        if (params.isEmpty()) return rawLines

        return rawLines.map { line ->
            var expandedLine = line
            for (i in params.indices) {
                // 如果实参不足，用空字符串代替
                val arg = args.getOrElse(i) { "" }
                val param = params[i]
                // 简单的字符串替换: [x] -> 10
                // 注意：这里假设参数在脚本中以 [param] 形式出现
                expandedLine = expandedLine.replace("[$param]", arg)
            }
            expandedLine
        }
    }

    private fun calculateTargetOffset(
        ts: Timestamp,
        currentBpm: Double,
        lastOffsetMs: Long
    ): Long {
        val msPerBeat = 60_000.0 / currentBpm

        return when (ts) {
            is Timestamp.AbsoluteMs -> ts.ms
            is Timestamp.AbsoluteBeat -> (ts.beat * msPerBeat).roundToLong()
            is Timestamp.AbsoluteBeatPlusMs -> (ts.beat * msPerBeat).roundToLong() + ts.offsetMs
            is Timestamp.AbsoluteBeatPlusFraction -> {
                val beatMs = ts.beat * msPerBeat
                val fracMs = (ts.numerator.toDouble() / ts.denominator) * msPerBeat
                (beatMs + fracMs).roundToLong()
            }

            is Timestamp.RelativeMs -> lastOffsetMs + ts.ms
            is Timestamp.RelativeBeat -> lastOffsetMs + (ts.beat * msPerBeat).roundToLong()
            is Timestamp.RelativeFractionBeat -> {
                val duration = (ts.numerator.toDouble() / ts.denominator) * msPerBeat
                lastOffsetMs + duration.roundToLong()
            }

            is Timestamp.Continuation -> lastOffsetMs
        }
    }
}

/**
 * 简易音频播放器 (JLayer Wrapper)
 */
class SimpleAudioPlayer(private val file: File) {
    private var player: Player? = null

    fun play() {
        try {
            val fis = FileInputStream(file)
            player = Player(fis)
            Thread {
                try { player?.play() } catch (e: Exception) { /* ignore end of stream */ }
            }.start()
        } catch (e: Exception) {
            System.err.println("Error playing audio: ${e.message}")
        }
    }

    fun stop() {
        try { player?.close() } catch (e: Exception) {}
    }
}