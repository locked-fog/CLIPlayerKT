package com.lockedfog.clip

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.terminal.Terminal
import com.lockedfog.clip.engine.ScriptEngine
import com.lockedfog.clip.parser.ScriptParser
import kotlinx.coroutines.runBlocking

class CliPlayer : CliktCommand(name = "cliplayer") {

    override fun help(context: Context) = """
        CLIPlayer 2.0 (Kotlin Edition - Coroutine Supported)
        A terminal-based kinetic typography player with concurrent execution support.
    """.trimIndent()

    private val scriptFile by argument(help = "The .clip script file")
        .file(mustExist = true, canBeDir = false, mustBeReadable = true)

    private val musicFile by option("-m", "--music", help = "Path to background music (mp3)")
        .file(mustExist = true, canBeDir = false, mustBeReadable = true)

    // 初始化终端
    private val t = Terminal()

    override fun run() {
        t.println(gray("Parsing script: ${scriptFile.name}..."))

        val parser = ScriptParser()
        val elements = try {
            parser.parse(scriptFile.readLines())
        } catch (e: Exception) {
            t.println(red("Parse Error: ${e.message}"))
            // 如果是调试模式可以 e.printStackTrace()
            return
        }

        if (elements.isEmpty()) {
            t.println(yellow("Warning: Script is empty."))
            return
        }

        t.println(gray("Initializing engine..."))

        // 构建引擎实例
        val engine = ScriptEngine(t, elements)

        // 启动主逻辑 (进入协程世界)
        runBlocking {
            try {
                engine.run(musicFile)
            } catch (e: Exception) {
                // 运行时异常捕获 (如协程内部错误)
                t.println(red("\nRuntime Error: ${e.message}"))
                e.printStackTrace()
            }
        }
    }
}

fun main(args: Array<String>) = CliPlayer().main(args)