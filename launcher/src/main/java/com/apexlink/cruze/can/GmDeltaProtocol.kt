package com.apexlink.cruze.can

import java.util.Locale

/**
 * Protocol parser for General Motors Delta II platform (2011-2015 Chevrolet Cruze LTZ)
 * across standard aftermarket automotive CAN-bus decoders (Raise, Hiworld, SimpleSoft, FYT, TS10).
 */
object GmDeltaProtocol {

    data class VehicleState(
        var doorFL: Boolean = false,
        var doorFR: Boolean = false,
        var doorRL: Boolean = false,
        var doorRR: Boolean = false,
        var trunkOpen: Boolean = false,
        var hoodOpen: Boolean = false,
        var doorsLocked: Boolean = true,
        var speedMph: Int = 0,
        var engineRpm: Int = 0,
        var fuelPercent: Int = 85,
        var batteryVolts: Float = 14.2f,
        var coolantTempF: Int = 195,
        var exteriorTempF: Int = 72,
        var steeringAngleDeg: Float = 0f,
        var headlightsOn: Boolean = false,
        var highBeamOn: Boolean = false,
        var fogLightsOn: Boolean = false,
        var hazardLightsOn: Boolean = false,
        var brakePressed: Boolean = false,
        var turnSignalLeft: Boolean = false,
        var turnSignalRight: Boolean = false,
        var handbrakeEngaged: Boolean = false,
        var tractionControlOn: Boolean = true,
        var parkAssistActive: Boolean = false,
        var cruiseControlOn: Boolean = false,
        var useMetricUnits: Boolean = false, // false = °F / MPH / PSI, true = °C / km/h / kPa
        // TPMS (Cruze LTZ standard PSI)
        var tpmsFL: Int = 32,
        var tpmsFR: Int = 32,
        var tpmsRL: Int = 32,
        var tpmsRR: Int = 32
    )

    data class ClimateState(
        var powerOn: Boolean = true,
        var driverTempF: Float = 72.0f,
        var passengerTempF: Float = 72.0f,
        var driverTempC: Float = 22.5f,
        var passengerTempC: Float = 22.5f,
        var fanSpeed: Int = 3, // 0 (off) to 8 (max)
        var acEnabled: Boolean = true,
        var maxAcEnabled: Boolean = false,
        var autoMode: Boolean = true,
        var dualZoneSync: Boolean = true,
        var recirculation: Boolean = false,
        var rearDefrost: Boolean = false,
        var frontDefrost: Boolean = false,
        var airDistribution: AirDirection = AirDirection.FACE,
        var driverSeatHeater: Int = 0, // 0 = Off, 1 = Low, 2 = Med, 3 = High
        var passengerSeatHeater: Int = 0,
        var volumeLevel: Int = 14,
        var isMuted: Boolean = false
    ) {
        fun formatDriverTemp(metric: Boolean): String {
            if (!powerOn) return "OFF"
            return if (metric) {
                if (driverTempC <= 16.0f) "LO"
                else if (driverTempC >= 28.5f) "HI"
                else String.format(Locale.US, "%.1f°C", driverTempC)
            } else {
                if (driverTempF <= 60.0f) "LO"
                else if (driverTempF >= 85.0f) "HI"
                else "${driverTempF.toInt()}°F"
            }
        }

        fun formatPassengerTemp(metric: Boolean): String {
            if (!powerOn) return "OFF"
            return if (metric) {
                if (passengerTempC <= 16.0f) "LO"
                else if (passengerTempC >= 28.5f) "HI"
                else String.format(Locale.US, "%.1f°C", passengerTempC)
            } else {
                if (passengerTempF <= 60.0f) "LO"
                else if (passengerTempF >= 85.0f) "HI"
                else "${passengerTempF.toInt()}°F"
            }
        }
    }

    enum class AirDirection(val label: String) {
        FACE("FACE"),
        BI_LEVEL("BI-LEVEL"),
        FLOOR("FEET"),
        FLOOR_DEFROST("FEET / DEF"),
        DEFROST("WINDSHIELD")
    }

