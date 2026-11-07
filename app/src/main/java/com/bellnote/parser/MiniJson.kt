package com.bellnote.parser

/**
 * 极简 JSON 解析器（只解析，不生成）。
 * 自己实现是为了不依赖 Android 的 org.json，这样解析逻辑能在 JVM 单测里跑。
 */
object MiniJson {

    sealed class Value
    class Text(val text: String) : Value()
    class Digit(val number: Double) : Value()
    class Flag(val flag: Boolean) : Value()
    class Items(val items: List<Value>) : Value()
    class Fields(val fields: Map<String, Value>) : Value()
    object Nil : Value()

    fun parse(raw: String): Value? {
        val start = raw.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        return try {
            Parser(raw, start).parseValue()
        } catch (e: Exception) {
            null
        }
    }

    /** 取一层对象的扁平标量字段；数组/对象字段跳过。 */
    fun flatScalars(value: Value?): Map<String, String> {
        val fields = (value as? Fields)?.fields ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((key, item) in fields) {
            val text = when (item) {
                is Text -> item.text
                is Digit -> formatNumber(item.number)
                is Flag -> item.flag.toString()
                else -> null
            }
            if (text != null) out[key] = text
        }
        return out
    }

    private fun formatNumber(number: Double): String =
        if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

    private class Parser(private val source: String, private var index: Int) {

        fun parseValue(): Value? {
            skipWhitespace()
            if (index >= source.length) return null
            return when (source[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()?.let { Text(it) }
                't' -> {
                    expect("true"); Flag(true)
                }
                'f' -> {
                    expect("false"); Flag(false)
                }
                'n' -> {
                    expect("null"); Nil
                }
                else -> parseNumber()
            }
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        private fun expect(word: String) {
            if (!source.startsWith(word, index)) throw IllegalStateException("非法字面量")
            index += word.length
        }

        private fun parseObject(): Value? {
            index++ // {
            val map = LinkedHashMap<String, Value>()
            skipWhitespace()
            if (index < source.length && source[index] == '}') {
                index++
                return Fields(map)
            }
            while (index < source.length) {
                skipWhitespace()
                val key = parseString() ?: return null
                skipWhitespace()
                if (index >= source.length || source[index] != ':') return null
                index++
                val value = parseValue() ?: return null
                map[key] = value
                skipWhitespace()
                if (index >= source.length) return null
                when (source[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return Fields(map)
                    }
                    else -> return null
                }
            }
            return Fields(map)
        }

        private fun parseArray(): Value? {
            index++ // [
            val list = ArrayList<Value>()
            skipWhitespace()
            if (index < source.length && source[index] == ']') {
                index++
                return Items(list)
            }
            while (index < source.length) {
                val value = parseValue() ?: return null
                list.add(value)
                skipWhitespace()
                if (index >= source.length) return null
                when (source[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return Items(list)
                    }
                    else -> return null
                }
            }
            return Items(list)
        }

        private fun parseString(): String? {
            if (index >= source.length || source[index] != '"') return null
            index++
            val builder = StringBuilder()
            while (index < source.length) {
                val c = source[index]
                when (c) {
                    '"' -> {
                        index++
                        return builder.toString()
                    }
                    '\\' -> {
                        index++
                        if (index >= source.length) return null
                        when (val escaped = source[index]) {
                            '"', '\\', '/' -> builder.append(escaped)
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 >= source.length) return null
                                val code = source.substring(index + 1, index + 5).toIntOrNull(16) ?: return null
                                builder.append(code.toChar())
                                index += 4
                            }
                            else -> builder.append(escaped)
                        }
                        index++
                    }
                    else -> {
                        builder.append(c)
                        index++
                    }
                }
            }
            return null
        }

        private fun parseNumber(): Value? {
            val start = index
            while (index < source.length &&
                (source[index].isDigit() || source[index] == '-' || source[index] == '+' ||
                    source[index] == '.' || source[index] == 'e' || source[index] == 'E')
            ) {
                index++
            }
            if (start == index) return null
            val number = source.substring(start, index).toDoubleOrNull() ?: return null
            return Digit(number)
        }
    }
}
