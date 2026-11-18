package app.aaps.pump.apex.protocol

import kotlin.math.abs

/** Groups, modes, opcodes, object types and scales of the Apex protocol. */
object ApexProtocol {

    /** Request group for control commands and targeted reads. */
    const val GROUP_CONTROL: Byte = 0x35

    /** Request group for whole histories. */
    const val GROUP_QUERY: Byte = 0x55

    /** Request mode, and answer frame id, of control commands. */
    const val MODE_CONTROL: Byte = 0xA1.toByte()

    /** Request mode, and answer frame id, of reads. */
    const val MODE_HISTORY: Byte = 0xA3.toByte()

    /**
     * Frame id of the heartbeat, `A5/<period>`: the one frame the pump sends by itself, every
     * [heartbeat period][ControlOpcode.SET_HEARTBEAT] minutes, whatever else goes on, its object
     * byte the period. It answers nothing and carries no state; it is told from other frames by its size.
     *
     * The heartbeat is the driver's anchor. It comes on second 0 of the pump's clock, and is the
     * only signal by which the pump's clock is checked and corrected. Its arrival says the pump
     * is alive and its cycle runs, and that the pump has closed the past minute: its minute
     * calculations, its journal writes, the temporary basal and basal windows that ended with it.
     * The driver reconciles its copy of the pump only in answer to the heartbeat, and nothing
     * else of the driver is on the air while it does.
     */
    const val MODE_HEARTBEAT: Byte = 0xA5.toByte()

    /** The heartbeat frame is eight bytes, though it declares six. */
    const val HEARTBEAT_FRAME_SIZE = 8

    /** Second byte of [ControlOpcode.SET_HEARTBEAT]: the heartbeat runs. */
    const val HEARTBEAT_ON: Byte = 0x00

    /** Second byte of [ControlOpcode.SET_HEARTBEAT]: the heartbeat stops until it is turned on again. The driver never sends it. */
    const val HEARTBEAT_OFF: Byte = 0x01

    /** The most frames an alias read answers with; such a read is complete by its frame count. */
    const val ALIAS_ANSWER_FRAMES = 10

    /** Parameter of a periodic read: the latest records. */
    const val PARAMETER_LATEST: Byte = 0x01

    /** Parameter of the bolus journal read with [GROUP_QUERY]: the last record alone. */
    const val PARAMETER_LAST_RECORD: Byte = 0x00

    object ControlOpcode {

        const val WRITE_BASAL_PROFILE: Byte = 0x00
        const val START_TBR: Byte = 0x02
        const val SWITCH_PROFILE: Byte = 0x04
        const val CANCEL_TBR: Byte = 0x05
        const val BOLUS: Byte = 0x12
        const val SET_CLOCK: Byte = 0x31
        const val WRITE_SETTINGS: Byte = 0x32
        /**
         * The heartbeat: two bytes, the period in minutes and [HEARTBEAT_ON] or [HEARTBEAT_OFF].
         * A new period rebuilds the grid from the last heartbeat,
         * the next one coming at last heartbeat + period, past slots skipped; the same period
         * changes nothing; off stops the heartbeat; on after a long stop counts from the command,
         * on right after off changes nothing. The pump does not always answer a period of 2 and
         * carries it out all the same. The object byte of the heartbeat frame is the period.
         */
        const val SET_HEARTBEAT: Byte = 0x33

        /** A new Bluetooth password, see `ApexBtPassword.changePayload`. The pump drops the link after it. */
        const val SET_BT_PASSWORD: Byte = 0x35

        /** Sent with [GROUP_QUERY]; with [GROUP_CONTROL] the same opcode is [START_TBR]. */
        const val CANCEL_BOLUS: Byte = 0x02
    }

    object TbrPayload {

        const val MODE_ABSOLUTE: Byte = 0x01

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

    /** Reads of the journals; each is answered under the object of the same number. */
    object HistoryOpcode {

        /** With [GROUP_QUERY]. */
        const val BOLUS_HISTORY: Byte = 0x01

        /** With [GROUP_CONTROL] and [PARAMETER_LATEST]. */
        const val LATEST_BOLUS: Byte = 0x21

        /** With [GROUP_CONTROL] and [PARAMETER_LATEST]. */
        const val TBR_HISTORY: Byte = 0x27

        /** Every day the pump holds; with [GROUP_QUERY]. */
        const val DAILY_STATS: Byte = 0x06

        /** The newest days, ten at most, answered under this object; with [GROUP_CONTROL] and [PARAMETER_LATEST]. */
        const val LATEST_DAILY_STATS: Byte = 0x26
    }

    /** Answers that come without a read of their own number. */
    object ObjectType {

        const val ACK: Byte = 0x55
        const val REJECTED: Byte = 0xA5.toByte()
        const val BOLUS_PROGRESS: Byte = 0xA0.toByte()
        const val EXTENDED_BOLUS_PROGRESS: Byte = 0xA1.toByte()
        const val BOLUS_COMPLETED: Byte = 0xAA.toByte()
    }

    /** One raw unit of a dose, U, or of a rate, U/h. */
    const val DOSE_SCALE = 0.025

    /** The same dose or rate on the pump's scale. */
    fun sameDose(units: Double, otherUnits: Double): Boolean = abs(units - otherUnits) < DOSE_SCALE / 2

    /** [units] rounded to the pump's step. */
    fun onStep(units: Double): Double = Math.round(units / DOSE_SCALE) * DOSE_SCALE

    /** Slack for doses held as doubles, U: two sums of the same steps can differ by this much. */
    const val ROUNDING = 1e-6

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
