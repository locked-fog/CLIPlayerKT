package com.lockedfog.clip

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.terminal.Terminal
import com.lockedfog.clip.model.Command
import com.lockedfog.clip.model.ScriptElement
import com.lockedfog.clip.model.Timestamp
import com.lockedfog.clip.parser.ScriptParser
import javazoom.jl.player.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import kotlin.math.roundToLong

// ==========================================
// 1. 核心模型：带绝对时间的动作
// ==========================================
data class TimedAction(
    val timeMs: Long,
    val command: Command
)

// ==========================================
// 2. 时间轴解析器 (Timeline Resolver)
// ==========================================
class TimelineResolver {

    private var currentBpm = 120.0
    private var currentTimeMs = 0L

    fun resolve(elements: List<ScriptElement>): List<TimedAction> {
        val resolvedActions = mutableListOf<TimedAction>()

        for (element in elements) {
            when (element) {
                is Command.SetBpm -> {
                    currentBpm = element.value
                }

                is Timestamp -> {
                    // 更新当前时间锚点
                    currentTimeMs = calculateNewTime(element, currentTimeMs, currentBpm)
                }

                is Command -> {
                    // 过滤掉非执行指令
                    if (element !is Command.DefineFunction && element !is Command.DefineAlias) {
                        resolvedActions.add(TimedAction(currentTimeMs, element))
                    }
                }
            }
        }

        return resolvedActions.sortedBy { it.timeMs }
    }

    private fun calculateNewTime(ts: Timestamp, currentMs: Long, bpm: Double): Long {
        val msPerBeat = (60_000.0 / bpm)

        return when (ts) {
            is Timestamp.AbsoluteMs -> ts.ms
            // 修正：调用 roundToLong()
            is Timestamp.AbsoluteBeat -> (ts.beat * msPerBeat).roundToLong()
            is Timestamp.AbsoluteBeatPlusMs -> (ts.beat * msPerBeat).roundToLong() + ts.offsetMs
            is Timestamp.AbsoluteBeatPlusFraction -> {
                val beatMs = ts.beat * msPerBeat
                val fracMs = (ts.numerator.toDouble() / ts.denominator) * msPerBeat
                (beatMs + fracMs).roundToLong()
            }

            // 相对时间叠加
            is Timestamp.RelativeMs -> currentMs + ts.ms
            is Timestamp.RelativeBeat -> currentMs + (ts.beat * msPerBeat).roundToLong()
            is Timestamp.RelativeFractionBeat -> {
                val duration = (ts.numerator.toDouble() / ts.denominator) * msPerBeat
                currentMs + duration.roundToLong()
            }

            is Timestamp.Continuation -> currentMs
        }
    }
}

// ==========================================
// 3. 简易音频播放器 (JLayer Wrapper)
// ==========================================
class AudioPlayer(private val file: File) {
    private var player: Player? = null

    fun start() {
        try {
            val fis = FileInputStream(file)
            player = Player(fis)
            Thread {
                try { player?.play() } catch (e: Exception) { e.printStackTrace() }
            }.start()
        } catch (e: Exception) {
            System.err.println("Error playing audio: ${e.message}")
        }
    }

    fun stop() {
        try { player?.close() } catch (e: Exception) {}
    }
}

// ==========================================
// 4. 主程序入口 (Entry Point)
// ==========================================
class CliPlayer : CliktCommand(name = "cliplayer") {

    override fun help(context: Context) = """
        CLIPlayer 2.0 (Kotlin Edition)
        A terminal-based kinetic typography player.
    """.trimIndent()

    private val scriptFile by argument(help = "The .clip script file")
        .file(mustExist = true, canBeDir = false, mustBeReadable = true)

    private val musicFile by option("-m", "--music", help = "Path to background music (mp3)")
        .file(mustExist = true, canBeDir = false, mustBeReadable = true)

    private val t = Terminal()

    override fun run() {
        t.println(gray("Parsing script: ${scriptFile.name}..."))
        val parser = ScriptParser()
        val rawElements = try {
            parser.parse(scriptFile.readLines())
        } catch (e: Exception) {
            t.println(red("Parse Error: ${e.message}"))
            return
        }

        t.println(gray("Resolving timeline..."))
        val resolver = TimelineResolver()
        val actions = resolver.resolve(rawElements)

        if (actions.isEmpty()) {
            t.println(yellow("Warning: Script is empty."))
            return
        }

        val totalDuration = actions.last().timeMs
        t.println(green("Ready! Total events: ${actions.size}, Duration: ${totalDuration}ms"))
        t.println(white("Press ENTER to start..."))
        readlnOrNull()

        val audioPlayer = musicFile?.let { AudioPlayer(it) }

        t.cursor.hide(showOnExit = true)

        runBlocking {
            t.cursor.move { setPosition(0, 0) } // 使用 setPosition 归位
            t.rawPrint("\u001b[2J")

            val startTime = System.currentTimeMillis()
            audioPlayer?.start()

            var actionIndex = 0
            val totalActions = actions.size

            while (actionIndex < totalActions) {
                val now = System.currentTimeMillis() - startTime
                val nextAction = actions[actionIndex]

                if (now >= nextAction.timeMs) {
                    executeCommand(nextAction.command)
                    actionIndex++
                    continue
                }

                val waitTime = nextAction.timeMs - now
                if (waitTime > 10) {
                    delay(waitTime - 5)
                }
            }

            delay(1000)
        }

        audioPlayer?.stop()
        t.cursor.show()
        t.println(gray("\nPlayback finished."))
    }

    private fun executeCommand(cmd: Command) {
        when (cmd) {
            is Command.PrintText -> t.rawPrint(cmd.text)

            is Command.PrintSpace -> t.rawPrint(" ".repeat(cmd.count))

            is Command.NewLine -> t.println()

            is Command.ClearScreen -> {
                t.cursor.move { setPosition(1, 1) } // 修正：ANSI 通常是 1-based，设置光标到左上角
                t.rawPrint("\u001b[2J")
            }
            is Command.ClearScreenNoReset -> {
                t.rawPrint("\u001b[2J")
            }

            is Command.MoveAbsolute -> t.cursor.move {
                // 修正：使用 setPosition 替代 to()
                // 注意：Mordant 的 setPosition 通常对应 ANSI 的 CUP 指令。
                // 如果出现错位，请确认是否需要 -1 (视 Mordant 版本是否做 0-based 处理)
                setPosition(cmd.y, cmd.x)
            }

            is Command.MoveRelative -> t.cursor.move {
                if (cmd.dy > 0) right(cmd.dy) else if (cmd.dy < 0) left(-cmd.dy)
                if (cmd.dx > 0) down(cmd.dx) else if (cmd.dx < 0) up(-cmd.dx)
            }

            is Command.SetColor -> t.rawPrint("\u001b[38;2;${cmd.r};${cmd.g};${cmd.b}m")
            is Command.ClearColor -> t.rawPrint("\u001b[39m")

            is Command.SetBackground -> t.rawPrint("\u001b[48;2;${cmd.r};${cmd.g};${cmd.b}m")
            is Command.ClearBackground -> t.rawPrint("\u001b[49m")

            is Command.SetStyle -> {
                if (cmd.bold) t.rawPrint("\u001b[1m")
                if (cmd.italic) t.rawPrint("\u001b[3m")
                if (cmd.underline) t.rawPrint("\u001b[4m")
                if (cmd.strikethrough) t.rawPrint("\u001b[9m")
            }
            is Command.ClearStyle -> t.rawPrint("\u001b[0m")

            else -> {}
        }
    }
}

fun main(args: Array<String>) = CliPlayer().main(args)