package com.ztune.libretune.core.ini.expression

// ---------------------------------------------------------------------------
// Lightweight Result type (mirrors Rust's Result<T, E> for this module)
// ---------------------------------------------------------------------------

/**
 * A discriminated union that is either [Ok] carrying a value of type [T],
 * or [Err] carrying a [String] error message.
 *
 * This avoids shadowing `kotlin.Result` while providing the two-type-parameter
 * ergonomics the expression module needs.
 */
sealed class ExprResult<out T> {
    /** Success variant carrying [value]. */
    data class Ok<out T>(val value: T) : ExprResult<T>()

    /** Failure variant carrying an error [message]. */
    data class Err(val message: String) : ExprResult<Nothing>()

    /** True when this is [Ok]. */
    val isSuccess: Boolean get() = this is Ok
    /** True when this is [Err]. */
    val isFailure: Boolean get() = this is Err

    /** Extract the value, or null if this is [Err]. */
    fun getOrNull(): T? = when (this) {
        is Ok -> value
        is Err -> null
    }

    /** Extract the value, or [default] if this is [Err]. */
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Ok -> value
        is Err -> default
    }

    /** Transform the success value; errors pass through unchanged. */
    inline fun <R> map(transform: (T) -> R): ExprResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    /** Transform with a function that may itself fail; short-circuits on error. */
    inline fun <R> flatMap(transform: (T) -> ExprResult<R>): ExprResult<R> = when (this) {
        is Ok -> transform(value)
        is Err -> this
    }

    companion object {
        /** Construct an [Ok] variant. */
        fun <T> ok(value: T): ExprResult<T> = Ok(value)
        /** Construct an [Err] variant. */
        fun err(message: String): ExprResult<Nothing> = Err(message)
    }
}

// ---------------------------------------------------------------------------
// Convenience constructors
// ---------------------------------------------------------------------------

/** Wrap a value in [ExprResult.Ok]. */
fun <T> ok(value: T): ExprResult<T> = ExprResult.Ok(value)

/** Wrap an error message in [ExprResult.Err]. */
fun err(message: String): ExprResult<Nothing> = ExprResult.Err(message)

// ---------------------------------------------------------------------------
// Tokens
// ---------------------------------------------------------------------------

/** Lexical token kinds produced by the [Parser] tokenizer. */
private enum class TokenKind {
    // Literals / identifiers
    NUMBER,
    IDENTIFIER,
    // Arithmetic
    PLUS,       // +
    MINUS,      // -
    STAR,       // *
    SLASH,      // /
    PERCENT,    // %
    STAR_STAR,  // **
    // Comparison
    EQ,         // ==
    NEQ,        // !=
    LT,         // <
    GT,         // >
    LTE,        // <=
    GTE,        // >=
    // Logical
    AND,        // &&
    OR,         // ||
    NOT,        // !
    // Bitwise
    BIT_AND,    // &
    BIT_OR,     // |
    BIT_XOR,    // ^
    BIT_NOT,    // ~
    SHIFT_LEFT, // <<
    SHIFT_RIGHT,// >>
    // Delimiters
    LPAREN,     // (
    RPAREN,     // )
    COMMA,      // ,
    QUESTION,   // ?
    COLON,      // :
    // End
    EOF,
}

/** A single lexical token with its [kind], original [text], and [pos] in the input. */
private data class Token(val kind: TokenKind, val text: String, val pos: Int)

// ---------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------

