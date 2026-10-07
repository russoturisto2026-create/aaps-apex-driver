package app.aaps.pump.atc3.protocol

/** Groups, modes, opcodes, object types and scales of the ATC3 protocol. */
object Atc3Protocol {

    /** Request group for control commands and targeted reads. */
    const val GROUP_CONTROL: Byte = 0x35

    /** Request group for whole histories. */
    const val GROUP_QUERY: Byte = 0x55

    /** Request mode, and answer frame id, of control commands. */
    const val MODE_CONTROL: Byte = 0xA1.toByte()

    /** Request mode, and answer frame id, of reads. */
    const val MODE_HISTORY: Byte = 0xA3.toByte()

    /** Frame id of the heartbeat the pump sends by itself; told from other frames by its size. */
    const val MODE_HEARTBEAT: Byte = 0xA5.toByte()
    const val HEARTBEAT_FRAME_SIZE = 8

    /** The most frames an alias read answers with; such a read is complete by its frame count. */
    const val ALIAS_ANSWER_FRAMES = 10

    /** Parameter of a periodic read: the latest records. */
    const val PARAMETER_LATEST: Byte = 0x01

    object ControlOpcode {

        const val WRITE_BASAL_PROFILE: Byte = 0x00
        const val START_TBR: Byte = 0x02
        const val SWITCH_PROFILE: Byte = 0x04
        const val CANCEL_TBR: Byte = 0x05
        const val BOLUS: Byte = 0x12
        const val SUSPEND: Byte = 0x21
        const val SET_CLOCK: Byte = 0x31
        const val WRITE_SETTINGS: Byte = 0x32
        const val SET_HEARTBEAT: Byte = 0x33
        const val SET_BT_PASSWORD: Byte = 0x35

        /** Sent with [GROUP_QUERY]; with [GROUP_CONTROL] the same opcode is [START_TBR]. */
        const val CANCEL_BOLUS: Byte = 0x02
    }

    object TbrPayload {

        const val MODE_ABSOLUTE: Byte = 0x01

        /** Set on the pump's keypad only; the driver sends absolute rates. */
        const val MODE_PERCENT: Byte = 0x00

        /** Minutes covered by one unit of the duration. */
        const val DURATION_UNIT_MINUTES = 15
    }

    /** Reads sent with [GROUP_CONTROL]; each is answered under the object of the same number. */
    object ReadOpcode {

        const val STATUS_V1: Byte = 0x00
        const val BASAL_PROFILES: Byte = 0x08
        const val TBR_ACTIVE: Byte = 0x0A
        const val TBR_FINISHED: Byte = 0x0B
        const val STATUS_V2: Byte = 0x0C
        const val VERSION: Byte = 0x31
    }

    object HistoryOpcode {

        /** With [GROUP_QUERY]. */
        const val BOLUS_HISTORY: Byte = 0x01

        /** With [GROUP_QUERY]. */
        const val ALARM_HISTORY: Byte = 0x03

        /** With [GROUP_QUERY]. */
        const val REFILL_HISTORY: Byte = 0x04

        /** With [GROUP_QUERY]. */
        const val DAILY_STATS_SCREEN: Byte = 0x06

        /** With [GROUP_CONTROL] and [PARAMETER_LATEST]. */
        const val LATEST_BOLUS: Byte = 0x21

        /** With [GROUP_CONTROL] and [PARAMETER_LATEST]. */
        const val TBR_HISTORY: Byte = 0x27
    }

    object ObjectType {

        const val ACK: Byte = 0x55
        const val REJECTED: Byte = 0xA5.toByte()
        const val BOLUS_PROGRESS: Byte = 0xA0.toByte()
        const val EXTENDED_BOLUS_PROGRESS: Byte = 0xA1.toByte()
        const val BOLUS_COMPLETED: Byte = 0xAA.toByte()
        const val STATUS_V1: Byte = 0x00
        const val BOLUS_RECORD: Byte = 0x01
        const val ALARM_RECORD: Byte = 0x03
        const val REFILL_RECORD: Byte = 0x04
        const val DAILY_STATS: Byte = 0x06
        const val TBR_ACTIVE: Byte = 0x0A
        const val TBR_FINISHED: Byte = 0x0B
        const val STATUS_V2: Byte = 0x0C
        const val LATEST_BOLUS: Byte = 0x21
        const val TBR_RECORD: Byte = 0x27
    }

    /** One raw unit of a dose, U, or of a rate, U/h. */
    const val DOSE_SCALE = 0.025

    /** One raw unit of the reservoir, U. */
    const val RESERVOIR_SCALE = 0.001

    /** One raw unit of the battery, V. */
    const val BATTERY_VOLTAGE_SCALE = 0.01

    /** Half hour rates in a basal profile. */
    const val BASAL_SLOTS = 48
    const val BASAL_SLOT_SECONDS = 1800

    /** Basal profiles the pump stores. */
    const val PROFILE_COUNT = 8
}
