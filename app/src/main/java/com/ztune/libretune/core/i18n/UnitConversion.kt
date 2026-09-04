package com.ztune.libretune.core.i18n

/**
 * Phase 24: Centralized unit conversion system.
 *
 * All unit conversions go through this object so there is a single source
 * of truth. UI components call [UnitConversion.convert] with the channel's
 * native unit and the user's preferred unit.
 *
 * Supported domains:
 * - Temperature: °C, °F, K
 * - Pressure: kPa, PSI, bar, inHg
 * - AFR / Lambda
 * - Speed: km/h, mph
 * - Distance: km, mi
 * - Volume: L, gal (US), gal (Imperial)
 * - Mass: kg, lbs
 * - Angle: degrees, radians
 * - Voltage: V, mV
 * - Time: s, ms
 */
object UnitConversion {

    // ------------------------------------------------------------------
    // Temperature
    // ------------------------------------------------------------------

    fun celsiusToFahrenheit(c: Double): Double = c * 9.0 / 5.0 + 32.0
    fun fahrenheitToCelsius(f: Double): Double = (f - 32.0) * 5.0 / 9.0
    fun celsiusToKelvin(c: Double): Double = c + 273.15
    fun kelvinToCelsius(k: Double): Double = k - 273.15

    fun convertTemperature(value: Double, from: String, to: String): Double {
        if (from.equals(to, ignoreCase = true)) return value
        val celsius = when (from.lowercase()) {
            "c", "°c", "celsius" -> value
            "f", "°f", "fahrenheit" -> fahrenheitToCelsius(value)
            "k", "kelvin" -> kelvinToCelsius(value)
            else -> value
        }
        return when (to.lowercase()) {
            "c", "°c", "celsius" -> celsius
            "f", "°f", "fahrenheit" -> celsiusToFahrenheit(celsius)
            "k", "kelvin" -> celsiusToKelvin(celsius)
            else -> celsius
        }
    }

    // ------------------------------------------------------------------
    // Pressure
    // ------------------------------------------------------------------

    fun kpaToPsi(kpa: Double): Double = kpa * 0.1450377377
    fun psiToKpa(psi: Double): Double = psi * 6.8947572932
    fun kpaToBar(kpa: Double): Double = kpa / 100.0
    fun barToKpa(bar: Double): Double = bar * 100.0
    fun kpaToInhg(kpa: Double): Double = kpa * 0.2952998016
    fun inhgToKpa(inhg: Double): Double = inhg * 3.3863886667

    fun convertPressure(value: Double, from: String, to: String): Double {
        if (from.equals(to, ignoreCase = true)) return value
        val kpa = when (from.lowercase()) {
            "kpa" -> value
            "psi" -> psiToKpa(value)
            "bar" -> barToKpa(value)
            "inhg" -> inhgToKpa(value)
            else -> value
        }
        return when (to.lowercase()) {
            "kpa" -> kpa
            "psi" -> kpaToPsi(kpa)
            "bar" -> kpaToBar(kpa)
            "inhg" -> kpaToInhg(kpa)
            else -> kpa
        }
    }

    // ------------------------------------------------------------------
    // AFR / Lambda
    // ------------------------------------------------------------------

    const val GASOLINE_STOICH = 14.7
    const val E85_STOICH = 9.8
    const val METHANOL_STOICH = 6.4
    const val ETHANOL_STOICH = 9.0
    const val LPG_STOICH = 15.5
    const val CNG_STOICH = 17.2
    const val DIESEL_STOICH = 14.5

    fun afrToLambda(afr: Double, stoich: Double = GASOLINE_STOICH): Double = afr / stoich
    fun lambdaToAfr(lambda: Double, stoich: Double = GASOLINE_STOICH): Double = lambda * stoich

    fun convertAfrLambda(value: Double, from: String, to: String, stoich: Double = GASOLINE_STOICH): Double {
        if (from.equals(to, ignoreCase = true)) return value
        return when {
            from.lowercase().startsWith("afr") && to.lowercase().startsWith("lambda") ->
                afrToLambda(value, stoich)
            from.lowercase().startsWith("lambda") && to.lowercase().startsWith("afr") ->
                lambdaToAfr(value, stoich)
            else -> value
        }
    }

    // ------------------------------------------------------------------
    // Speed
    // ------------------------------------------------------------------

    fun kmhToMph(kmh: Double): Double = kmh * 0.621371
    fun mphToKmh(mph: Double): Double = mph * 1.60934

    fun convertSpeed(value: Double, from: String, to: String): Double {
        if (from.equals(to, ignoreCase = true)) return value
        val kmh = when (from.lowercase()) {
            "km/h", "kmh" -> value
            "mph" -> mphToKmh(value)
            else -> value
        }
        return when (to.lowercase()) {
            "km/h", "kmh" -> kmh
            "mph" -> kmhToMph(kmh)
            else -> kmh
        }
    }

    // ------------------------------------------------------------------
    // Distance
    // ------------------------------------------------------------------

    fun kmToMiles(km: Double): Double = km * 0.621371
    fun milesToKm(mi: Double): Double = mi * 1.60934

