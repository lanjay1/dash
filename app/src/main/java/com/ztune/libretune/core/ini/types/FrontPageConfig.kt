package com.ztune.libretune.core.ini.types

/** Layout description for the Tuning / front page.
 *  Matches LibreTune's Rust `FrontPageConfig`.
 */
data class FrontPageConfig(
    val gauges: List<String> = emptyList(),
    val indicators: List<String> = emptyList(),
    val readouts: List<String> = emptyList()
)
