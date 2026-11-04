package com.apexlink.cruze.can

/**
 * Protocol parser for General Motors Delta II platform (2011-2015 Chevrolet Cruze LTZ)
 * across standard aftermarket automotive CAN-bus decoders (Raise, Hiworld, SimpleSoft, FYT).
 */
object GmDeltaProtocol {

    data class VehicleState(
        var doorFL: Boolean = false,
        var doorFR: Boolean = false,
        var doorRL: Boolean = false,
        var doorRR: Boolean = false,
        var trunkOpen: Boolean = false,
        var hoodOpen: Boolean = false,
        var speedMph: Int = 0,
        var engineRpm: Int = 0,
        var fuelPercent: Int = 85,
        var batteryVolts: Float = 14.2f,
        var coolantTempF: Int = 195,
        var exteriorTempF: Int = 72,
        var steeringAngleDeg: Float = 0f,
        var headlightsOn: Boolean = false,
        var highBeamOn: Boolean = false,
        var brakePressed: Boolean = false,
        var turnSignalLeft: Boolean = false,
        var turnSignalRight: Boolean = false,
        var handbrakeEngaged: Boolean = false,
        // TPMS (Cruze LTZ standard PSI)
        var tpmsFL: Int = 32,
        var tpmsFR: Int = 32,
        var tpmsRL: Int = 32,
        var tpmsRR: Int = 32
    )

    data class ClimateState(
        var driverTempF: Float = 70.0f,
        var passengerTempF: Float = 70.0f,
        var fanSpeed: Int = 3, // 0 to 8
        var acEnabled: Boolean = true,
        var maxAcEnabled: Boolean = false,
        var autoMode: Boolean = true,
        var dualZoneSync: Boolean = true,
        var recirculation: Boolean = false,
        var rearDefrost: Boolean = false,
        var frontDefrost: Boolean = false,
        var airDistribution: AirDirection = AirDirection.FACE,
        var driverSeatHeater: Int = 0, // 0 = Off, 1 = Low, 2 = Med, 3 = High
        var passengerSeatHeater: Int = 0
    )

    enum class AirDirection {
        FACE, BI_LEVEL, FLOOR, FLOOR_DEFROST, DEFROST
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

            // Climate Control Frame (0x31 / Raise GM Delta HVAC)
            0x31, 0x21 -> {
                if (data.size >= 4) {
                    // Driver Temp byte
                    val rawDriverTemp = data[0].toInt() and 0xFF
                    climate.driverTempF = if (rawDriverTemp == 0x00) 60.0f else if (rawDriverTemp == 0xFF) 85.0f else 60.0f + (rawDriverTemp * 0.5f)
                    
                    // Passenger Temp byte
                    val rawPassTemp = data[1].toInt() and 0xFF
                    climate.passengerTempF = if (rawPassTemp == 0x00) 60.0f else if (rawPassTemp == 0xFF) 85.0f else 60.0f + (rawPassTemp * 0.5f)

                    // Blower speed (bits 0-3)
                    climate.fanSpeed = data[2].toInt() and 0x0F
                    climate.acEnabled = (data[2].toInt() and 0x40) != 0
                    climate.autoMode = (data[2].toInt() and 0x80) != 0

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
