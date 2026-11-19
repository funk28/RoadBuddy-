package com.roadbuddy.core.network

/**
 * A minimal JSON reader/writer, enough for the detection endpoints.
 *
 * It keeps the network module free of extra dependencies and runs in plain JVM
 * unit tests (Android's org.json is stubbed out there). Objects decode to
 * [Map], arrays to [List], numbers to [Double], and `null` to Kotlin `null`.
 * Swap it for kotlinx.serialization once more endpoints are added.
 */
internal object Json {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) throw reader.error("Unexpected trailing content")
        return value
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value)
            is Double -> {
                require(value.isFinite()) { "JSON cannot encode $value" }
                out.append(value)
            }
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) out.append(',')
                    writeString(out, key as String)
                    out.append(':')
                    write(out, item)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(out, item)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("Cannot encode ${value::class}")
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    private class Reader(private val text: String) {
        private var pos = 0

        fun atEnd() = pos >= text.length

        fun error(message: String) = IllegalArgumentException("$message at offset $pos")

        fun skipWhitespace() {
            while (!atEnd() && text[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw error("Unexpected end of JSON")
            return when (val c = text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw error("Unexpected '$c'")
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) throw error("Expected $word")
            pos += word.length
            return value
        }

        private fun readNumber(): Double {
            val start = pos
            while (!atEnd() && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
            return text.substring(start, pos).toDoubleOrNull() ?: throw error("Bad number")
        }

        private fun readString(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw error("Unterminated string")
                val c = text[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) throw error("Unterminated escape")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw error("Bad unicode escape")
                                out.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw error("Bad escape '\\$e'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // [
            val items = mutableListOf<Any?>()
            skipWhitespace()
            if (!atEnd() && text[pos] == ']') {
                pos++
                return items
            }
            while (true) {
                items += readValue()
                skipWhitespace()
                if (atEnd()) throw error("Unterminated array")
                when (text[pos++]) {
                    ',' -> continue
                    ']' -> return items
                    else -> throw error("Expected ',' or ']'")
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // {
            val members = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return members
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[pos] != '"') throw error("Expected a key")
                val key = readString()
                skipWhitespace()
                if (atEnd() || text[pos++] != ':') throw error("Expected ':'")
                members[key] = readValue()
                skipWhitespace()
                if (atEnd()) throw error("Unterminated object")
                when (text[pos++]) {
                    ',' -> continue
                    '}' -> return members
                    else -> throw error("Expected ',' or '}'")
                }
            }
        }
    }
}
