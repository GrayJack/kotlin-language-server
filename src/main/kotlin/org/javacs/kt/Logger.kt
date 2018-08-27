package org.javacs.kt

import java.io.PrintWriter
import java.io.StringWriter
import java.util.*
import java.util.logging.Formatter
import java.util.logging.LogRecord
import java.util.logging.Handler
import java.util.logging.Level
import java.time.Instant
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import org.javacs.kt.util.DelegatePrintStream

val LOG = Logger()

private class JULRedirector(private val downstream: Logger): Handler() {
    override fun publish(record: LogRecord) {
        when (record.level) {
            Level.SEVERE -> downstream.error(record.message)
            Level.WARNING -> downstream.warn(record.message)
            Level.INFO -> downstream.info(record.message)
            Level.CONFIG -> downstream.debug(record.message)
            Level.FINE -> downstream.trace(record.message)
            else -> downstream.deepTrace(record.message)
        }
    }
    
    override fun flush() {}
    
    override fun close() {}
}

enum class LogLevel(val level: Int) {
    NONE(100),
    ERROR(2),
    WARN(1),
    INFO(0),
    DEBUG(-1),
    TRACE(-2),
    DEEP_TRACE(-3),
    ALL(-100)
}

class LogMessage(
    val level: LogLevel,
    val message: String
)

class Logger {
    private var outBackend: ((LogMessage) -> Unit)? = null
    private var errBackend: ((LogMessage) -> Unit)? = null
    private val outQueue: Queue<LogMessage> = ArrayDeque()
    private val errQueue: Queue<LogMessage> = ArrayDeque()
    private val errStream = DelegatePrintStream { outputError(LogMessage(LogLevel.ERROR, it)) }
    
    private val newline = System.lineSeparator()
    val logTime = false
    var level = LogLevel.INFO
    
    private fun outputError(msg: LogMessage) {
        if (errBackend == null) {
            errQueue.offer(msg)
        } else {
            errBackend?.invoke(msg)
        }
    }
    
    private fun output(msg: LogMessage) {
        if (outBackend == null) {
            outQueue.offer(msg)
        } else {
            outBackend?.invoke(msg)
        }
    }
    
    private fun log(msgLevel: LogLevel, msg: String, placeholders: Array<out Any>) {
        if (level <= msgLevel) {
            output(LogMessage(msgLevel, format(insertPlaceholders(msg, placeholders))))
        }
    }
    
    fun error(throwable: Throwable) = throwable.printStackTrace(errStream)
    
    fun error(msg: String, vararg placeholders: Any) = log(LogLevel.ERROR, msg, placeholders)
    
    fun warn(msg: String, vararg placeholders: Any) = log(LogLevel.WARN, msg, placeholders)
    
    fun info(msg: String, vararg placeholders: Any) = log(LogLevel.INFO, msg, placeholders)
    
    fun debug(msg: String, vararg placeholders: Any) = log(LogLevel.DEBUG, msg, placeholders)
    
    fun trace(msg: String, vararg placeholders: Any) = log(LogLevel.TRACE, msg, placeholders)
    
    fun deepTrace(msg: String, vararg placeholders: Any) = log(LogLevel.DEEP_TRACE, msg, placeholders)
    
    fun connectJULFrontend() {
        val rootLogger = java.util.logging.Logger.getLogger("")
        rootLogger.addHandler(JULRedirector(this))
    }
    
    fun connectOutputBackend(outBackend: (LogMessage) -> Unit) {
        this.outBackend = outBackend
        flushOutQueue()
    }
    
    fun connectErrorBackend(errBackend: (LogMessage) -> Unit) {
        this.errBackend = errBackend
        flushErrQueue()
    }
    
    private fun insertPlaceholders(msg: String, placeholders: Array<out Any>): String {
        val msgLength = msg.length
        val lastIndex = msgLength - 1
        var charIndex = 0
        var placeholderIndex = 0
        var result = ""
        
        while (charIndex < msgLength) {
            val currentChar = msg.get(charIndex)
            val nextChar = if (charIndex != lastIndex) msg.get(charIndex + 1) else '?'
            if ((currentChar == '{') && (nextChar == '}')) {
                if (placeholderIndex >= placeholders.size) {
                    return "ERROR: Tried to log more '{}' placeholders than there are values"
                }
                result += placeholders[placeholderIndex]
                placeholderIndex += 1
                charIndex += 2
            } else {
                result += currentChar
                charIndex += 1
            }
        }
        
        return result
    }
    
    private fun flushOutQueue() {
        while (outQueue.isNotEmpty()) {
            outBackend?.invoke(outQueue.poll())
        }
    }
    
    private fun flushErrQueue() {
        while (errQueue.isNotEmpty()) {
            errBackend?.invoke(errQueue.poll())
        }
    }
    
    private fun format(message: String): String {
        val now = Instant.now()
        val time = if (logTime) "$now " else ""

        var thread = Thread.currentThread().name
        val prefix = time

        return multiLineFormat(
            2, // padding between columns
            FormatValue(prefix),
            FormatValue(shortenOrPad(thread, 10)),
            FormatValue(message)
        )
    }

    data class FormatValue(val str: String, val charsPerLine: Int = str.length)

    private fun multiLineFormat(padding: Int, vararg values: FormatValue): String {
        val splittedValues = values.map { createLineBreaks(it.str, it.charsPerLine) }
        return mergeSplittedLines(splittedValues, padding)
    }

    private fun mergeSplittedLines(splittedValues: List<List<String>>, padding: Int): String {
        var charOffset = 0
        val lines = mutableListOf<String>()
        for (splittedValue in splittedValues) {
            var lineIndex = 0
            var maxOffset = 0
            for (valueLine in splittedValue) {
                while (lineIndex >= lines.size) lines.add("")

                lines[lineIndex] = lines[lineIndex].padEnd(charOffset, ' ') + valueLine

                maxOffset = Math.max(maxOffset, valueLine.length)
                lineIndex++
            }
            charOffset += maxOffset + padding
        }
        return lines.reduce { prev, current -> prev + newline + current } + newline
    }

    private fun createLineBreaks(str: String, maxLength: Int): List<String> {
        var current = ""
        var lines = mutableListOf<String>()
        var i = maxLength
        for (character in str) {
            val isNewline = character == '\n'
            if (i == 0 || isNewline) {
                lines.add(current.trim())
                current = ""
                i = maxLength
            }
            if (!isNewline) {
                current += character
            }
            i--
        }
        if (current.length > 0) lines.add(current.trim().padEnd(maxLength, ' '))
        return lines
    }

    private fun shortenOrPad(str: String, length: Int): String =
            if (str.length <= length) {
                str.padEnd(length, ' ')
            } else {
                ".." + str.substring(str.length - length + 2)
            }
}