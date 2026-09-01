package com.ztune.libretune.core.ini.types

/**
 * Describes the shape (dimensionality) of a value in the INI.
 * Mirrors LibreTune's Rust `Shape` enum.
 */
sealed class Shape {
    /** A single scalar value. */
    data object Scalar : Shape()

    /** A one-dimensional array of [size] elements. */
    data class Array1D(val size: Int) : Shape()

    /** A two-dimensional (row-major) array of [rows] x [cols] elements. */
    data class Array2D(val rows: Int, val cols: Int) : Shape()
}