    /**
     * Parses incoming CAN frame payload from aftermarket serial decoder or MCU broadcast.
     */
    fun parseCanPacket(packetId: Int, data: ByteArray, state: VehicleState, climate: ClimateState): Boolean {
        if (data.isEmpty()) return false
        var modified = false

        when (packetId) {
            // Door Status Packet (FYT 0x24 / Hiworld 0x41 / GM Delta 0x1A0)
            0x24, 0x41, 0x1A0 -> {
                val b = data[0].toInt()
                state.doorFL = (b and 0x01) != 0
                state.doorFR = (b and 0x02) != 0
                state.doorRL = (b and 0x04) != 0
                state.doorRR = (b and 0x08) != 0
                state.trunkOpen = (b and 0x10) != 0
                state.hoodOpen = (b and 0x20) != 0
                if (data.size > 1) {
                    state.doorsLocked = (data[1].toInt() and 0x01) != 0
                }
                modified = true
            }

            // Speed & RPM (GM Delta II 0x16 / 0x280)
            0x16, 0x280 -> {
                if (data.size >= 4) {
                    val rawRpm = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    state.engineRpm = rawRpm / 4
                    val rawSpeedKm = (data[2].toInt() and 0xFF)
                    state.speedMph = (rawSpeedKm * 0.621371f).toInt()
                    modified = true
                }
            }

            // Steering Wheel Angle (GM 0x32 / 0x1E5)
            0x32, 0x1E5 -> {
                if (data.size >= 2) {
                    val rawAngle = ((data[0].toInt()) shl 8) or (data[1].toInt() and 0xFF)
                    state.steeringAngleDeg = rawAngle * 0.1f
                    modified = true
                }
            }

            // Climate Control Frame (0x31 / 0x21 / Raise GM Delta HVAC)
            0x31, 0x21 -> {
                if (data.size >= 4) {
                    // Power & Modes
                    climate.powerOn = (data[0].toInt() and 0x80) != 0
                    
                    // Driver Temp byte
                    val rawDriverTemp = data[0].toInt() and 0x7F
                    if (rawDriverTemp == 0x00) {
                        climate.driverTempF = 60.0f
                        climate.driverTempC = 16.0f
                    } else if (rawDriverTemp >= 0x3F) {
                        climate.driverTempF = 85.0f
                        climate.driverTempC = 28.5f
                    } else {
                        climate.driverTempC = 16.0f + (rawDriverTemp * 0.5f)
                        climate.driverTempF = (climate.driverTempC * 1.8f + 32f)
                    }

                    // Passenger Temp byte
                    if (data.size >= 2) {
                        val rawPassTemp = data[1].toInt() and 0x7F
                        if (rawPassTemp == 0x00) {
                            climate.passengerTempF = 60.0f
                            climate.passengerTempC = 16.0f
                        } else if (rawPassTemp >= 0x3F) {
                            climate.passengerTempF = 85.0f
                            climate.passengerTempC = 28.5f
                        } else {
                            climate.passengerTempC = 16.0f + (rawPassTemp * 0.5f)
                            climate.passengerTempF = (climate.passengerTempC * 1.8f + 32f)
                        }
                    }

                    // Blower speed (bits 0-3) & A/C / Auto
                    if (data.size >= 3) {
                        val b2 = data[2].toInt()
                        climate.fanSpeed = b2 and 0x0F
                        climate.acEnabled = (b2 and 0x40) != 0
                        climate.autoMode = (b2 and 0x80) != 0
                        climate.recirculation = (b2 and 0x20) != 0
                        climate.dualZoneSync = (b2 and 0x10) != 0
                    }

                    // Defrost & Direction
                    if (data.size >= 4) {
                        val b3 = data[3].toInt()
                        climate.frontDefrost = (b3 and 0x01) != 0
                        climate.rearDefrost = (b3 and 0x02) != 0
                        val dir = (b3 shr 4) and 0x07
                        climate.airDistribution = when (dir) {
                            1 -> AirDirection.FACE
                            2 -> AirDirection.BI_LEVEL
                            3 -> AirDirection.FLOOR
                            4 -> AirDirection.FLOOR_DEFROST
                            5 -> AirDirection.DEFROST
                            else -> AirDirection.FACE
                        }
                    }

                    // Seat Heaters (Cruze LTZ 3-stage)
                    if (data.size >= 5) {
                        climate.driverSeatHeater = (data[4].toInt() and 0x03)
                        climate.passengerSeatHeater = ((data[4].toInt() shr 2) and 0x03)
                    }
                    modified = true
                }
            }
        }
        return modified
    }
}
