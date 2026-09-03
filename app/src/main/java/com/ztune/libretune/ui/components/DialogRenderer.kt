package com.ztune.libretune.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.Constant
import com.ztune.libretune.core.ini.types.DialogComponent
import com.ztune.libretune.core.ini.types.DialogComponentType
import com.ztune.libretune.core.ini.types.DialogDefinition
import com.ztune.libretune.core.ini.types.IndicatorPanel
import com.ztune.libretune.core.ini.types.ReadoutPanel

@Composable
fun DialogRenderer(
    dialog: DialogDefinition,
    definition: EcuDefinition,
    constantValues: Map<String, Double>,
    onConstantChanged: (String, Double) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToTable: ((String) -> Unit)? = null,
    onNavigateToCurve: ((String) -> Unit)? = null
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = dialog.title.ifEmpty { dialog.name },
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        val tabGroups = partitionTabs(dialog.components)

        if (tabGroups.hasTabs) {
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabGroups.tabNames.forEachIndexed { index, name ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(name) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            val currentComponents = tabGroups.groups.getOrNull(selectedTabIndex) ?: emptyList()
            currentComponents.forEach { component ->
                DialogComponentRenderer(
                    component = component,
                    definition = definition,
                    constantValues = constantValues,
                    onConstantChanged = onConstantChanged,
                    onNavigateToTable = onNavigateToTable,
                    onNavigateToCurve = onNavigateToCurve
                )
            }
        } else {
            dialog.components.forEach { component ->
                DialogComponentRenderer(
                    component = component,
                    definition = definition,
                    constantValues = constantValues,
                    onConstantChanged = onConstantChanged,
                    onNavigateToTable = onNavigateToTable,
                    onNavigateToCurve = onNavigateToCurve
                )
            }
        }
    }
}

@Composable
private fun DialogComponentRenderer(
    component: DialogComponent,
    definition: EcuDefinition,
    constantValues: Map<String, Double>,
    onConstantChanged: (String, Double) -> Unit,
    onNavigateToTable: ((String) -> Unit)?,
    onNavigateToCurve: ((String) -> Unit)?
) {
    // Check visibility
    if (component.visibilityCondition != null) {
        val visible = evaluateSimpleCondition(component.visibilityCondition, constantValues)
        if (!visible) return
    }

    val enabled = if (component.enabledCondition != null) {
        evaluateSimpleCondition(component.enabledCondition, constantValues)
    } else {
        true
    }

    when (component.type) {
        DialogComponentType.FIELD -> {
            FieldEditor(
                label = component.label,
                constantName = component.name,
                constant = definition.constants[component.name],
                value = constantValues[component.name] ?: 0.0,
                enabled = enabled,
                onValueChange = { onConstantChanged(component.name, it) }
            )
        }

        DialogComponentType.SEPARATOR -> {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        }

        DialogComponentType.PANEL -> {
            val panelName = component.panelName ?: return
            // Try to resolve the panel by checking each known panel type
            if (definition.indicatorPanels.containsKey(panelName)) {
                IndicatorPanelRenderer(
                    panel = definition.indicatorPanels[panelName]!!,
                    constantValues = constantValues
                )
            } else if (definition.readoutPanels.containsKey(panelName)) {
                ReadoutPanelRenderer(
                    panel = definition.readoutPanels[panelName]!!,
                    constantValues = constantValues
                )
            } else if (definition.dialogs.containsKey(panelName)) {
                DialogRenderer(
                    dialog = definition.dialogs[panelName]!!,
                    definition = definition,
                    constantValues = constantValues,
                    onConstantChanged = onConstantChanged,
                    onNavigateToTable = onNavigateToTable,
                    onNavigateToCurve = onNavigateToCurve
                )
            } else {
                // Try as table or curve
                val tbl = definition.getTableByNameOrMap(panelName)
                if (tbl != null) {
                    TextButton(onClick = { onNavigateToTable?.invoke(panelName) }) {
                        Text("Open Table: ${tbl.name.ifEmpty { panelName }}")
                    }
                } else {
                    val crv = definition.getCurveByNameOrMap(panelName)
                    if (crv != null) {
                        TextButton(onClick = { onNavigateToCurve?.invoke(panelName) }) {
                            Text("Open Curve: ${crv.name.ifEmpty { panelName }}")
                        }
                    } else {
                        // Try standard panel synthesis
                        val stdDef = definition.stdPanelDefinition(panelName)
                        if (stdDef != null) {
                            DialogRenderer(
                                dialog = stdDef,
                                definition = definition,
                                constantValues = constantValues,
                                onConstantChanged = onConstantChanged,
                                onNavigateToTable = onNavigateToTable,
                                onNavigateToCurve = onNavigateToCurve
                            )
                        }
                    }
                }
            }
        }

        DialogComponentType.SPINNER -> {
            // Spinner renders as a dropdown for enum-like constants
            FieldEditor(
                label = component.label,
                constantName = component.name,
                constant = definition.constants[component.name],
                value = constantValues[component.name] ?: 0.0,
                enabled = enabled,
                onValueChange = { onConstantChanged(component.name, it) }
            )
        }

        DialogComponentType.TAB -> {
            // Tabs are handled at the dialog level by partitionTabs
        }
    }
}

