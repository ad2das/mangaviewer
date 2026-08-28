package ml.melun.mangaview.reader

/** Extracts one Kotlin/Java/C++ brace body without treating comments or literals as code. */
internal object SourceFunctionBody {
    fun extract(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val open = firstCodeBrace(source, start)
        require(open >= 0) { "Missing opening brace: $signature" }

        var depth = 0
        scanCode(source, open) { index, character ->
            when (character) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
            true
        }
        error("Missing closing brace: $signature")
    }

    private fun firstCodeBrace(source: String, start: Int): Int {
        var result = -1
        scanCode(source, start) { index, character ->
            if (character == '{') {
                result = index
                return@scanCode false
            }
            true
        }
        return result
    }

    private inline fun scanCode(
        source: String,
        start: Int,
        visit: (Int, Char) -> Boolean,
    ) {
        var index = start
        var state = State.CODE
        while (index < source.length) {
            val current = source[index]
            val previous = source.getOrNull(index - 1)
            val next = source.getOrNull(index + 1)
            when (state) {
                State.CODE -> when {
                    current == '/' && next == '/' -> {
                        state = State.LINE_COMMENT
                        index++
                    }
                    current == '/' && next == '*' -> {
                        state = State.BLOCK_COMMENT
                        index++
                    }
                    current == '"' && source.startsWith("\"\"\"", index) -> {
                        state = State.TRIPLE_STRING
                        index += 2
                    }
                    current == '"' -> state = State.STRING
                    current == '\'' &&
                        previous?.isLetterOrDigit() == true &&
                        next?.isLetterOrDigit() == true -> {
                        // C++14 digit separator (for example 1'000'000), not a character literal.
                        if (!visit(index, current)) return
                    }
                    current == '\'' -> state = State.CHAR
                    !visit(index, current) -> return
                }
                State.LINE_COMMENT -> if (current == '\n') state = State.CODE
                State.BLOCK_COMMENT -> if (current == '*' && next == '/') {
                    state = State.CODE
                    index++
                }
                State.STRING -> when {
                    current == '\\' -> index++
                    current == '"' -> state = State.CODE
                }
                State.CHAR -> when {
                    current == '\\' -> index++
                    current == '\'' -> state = State.CODE
                }
                State.TRIPLE_STRING -> if (source.startsWith("\"\"\"", index)) {
                    state = State.CODE
                    index += 2
                }
            }
            index++
        }
    }

    private enum class State {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHAR,
        TRIPLE_STRING,
    }
}
