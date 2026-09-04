package com.ztune.libretune.core.performance

import kotlin.math.PI
import kotlin.math.pow

/**
 * Phase 25: Performance Calculator.
 *
 * Estimates horsepower, torque, and acceleration based on vehicle
 * parameters and realtime data (RPM, vehicle speed).
 */
data class VehicleSpec(
    val massKg: Double = 1200.0,
    val tireDiameterMm: Double = 650.0,
    val finalDriveRatio: Double = 3.9,
    val gearRatios: List<Double> = listOf(3.3, 2.0, 1.4, 1.0, 0.8, 0.65),
    val cd: Double = 0.32,
    val frontalAreaM2: Double = 2.2,
    val rollingResistance: Double = 0.015
)

data class PerformanceResult(
    val rpm: Double,
    val speedKmh: Double,
    val gear: Int,
    val wheelForceN: Double,
    val dragForceN: Double,
    val rollingForceN: Double,
    val netForceN: Double,
    val accelerationMs2: Double,
    val estimatedHp: Double,
    val estimatedTorqueNm: Double
)

object PerformanceCalculator {

    private const val AIR_DENSITY = 1.225
    private const val GRAVITY = 9.81

    fun calculateSpeed(rpm: Double, gearIdx: Int, spec: VehicleSpec): Double {
        if (gearIdx !in spec.gearRatios.indices) return 0.0
        val gearRatio = spec.gearRatios[gearIdx]
        val totalRatio = gearRatio * spec.finalDriveRatio
        val tireCircumferenceM = spec.tireDiameterMm / 1000.0 * PI
        val wheelRpm = rpm / totalRatio
        val wheelMs = wheelRpm / 60.0 * tireCircumferenceM
        return wheelMs * 3.6
    }

    fun estimateGear(rpm: Double, speedKmh: Double, spec: VehicleSpec): Int {
        if (rpm < 100 || speedKmh < 1) return -1
        for (i in spec.gearRatios.indices) {
            val expectedSpeed = calculateSpeed(rpm, i, spec)
            if (speedKmh in expectedSpeed * 0.85..expectedSpeed * 1.15) return i
        }
        return -1
    }

    fun calculate(rpm: Double, speedKmh: Double, spec: VehicleSpec, wheelForceN: Double? = null): PerformanceResult {
        val gear = estimateGear(rpm, speedKmh, spec)
        val speedMs = speedKmh / 3.6
        val dragForceN = 0.5 * AIR_DENSITY * speedMs.pow(2) * spec.cd * spec.frontalAreaM2
        val rollingForceN = spec.rollingResistance * spec.massKg * GRAVITY
        val estimatedPowerKw = estimatePower(rpm)
        val estimatedPowerHp = estimatedPowerKw * 1.36
        val effectiveWheelForce = wheelForceN ?: if (speedMs > 0.5) estimatedPowerKw * 1000 / speedMs else 0.0
        val netForceN = effectiveWheelForce - dragForceN - rollingForceN
        val accelerationMs2 = netForceN / spec.massKg
        val estimatedTorqueNm = if (rpm > 100) estimatedPowerKw * 9549 / rpm else 0.0
        return PerformanceResult(rpm, speedKmh, gear, effectiveWheelForce, dragForceN, rollingForceN, netForceN, accelerationMs2, estimatedPowerHp, estimatedTorqueNm)
    }

    private fun estimatePower(rpm: Double): Double {
        val peakRpm = 6000.0
        val peakPowerKw = 110.0
        val normalized = (rpm / peakRpm).coerceIn(0.0, 1.5)
        val factor = if (normalized <= 1.0) 0.3 + 0.7 * normalized else (1.0 - (normalized - 1.0) * 0.5).coerceAtLeast(0.3)
        return peakPowerKw * factor
    }

    fun estimateZeroToHundred(spec: VehicleSpec): Double {
        var speed = 0.0; var time = 0.0; val dt = 0.1; val targetSpeed = 100.0 / 3.6
        while (speed < targetSpeed && time < 60.0) {
            val rpm = estimateRpmFromSpeed(speed * 3.6, 1, spec)
            val power = estimatePower(rpm) * 1000
            val force = if (speed > 0.5) power / speed else power / 0.5
            val drag = 0.5 * AIR_DENSITY * speed.pow(2) * spec.cd * spec.frontalAreaM2
            val rolling = spec.rollingResistance * spec.massKg * GRAVITY
            val netForce = (force - drag - rolling).coerceAtLeast(0.0)
            speed += (netForce / spec.massKg) * dt
            time += dt
        }
        return if (speed >= targetSpeed) time else Double.NaN
    }

    private fun estimateRpmFromSpeed(speedKmh: Double, gearIdx: Int, spec: VehicleSpec): Double {
        if (gearIdx !in spec.gearRatios.indices) return 1000.0
        val gearRatio = spec.gearRatios[gearIdx]
        val totalRatio = gearRatio * spec.finalDriveRatio
        val tireCircumferenceM = spec.tireDiameterMm / 1000.0 * PI
        val wheelMs = speedKmh / 3.6
        val wheelRpm = wheelMs * 60.0 / tireCircumferenceM
        return (wheelRpm * totalRatio).coerceAtLeast(800.0)
    }
}
