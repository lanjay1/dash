package com.ztune.libretune.core.ini.parser

/**
 * Token types produced by [IniTokenizer].
 *
 * Each type corresponds to a syntactic construct in the INI file format.
 * The [IniParser] consumes these tokens to build an [EcuDefinition].
 */
enum class TokenType {
    /** Section header: `[SectionName]` */
    SECTION,
    /** Key-value pair: `key = value` */
    KEY_VALUE,
    /** Preprocessor: `#if SYMBOL` */
    DIRECTIVE_IF,
    /** Preprocessor: `#else` */
    DIRECTIVE_ELSE,
    /** Preprocessor: `#endif` */
    DIRECTIVE_ENDIF,
    /** Preprocessor: `#define NAME value1 value2 ...` */
    DIRECTIVE_DEFINE,
    /** Preprocessor: `#include filename` */
    DIRECTIVE_INCLUDE,
    /** Comment line: `; ...` or `// ...` at start of line */
    COMMENT,
    /** Blank / whitespace-only line */
    BLANK,
    /** Continuation of previous line (previous line ended with `,`) */
    CONTINUATION
}

/**
 * A single token produced by the INI tokenizer.
 *
 * @property type    The token category.
 * @property content The full trimmed line text.
 * @property key     For [TokenType.KEY_VALUE]: the key (left side of `=`).
 * @property value   For [TokenType.KEY_VALUE]: the value (right side of `=`).
 * @property lineNumber 1-based line number in the source file.
 */
data class IniToken(
    val type: TokenType,
    val content: String,
    val key: String = "",
    val value: String = "",
    val lineNumber: Int = 0
)

/**
 * Line-by-line tokenizer for TunerStudio / MegaTune INI files.
 *
 * Handles:
 * - Section headers: `[SectionName]`
 * - Key-value pairs: `key = value`
 * - Comments: `;` and `//` at the *start* of a line
 * - Preprocessor directives: `#if`, `#else`, `#endif`, `#define`, `#include`
 * - Continuation lines (previous line ended with `,` outside of braces/quotes)
 * - Expression values in braces `{…}` are kept as-is (no inner tokenization)
 * - Dollar references `$variableName` are kept as-is
 *
 * The tokenizer does **not** interpret the contents — that is the parser's job.
 */
class IniTokenizer {

