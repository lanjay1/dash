package com.ztune.libretune.core.ini.expression

/**
 * AST node for parsed expressions.
 *
 * This models the abstract syntax tree produced by [ExpressionParser.Parser.parse]
 * and consumed by [ExpressionParser.evaluate].  Every node represents a single
 * sub-expression that can be recursively evaluated.
 */
sealed class Expr {

    /** Numeric literal (integer or floating-point, always stored as Double). */
    data class Number(val value: Double) : Expr()

    /** Variable / identifier reference.  Resolved at evaluation time via the
     *  `variables` map passed to [ExpressionParser.evaluate]. */
    data class Var(val name: String) : Expr()

    /** Binary operation: `left op right`. */
    data class Binary(
        val op: BinaryOp,
        val left: Expr,
        val right: Expr,
    ) : Expr()

    /** Unary operation: `op operand`. */
    data class Unary(
        val op: UnaryOp,
        val operand: Expr,
    ) : Expr()

    /** Ternary conditional: `condition ? trueExpr : falseExpr`. */
    data class Ternary(
        val condition: Expr,
        val trueExpr: Expr,
        val falseExpr: Expr,
    ) : Expr()

    /** Function call: `name(args...)`. */
    data class FuncCall(
        val name: String,
        val args: List<Expr>,
    ) : Expr()

    /** Special `bitStringValue(value, units, options)` function.
     *  The actual bit-string resolution happens at INI parse time;
     *  the evaluator returns 0.0 as a placeholder. */
    data class BitStringValue(
        val valueExpr: Expr,
        val unitsExpr: Expr,
        val optionsExpr: Expr,
    ) : Expr()
}

/** Operators that appear in a binary expression.  Ordering matches the
 *  precedence table used by the parser (low → high). */
enum class BinaryOp {
    // Ternary is not here – it is a separate AST node.
    // Logical
    OR,            // ||
    AND,           // &&
    // Bitwise
    BIT_OR,        // |
    BIT_XOR,       // ^
    BIT_AND,       // &
    // Equality
    EQ,            // ==
    NEQ,           // !=
    // Comparison
    LT,            // <
    GT,            // >
    LTE,           // <=
    GTE,           // >=
    // Shift
    SHIFT_LEFT,    // <<
    SHIFT_RIGHT,   // >>
    // Additive
    ADD,           // +
    SUB,           // -
    // Multiplicative
    MUL,           // *
    DIV,           // /
    MOD,           // %
    // Power
    POW,           // **
}

/** Operators that appear in a unary expression. */
enum class UnaryOp {
    NEG,      // - (unary minus)
    NOT,      // ! (logical not)
    BIT_NOT,  // ~ (bitwise complement)
}
