package com.ztune.libretune.core.i18n

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.DecimalFormat

// ======================================================================
// Enums
// ======================================================================

enum class TemperatureUnit(val symbol: String) {
    CELSIUS("°C"), FAHRENHEIT("°F"), KELVIN("K");

    companion object {
        fun fromString(value: String): TemperatureUnit =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CELSIUS
    }
}

enum class PressureUnit(val symbol: String) {
    KPA("kPa"), PSI("PSI"), BAR("bar"), INHG("inHg");

    companion object {
        fun fromString(value: String): PressureUnit =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: KPA
    }
}

// ======================================================================
// Preferences data class
// ======================================================================

data class UnitPreferences(
    val temperature: TemperatureUnit = TemperatureUnit.CELSIUS,
    val pressure: PressureUnit = PressureUnit.KPA,
    val useLambda: Boolean = false
)

// ======================================================================
// Singleton DataStore-backed manager
// ======================================================================

/** DataStore delegate — one per application. */
private val Context.unitPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ztune_unit_preferences"
)

/**
 * Manages per-user unit preferences persisted via [androidx.datastore.DataStore].
 *
 * Exposes a hot [Flow] of [UnitPreferences] that the UI can collect.
 * Conversion helpers let any code path turn a raw ECU value into the
 * user's preferred unit without passing the whole preferences object.
 *
 * Usage:
 * ```
 * val prefs = UnitPreferencesStore.getInstance(context)
 * val current = prefs.flow.first() // or collect in Compose
 * val displayTemp = UnitPreferencesStore.convertTemperature(90.0, TemperatureUnit.CELSIUS, current.temperature)
 * ```
 */
class UnitPreferencesStore private constructor(private val dataStore: DataStore<Preferences>) {

    /** Hot flow of the current preferences — observe from UI via `collectAsState()`. */
    val flow: Flow<UnitPreferences> = dataStore.data.map { prefs ->
        UnitPreferences(
            temperature = TemperatureUnit.fromString(
                prefs[KEY_TEMPERATURE] ?: TemperatureUnit.CELSIUS.name
            ),
            pressure = PressureUnit.fromString(
                prefs[KEY_PRESSURE] ?: PressureUnit.KPA.name
            ),
            useLambda = (prefs[KEY_LAMBDA] ?: "false").toBoolean()
        )
    }

    /** Update the temperature unit preference. */
    suspend fun setTemperature(unit: TemperatureUnit) {
        dataStore.edit { it[KEY_TEMPERATURE] = unit.name }
    }

    /** Update the pressure unit preference. */
    suspend fun setPressure(unit: PressureUnit) {
        dataStore.edit { it[KEY_PRESSURE] = unit.name }
    }

    /** Toggle AFR vs Lambda display. */
    suspend fun setUseLambda(useLambda: Boolean) {
        dataStore.edit { it[KEY_LAMBDA] = useLambda.toString() }
    }

    // ==================================================================
    // Static conversion helpers (pure functions, no state)
    // ==================================================================

    companion object {
        @Volatile private var instance: UnitPreferencesStore? = null

        /** Get or create the singleton [UnitPreferencesStore] backed by [context]. */
        fun getInstance(context: Context): UnitPreferencesStore {
            return instance ?: synchronized(this) {
                instance ?: UnitPreferencesStore(context.unitPrefsDataStore).also { instance = it }
            }
        }

        private val KEY_TEMPERATURE = stringPreferencesKey("temperature_unit")
        private val KEY_PRESSURE = stringPreferencesKey("pressure_unit")
        private val KEY_LAMBDA = stringPreferencesKey("use_lambda")

        // Stochiometric AFR for gasoline
        private const val STOICH_AFR = 14.7

        // ------------------------------------------------------------------
        // Temperature conversion
        // ------------------------------------------------------------------

        /**
         * Convert a temperature value between two units.
         *
         * All conversions go through Celsius as the intermediate.
         *
         * @param value Raw value in [from] units.
         * @param from  Source unit.
         * @param to    Target unit.
         * @return Converted value.
         */
        fun convertTemperature(value: Double, from: TemperatureUnit, to: TemperatureUnit): Double {
            if (from == to) return value
            val celsius = when (from) {
                TemperatureUnit.CELSIUS -> value
                TemperatureUnit.FAHRENHEIT -> (value - 32.0) * 5.0 / 9.0
                TemperatureUnit.KELVIN -> value - 273.15
            }
            return when (to) {
                TemperatureUnit.CELSIUS -> celsius
                TemperatureUnit.FAHRENHEIT -> celsius * 9.0 / 5.0 + 32.0
                TemperatureUnit.KELVIN -> celsius + 273.15
            }
        }

        // ------------------------------------------------------------------
        // Pressure conversion
        // ------------------------------------------------------------------

        /**
         * Convert a pressure value between two units.
         *
         * All conversions go through kPa as the intermediate.
         *
         * @param value Raw value in [from] units.
         * @param from  Source unit.
         * @param to    Target unit.
         * @return Converted value.
         */
        fun convertPressure(value: Double, from: PressureUnit, to: PressureUnit): Double {
            if (from == to) return value
            val kpa = when (from) {
                PressureUnit.KPA -> value
                PressureUnit.PSI -> value * 6.894757293168361
                PressureUnit.BAR -> value * 100.0
                PressureUnit.INHG -> value * 3.386389
            }
            return when (to) {
                PressureUnit.KPA -> kpa
                PressureUnit.PSI -> kpa / 6.894757293168361
                PressureUnit.BAR -> kpa / 100.0
                PressureUnit.INHG -> kpa / 3.386389
            }
        }

        // ------------------------------------------------------------------
        // AFR / Lambda formatting
        // ------------------------------------------------------------------

        /**
         * Format an AFR or Lambda value for display.
         *
         * @param value     The AFR value (or Lambda value if already converted).
         * @param isLambda  If true, display as Lambda (e.g. "1.00").
         *                  If false, display as AFR (e.g. "14.70:1").
         * @return Formatted string with appropriate units.
         */
        fun formatAfr(value: Double, isLambda: Boolean): String {
            return if (isLambda) {
                val lambda = value / STOICH_AFR
                DFMT.format(lambda)
            } else {
                "${DFMT.format(value)}:1"
            }
        }

        /** Convert AFR to Lambda. */
        fun afrToLambda(afr: Double): Double = afr / STOICH_AFR

        /** Convert Lambda to AFR. */
        fun lambdaToAfr(lambda: Double): Double = lambda * STOICH_AFR

        private val DFMT = DecimalFormat("0.00")
    }
}