    /**
     * Tokenize the full [content] of an INI file.
     *
     * Continuation lines (where the previous non-blank, non-comment line
     * ended with a `,` outside of braces/quotes) are merged into the
     * previous token's [IniToken.content] and emitted as
     * [TokenType.CONTINUATION] tokens that carry the joined text.
     *
     * @return A list of [IniToken] in source order.
     */
    fun tokenize(content: String): List<IniToken> {
        val lines = content.lineSequence().toList()
        val tokens = mutableListOf<IniToken>()
        var i = 0

        while (i < lines.size) {
            val rawLine = lines[i]
            val lineNumber = i + 1   // 1-based
            val trimmed = rawLine.trim()

            // ---- Blank lines ----
            if (trimmed.isEmpty()) {
                tokens.add(IniToken(TokenType.BLANK, trimmed, lineNumber = lineNumber))
                i++
                continue
            }

            // ---- Comments: ; or // at the start of the trimmed line ----
            if (trimmed.startsWith(";") || trimmed.startsWith("//")) {
                tokens.add(IniToken(TokenType.COMMENT, trimmed, lineNumber = lineNumber))
                i++
                continue
            }

            // ---- Preprocessor directives ----
            if (trimmed.startsWith("#")) {
                val directiveToken = tokenizeDirective(trimmed, lineNumber)
                if (directiveToken != null) {
                    tokens.add(directiveToken)
                    i++
                    continue
                }
                // Unknown # directive — treat as comment
                tokens.add(IniToken(TokenType.COMMENT, trimmed, lineNumber = lineNumber))
                i++
                continue
            }

            // ---- Section headers: [SectionName] ----
            if (trimmed.startsWith("[")) {
                tokens.add(IniToken(TokenType.SECTION, trimmed, lineNumber = lineNumber))
                i++
                continue
            }

            // ---- Key-value pairs (may span multiple lines via continuation) ----
            val (token, nextIndex) = tokenizeKeyValue(lines, i)
            tokens.add(token)
            i = nextIndex
        }

        return tokens
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Tokenize a `#` directive line.
     */
    private fun tokenizeDirective(trimmed: String, lineNumber: Int): IniToken? {
        // Strip the leading #
        val body = trimmed.substring(1).trim()

        val lowerBody = body.lowercase()
        return when {
            lowerBody.startsWith("if ") || lowerBody.startsWith("ifdef ") -> {
                val symbol = body.substringAfter(' ').trim()
                // Handle compound conditions like "#if CELSIUS && STM32"
                // For now, take the whole condition string
                IniToken(TokenType.DIRECTIVE_IF, trimmed, key = "if", value = symbol, lineNumber = lineNumber)
            }
            lowerBody == "else" || lowerBody.startsWith("else ") -> {
                IniToken(TokenType.DIRECTIVE_ELSE, trimmed, lineNumber = lineNumber)
            }
            lowerBody.startsWith("endif") -> {
                IniToken(TokenType.DIRECTIVE_ENDIF, trimmed, lineNumber = lineNumber)
            }
            lowerBody.startsWith("define ") -> {
                val defineBody = body.substringAfter(' ').trim()
                val spaceIdx = defineBody.indexOfFirst { it.isWhitespace() }
                val name = if (spaceIdx >= 0) defineBody.substring(0, spaceIdx) else defineBody
                val params = if (spaceIdx >= 0) defineBody.substring(spaceIdx).trim() else ""
                IniToken(TokenType.DIRECTIVE_DEFINE, trimmed, key = name, value = params, lineNumber = lineNumber)
            }
            lowerBody.startsWith("include ") -> {
                val filename = body.substringAfter(' ').trim().removeSurrounding("\"", "\"")
                IniToken(TokenType.DIRECTIVE_INCLUDE, trimmed, key = "include", value = filename, lineNumber = lineNumber)
            }
            else -> null
        }
    }

    /**
     * Tokenize a key-value pair, consuming continuation lines.
     *
     * A continuation line is one where the previous *meaningful* line
     * ends with a `,` that is outside of any braces or quotes.
     *
     * Returns the token and the index of the next line to process.
     */
    private fun tokenizeKeyValue(lines: List<String>, startIndex: Int): Pair<IniToken, Int> {
        val lineNumber = startIndex + 1
        var currentLine = lines[startIndex].trim()

        // Merge continuation lines
        var idx = startIndex + 1
        while (idx < lines.size) {
            val nextTrimmed = lines[idx].trim()
            if (nextTrimmed.isEmpty() || nextTrimmed.startsWith(";") ||
                nextTrimmed.startsWith("//") || nextTrimmed.startsWith("[") ||
                nextTrimmed.startsWith("#")
            ) {
                break
            }
            if (endsWithContinuationComma(currentLine)) {
                currentLine = "$currentLine $nextTrimmed"
                idx++
            } else {
                break
            }
        }

        // Split key = value
        val (key, value) = splitKeyValue(currentLine)

        val token = if (idx > startIndex + 1) {
            // There were continuation lines
            IniToken(TokenType.CONTINUATION, currentLine, key = key, value = value, lineNumber = lineNumber)
        } else {
            IniToken(TokenType.KEY_VALUE, currentLine, key = key, value = value, lineNumber = lineNumber)
        }

        return Pair(token, idx)
    }

    /**
     * Check whether a line ends with a `,` that is not inside braces or quotes,
     * indicating the next line is a continuation.
     */
    private fun endsWithContinuationComma(line: String): Boolean {
        var braceDepth = 0
        var inQuote = false
        var quoteChar = '\u0000'
        var lastNonWhitespace = -1
        var lastCommaPos = -1
        var commaInBraces = false
        var commaInQuote = false

        for (i in line.indices) {
            val ch = line[i]
            when {
                inQuote -> {
                    if (ch == quoteChar) inQuote = false
                }
                ch == '"' || ch == '\'' -> {
                    inQuote = true
                    quoteChar = ch
                }
                ch == '{' -> braceDepth++
                ch == '}' -> braceDepth--
            }
            if (!ch.isWhitespace()) {
                lastNonWhitespace = i
            }
            if (ch == ',') {
                lastCommaPos = i
                commaInBraces = braceDepth != 0
                commaInQuote = inQuote
            }
        }

        // The last non-whitespace character must be a comma,
        // and that comma must be outside braces and quotes.
        return lastNonWhitespace == lastCommaPos && !commaInBraces && !commaInQuote
    }

    /**
     * Split a line into (key, value) on the first `=` that is not inside braces
     * or quotes.  This is a simplified version used only by the tokenizer;
     * [FieldParser.splitKeyValue] provides the full implementation.
     */
    private fun splitKeyValue(line: String): Pair<String, String> {
        var braceDepth = 0
        var inQuote = false
        var quoteChar = '\u0000'

        for (i in line.indices) {
            val ch = line[i]
            when {
                inQuote -> {
                    if (ch == quoteChar) inQuote = false
                }
                ch == '"' || ch == '\'' -> {
                    inQuote = true
                    quoteChar = ch
                }
                ch == '{' -> braceDepth++
                ch == '}' -> braceDepth--
                ch == '=' && braceDepth == 0 -> {
                    return Pair(
                        line.substring(0, i).trim(),
                        line.substring(i + 1).trim()
                    )
                }
            }
        }
        return Pair(line.trim(), "")
    }
}
