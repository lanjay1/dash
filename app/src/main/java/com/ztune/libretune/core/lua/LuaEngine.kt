package com.ztune.libretune.core.lua

import kotlin.math.*

// ── Token types ──────────────────────────────────────────────────────────────

private enum class TokenType {
    NUMBER, STRING, IDENT, PLUS, MINUS, STAR, SLASH, PERCENT, CARET,
    EQ, NEQ, LT, GT, LEQ, GEQ, ASSIGN, LPAREN, RPAREN, COMMA, DOT, DOTDOT,
    IF, ELSEIF, ELSE, END, WHILE, DO, FOR, THEN, LOCAL, NIL, TRUE, FALSE,
    NOT, AND, OR, RETURN, EOF
}

private data class Token(val type: TokenType, val value: String, val line: Int)

// ── Lexer ────────────────────────────────────────────────────────────────────

private class Lexer(private val src: String) {
    private var pos = 0
    private var line = 1

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (pos < src.length) {
            val c = src[pos]
            when {
                c.isWhitespace() -> { if (c == '\n') line++; pos++ }
                c == '-' && pos + 1 < src.length && src[pos + 1] == '-' -> skipComment()
                c == '"' || c == '\'' -> tokens.add(readString())
                c.isDigit() || (c == '.' && pos + 1 < src.length && src[pos + 1].isDigit()) ->
                    tokens.add(readNumber())
                c.isLetter() || c == '_' -> tokens.add(readIdent())
                else -> tokens.add(readSymbol(c))
            }
        }
        tokens.add(Token(TokenType.EOF, "", line))
        return tokens
    }

    private fun skipComment() {
        pos += 2
        if (pos < src.length && src[pos] == '[' && pos + 1 < src.length && src[pos + 1] == '[') {
            val close = src.indexOf("]]", pos + 2)
            if (close >= 0) { countNewlines(src, pos + 2, close); pos = close + 2 }
            else { pos = src.length }
        } else {
            while (pos < src.length && src[pos] != '\n') pos++
        }
    }

    private fun countNewlines(s: String, from: Int, to: Int) {
        for (i in from until to) if (s[i] == '\n') line++
    }

    private fun readString(): Token {
        val quote = src[pos]; pos++
        val sb = StringBuilder()
        while (pos < src.length && src[pos] != quote) {
            if (src[pos] == '\\' && pos + 1 < src.length) {
                pos++
                sb.append(when (src[pos]) {
                    'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '\\' -> '\\'
                    '"' -> '"'; '\'' -> '\''; else -> src[pos]
                })
            } else {
                if (src[pos] == '\n') line++
                sb.append(src[pos])
            }
            pos++
        }
        if (pos < src.length) pos++ // skip closing quote
        return Token(TokenType.STRING, sb.toString(), line)
    }

    private fun readNumber(): Token {
        val start = pos
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
        return Token(TokenType.NUMBER, src.substring(start, pos), line)
    }

    private fun readIdent(): Token {
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
        val word = src.substring(start, pos)
        val type = when (word) {
            "if" -> TokenType.IF; "elseif" -> TokenType.ELSEIF; "else" -> TokenType.ELSE
            "end" -> TokenType.END; "while" -> TokenType.WHILE; "do" -> TokenType.DO
            "for" -> TokenType.FOR; "then" -> TokenType.THEN; "local" -> TokenType.LOCAL
            "nil" -> TokenType.NIL; "true" -> TokenType.TRUE; "false" -> TokenType.FALSE
            "not" -> TokenType.NOT; "and" -> TokenType.AND; "or" -> TokenType.OR
            "return" -> TokenType.RETURN
            else -> TokenType.IDENT
        }
        return Token(type, word, line)
    }

    private fun readSymbol(c: Char): Token {
        val two = if (pos + 1 < src.length) src.substring(pos, pos + 2) else ""
        val t = when (two) {
            "==" -> TokenType.EQ; "~=" -> TokenType.NEQ; "<=" -> TokenType.LEQ
            ">=" -> TokenType.GEQ; ".." -> TokenType.DOTDOT; else -> null
        }
        if (t != null) { pos += 2; return Token(t, two, line) }
        pos++
        val st = when (c) {
            '+' -> TokenType.PLUS; '-' -> TokenType.MINUS; '*' -> TokenType.STAR
            '/' -> TokenType.SLASH; '%' -> TokenType.PERCENT; '^' -> TokenType.CARET
            '<' -> TokenType.LT; '>' -> TokenType.GT; '=' -> TokenType.ASSIGN
            '(' -> TokenType.LPAREN; ')' -> TokenType.RPAREN; ',' -> TokenType.COMMA
            '.' -> TokenType.DOT
            else -> TokenType.EOF
        }
        return Token(st, c.toString(), line)
    }
}

