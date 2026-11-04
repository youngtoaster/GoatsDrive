package com.apexlink.cruze.climate

import android.content.Context
import com.apexlink.cruze.can.CanBusService
import com.apexlink.cruze.can.GmDeltaProtocol

/**
 * Controller managing the 2012 Chevrolet Cruze LTZ Dual-Zone HVAC & Seat Heaters.
 * Dispatches MCU broadcast packets and synchronizes with vehicle CAN-bus state with zero lag.
 */
object ClimateController {

    val state: GmDeltaProtocol.ClimateState
        get() = CanBusService.climateState

    val vehicle: GmDeltaProtocol.VehicleState
        get() = CanBusService.vehicleState

    fun togglePower(context: Context) {
        state.powerOn = !state.powerOn
        CanBusService.broadcastMcuCommand(context, "HVAC_POWER", if (state.powerOn) 1 else 0)
        CanBusService.notifyUpdate()
    }

    fun adjustDriverTemp(context: Context, deltaF: Float) {
        state.powerOn = true
        val isMetric = vehicle.useMetricUnits
        if (isMetric) {
            val deltaC = if (deltaF > 0) 0.5f else -0.5f
            state.driverTempC = (state.driverTempC + deltaC).coerceIn(16.0f, 28.5f)
            state.driverTempF = (state.driverTempC * 1.8f + 32f)
        } else {
            val nextF = (state.driverTempF + deltaF).coerceIn(60.0f, 85.0f)
            state.driverTempF = Math.round(nextF * 2) / 2.0f
            state.driverTempC = ((state.driverTempF - 32f) / 1.8f)
        }

        if (state.dualZoneSync) {
            state.passengerTempF = state.driverTempF
            state.passengerTempC = state.driverTempC
        }
        CanBusService.broadcastMcuCommand(context, "SET_DRIVER_TEMP", state.driverTempF.toInt())
        CanBusService.notifyUpdate()
    }

    fun adjustPassengerTemp(context: Context, deltaF: Float) {
        state.powerOn = true
        val isMetric = vehicle.useMetricUnits
        if (isMetric) {
            val deltaC = if (deltaF > 0) 0.5f else -0.5f
            state.passengerTempC = (state.passengerTempC + deltaC).coerceIn(16.0f, 28.5f)
            state.passengerTempF = (state.passengerTempC * 1.8f + 32f)
        } else {
            val nextF = (state.passengerTempF + deltaF).coerceIn(60.0f, 85.0f)
            state.passengerTempF = Math.round(nextF * 2) / 2.0f
            state.passengerTempC = ((state.passengerTempF - 32f) / 1.8f)
        }
        state.dualZoneSync = false
        CanBusService.broadcastMcuCommand(context, "SET_PASS_TEMP", state.passengerTempF.toInt())
        CanBusService.notifyUpdate()
    }

    fun setFanSpeed(context: Context, speed: Int) {
        state.powerOn = true
        state.fanSpeed = speed.coerceIn(0, 8)
        CanBusService.broadcastMcuCommand(context, "SET_FAN_SPEED", state.fanSpeed)
        CanBusService.notifyUpdate()
    }

    fun stepFanSpeed(context: Context, delta: Int = 1) {
        state.powerOn = true
        val next = (state.fanSpeed + delta).coerceIn(0, 8)
        setFanSpeed(context, next)
    }

    fun toggleAc(context: Context) {
        state.powerOn = true
        state.acEnabled = !state.acEnabled
        if (!state.acEnabled) state.maxAcEnabled = false
        CanBusService.broadcastMcuCommand(context, "TOGGLE_AC", if (state.acEnabled) 1 else 0)
        CanBusService.notifyUpdate()
    }