    fun convertDistance(value: Double, from: String, to: String): Double {
        if (from.equals(to, ignoreCase = true)) return value
        val km = when (from.lowercase()) {
            "km" -> value
            "mi", "mile", "miles" -> milesToKm(value)
            else -> value
        }
        return when (to.lowercase()) {
            "km" -> km
            "mi", "mile", "miles" -> kmToMiles(km)
            else -> km
        }
    }

    // ------------------------------------------------------------------
    // Volume
    // ------------------------------------------------------------------

    fun litersToGallonsUs(l: Double): Double = l * 0.264172
    fun gallonsUsToLiters(g: Double): Double = g * 3.78541
    fun litersToGallonsImperial(l: Double): Double = l * 0.219969
    fun gallonsImperialToLiters(g: Double): Double = g * 4.54609

    fun convertVolume(value: Double, from: String, to: String): Double {
        if (from.equals(to, ignoreCase = true)) return value
        val liters = when (from.lowercase()) {
            "l", "liter", "liters" -> value
            "gal", "gallon", "gallons", "gal_us" -> gallonsUsToLiters(value)
            "gal_uk", "gallon_uk" -> gallonsImperialToLiters(value)
            else -> value
        }
        return when (to.lowercase()) {
            "l", "liter", "liters" -> liters
            "gal", "gallon", "gallons", "gal_us" -> litersToGallonsUs(liters)
            "gal_uk", "gallon_uk" -> litersToGallonsImperial(liters)
            else -> liters
        }
    }

    // ------------------------------------------------------------------
    // Mass
    // ------------------------------------------------------------------

    fun kgToLbs(kg: Double): Double = kg * 2.20462
    fun lbsToKg(lbs: Double): Double = lbs * 0.453592

    fun convertMass(value: Double, from: String, to: String): Double {
        if (from.equals(to, ignoreCase = true)) return value
        val kg = when (from.lowercase()) {
            "kg" -> value
            "lb", "lbs", "pound", "pounds" -> lbsToKg(value)
            else -> value
        }
        return when (to.lowercase()) {
            "kg" -> kg
            "lb", "lbs", "pound", "pounds" -> kgToLbs(kg)
            else -> kg
        }
    }

    // ------------------------------------------------------------------
    // Angle
    // ------------------------------------------------------------------

    fun degreesToRadians(d: Double): Double = d * Math.PI / 180.0
    fun radiansToDegrees(r: Double): Double = r * 180.0 / Math.PI

    // ------------------------------------------------------------------
    // Voltage
    // ------------------------------------------------------------------

    fun voltsToMilliVolts(v: Double): Double = v * 1000.0
    fun milliVoltsToVolts(mv: Double): Double = mv / 1000.0

    // ------------------------------------------------------------------
    // Time
    // ------------------------------------------------------------------

    fun secondsToMilliseconds(s: Double): Double = s * 1000.0
    fun millisecondsToSeconds(ms: Double): Double = ms / 1000.0

    // ------------------------------------------------------------------
    // Generic conversion dispatcher
    // ------------------------------------------------------------------

    /**
     * Convert [value] from [fromUnit] to [toUnit].
     *
     * The unit domain (temperature, pressure, etc.) is inferred from the
     * unit strings. If the units are in different domains or unknown,
     * the value is returned unchanged.
     *
     * @param stoich Stoichiometric AFR for the fuel type (used for AFR↔λ
     *   conversion). Defaults to gasoline (14.7).
     */
    fun convert(value: Double, fromUnit: String, toUnit: String, stoich: Double = GASOLINE_STOICH): Double {
        if (fromUnit.equals(toUnit, ignoreCase = true) || fromUnit.isBlank() || toUnit.isBlank()) return value

        // Try each domain
        val fromLower = fromUnit.lowercase()
        val toLower = toUnit.lowercase()

        // Temperature
        if (fromLower in setOf("c", "°c", "celsius", "f", "°f", "fahrenheit", "k", "kelvin") &&
            toLower in setOf("c", "°c", "celsius", "f", "°f", "fahrenheit", "k", "kelvin")) {
            return convertTemperature(value, fromUnit, toUnit)
        }

        // Pressure
        if (fromLower in setOf("kpa", "psi", "bar", "inhg") &&
            toLower in setOf("kpa", "psi", "bar", "inhg")) {
            return convertPressure(value, fromUnit, toUnit)
        }

        // AFR / Lambda
        if (fromLower.startsWith("afr") || fromLower.startsWith("lambda")) {
            return convertAfrLambda(value, fromUnit, toUnit, stoich)
        }

        // Speed
        if (fromLower in setOf("km/h", "kmh", "mph") &&
            toLower in setOf("km/h", "kmh", "mph")) {
            return convertSpeed(value, fromUnit, toUnit)
        }

        // Unknown domain — return unchanged
        return value
    }

    /**
     * Format a value with [value] converted to [toUnit], with [decimals]
     * decimal places and the unit suffix appended.
     */
    fun format(value: Double, fromUnit: String, toUnit: String, decimals: Int = 1): String {
        val converted = convert(value, fromUnit, toUnit)
        val formatStr = "%.${decimals}f"
        return "${formatStr.format(converted)} $toUnit"
    }
}
