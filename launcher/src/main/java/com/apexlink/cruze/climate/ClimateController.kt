package com.apexlink.cruze.climate

import android.content.Context
import com.apexlink.cruze.can.CanBusService
import com.apexlink.cruze.can.GmDeltaProtocol

/**
 * Controller managing the 2012 Chevrolet Cruze LTZ Dual-Zone HVAC & Seat Heaters.
 * Dispatches MCU broadcast packets and synchronizes with vehicle CAN-bus state.
 */
object ClimateController {

    val state: GmDeltaProtocol.ClimateState
        get() = CanBusService.climateState

    fun adjustDriverTemp(context: Context, delta: Float) {
        val current = state.driverTempF
        val next = (current + delta).coerceIn(60.0f, 85.0f)
        state.driverTempF = Math.round(next * 2) / 2.0f
        if (state.dualZoneSync) {
            state.passengerTempF = state.driverTempF
        }
        CanBusService.broadcastMcuCommand(context, "SET_DRIVER_TEMP", state.driverTempF.toInt())
        CanBusService.notifyUpdate()
    }

    fun adjustPassengerTemp(context: Context, delta: Float) {
        val current = state.passengerTempF
        val next = (current + delta).coerceIn(60.0f, 85.0f)
        state.passengerTempF = Math.round(next * 2) / 2.0f
        state.dualZoneSync = false
        CanBusService.broadcastMcuCommand(context, "SET_PASS_TEMP", state.passengerTempF.toInt())
        CanBusService.notifyUpdate()
    }

    fun setFanSpeed(context: Context, speed: Int) {
        state.fanSpeed = speed.coerceIn(0, 8)
        CanBusService.broadcastMcuCommand(context, "SET_FAN_SPEED", state.fanSpeed)
        CanBusService.notifyUpdate()
    }

    fun stepFanSpeed(context: Context) {
        val next = (state.fanSpeed + 1) % 9
        setFanSpeed(context, next)
    }

    fun toggleAc(context: Context) {
        state.acEnabled = !state.acEnabled
        if (!state.acEnabled) state.maxAcEnabled = false
        CanBusService.broadcastMcuCommand(context, "TOGGLE_AC", if (state.acEnabled) 1 else 0)
        CanBusService.notifyUpdate()
    }

    fun toggleMaxAc(context: Context) {
        state.maxAcEnabled = !state.maxAcEnabled
        if (state.maxAcEnabled) {
            state.acEnabled = true
            state.driverTempF = 60.0f
            state.passengerTempF = 60.0f
            state.fanSpeed = 8
            state.recirculation = true
        }
        CanBusService.broadcastMcuCommand(context, "SET_MAX_AC", if (state.maxAcEnabled) 1 else 0)
        CanBusService.notifyUpdate()
    }

    fun toggleAuto(context: Context) {
        state.autoMode = !state.autoMode
        CanBusService.broadcastMcuCommand(context, "TOGGLE_AUTO", if (state.autoMode) 1 else 0)
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

    fun cycleDriverSeatHeater(context: Context): Int {
        state.driverSeatHeater = (state.driverSeatHeater + 1) % 4
        CanBusService.broadcastMcuCommand(context, "SET_DRIVER_SEAT_HEATER", state.driverSeatHeater)
        CanBusService.notifyUpdate()
        return state.driverSeatHeater
    }

    fun cyclePassengerSeatHeater(context: Context): Int {
        state.passengerSeatHeater = (state.passengerSeatHeater + 1) % 4
        CanBusService.broadcastMcuCommand(context, "SET_PASS_SEAT_HEATER", state.passengerSeatHeater)
        CanBusService.notifyUpdate()
        return state.passengerSeatHeater
    }
}