    fun toggleMaxAc(context: Context) {
        state.powerOn = true
        state.maxAcEnabled = !state.maxAcEnabled
        if (state.maxAcEnabled) {
            state.acEnabled = true
            state.driverTempF = 60.0f
            state.passengerTempF = 60.0f
            state.driverTempC = 16.0f
            state.passengerTempC = 16.0f
            state.fanSpeed = 8
            state.recirculation = true
        }
        CanBusService.broadcastMcuCommand(context, "SET_MAX_AC", if (state.maxAcEnabled) 1 else 0)
        CanBusService.notifyUpdate()
    }

    fun toggleAuto(context: Context) {
        state.powerOn = true
        state.autoMode = !state.autoMode
        CanBusService.broadcastMcuCommand(context, "TOGGLE_AUTO", if (state.autoMode) 1 else 0)
        CanBusService.notifyUpdate()
    }

    fun toggleDual(context: Context) {
        state.dualZoneSync = !state.dualZoneSync
        if (state.dualZoneSync) {
            state.passengerTempF = state.driverTempF
            state.passengerTempC = state.driverTempC
        }
        CanBusService.broadcastMcuCommand(context, "TOGGLE_DUAL", if (state.dualZoneSync) 1 else 0)
        CanBusService.notifyUpdate()
    }

    fun toggleRecirculation(context: Context) {
        state.recirculation = !state.recirculation
        CanBusService.broadcastMcuCommand(context, "TOGGLE_RECIRC", if (state.recirculation) 1 else 0)
        CanBusService.notifyUpdate()
    }

    fun toggleRearDefrost(context: Context) {
        state.rearDefrost = !state.rearDefrost
        CanBusService.broadcastMcuCommand(context, "TOGGLE_REAR_DEFROST", if (state.rearDefrost) 1 else 0)
        CanBusService.notifyUpdate()
    }

    fun toggleFrontDefrost(context: Context) {
        state.frontDefrost = !state.frontDefrost
        if (state.frontDefrost) {
            state.airDistribution = GmDeltaProtocol.AirDirection.DEFROST
            state.fanSpeed = 7
            state.acEnabled = true
        }
        CanBusService.broadcastMcuCommand(context, "TOGGLE_FRONT_DEFROST", if (state.frontDefrost) 1 else 0)
        CanBusService.notifyUpdate()
    }

    fun setAirDirection(context: Context, dir: GmDeltaProtocol.AirDirection) {
        state.airDistribution = dir
        CanBusService.broadcastMcuCommand(context, "SET_AIR_DIRECTION", dir.ordinal)
        CanBusService.notifyUpdate()
    }

    fun cycleAirDirection(context: Context) {
        val dirs = GmDeltaProtocol.AirDirection.values()
        val next = dirs[(state.airDistribution.ordinal + 1) % dirs.size]
        setAirDirection(context, next)
    }

    fun cycleDriverSeatHeater(context: Context): Int {
        // Cycle: 0 -> 3 (High) -> 2 (Med) -> 1 (Low) -> 0 (Off)
        state.driverSeatHeater = when (state.driverSeatHeater) {
            0 -> 3
            3 -> 2
            2 -> 1
            else -> 0
        }
        CanBusService.broadcastMcuCommand(context, "SET_DRIVER_SEAT", state.driverSeatHeater)
        CanBusService.notifyUpdate()
        return state.driverSeatHeater
    }

    fun cyclePassengerSeatHeater(context: Context): Int {
        // Cycle: 0 -> 3 -> 2 -> 1 -> 0
        state.passengerSeatHeater = when (state.passengerSeatHeater) {
            0 -> 3
            3 -> 2
            2 -> 1
            else -> 0
        }
        CanBusService.broadcastMcuCommand(context, "SET_PASS_SEAT", state.passengerSeatHeater)
        CanBusService.notifyUpdate()
        return state.passengerSeatHeater
    }

    fun toggleMetricUnits(context: Context) {
        vehicle.useMetricUnits = !vehicle.useMetricUnits
        CanBusService.notifyUpdate()
    }
}
