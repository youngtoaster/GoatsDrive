package com.apexlink.cruze.climate

import com.apexlink.cruze.can.CanBusService
import com.apexlink.cruze.can.GmDeltaProtocol

/**
 * Controller managing the 2012 Chevrolet Cruze LTZ Dual-Zone HVAC & Seat Heaters.
 */
object ClimateController {

    val state: GmDeltaProtocol.ClimateState
        get() = CanBusService.climateState

    fun adjustDriverTemp(delta: Float) {
        val current = state.driverTempF
        val next = (current + delta).coerceIn(60.0f, 85.0f)
        state.driverTempF = Math.round(next * 2) / 2.0f
        if (state.dualZoneSync) {
            state.passengerTempF = state.driverTempF
        }
        CanBusService.notifyUpdate()
    }

    fun adjustPassengerTemp(delta: Float) {
        val current = state.passengerTempF
        val next = (current + delta).coerceIn(60.0f, 85.0f)
        state.passengerTempF = Math.round(next * 2) / 2.0f
        state.dualZoneSync = false
        CanBusService.notifyUpdate()
    }

    fun setFanSpeed(speed: Int) {
        state.fanSpeed = speed.coerceIn(0, 8)
        CanBusService.notifyUpdate()
    }

    fun stepFanSpeed() {
        val next = (state.fanSpeed + 1) % 9
        setFanSpeed(next)
    }

    fun toggleAc() {
        state.acEnabled = !state.acEnabled
        if (!state.acEnabled) state.maxAcEnabled = false
        CanBusService.notifyUpdate()
    }

    fun toggleMaxAc() {
        state.maxAcEnabled = !state.maxAcEnabled
        if (state.maxAcEnabled) {
            state.acEnabled = true
            state.driverTempF = 60.0f
            state.passengerTempF = 60.0f
            state.fanSpeed = 8
            state.recirculation = true
        }
        CanBusService.notifyUpdate()
    }

    fun toggleAuto() {
        state.autoMode = !state.autoMode
        CanBusService.notifyUpdate()
    }

    fun toggleRecirculation() {
        state.recirculation = !state.recirculation
        CanBusService.notifyUpdate()
    }

    fun toggleRearDefrost() {
        state.rearDefrost = !state.rearDefrost
        CanBusService.notifyUpdate()
    }

    fun toggleFrontDefrost() {
        state.frontDefrost = !state.frontDefrost
        if (state.frontDefrost) {
            state.airDistribution = GmDeltaProtocol.AirDirection.DEFROST
            state.fanSpeed = 7
            state.acEnabled = true
        }
        CanBusService.notifyUpdate()
    }

    fun setAirDirection(dir: GmDeltaProtocol.AirDirection) {
        state.airDistribution = dir
        CanBusService.notifyUpdate()
    }

    // Cruze LTZ 3-stage seat warmers (0=Off, 1=Low, 2=Med, 3=High)
    fun cycleDriverSeatHeater(): Int {
        state.driverSeatHeater = (state.driverSeatHeater + 1) % 4
        CanBusService.notifyUpdate()
        return state.driverSeatHeater
    }

    fun cyclePassengerSeatHeater(): Int {
        state.passengerSeatHeater = (state.passengerSeatHeater + 1) % 4
        CanBusService.notifyUpdate()
        return state.passengerSeatHeater
    }

    fun formatTemp(temp: Float): String {
        return if (temp <= 60.0f) "LO" else if (temp >= 85.0f) "HI" else "${temp.toInt()}°"
    }
}