// ── AST nodes ─────────────────────────────────────────────────────────────────

private sealed class Expr
private data class NumberLit(val value: Double) : Expr()
private data class StringLit(val value: String) : Expr()
private data class BoolLit(val value: Boolean) : Expr()
private object NilLit : Expr()
private data class Ident(val name: String) : Expr()
private data class BinOp(val op: String, val left: Expr, val right: Expr) : Expr()
private data class UnOp(val op: String, val operand: Expr) : Expr()
private data class CallExpr(val func: Expr, val args: List<Expr>) : Expr()
private data class IndexExpr(val table: Expr, val key: Expr) : Expr()

private sealed class Stmt
private data class AssignStmt(val target: String, val value: Expr) : Stmt()
private data class LocalStmt(val name: String, val value: Expr?) : Stmt()
private data class IfStmt(
    val branches: List<Pair<Expr, List<Stmt>>>, val elseBlock: List<Stmt>?
) : Stmt()
private data class WhileStmt(val condition: Expr, val body: List<Stmt>) : Stmt()
private data class ForStmt(
    val varName: String, val start: Expr, val stop: Expr, val step: Expr?, val body: List<Stmt>
) : Stmt()
private data class ExprStmt(val expr: Expr) : Stmt()
private data class ReturnStmt(val value: Expr?) : Stmt()

// ── Parser ───────────────────────────────────────────────────────────────────

private class Parser(private val tokens: List<Token>) {
    private var idx = 0
    fun cur() = tokens[idx]
    fun advance(): Token { val t = cur(); if (idx < tokens.size - 1) idx++; return t }
    fun expect(type: TokenType): Token {
        if (cur().type != type) throw LuaError("${type} expected, got ${cur().type}", cur().line)
        return advance()
    }
    fun match(vararg types: TokenType): Boolean {
        if (cur().type in types) { advance(); return true }; return false
    }

    fun parse(): List<Stmt> {
        val stmts = mutableListOf<Stmt>()
        while (cur().type != TokenType.EOF) stmts.add(parseStmt())
        return stmts
    }

    private fun parseStmt(): Stmt = when (cur().type) {
        TokenType.LOCAL -> { advance(); val n = expect(TokenType.IDENT).value;
            val v = if (match(TokenType.ASSIGN)) parseExpr() else null; LocalStmt(n, v) }
        TokenType.IF -> parseIf()
        TokenType.WHILE -> { advance(); val c = parseExpr(); expect(TokenType.DO);
            val b = parseBlock(); expect(TokenType.END); WhileStmt(c, b) }
        TokenType.FOR -> { advance(); val n = expect(TokenType.IDENT).value; expect(TokenType.ASSIGN);
            val s = parseExpr(); expect(TokenType.COMMA); val e = parseExpr();
            val step = if (match(TokenType.COMMA)) parseExpr() else null;
            expect(TokenType.DO); val b = parseBlock(); expect(TokenType.END); ForStmt(n, s, e, step, b) }
        TokenType.RETURN -> { advance(); val v = if (cur().type == TokenType.EOF || cur().type == TokenType.END) null else parseExpr()
            ReturnStmt(v) }
        else -> {
            val expr = parseExpr()
            if (cur().type == TokenType.ASSIGN && expr is Ident) {
                advance(); AssignStmt(expr.name, parseExpr())
            } else ExprStmt(expr)
        }
    }