/**
 * Recursive-descent parser for INI expression strings.
 *
 * Supported syntax (low → high precedence):
 *   ternary   `a ? b : c`
 *   logical   `||`, `&&`
 *   bitwise   `|`, `^`, `&`, `<<`, `>>`
 *   equality  `==`, `!=`
 *   compare   `<`, `>`, `<=`, `>=`
 *   additive  `+`, `-`
 *   mult      `*`, `/`, `%`
 *   power     `**` (right-associative)
 *   unary     `-`, `!`, `~`
 *   primary   numbers, identifiers, function calls, `(expr)`
 *
 * Also handles C-style comments (`//`, `/* */`) and hex literals (`0x…`).
 */
class Parser(private val input: String) {

    /* ------------------------------------------------------------------ */
    /* Tokenizer                                                          */
    /* ------------------------------------------------------------------ */

    private val tokens: MutableList<Token> = mutableListOf()
    private var lexPos = 0

    init {
        tokenize()
    }

    /** Current token index consumed by the parser. */
    private var pos = 0

    // ---- low-level lexer helpers ----------------------------------------

    private fun peekChar(): Char = input.getOrElse(lexPos) { '\u0000' }
    private fun peekChar(offset: Int): Char = input.getOrElse(lexPos + offset) { '\u0000' }
    private fun advanceChar(): Char {
        val ch = peekChar()
        if (lexPos < input.length) lexPos++
        return ch
    }

    private fun isDigit(ch: Char): Boolean = ch in '0'..'9'
    private fun isHexDigit(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
    private fun isIdentStart(ch: Char): Boolean =
        ch == '_' || ch in 'a'..'z' || ch in 'A'..'Z'
    private fun isIdentContinue(ch: Char): Boolean =
        isIdentStart(ch) || isDigit(ch)

    private fun skipWhitespaceAndComments() {
        while (lexPos < input.length) {
            val ch = peekChar()
            // whitespace
            if (ch.isWhitespace()) {
                lexPos++
                continue
            }
            // single-line comment
            if (ch == '/' && peekChar(1) == '/') {
                lexPos += 2
                while (lexPos < input.length && peekChar() != '\n') lexPos++
                continue
            }
            // block comment
            if (ch == '/' && peekChar(1) == '*') {
                lexPos += 2
                while (lexPos < input.length) {
                    if (peekChar() == '*' && peekChar(1) == '/') {
                        lexPos += 2
                        break
                    }
                    lexPos++
                }
                continue
            }
            break
        }
    }

    private fun tokenize() {
        while (true) {
            skipWhitespaceAndComments()
            if (lexPos >= input.length) {
                tokens.add(Token(TokenKind.EOF, "", lexPos))
                break
            }
            val startPos = lexPos
            val ch = peekChar()

            // ---- Hex number 0x... (must be checked before plain numbers) --
            if (ch == '0' && (peekChar(1) == 'x' || peekChar(1) == 'X')) {
                lexHexNumber(startPos)
                continue
            }

            // ---- Numbers ------------------------------------------------
            if (isDigit(ch) || (ch == '.' && isDigit(peekChar(1)))) {
                lexNumber(startPos)
                continue
            }

            // ---- Identifiers --------------------------------------------
            if (isIdentStart(ch)) {
                lexIdentifier(startPos)
                continue
            }

            // ---- Two-character operators (check first) ------------------
            val two = if (lexPos + 1 < input.length) input.substring(lexPos, lexPos + 2) else ""
            val twoKind = when (two) {
                "**" -> TokenKind.STAR_STAR
                "==" -> TokenKind.EQ
                "!=" -> TokenKind.NEQ
                "<=" -> TokenKind.LTE
                ">=" -> TokenKind.GTE
                "&&" -> TokenKind.AND
                "||" -> TokenKind.OR
                "<<" -> TokenKind.SHIFT_LEFT
                ">>" -> TokenKind.SHIFT_RIGHT
                else -> null
            }
            if (twoKind != null) {
                tokens.add(Token(twoKind, two, startPos))
                lexPos += 2
                continue
            }

            // ---- Single-character operators / delimiters -----------------
            val oneKind = when (ch) {
                '+' -> TokenKind.PLUS
                '-' -> TokenKind.MINUS
                '*' -> TokenKind.STAR
                '/' -> TokenKind.SLASH
                '%' -> TokenKind.PERCENT
                '<' -> TokenKind.LT
                '>' -> TokenKind.GT
                '!' -> TokenKind.NOT
                '&' -> TokenKind.BIT_AND
                '|' -> TokenKind.BIT_OR
                '^' -> TokenKind.BIT_XOR
                '~' -> TokenKind.BIT_NOT
                '(' -> TokenKind.LPAREN
                ')' -> TokenKind.RPAREN
                ',' -> TokenKind.COMMA
                '?' -> TokenKind.QUESTION
                ':' -> TokenKind.COLON
                else -> null
            }
            if (oneKind != null) {
                tokens.add(Token(oneKind, ch.toString(), startPos))
                lexPos++
                continue
            }

            // ---- Unknown character --------------------------------------
            tokens.add(Token(TokenKind.EOF, "", lexPos))
            break
        }
    }

    private fun lexNumber(startPos: Int) {
        val sb = StringBuilder()
        while (lexPos < input.length && isDigit(peekChar())) {
            sb.append(advanceChar())
        }
        // fractional part
        if (lexPos < input.length && peekChar() == '.') {
            sb.append(advanceChar()) // consume '.'
            while (lexPos < input.length && isDigit(peekChar())) {
                sb.append(advanceChar())
            }
        }
        // exponent part
        if (lexPos < input.length && (peekChar() == 'e' || peekChar() == 'E')) {
            sb.append(advanceChar()) // consume 'e'/'E'
            if (lexPos < input.length && (peekChar() == '+' || peekChar() == '-')) {
                sb.append(advanceChar())
            }
            while (lexPos < input.length && isDigit(peekChar())) {
                sb.append(advanceChar())
            }
        }
        tokens.add(Token(TokenKind.NUMBER, sb.toString(), startPos))
    }

    private fun lexHexNumber(startPos: Int) {
        val sb = StringBuilder()
        sb.append(advanceChar()) // '0'
        sb.append(advanceChar()) // 'x' / 'X'
        while (lexPos < input.length && isHexDigit(peekChar())) {
            sb.append(advanceChar())
        }
        tokens.add(Token(TokenKind.NUMBER, sb.toString(), startPos))
    }

    private fun lexIdentifier(startPos: Int) {
        val sb = StringBuilder()
        while (lexPos < input.length && isIdentContinue(peekChar())) {
            sb.append(advanceChar())
        }
        tokens.add(Token(TokenKind.IDENTIFIER, sb.toString(), startPos))
    }

    // ---- parser helpers --------------------------------------------------

    private fun current(): Token = tokens.getOrElse(pos) { tokens.last() }
    private fun currentKind(): TokenKind = current().kind

    private fun advance(): Token {
        val tok = current()
        if (pos < tokens.size - 1) pos++
        return tok
    }

    private fun expect(kind: TokenKind): ExprResult<Token> {
        val tok = current()
        return if (tok.kind == kind) {
            advance()
            ok(tok)
        } else {
            err("Expected ${kind.name} but got '${tok.text}' at position ${tok.pos}")
        }
    }

    private fun unexpectedToken(context: String): ExprResult<Nothing> =
        err("Unexpected token '${current().text}' ($context) at position ${current().pos}")

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Parse the full input string into an [Expr] AST.
     * Returns [Err] with a descriptive message on syntax errors.
     */
    fun parse(): ExprResult<Expr> {
        if (tokens.size <= 1 && currentKind() == TokenKind.EOF) {
            return err("Empty expression")
        }
        val result = parseTernary()
        if (result.isFailure) return result
        if (currentKind() != TokenKind.EOF) {
            return err("Unexpected token '${current().text}' at position ${current().pos}")
        }
        return result
    }

    // ---------------------------------------------------------------------
    // Precedence levels (low → high)
    //
    // 14. ternary       ? :
    // 13. logical OR    ||
    // 12. logical AND   &&
    // 11. bitwise OR    |
    // 10. bitwise XOR   ^
    //  9. bitwise AND   &
    //  8. equality      == !=
    //  7. comparison    < > <= >=
    //  6. shift         << >>
    //  5. additive      + -
    //  4. multiplicative * / %
    //  3. power         **  (right-assoc)
    //  2. unary         - ! ~
    //  1. primary       numbers, idents, calls, parens
    // ---------------------------------------------------------------------

    // ---- 14. Ternary  (right-associative) --------------------------------
    private fun parseTernary(): ExprResult<Expr> {
        val condition = parseOr()
        if (condition.isFailure) return condition
        if (currentKind() != TokenKind.QUESTION) return condition
        advance() // consume '?'
        val trueExpr = parseTernary() // allow nested ternaries in true-branch
        if (trueExpr.isFailure) return trueExpr
        val colon = expect(TokenKind.COLON)
        if (colon.isFailure) return err((colon as ExprResult.Err).message)
        val falseExpr = parseTernary() // right-associative
        if (falseExpr.isFailure) return falseExpr
        return ok(Expr.Ternary(condition.getOrNull()!!, trueExpr.getOrNull()!!, falseExpr.getOrNull()!!))
    }

    // ---- 13. Logical OR  (left-associative) ------------------------------
    private fun parseOr(): ExprResult<Expr> {
        var left = parseAnd()
        if (left.isFailure) return left
        while (currentKind() == TokenKind.OR) {
            advance()
            val right = parseAnd()
            if (right.isFailure) return right
            left = ok(Expr.Binary(BinaryOp.OR, left.getOrNull()!!, right.getOrNull()!!))
        }
        return left
    }

    // ---- 12. Logical AND  (left-associative) -----------------------------
    private fun parseAnd(): ExprResult<Expr> {
        var left = parseBitOr()
        if (left.isFailure) return left
        while (currentKind() == TokenKind.AND) {
            advance()
            val right = parseBitOr()
            if (right.isFailure) return right
            left = ok(Expr.Binary(BinaryOp.AND, left.getOrNull()!!, right.getOrNull()!!))
        }
        return left
    }

    // ---- 11. Bitwise OR  (left-associative) ------------------------------
    private fun parseBitOr(): ExprResult<Expr> {
        var left = parseBitXor()
        if (left.isFailure) return left
        while (currentKind() == TokenKind.BIT_OR) {
            advance()
            val right = parseBitXor()
            if (right.isFailure) return right
            left = ok(Expr.Binary(BinaryOp.BIT_OR, left.getOrNull()!!, right.getOrNull()!!))
        }
        return left
    }

    // ---- 10. Bitwise XOR  (left-associative) -----------------------------
    private fun parseBitXor(): ExprResult<Expr> {
        var left = parseBitAnd()
        if (left.isFailure) return left
        while (currentKind() == TokenKind.BIT_XOR) {
            advance()
            val right = parseBitAnd()
            if (right.isFailure) return right
            left = ok(Expr.Binary(BinaryOp.BIT_XOR, left.getOrNull()!!, right.getOrNull()!!))
        }
        return left
    }

    // ----  9. Bitwise AND  (left-associative) -----------------------------
    private fun parseBitAnd(): ExprResult<Expr> {
        var left = parseEquality()
        if (left.isFailure) return left
        while (currentKind() == TokenKind.BIT_AND) {
            advance()
            val right = parseEquality()
            if (right.isFailure) return right
            left = ok(Expr.Binary(BinaryOp.BIT_AND, left.getOrNull()!!, right.getOrNull()!!))
        }
        return left
    }

    // ----  8. Equality  (left-associative) --------------------------------
    private fun parseEquality(): ExprResult<Expr> {
        var left = parseComparison()
        if (left.isFailure) return left
        while (true) {
            val op = when (currentKind()) {
                TokenKind.EQ -> BinaryOp.EQ
                TokenKind.NEQ -> BinaryOp.NEQ
                else -> break
            }
            advance()
            val right = parseComparison()
            if (right.isFailure) return right
            left = ok(Expr.Binary(op, left.getOrNull()!!, right.getOrNull()!!))
        }
        return left
    }

    // ----  7. Comparison  (left-associative, non-chaining) ----------------
    private fun parseComparison(): ExprResult<Expr> {
        val left = parseShift()
        if (left.isFailure) return left
        val op = when (currentKind()) {
            TokenKind.LT -> BinaryOp.LT
            TokenKind.GT -> BinaryOp.GT
            TokenKind.LTE -> BinaryOp.LTE
            TokenKind.GTE -> BinaryOp.GTE
            else -> return left
        }
        advance()
        val right = parseShift()
        if (right.isFailure) return right
        return ok(Expr.Binary(op, left.getOrNull()!!, right.getOrNull()!!))
    }

    // ----  6. Shift  (left-associative) -----------------------------------
    private fun parseShift(): ExprResult<Expr> {
        var left = parseAdditive()
        if (left.isFailure) return left
        while (true) {
            val op = when (currentKind()) {
                TokenKind.SHIFT_LEFT -> BinaryOp.SHIFT_LEFT
                TokenKind.SHIFT_RIGHT -> BinaryOp.SHIFT_RIGHT
                else -> break
            }
            advance()
            val right = parseAdditive()
            if (right.isFailure) return right
            left = ok(Expr.Binary(op, left.getOrNull()!!, right.getOrNull()!!))
        }
        return left
    }

    // ----  5. Additive  (left-associative) --------------------------------
    private fun parseAdditive(): ExprResult<Expr> {
        var left = parseMultiplicative()
        if (left.isFailure) return left
        while (true) {
            val op = when (currentKind()) {
                TokenKind.PLUS -> BinaryOp.ADD
                TokenKind.MINUS -> BinaryOp.SUB
                else -> break
            }
            advance()
            val right = parseMultiplicative()
            if (right.isFailure) return right
            left = ok(Expr.Binary(op, left.getOrNull()!!, right.getOrNull()!!))
        }
        return left
    }

    // ----  4. Multiplicative  (left-associative) --------------------------
    private fun parseMultiplicative(): ExprResult<Expr> {
        var left = parsePower()
        if (left.isFailure) return left
        while (true) {
            val op = when (currentKind()) {
                TokenKind.STAR -> BinaryOp.MUL
                TokenKind.SLASH -> BinaryOp.DIV
                TokenKind.PERCENT -> BinaryOp.MOD
                else -> break
            }
            advance()
            val right = parsePower()
            if (right.isFailure) return right
            left = ok(Expr.Binary(op, left.getOrNull()!!, right.getOrNull()!!))
        }
        return left
    }

    // ----  3. Power  (right-associative) ----------------------------------
    private fun parsePower(): ExprResult<Expr> {
        val base = parseUnary()
        if (base.isFailure) return base
        if (currentKind() == TokenKind.STAR_STAR) {
            advance()
            // Right-associative: recursively parse the exponent at the same level.
            val exponent = parsePower()
            if (exponent.isFailure) return exponent
            return ok(Expr.Binary(BinaryOp.POW, base.getOrNull()!!, exponent.getOrNull()!!))
        }
        return base
    }

    // ----  2. Unary  (prefix) ---------------------------------------------
    private fun parseUnary(): ExprResult<Expr> {
        return when (currentKind()) {
            TokenKind.MINUS -> {
                advance()
                val operand = parseUnary()
                if (operand.isFailure) return operand
                ok(Expr.Unary(UnaryOp.NEG, operand.getOrNull()!!))
            }
            TokenKind.NOT -> {
                advance()
                val operand = parseUnary()
                if (operand.isFailure) return operand
                ok(Expr.Unary(UnaryOp.NOT, operand.getOrNull()!!))
            }
            TokenKind.BIT_NOT -> {
                advance()
                val operand = parseUnary()
                if (operand.isFailure) return operand
                ok(Expr.Unary(UnaryOp.BIT_NOT, operand.getOrNull()!!))
            }
            else -> parsePrimary()
        }
    }

    // ----  1. Primary -----------------------------------------------------
    private fun parsePrimary(): ExprResult<Expr> {
        val tok = current()

        return when (tok.kind) {
            // ---- Numeric literal ----------------------------------------
            TokenKind.NUMBER -> {
                advance()
                val value = parseNumberLiteral(tok.text)
                if (value == null) {
                    err("Invalid number '${tok.text}' at position ${tok.pos}")
                } else {
                    ok(Expr.Number(value))
                }
            }

            // ---- Parenthesised sub-expression ----------------------------
            TokenKind.LPAREN -> {
                advance()
                val inner = parseTernary()
                if (inner.isFailure) return inner
                if (expect(TokenKind.RPAREN).isFailure) {
                    return err("Expected ')' at position ${current().pos}")
                }
                inner
            }

            // ---- Identifier or function call -----------------------------
            TokenKind.IDENTIFIER -> {
                advance()
                val name = tok.text

                // Check for function call: identifier followed by '('
                if (currentKind() == TokenKind.LPAREN) {
                    // Special case: bitStringValue(a, b, c)
                    if (name.equals("bitStringValue", ignoreCase = true)) {
                        return parseBitStringValueCall(tok.pos)
                    }
                    // Generic function call
                    return parseFuncCall(name, tok.pos)
                }

                ok(Expr.Var(name))
            }

            else -> unexpectedToken("expected number, identifier, or '('")
        }
    }

    /** Parse `bitStringValue(valueExpr, unitsExpr, optionsExpr)` into a [Expr.BitStringValue]. */
    private fun parseBitStringValueCall(namePos: Int): ExprResult<Expr> {
        if (expect(TokenKind.LPAREN).isFailure) {
            return err("Expected '(' after 'bitStringValue' at position ${current().pos}")
        }
        val valueExpr = parseTernary()
        if (valueExpr.isFailure) return valueExpr
        if (expect(TokenKind.COMMA).isFailure) {
            return err("Expected ',' in bitStringValue() at position ${current().pos}")
        }
        val unitsExpr = parseTernary()
        if (unitsExpr.isFailure) return unitsExpr
        if (expect(TokenKind.COMMA).isFailure) {
            return err("Expected ',' in bitStringValue() at position ${current().pos}")
        }
        val optionsExpr = parseTernary()
        if (optionsExpr.isFailure) return optionsExpr
        if (expect(TokenKind.RPAREN).isFailure) {
            return err("Expected ')' to close bitStringValue() at position ${current().pos}")
        }
        return ok(Expr.BitStringValue(
            valueExpr = valueExpr.getOrNull()!!,
            unitsExpr = unitsExpr.getOrNull()!!,
            optionsExpr = optionsExpr.getOrNull()!!,
        ))
    }

    /** Parse a generic function call `name(arg1, arg2, ...)`. */
    private fun parseFuncCall(name: String, namePos: Int): ExprResult<Expr> {
        if (expect(TokenKind.LPAREN).isFailure) {
            return err("Expected '(' after '$name' at position ${current().pos}")
        }
        val args = mutableListOf<Expr>()
        if (currentKind() != TokenKind.RPAREN) {
            // Parse first argument
            val first = parseTernary()
            if (first.isFailure) return first
            args.add(first.getOrNull()!!)
            // Parse remaining arguments
            while (currentKind() == TokenKind.COMMA) {
                advance()
                val next = parseTernary()
                if (next.isFailure) return next
                args.add(next.getOrNull()!!)
            }
        }
        if (expect(TokenKind.RPAREN).isFailure) {
            return err("Expected ')' to close function call '$name' at position ${current().pos}")
        }
        return ok(Expr.FuncCall(name, args))
    }

    /** Parse a numeric string (decimal, hex, float, scientific) into a [Double]. */
    private fun parseNumberLiteral(text: String): Double? {
        return if (text.startsWith("0x") || text.startsWith("0X")) {
            // Hex literal → Long → Double
            try {
                text.substring(2).toLong(16).toDouble()
            } catch (_: NumberFormatException) {
                null
            }
        } else {
            text.toDoubleOrNull()
        }
    }
}

// ---------------------------------------------------------------------------
// Function call handler
// ---------------------------------------------------------------------------

/** Handler for user-defined / extension functions called from expressions. */
typealias FunctionCallHandler = (List<Double>) -> ExprResult<Double>

// ---------------------------------------------------------------------------
// Evaluator
// ---------------------------------------------------------------------------

/**
 * Evaluate a parsed [Expr] AST to a [Double] result.
 *
 * @param expr       The AST to evaluate.
 * @param variables  Map of variable names → their current numeric values.
 * @param functions  Optional map of custom function name → handler.  Built-in
 *                   functions (`min`, `max`, `abs`, `sqrt`, `clamp`, `map`)
 *                   are always available and can be overridden.
 * @return [Ok] with the computed value, or [Err] with a descriptive message.
 */
fun evaluate(
    expr: Expr,
    variables: Map<String, Double>,
    functions: Map<String, FunctionCallHandler>? = null,
): ExprResult<Double> = EvalContext(variables, functions).eval(expr)

/** Internal evaluation context holding variable/function bindings. */
private class EvalContext(
    private val variables: Map<String, Double>,
    private val functions: Map<String, FunctionCallHandler>?,
) {
    fun eval(e: Expr): ExprResult<Double> = when (e) {
        is Expr.Number -> ok(e.value)

        is Expr.Var -> {
            val v = variables[e.name]
            if (v != null) ok(v)
            else err("Undefined variable '${e.name}'")
        }

        is Expr.Unary -> {
            val operand = eval(e.operand)
            if (operand.isFailure) return operand
            val v = operand.getOrNull()!!
            when (e.op) {
                UnaryOp.NEG -> ok(-v)
                UnaryOp.NOT -> ok(if (v != 0.0) 0.0 else 1.0)
                UnaryOp.BIT_NOT -> ok((v.toLong().inv()).toDouble())
            }
        }

        is Expr.Binary -> evalBinary(e)

        is Expr.Ternary -> {
            val cond = eval(e.condition)
            if (cond.isFailure) return cond
            if (cond.getOrNull()!! != 0.0) eval(e.trueExpr) else eval(e.falseExpr)
        }

        is Expr.FuncCall -> evalFunction(e.name, e.args)

        is Expr.BitStringValue -> {
            // BitStringValue resolution happens at INI parse time;
            // return 0.0 as a placeholder.
            ok(0.0)
        }
    }

    fun evalBinary(e: Expr.Binary): ExprResult<Double> {
        // Short-circuit for logical operators
        if (e.op == BinaryOp.OR) {
            val left = eval(e.left)
            if (left.isFailure) return left
            if (left.getOrNull()!! != 0.0) return ok(1.0)
            val right = eval(e.right)
            if (right.isFailure) return right
            return ok(if (right.getOrNull()!! != 0.0) 1.0 else 0.0)
        }
        if (e.op == BinaryOp.AND) {
            val left = eval(e.left)
            if (left.isFailure) return left
            if (left.getOrNull()!! == 0.0) return ok(0.0)
            val right = eval(e.right)
            if (right.isFailure) return right
            return ok(if (right.getOrNull()!! != 0.0) 1.0 else 0.0)
        }

        val left = eval(e.left)
        if (left.isFailure) return left
        val lv = left.getOrNull()!!
        val right = eval(e.right)
        if (right.isFailure) return right
        val rv = right.getOrNull()!!

        return when (e.op) {
            BinaryOp.ADD -> ok(lv + rv)
            BinaryOp.SUB -> ok(lv - rv)
            BinaryOp.MUL -> ok(lv * rv)
            BinaryOp.DIV -> {
                if (rv == 0.0) ok(0.0) else ok(lv / rv)
            }
            BinaryOp.MOD -> {
                if (rv == 0.0) ok(0.0) else ok(lv % rv)
            }
            BinaryOp.POW -> ok(Math.pow(lv, rv))
            // Comparison → 1.0 true, 0.0 false
            BinaryOp.EQ -> ok(if (lv == rv) 1.0 else 0.0)
            BinaryOp.NEQ -> ok(if (lv != rv) 1.0 else 0.0)
            BinaryOp.LT -> ok(if (lv < rv) 1.0 else 0.0)
            BinaryOp.GT -> ok(if (lv > rv) 1.0 else 0.0)
            BinaryOp.LTE -> ok(if (lv <= rv) 1.0 else 0.0)
            BinaryOp.GTE -> ok(if (lv >= rv) 1.0 else 0.0)
            // Bitwise
            BinaryOp.BIT_AND -> ok((lv.toLong() and rv.toLong()).toDouble())
            BinaryOp.BIT_OR -> ok((lv.toLong() or rv.toLong()).toDouble())
            BinaryOp.BIT_XOR -> ok((lv.toLong() xor rv.toLong()).toDouble())
            BinaryOp.SHIFT_LEFT -> ok((lv.toLong() shl rv.toInt()).toDouble())
            BinaryOp.SHIFT_RIGHT -> ok((lv.toLong() shr rv.toInt()).toDouble())
            // OR / AND are handled above with short-circuit; these branches
            // are unreachable but required for exhaustiveness.
            BinaryOp.OR -> ok(if (lv != 0.0 || rv != 0.0) 1.0 else 0.0)
            BinaryOp.AND -> ok(if (lv != 0.0 && rv != 0.0) 1.0 else 0.0)
        }
    }

    fun evalFunction(name: String, argExprs: List<Expr>): ExprResult<Double> {
        // Check user-provided functions first
        if (functions != null) {
            val handler = functions[name]
            if (handler != null) {
                val args = mutableListOf<Double>()
                for (ae in argExprs) {
                    val v = eval(ae)
                    if (v.isFailure) return v
                    args.add(v.getOrNull()!!)
                }
                return handler(args)
            }
        }

        // Built-in functions
        return when (name.lowercase()) {
            "min" -> {
                if (argExprs.size < 2) return err("min() requires at least 2 arguments")
                var result = Double.MAX_VALUE
                for (ae in argExprs) {
                    val v = eval(ae)
                    if (v.isFailure) return v
                    result = minOf(result, v.getOrNull()!!)
                }
                ok(result)
            }
            "max" -> {
                if (argExprs.size < 2) return err("max() requires at least 2 arguments")
                var result = -Double.MAX_VALUE
                for (ae in argExprs) {
                    val v = eval(ae)
                    if (v.isFailure) return v
                    result = maxOf(result, v.getOrNull()!!)
                }
                ok(result)
            }
            "abs" -> {
                if (argExprs.size != 1) return err("abs() requires exactly 1 argument")
                val v = eval(argExprs[0])
                if (v.isFailure) return v
                ok(kotlin.math.abs(v.getOrNull()!!))
            }
            "sqrt" -> {
                if (argExprs.size != 1) return err("sqrt() requires exactly 1 argument")
                val v = eval(argExprs[0])
                if (v.isFailure) return v
                ok(kotlin.math.sqrt(v.getOrNull()!!))
            }
            "clamp" -> {
                if (argExprs.size != 3) return err("clamp() requires exactly 3 arguments (x, lo, hi)")
                val x = eval(argExprs[0]); if (x.isFailure) return x
                val lo = eval(argExprs[1]); if (lo.isFailure) return lo
                val hi = eval(argExprs[2]); if (hi.isFailure) return hi
                ok(x.getOrNull()!!.coerceIn(lo.getOrNull()!!, hi.getOrNull()!!))
            }
            "map" -> {
                if (argExprs.size != 5) return err("map() requires exactly 5 arguments (x, inLo, inHi, outLo, outHi)")
                val x = eval(argExprs[0]); if (x.isFailure) return x
                val inLo = eval(argExprs[1]); if (inLo.isFailure) return inLo
                val inHi = eval(argExprs[2]); if (inHi.isFailure) return inHi
                val outLo = eval(argExprs[3]); if (outLo.isFailure) return outLo
                val outHi = eval(argExprs[4]); if (outHi.isFailure) return outHi
                val xv = x.getOrNull()!!
                val lo = inLo.getOrNull()!!
                val hi = inHi.getOrNull()!!
                val oLo = outLo.getOrNull()!!
                val oHi = outHi.getOrNull()!!
                val result = if (hi == lo) {
                    oLo // avoid division by zero
                } else {
                    oLo + (xv - lo) / (hi - lo) * (oHi - oLo)
                }
                ok(result)
            }
            else -> err("Unknown function '$name'")
        }
    }
}

// ---------------------------------------------------------------------------
// Identifier extraction
// ---------------------------------------------------------------------------

/**
 * Extract all variable identifiers referenced by [expr], in traversal order.
 * Function names are **not** included – only [Expr.Var] nodes.
 *
 * Used by `EcuDefinition.constantFeedsDynamicScale()` to discover which
 * variables an expression depends on.
 */
fun identifiers(expr: Expr): List<String> {
    val result = mutableListOf<String>()
    fun walk(e: Expr) {
        when (e) {
            is Expr.Number -> {} // nothing
            is Expr.Var -> result.add(e.name)
            is Expr.Binary -> { walk(e.left); walk(e.right) }
            is Expr.Unary -> walk(e.operand)
            is Expr.Ternary -> { walk(e.condition); walk(e.trueExpr); walk(e.falseExpr) }
            is Expr.FuncCall -> e.args.forEach { walk(it) }
            is Expr.BitStringValue -> { walk(e.valueExpr); walk(e.unitsExpr); walk(e.optionsExpr) }
        }
    }
    walk(expr)
    return result
}