@Composable
private fun FieldEditor(
    label: String,
    constantName: String,
    constant: Constant?,
    value: Double,
    enabled: Boolean,
    onValueChange: (Double) -> Unit
) {
    var editValue by remember(value) { mutableStateOf(value.toString()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label.ifEmpty { constantName },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = editValue,
            onValueChange = { newText ->
                editValue = newText
                newText.toDoubleOrNull()?.let { onValueChange(it) }
            },
            modifier = Modifier.width(120.dp),
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
fun IndicatorPanelRenderer(
    panel: IndicatorPanel,
    constantValues: Map<String, Double>,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = panel.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            panel.indicators.forEach { indicator ->
                val isOn = evaluateIndicatorCondition(indicator.channelName, constantValues)
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(12.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = if (isOn) Color.Green else Color.Red
                    ) {}
                    Text(
                        indicator.label.ifEmpty { indicator.channelName },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun ReadoutPanelRenderer(
    panel: ReadoutPanel,
    constantValues: Map<String, Double>,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = panel.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            panel.readouts.forEach { readout ->
                val displayValue = constantValues[readout.channelName] ?: 0.0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        readout.label.ifEmpty { readout.channelName },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = String.format("%.${readout.decimals}f %s", displayValue, readout.units),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

// --- Helpers ---

private data class TabPartitionResult(
    val hasTabs: Boolean,
    val tabNames: List<String>,
    val groups: List<List<DialogComponent>>
)

private fun partitionTabs(components: List<DialogComponent>): TabPartitionResult {
    val tabNames = mutableListOf<String>()
    val groups = mutableListOf<MutableList<DialogComponent>>()
    var currentGroup = mutableListOf<DialogComponent>()

    for (comp in components) {
        when (comp.type) {
            DialogComponentType.TAB -> {
                if (currentGroup.isNotEmpty()) {
                    groups.add(currentGroup)
                }
                tabNames.add(comp.tabName ?: "Tab ${tabNames.size + 1}")
                currentGroup = mutableListOf()
            }
            else -> currentGroup.add(comp)
        }
    }
    if (currentGroup.isNotEmpty()) {
        groups.add(currentGroup)
    }

    return TabPartitionResult(
        hasTabs = tabNames.isNotEmpty(),
        tabNames = tabNames,
        groups = groups
    )
}

private fun evaluateSimpleCondition(condition: String, values: Map<String, Double>): Boolean {
    return try {
        val trimmed = condition.trim()

        // Handle != operator
        val neqMatch = Regex("""(\w+)\s*!=\s*([0-9.]+)""").find(trimmed)
        if (neqMatch != null) {
            val val_ = values[neqMatch.groupValues[1]] ?: return false
            return val_ != neqMatch.groupValues[2].toDouble()
        }

        // Handle == or = operator
        val eqMatch = Regex("""(\w+)\s*(?:==|=)\s*([0-9.]+)""").find(trimmed)
        if (eqMatch != null) {
            val val_ = values[eqMatch.groupValues[1]] ?: return false
            return val_ == eqMatch.groupValues[2].toDouble()
        }

        // Handle >= operator
        val gteMatch = Regex("""(\w+)\s*>=\s*([0-9.]+)""").find(trimmed)
        if (gteMatch != null) {
            val val_ = values[gteMatch.groupValues[1]] ?: return false
            return val_ >= gteMatch.groupValues[2].toDouble()
        }

        // Handle <= operator
        val lteMatch = Regex("""(\w+)\s*<=\s*([0-9.]+)""").find(trimmed)
        if (lteMatch != null) {
            val val_ = values[lteMatch.groupValues[1]] ?: return false
            return val_ <= lteMatch.groupValues[2].toDouble()
        }

        // Handle > operator
        val gtMatch = Regex("""(\w+)\s*>\s*([0-9.]+)""").find(trimmed)
        if (gtMatch != null) {
            val val_ = values[gtMatch.groupValues[1]] ?: return false
            return val_ > gtMatch.groupValues[2].toDouble()
        }

        // Handle < operator
        val ltMatch = Regex("""(\w+)\s*<\s*([0-9.]+)""").find(trimmed)
        if (ltMatch != null) {
            val val_ = values[ltMatch.groupValues[1]] ?: return false
            return val_ < ltMatch.groupValues[2].toDouble()
        }

        // Default: try to evaluate as boolean (non-zero = true)
        val varVal = values[trimmed]
        varVal != null && varVal != 0.0
    } catch (_: Exception) {
        true // If condition can't be evaluated, show the component
    }
}

private fun evaluateIndicatorCondition(channelName: String, values: Map<String, Double>): Boolean {
    val val_ = values[channelName] ?: return false
    return val_ > 0.0
}