    private fun parseIf(): IfStmt {
        expect(TokenType.IF); val branches = mutableListOf<Pair<Expr, List<Stmt>>>()
        val cond = parseExpr(); expect(TokenType.THEN); val body = parseBlock()
        branches.add(cond to body)
        while (match(TokenType.ELSEIF)) {
            val ec = parseExpr(); expect(TokenType.THEN); val eb = parseBlock()
            branches.add(ec to eb)
        }
        val elseBlock = if (match(TokenType.ELSE)) parseBlock() else null
        expect(TokenType.END)
        return IfStmt(branches, elseBlock)
    }

    private fun parseBlock(): List<Stmt> {
        val stmts = mutableListOf<Stmt>()
        while (cur().type !in listOf(TokenType.EOF, TokenType.END, TokenType.ELSE, TokenType.ELSEIF))
            stmts.add(parseStmt())
        return stmts
    }

    // Expression parsing with precedence climbing
    private fun parseExpr(): Expr = parseOr()
    private fun parseOr(): Expr {
        var left = parseAnd()
        while (cur().type == TokenType.OR) { advance(); left = BinOp("or", left, parseAnd()) }
        return left
    }
    private fun parseAnd(): Expr {
        var left = parseComp()
        while (cur().type == TokenType.AND) { advance(); left = BinOp("and", left, parseComp()) }
        return left
    }
    private fun parseComp(): Expr {
        var left = parseConcat()
        val ops = mapOf(TokenType.EQ to "==", TokenType.NEQ to "~=", TokenType.LT to "<",
            TokenType.GT to ">", TokenType.LEQ to "<=", TokenType.GEQ to ">=")
        while (cur().type in ops) { val op = ops[cur().type]!!; advance(); left = BinOp(op, left, parseConcat()) }
        return left
    }
    private fun parseConcat(): Expr {
        var left = parseAdd()
        while (cur().type == TokenType.DOTDOT) { advance(); left = BinOp("..", left, parseAdd()) }
        return left
    }
    private fun parseAdd(): Expr {
        var left = parseMul()
        while (cur().type in listOf(TokenType.PLUS, TokenType.MINUS)) {
            val op = if (cur().type == TokenType.PLUS) "+" else "-"; advance(); left = BinOp(op, left, parseMul()) }
        return left
    }
    private fun parseMul(): Expr {
        var left = parseUnary()
        while (cur().type in listOf(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
            val op = when (cur().type) { TokenType.STAR -> "*"; TokenType.SLASH -> "/"; else -> "%" }
            advance(); left = BinOp(op, left, parseUnary()) }
        return left
    }
    private fun parseUnary(): Expr {
        if (cur().type == TokenType.NOT) { advance(); return UnOp("not", parseUnary()) }
        if (cur().type == TokenType.MINUS) { advance(); return UnOp("-", parseUnary()) }
        return parsePower()
    }
    private fun parsePower(): Expr {
        var base = parsePostfix()
        if (cur().type == TokenType.CARET) { advance(); base = BinOp("^", base, parseUnary()) }
        return base
    }
    private fun parsePostfix(): Expr {
        var expr = parsePrimary()
        while (true) {
            when (cur().type) {
                TokenType.DOT -> { advance(); val key = StringLit(expect(TokenType.IDENT).value); expr = IndexExpr(expr, key) }
                TokenType.LPAREN -> {
                    advance(); val args = mutableListOf<Expr>()
                    if (cur().type != TokenType.RPAREN) {
                        args.add(parseExpr())
                        while (match(TokenType.COMMA)) args.add(parseExpr())
                    }
                    expect(TokenType.RPAREN); expr = CallExpr(expr, args)
                }
                else -> break
            }
        }
        return expr
    }
    private fun parsePrimary(): Expr = when (cur().type) {
        TokenType.NUMBER -> NumberLit(cur().value.toDouble()).also { advance() }
        TokenType.STRING -> StringLit(cur().value).also { advance() }
        TokenType.TRUE -> BoolLit(true).also { advance() }
        TokenType.FALSE -> BoolLit(false).also { advance() }
        TokenType.NIL -> NilLit.also { advance() }
        TokenType.IDENT -> Ident(cur().value).also { advance() }
        TokenType.LPAREN -> { advance(); val e = parseExpr(); expect(TokenType.RPAREN); e }
        else -> throw LuaError("Unexpected token: ${cur().value}", cur().line)
    }
}

// ── Interpreter ───────────────────────────────────────────────────────────────

/**
 * Thrown inside a Lua script when `return` is executed; caught by the
 * top-level [LuaEngine.execute] to terminate interpretation and yield the
 * returned value.
 */
private class ReturnSignal(val value: Any?) : Throwable()

private class Environment(private val parent: Environment? = null) {
    private val vars = mutableMapOf<String, Any?>()
    fun get(name: String): Any? =
        if (name in vars) vars[name] else parent?.get(name)
    fun set(name: String, value: Any?) {
        if (name in vars) vars[name] = value
        else parent?.let { if (it.has(name)) it.set(name, value) } ?: run { vars[name] = value }
    }
    fun define(name: String, value: Any? = null) { vars[name] = value }
    fun has(name: String): Boolean = name in vars || parent?.has(name) == true
    fun child() = Environment(this)
}

class LuaError(message: String, val line: Int) : Exception("[line $line] $message")

data class LuaResult(val output: List<String>, val error: String?)

/** Callback for ECU commands. Returns result string or null on failure. */
fun interface EcuCommandCallback { fun execute(command: String, args: List<Any?>): String? }
/** Provides channel values by name. Returns Double or null. */
fun interface ChannelProvider { fun getChannel(name: String): Double? }
/** Provides tune constant values by name. Returns Double or null. */
fun interface ConstantProvider { fun getConstant(name: String): Double? }

/** Top-level typealias for a Lua function value (a Kotlin lambda). */
internal typealias LuaFunction = (List<Any?>) -> Any?

class LuaEngine(
    private val channelProvider: ChannelProvider? = null,
    private val constantProvider: ConstantProvider? = null,
    private val ecuCallback: EcuCommandCallback? = null
) {
    private val output = mutableListOf<String>()

    fun execute(script: String): LuaResult {
        output.clear()
        return try {
            val tokens = Lexer(script).tokenize()
            val stmts = Parser(tokens).parse()
            val env = makeGlobalEnv()
            execBlock(stmts, env)
            LuaResult(output.toList(), null)
        } catch (e: ReturnSignal) {
            LuaResult(output.toList(), null)
        } catch (e: LuaError) {
            output.add("Error: ${e.message}")
            LuaResult(output.toList(), e.message)
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            output.add("Error: $msg")
            LuaResult(output.toList(), msg)
        }
    }

    private fun makeGlobalEnv(): Environment {
        val env = Environment()
        // print function
        env.define("print") { args: List<Any?> ->
            val line = args.joinToString("\t") { fmt(it) }
            output.add(line); null
        }
        // math library
        val math = mutableMapOf<String, Any>(
            "pi" to PI, "huge" to Double.MAX_VALUE, "maxinteger" to Long.MAX_VALUE.toDouble(),
            "mininteger" to Long.MIN_VALUE.toDouble()
        )
        math["sin"] = fn1 { sin(it) }; math["cos"] = fn1 { cos(it) }
        math["tan"] = fn1 { tan(it) }; math["sqrt"] = fn1 { sqrt(it) }
        math["abs"] = fn1 { kotlin.math.abs(it) }; math["floor"] = fn1 { floor(it) }
        math["ceil"] = fn1 { ceil(it) }; math["exp"] = fn1 { kotlin.math.exp(it) }
        math["log"] = fn1 { kotlin.math.ln(it) }; math["log10"] = fn1 { kotlin.math.log10(it) }
        math["pow"] = fn2 { a, b -> a.pow(b) }
        math["min"] = fn2 { a, b -> minOf(a, b) }
        math["max"] = fn2 { a, b -> maxOf(a, b) }
        math["fmod"] = fn2 { a, b -> a % b }
        math["atan2"] = fn2 { a, b -> atan2(a, b) }
        math["random"] = { _: List<Any?> -> Math.random() }
        math["randomseed"] = { _: List<Any?> -> Math.random(); null }
        math["deg"] = fn1 { Math.toDegrees(it) }
        math["rad"] = fn1 { Math.toRadians(it) }
        env.define("math", math)
        // string library
        val str = mutableMapOf<String, Any>()
        str["format"] = { args: List<Any?> ->
            if (args.isEmpty()) "" else formatString(args[0].toString(), args.drop(1))
        }
        str["len"] = fn1s { it.length.toDouble() }
        str["upper"] = fn1s { it.uppercase() }
        str["lower"] = fn1s { it.lowercase() }
        str["sub"] = { args: List<Any?> ->
            val s = args.getOrNull(0)?.toString() ?: ""
            val i = (args.getOrNull(1) as? Double)?.toInt()?.let { if (it < 0) s.length + it + 1 else it } ?: 1
            val j = (args.getOrNull(2) as? Double)?.toInt()?.let { if (it < 0) s.length + it + 1 else it } ?: -1
            val start = (i - 1).coerceIn(0, s.length)
            val end = (if (j < 0) s.length + j + 1 else j).coerceIn(start, s.length)
            s.substring(start, end)
        }
        str["rep"] = { args: List<Any?> ->
            val s = args.getOrNull(0)?.toString() ?: ""
            val n = (args.getOrNull(1) as? Double)?.toInt() ?: 0
            s.repeat(n.coerceAtLeast(0))
        }
        env.define("string", str)
        // tonumber / tostring
        env.define("tonumber") { args: List<Any?> ->
            args.getOrNull(0)?.toString()?.toDoubleOrNull()
        }
        env.define("tostring") { args: List<Any?> -> fmt(args.getOrNull(0)) }
        // type
        env.define("type") { args: List<Any?> ->
            when (args.getOrNull(0)) {
                null -> "nil"; is Double -> "number"; is String -> "string"
                is Boolean -> "boolean"; is Function<*>, is Map<*, *> -> "table"; else -> "userdata"
            }
        }
        // ecu command bridge
        if (ecuCallback != null) {
            env.define("ecu") { args: List<Any?> ->
                val cmd = args.getOrNull(0)?.toString() ?: return@define "Error: no command"
                ecuCallback.execute(cmd, args.drop(1))
            }
        }
        // channel access: channel("RPM") or channel.RPM
        if (channelProvider != null) {
            val chMap = mutableMapOf<String, Any>()
            env.define("channel") { args: List<Any?> ->
                val name = args.getOrNull(0)?.toString() ?: return@define null
                channelProvider.getChannel(name)
            }
            env.define("channels", chMap)
        }
        if (constantProvider != null) {
            env.define("constant") { args: List<Any?> ->
                val name = args.getOrNull(0)?.toString() ?: return@define null
                constantProvider.getConstant(name)
            }
        }
        return env
    }

    private fun fn1(body: (Double) -> Any?): (List<Any?>) -> Any? =
        { args -> body(args.getOrNull(0) as? Double ?: 0.0) }
    private fun fn2(body: (Double, Double) -> Any?): (List<Any?>) -> Any? =
        { args -> body(args.getOrNull(0) as? Double ?: 0.0, args.getOrNull(1) as? Double ?: 0.0) }
    private fun fn1s(body: (String) -> Any?): (List<Any?>) -> Any? =
        { args -> body(args.getOrNull(0)?.toString() ?: "") }

    private fun formatString(fmt: String, args: List<Any?>): String {
        val sb = StringBuilder(); var i = 0; var ai = 0
        while (i < fmt.length) {
            if (fmt[i] == '%' && i + 1 < fmt.length) {
                i++
                if (fmt[i] == '%') { sb.append('%'); i++; continue }
                val spec = StringBuilder(); spec.append('%')
                while (i < fmt.length && fmt[i] in "0123456789.-+") { spec.append(fmt[i]); i++ }
                if (i < fmt.length) {
                    val conv = fmt[i]; i++
                    val arg = args.getOrNull(ai++)
                    val formatted = when (conv) {
                        'd', 'i' -> spec.toString() + (arg as? Double)?.toLong()?.toString() ?: (arg?.toString() ?: "nil")
                        'f' -> spec.toString() + (arg as? Double)?.toString() ?: (arg?.toString() ?: "nil")
                        's' -> (arg?.toString() ?: "nil")
                        'x' -> spec.toString() + (arg as? Double)?.toLong()?.toString(16) ?: (arg?.toString() ?: "nil")
                        'X' -> spec.toString() + (arg as? Double)?.toLong()?.toString(16)?.uppercase() ?: (arg?.toString() ?: "nil")
                        'o' -> spec.toString() + (arg as? Double)?.toLong()?.toString(8) ?: (arg?.toString() ?: "nil")
                        'e' -> spec.toString() + String.format("%e", arg as? Double ?: 0.0)
                        'g' -> spec.toString() + String.format("%g", arg as? Double ?: 0.0)
                        else -> spec.toString() + conv
                    }
                    sb.append(formatted)
                }
            } else { sb.append(fmt[i]); i++ }
        }
        return sb.toString()
    }

    // ── Statement execution ──

    private fun execBlock(stmts: List<Stmt>, env: Environment) {
        for (s in stmts) exec(s, env)
    }

    private fun exec(s: Stmt, env: Environment) {
        when (s) {
            is LocalStmt -> env.define(s.name, s.value?.let { eval(it, env) })
            is AssignStmt -> {
                val v = eval(s.value, env)
                if (!env.has(s.target)) env.define(s.target, v) else env.set(s.target, v)
            }
            is IfStmt -> {
                for ((cond, body) in s.branches) {
                    if (isTruthy(eval(cond, env))) { execBlock(body, env.child()); return }
                }
                s.elseBlock?.let { execBlock(it, env.child()) }
            }
            is WhileStmt -> {
                var guard = 0
                while (isTruthy(eval(s.condition, env))) {
                    if (guard++ > 1_000_000) throw LuaError("While loop exceeded max iterations", 0)
                    execBlock(s.body, env.child())
                }
            }
            is ForStmt -> {
                val start = (eval(s.start, env) as? Double)?.toLong() ?: throw LuaError("for start must be number", 0)
                val stop = (eval(s.stop, env) as? Double)?.toLong() ?: throw LuaError("for stop must be number", 0)
                val step = (s.step?.let { eval(it, env) as? Double }?.toLong() ?: 1L)
                if (step == 0L) throw LuaError("for step cannot be zero", 0)
                var i = start
                val forEnv = env.child()
                while (if (step > 0) i <= stop else i >= stop) {
                    forEnv.define(s.varName, i.toDouble())
                    execBlock(s.body, forEnv)
                    i += step
                }
            }
            is ExprStmt -> eval(s.expr, env)
            is ReturnStmt -> throw ReturnSignal(s.value?.let { eval(it, env) })
        }
    }

    // ── Expression evaluation ──

    private fun eval(e: Expr, env: Environment): Any? = when (e) {
        is NumberLit -> e.value
        is StringLit -> e.value
        is BoolLit -> e.value
        is NilLit -> null
        is Ident -> env.get(e.name)
        is UnOp -> when (e.op) {
            "-" -> numVal(eval(e.operand, env), "unary -") { -it }
            "not" -> !isTruthy(eval(e.operand, env))
            else -> throw LuaError("Unknown unary op: ${e.op}", 0)
        }
        is BinOp -> evalBinOp(e, env)
        is CallExpr -> evalCall(e, env)
        is IndexExpr -> {
            val table = eval(e.table, env)
            val key = eval(e.key, env)
            when {
                table is Map<*, *> -> table[key]
                key is String && table is Function<*> -> null // invalid
                else -> null
            }
        }
    }

    private fun evalBinOp(e: BinOp, env: Environment): Any? {
        // short-circuit
        if (e.op == "and") return if (!isTruthy(eval(e.left, env))) eval(e.left, env) else eval(e.right, env)
        if (e.op == "or") return if (isTruthy(eval(e.left, env))) eval(e.left, env) else eval(e.right, env)
        val l = eval(e.left, env); val r = eval(e.right, env)
        return when (e.op) {
            "+" -> numBin(l, r, "+") { a, b -> a + b }
            "-" -> numBin(l, r, "-") { a, b -> a - b }
            "*" -> numBin(l, r, "*") { a, b -> a * b }
            "/" -> numBin(l, r, "/") { a, b -> if (b == 0.0) throw LuaError("Division by zero", 0) else a / b }
            "%" -> numBin(l, r, "%") { a, b -> a % b }
            "^" -> numBin(l, r, "^") { a, b -> a.pow(b) }
            ".." -> (l?.toString() ?: "nil") + (r?.toString() ?: "nil")
            "==" -> luaEq(l, r)
            "~=" -> !luaEq(l, r)
            "<" -> cmpBin(l, r, "<") { a, b -> a < b }
            ">" -> cmpBin(l, r, ">") { a, b -> a > b }
            "<=" -> cmpBin(l, r, "<=") { a, b -> a <= b }
            ">=" -> cmpBin(l, r, ">=") { a, b -> a >= b }
            else -> throw LuaError("Unknown binary op: ${e.op}", 0)
        }
    }

    private fun numVal(v: Any?, ctx: String, op: (Double) -> Number): Double {
        return (v as? Double) ?: throw LuaError("$ctx: expected number, got ${v?.let { it::class.simpleName } ?: "nil"}", 0)
    }

    private fun numBin(l: Any?, r: Any?, op: String, fn: (Double, Double) -> Double): Double {
        val a = l as? Double ?: throw LuaError("$op: left must be number", 0)
        val b = r as? Double ?: throw LuaError("$op: right must be number", 0)
        return fn(a, b)
    }

    private fun cmpBin(l: Any?, r: Any?, op: String, fn: (Double, Double) -> Boolean): Boolean {
        val a = l as? Double ?: throw LuaError("$op: left must be number", 0)
        val b = r as? Double ?: throw LuaError("$op: right must be number", 0)
        return fn(a, b)
    }

    private fun luaEq(a: Any?, b: Any?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        a is Double && b is Double -> a == b
        a is String && b is String -> a == b
        a is Boolean && b is Boolean -> a == b
        else -> a === b
    }

    private fun evalCall(e: CallExpr, env: Environment): Any? {
        val func = eval(e.func, env)
        val args = e.args.map { eval(it, env) }
        return when (func) {
            is Function<*> -> {
                @Suppress("UNCHECKED_CAST")
                (func as (List<Any?>) -> Any?)(args)
            }
            is Map<*, *> -> {
                val fn = func["__call"]
                @Suppress("UNCHECKED_CAST")
                (fn as? ((List<Any?>) -> Any?))?.invoke(args)
                    ?: throw LuaError("Cannot call table (no __call metamethod)", 0)
            }
            else -> throw LuaError(
                "Attempt to call a ${if (func == null) "nil value" else func::class.simpleName}", 0
            )
        }
    }

    private fun isTruthy(v: Any?): Boolean = v != null && v != false

    private fun fmt(v: Any?): String = when (v) {
        null -> "nil"
        is Double -> if (v == v.toLong().toDouble() && v != Double.POSITIVE_INFINITY && v != Double.NEGATIVE_INFINITY)
            v.toLong().toString() else v.toString()
        else -> v.toString()
    }
}
