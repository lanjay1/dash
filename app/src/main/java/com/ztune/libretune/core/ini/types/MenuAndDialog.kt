package com.ztune.libretune.core.ini.types

/** Kinds of widgets that can appear inside a dialog. */
enum class DialogComponentType { FIELD, SPINNER, PANEL, SEPARATOR, TAB }

/** A single widget inside a dialog definition.
 *  Matches LibreTune's Rust `DialogComponent`.
 */
data class DialogComponent(
    val type: DialogComponentType = DialogComponentType.FIELD,
    val label: String = "",
    val name: String = "",
    val visibilityCondition: String? = null,
    val enabledCondition: String? = null,
    val panelName: String? = null,
    val tabName: String? = null,
    val children: List<DialogComponent> = emptyList()
)

/** A named dialog (form) containing a list of components.
 *  Matches LibreTune's Rust `DialogDefinition`.
 */
data class DialogDefinition(
    val name: String = "",
    val title: String = "",
    val components: List<DialogComponent> = emptyList(),
    val layoutHint: String? = null
)

/** A named setting group that groups related constants.
 *  Matches LibreTune's Rust `SettingGroup`.
 */
data class SettingGroup(
    val name: String = "",
    val title: String = "",
    val constants: List<String> = emptyList()
)

/** A menu item in the Tune menu bar / tree.
 *  Matches LibreTune's Rust `Menu` struct.
 *
 *  Note: `condition` is a regular `val` (data classes cannot have `const val` members).
 */
data class Menu(
    val label: String = "",
    val command: String = "",
    val subMenu: List<Menu> = emptyList(),
    val dialogName: String? = null,
    val tableName: String? = null,
    val helpTopic: String? = null,
    val condition: String? = null
)