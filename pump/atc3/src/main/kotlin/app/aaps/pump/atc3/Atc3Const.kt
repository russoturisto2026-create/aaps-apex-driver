package app.aaps.pump.atc3

/**
 * Protocol constants for ATC3, protocol version 4.12.
 *
 * The tables describe the protocol, not only what the driver sends. Some entries are not
 * referenced: [ReadOpcode.TBR_FINISHED], [FinishedTbr], [HistoryOpcode.DAILY_RECORD],
 * [ControlOpcode.EXTENDED_BOLUS] and [ControlOpcode.DUAL_BOLUS] describe parts of the protocol the
 * driver does not use, and each says so where it is declared.
 */
object Atc3Const {

    /** Request group for control commands and targeted queries. */
    const val GROUP_CONTROL: Byte = 0x35

    /** Request group for full data set queries. */
    const val GROUP_QUERY: Byte = 0x55

    /** Request mode for control commands, acknowledged with object 0x55. */
    const val MODE_CONTROL: Byte = 0xA1.toByte()

    /** Request mode for history and statistics. */
    const val MODE_HISTORY: Byte = 0xA3.toByte()

    /**
     * Frame id of the heartbeat the pump sends unbidden, eight bytes.
     *
     * It answers no request and carries no state, only liveness. It arrives every
     * [HEARTBEAT_PERIOD_MINUTES] on the same second of the minute whether or not anything else is
     * happening, which lets a link held open confirm that the pump is still there without asking.
     *
     * It shares the byte 0xA5 with [ObjectType.REJECTED], which is an object type rather than a
     * frame id, so the two are told apart by which field carries the number. The alarm history
     * arrives on this frame id too, with the same object byte; only the size tells them apart, see
     * [HEARTBEAT_FRAME_SIZE].
     */
    const val MODE_HEARTBEAT: Byte = 0xA5.toByte()

    /**
     * Minutes between two heartbeats.
     *
     * The pump keeps the period across links and takes it from whichever client last set it, see
     * [ControlOpcode.SET_HEARTBEAT], so the driver sets it on every link rather than assuming it.
     */
    const val HEARTBEAT_PERIOD_MINUTES = 3

    /** Object byte of the heartbeat frame: the pump writes its heartbeat period, in minutes, there. */
    const val HEARTBEAT_OBJECT: Byte = HEARTBEAT_PERIOD_MINUTES.toByte()

    /** Size of the heartbeat frame on the wire, header and crc included. */
    const val HEARTBEAT_FRAME_SIZE = 8

    /**
     * How many frames the periodic bolus search answers with at most.
     *
     * The answer to [HistoryOpcode.LATEST_BOLUS] stops at ten frames however many records the pump
     * holds, while its count byte counts all of them. The last record flag therefore never appears
     * on this answer once more than ten records are stored, and the frame count, not the flag, says
     * that the answer is complete.
     *
     * A property of the request rather than of any object, so it sits here and not among the
     * object types.
     */
    const val PERIODIC_BOLUS_FRAMES = 10

    /**
     * Frames a `+0x20` alias answers with at most, however many records it declares.
     *
     * The same cap as [PERIODIC_BOLUS_FRAMES], shared by the whole alias family: `0x22`, `0x23`,
     * `0x24`, `0x26` and `0x27` all stop here, so a read of one of them finishes by counting
     * frames rather than by waiting for a record flagged as the last.
     */
    const val ALIAS_BURST_FRAMES = PERIODIC_BOLUS_FRAMES

    /** Control opcodes, all sent with [GROUP_CONTROL] and [MODE_CONTROL]. */
    object ControlOpcode {

        /** Replace all 48 half hour rates of the active profile. */
        const val WRITE_BASAL_PROFILE: Byte = 0x00

        /** Start an absolute temporary basal. */
        const val START_TBR: Byte = 0x02

        /** Select the active profile, payload is a single profile index. */
        const val SWITCH_PROFILE: Byte = 0x04

        /** Write the whole settings block, payload is [Settings.PAYLOAD_LENGTH] bytes. */
        const val WRITE_SETTINGS: Byte = 0x32

        /**
         * Stop or resume delivery. Payload is a single byte, `01` to stop and `00` to resume.
         *
         * While stopped, the scheduled basal field of Status V1 reads `0xFFFF`. **Stopping the
         * pump stops a running bolus as well**, which is why a bolus is cancelled with its own
         * command, [CANCEL_BOLUS], instead.
         */
        const val SUSPEND: Byte = 0x21

        /**
         * Set the pump clock. Payload is six binary bytes in the pump's own clock format.
         *
         * The same opcode read with [MODE_HISTORY] is a different request, see
         * [ReadOpcode.VERSION]; the mode byte tells them apart.
         */
        const val SET_CLOCK: Byte = 0x31

        /** Cancel a running temporary basal, no payload. */
        const val CANCEL_TBR: Byte = 0x05

        /** Standard bolus. Payload is the amount as uint16 little endian, then one zero byte. */
        const val BOLUS: Byte = 0x12

        /**
         * Extended bolus. Payload is the amount as uint16 little endian, then the duration as
         * uint16 little endian in units of 15 minutes.
         *
         * The driver does not send this: it offers no extended bolus. It is named so that the
         * records and progress frames of an extended bolus given on the pump are read correctly.
         */
        const val EXTENDED_BOLUS: Byte = 0x13

        /**
         * Dual bolus. Payload is the immediate amount, the extended amount and the duration, each
         * uint16 little endian, the duration in units of 15 minutes.
         *
         * Not sent by the driver, for the same reason as [EXTENDED_BOLUS].
         */
        const val DUAL_BOLUS: Byte = 0x14

        /**
         * Replace the pump's Bluetooth password. Payload is six bytes, one per decimal digit of
         * the new password plus 65536, see [app.aaps.pump.atc3.comm.Atc3BtPassword].
         *
         * The pump answers with an ordinary acknowledgement and then restarts its Bluetooth stack:
         * the link drops, and the next connection has to discover the services again.
         */
        const val SET_BT_PASSWORD: Byte = 0x35

        /**
         * Set the period of the pump's heartbeat. Payload: the period in minutes, then one zero byte.
         *
         * The pump echoes the period as the object byte of every heartbeat frame that follows, so a
         * period other than [HEARTBEAT_PERIOD_MINUTES] would make the heartbeat unrecognisable to a
         * client expecting the object byte alone. Answered with an acknowledgement and nothing else.
         */
        const val SET_HEARTBEAT: Byte = 0x33

        /**
         * Cancel a running bolus. Payload is two zero bytes.
         *
         * Sent with [GROUP_QUERY], not [GROUP_CONTROL]. The opcode alone is ambiguous: the same
         * `0x02` with [GROUP_CONTROL] starts a temporary basal, so the group byte is what tells
         * the two apart.
         */
        const val CANCEL_BOLUS: Byte = 0x02
    }

    /**
     * Temporary basal payload.
     *
     * ```
     * offset 0     mode, 0x01 for an absolute rate
     * offset 1     duration in units of 15 minutes
     * offset 2..3  rate, uint16 little endian, 0.025 U/h per raw unit
     * ```
     */
    object TbrPayload {

        const val MODE_ABSOLUTE: Byte = 0x01

        /**
         * The other mode the pump has: the rate field then carries whole percent of the scheduled
         * basal instead of a rate.
         *
         * The driver never sends this, AAPS asks for absolute rates. The pump's own keypad offers
         * it, so a percentage temporary basal can come back in [ActiveTbr] and has to be told apart
         * from an absolute one there.
         */
        const val MODE_PERCENT: Byte = 0x00

        /** Minutes covered by one unit of the duration byte. */
        const val DURATION_UNIT_MINUTES = 15
    }

    /**
     * Read opcodes, sent with [GROUP_CONTROL] and [MODE_HISTORY].
     *
     * Each answer echoes the opcode as its object type.
     */
    object ReadOpcode {

        /** Status V1, the main pump state frame. */
        const val STATUS_V1: Byte = 0x00

        /** Status V2. */
        const val STATUS_V2: Byte = 0x0C

        /** All stored basal profiles, answered with one frame per profile. */
        const val BASAL_PROFILES: Byte = 0x08

        /** Running temporary basal, answered with record count 0 when none is running. */
        const val TBR_ACTIVE: Byte = 0x0A

        /**
         * Last finished temporary basal.
         *
         * Says when a temporary basal ended and which of three ways ended it. Without it the end is
         * the moment of the poll that noticed, up to a poll interval late. The result byte is the
         * only signal that a temporary basal was stopped on the pump's keypad.
         */
        const val TBR_FINISHED: Byte = 0x0B

        /**
         * Last temporary basal command in short form, object `0x09`.
         *
         * Carries the same command as [TBR_ACTIVE] plus the minutes elapsed, which `0x0A` does not,
         * and its rate field is always an absolute rate even in percentage mode, where `0x0A`
         * carries the percentage itself.
         *
         * It answers whether or not a temporary basal is running, so it is not a "running" flag:
         * once nothing runs, duration and elapsed read zero while the rate keeps its last value.
         * Status V1 offset 53 is the authority on that.
         */
        const val TBR_SHORT: Byte = 0x09

        /**
         * The pump's own bolus calculator settings, object `0x07`.
         *
         * The loop needs none of it, AAPS carries its own carb ratio, sensitivity and target. It is
         * read to show the same three quantities as the pump holds them, so that a difference
         * between the two can be seen.
         */
        const val BOLUS_CALCULATOR: Byte = 0x07

        /**
         * Handshake answered with the pump's firmware and protocol versions.
         *
         * Sent once per connection, because the firmware version decides whether the pump can be
         * asked for a Bluetooth password at all. Everything else works without it.
         *
         * See [Version] for the answer's layout.
         */
        const val VERSION: Byte = 0x31
    }

    /** History and statistics opcodes, sent with [MODE_HISTORY]. */
    object HistoryOpcode {

        /** With [GROUP_QUERY], the full bolus history, answered with object 0x01. */
        const val BOLUS_HISTORY: Byte = 0x01

        /** With [GROUP_QUERY], the daily statistics, answered with object 0x06. */
        const val DAILY_STATS_SCREEN: Byte = 0x06

        /**
         * With [GROUP_QUERY], the alarm history, answered with object 0x03.
         *
         * The record of every alarm the pump has raised, and the only place an occlusion is
         * reported at all: the piston pressure itself is never sent. What is active at this moment
         * is in Status V1 instead, [StatusV1.ALARM_SLOT_FIRST].
         */
        const val ALARM_HISTORY: Byte = 0x03

        /** With [GROUP_CONTROL], the periodic latest bolus search, answered with object 0x21. */
        const val LATEST_BOLUS: Byte = 0x21

        /**
         * With [GROUP_CONTROL], the periodic daily record, answered with object 0x26.
         *
         * Not sent. It carries the same fields in the same shape and scale as
         * [DAILY_STATS_SCREEN], which is the one the driver reads daily totals from.
         */
        const val DAILY_RECORD: Byte = 0x26

        /**
         * With [GROUP_QUERY], the basal change history, answered with object 0x02.
         *
         * One record per moment the effective basal schedule changed, each carrying the whole
         * schedule as it stood right after the change. The base object is asked for rather than
         * its `0x22` alias, because an alias stops at ten frames however many records exist.
         */
        const val BASAL_CHANGE_HISTORY: Byte = 0x02

        /**
         * With [GROUP_QUERY], the reservoir refill history, answered with object 0x04.
         *
         * The only place the date a reservoir was started is reported, and the only record a
         * catheter prime leaves: the insulin a prime uses appears in neither the bolus history nor
         * the bolus field of the daily statistics, though it does leave the reservoir.
         */
        const val REFILL_HISTORY: Byte = 0x04

        /**
         * With [GROUP_CONTROL] and [PARAMETER_LATEST], the temporary basal history, object 0x27.
         *
         * One record per temporary basal that has finished, carrying what it actually delivered.
         * It behaves like the `+0x20` alias family, answering at parameter `0x01` and stopping at
         * ten frames, but has no base counterpart: `0x07` is the bolus calculator, not a history.
         */
        const val TBR_HISTORY: Byte = 0x27
    }

    /** Object types carried by response frames. */
    object ObjectType {

        /** Control command accepted. Not proof that the command took effect. */
        const val ACK: Byte = 0x55

        /**
         * Control command refused by the pump, for example a bolus above the pump's own maximum.
         *
         * A refusal has to be recognised, otherwise the driver waits out its timeout and reports a
         * communication problem for what is really a rejected command.
         */
        const val REJECTED: Byte = 0xA5.toByte()

        /** Bolus progress, carries the cumulative delivered amount. */
        const val BOLUS_PROGRESS: Byte = 0xA0.toByte()

        /**
         * Extended bolus progress, carries the cumulative amount delivered by the extended part.
         *
         * The extended part of a bolus reports on this object only; [BOLUS_PROGRESS] does not
         * appear while it runs.
         */
        const val EXTENDED_BOLUS_PROGRESS: Byte = 0xA1.toByte()

        /** Bolus completed, carries the final delivered amount. */
        const val BOLUS_COMPLETED: Byte = 0xAA.toByte()

        /** Status V1, the main pump state frame. */
        const val STATUS_V1: Byte = 0x00

        /** Status V2. */
        const val STATUS_V2: Byte = 0x0C

        /** One stored basal profile, 48 half hour rates. */
        const val BASAL_PROFILE: Byte = 0x08

        /** Active temporary basal including the amount delivered so far. */
        const val TBR_ACTIVE: Byte = 0x0A

        /** Finished temporary basal including its outcome. */
        const val TBR_FINISHED: Byte = 0x0B

        /** A bolus history entry. */
        const val BOLUS_RECORD: Byte = 0x01

        /**
         * An alarm history entry.
         *
         * It shares its number with the heartbeat, which is why a frame's object type alone never
         * identifies it: the heartbeat carries frame id [REJECTED], `0xA5`, and this arrives on
         * [MODE_HISTORY].
         */
        const val ALARM_RECORD: Byte = 0x03

        /** The latest bolus, returned by the periodic search. */
        const val LATEST_BOLUS: Byte = 0x21

        /** Daily totals, periodic form. */
        const val DAILY_RECORD: Byte = 0x26

        /** Daily totals, statistics form. */
        const val DAILY_STATS: Byte = 0x06

        /** A basal change history entry, the whole schedule as it stood after one change. */
        const val BASAL_CHANGE_RECORD: Byte = 0x02

        /** A reservoir refill history entry. */
        const val REFILL_RECORD: Byte = 0x04

        /** A temporary basal history entry, one per temporary basal that has finished. */
        const val TBR_RECORD: Byte = 0x27

        /** Last temporary basal command, short form. */
        const val TBR_SHORT: Byte = 0x09

        /** The pump's own bolus calculator settings. */
        const val BOLUS_CALCULATOR: Byte = 0x07
    }

    /**
     * Response data offsets inside a Status V1 frame.
     *
     * Response data offset equals frame offset minus 4. These constants use the response data base,
     * matching [app.aaps.pump.atc3.comm.Atc3ResponseFrame]; the frame offset is given alongside.
     */
    object StatusV1 {

        /** Active profile index. Frame offset 18. */
        const val ACTIVE_PROFILE = 14

        /**
         * Time of the status snapshot, six binary bytes year(+2000) month day hour minute second.
         * Frame offset 50.
         *
         * Not the pump's clock: it is when the pump last rebuilt this frame, once a minute and on
         * anything that changes the status. No clock drift may be read out of it. It gives the
         * moment the other fields of the frame belong to.
         */
        const val CLOCK = 46

        /** Reservoir, uint24 little endian, 0.001 U per raw unit. Frame offset 58. */
        const val RESERVOIR = 54

        /**
         * Delivered today, uint16 little endian, [DOSE_SCALE] per raw unit. Frame offset 22.
         *
         * Basal and bolus together, counted as the pump delivers them, and the one field that says
         * whether insulin is going in at all: it stands still while the pump delivers nothing, even
         * when every other field reports a running pump. Object `0x06` breaks the same number down
         * by bolus, basal and temporary basal, and the three add up to it exactly.
         *
         * It belongs to the snapshot, like everything in this frame: the value is the one at
         * [CLOCK], not at the moment of the read. The reservoir in the same snapshot does not move
         * in step with it, the two can sit one or two steps apart in either direction, so a
         * comparison finer than a step uses this field alone, against a figure worked out for the
         * same snapshot moment.
         */
        const val DELIVERED_TODAY = 18

        /**
         * Hour and minute of the last stop, two binary bytes. Frame offsets 84..85.
         *
         * Moved only by an explicit stop of the pump; a stop by alarm leaves it alone, and a pump
         * restart puts it back to `00 00`. To the minute only: the second of the stop is in the
         * snapshot clock when the snapshot was rebuilt by the stop itself, and nowhere else.
         */
        const val LAST_STOP = 80

        /** Whole minutes the running temporary basal has been going, uint16. Frame offset 92. */
        const val TBR_ELAPSED = 88

        /** Temporary basal active flag. */
        const val TBR_ACTIVE = 53

        /**
         * First active alarm slot: the alarm code, then a flag. Frame offsets 62..63.
         *
         * Both slots read `00 00` while nothing is active. The first fills when an alarm becomes
         * active and the second when a second one joins it, and a slot clears when its condition
         * is cleared.
         */
        const val ALARM_SLOT_FIRST = 58

        /** Second active alarm slot, the same encoding. Frame offsets 64..65. */
        const val ALARM_SLOT_SECOND = 60

        /** Currently scheduled basal rate, uint16 little endian. Frame offset 82. */
        const val SCHEDULED_BASAL = 78

        /** Temporary basal rate, uint16 little endian. */
        const val TBR_RATE = 82

        /** Temporary basal mode. */
        const val TBR_MODE = 84

        /** Temporary basal duration in minutes, uint16 little endian. */
        const val TBR_DURATION = 86

        // The settings block mirrored into the status frame, see [Settings]. Counted on the
        // frame offset base of the settings write these are four more than here.

        /** Alarm signal type. Frame offset 7. */
        const val ALARM_SIGNAL_TYPE = 3

        /** Bolus speed, non zero for the low speed. Frame offset 8. */
        const val LOW_BOLUS_SPEED = 4

        /** Screen brightness level. Frame offset 9. */
        const val BRIGHTNESS = 5

        /** Bolus types allowed, the payload byte shifted up one bit. Frame offset 10. */
        const val BOLUS_TYPES = 6

        /** Keypad lock. Frame offset 11. */
        const val KEYPAD_LOCK = 7

        /** Auto off. Frame offset 12. */
        const val AUTO_OFF = 8

        /** Auto off delay in whole hours. Frame offset 13. */
        const val AUTO_OFF_HOURS = 9

        /** Low insulin threshold in whole units. Frame offset 14. */
        const val LOW_INSULIN_UNITS = 10

        /** Low insulin threshold in half hours. Frame offset 15. */
        const val LOW_INSULIN_HALF_HOURS = 11

        /** Basal patterns. Frame offset 16. */
        const val BASAL_PATTERNS = 12

        /**
         * Whether the pump is locked, `01` while it is and `00` when it is not. Frame offset 17.
         *
         * Not part of the mirrored settings block, despite sitting among them: the keypad lock
         * switch at [KEYPAD_LOCK] is a setting the user turns on, this is the state the pump is in
         * right now. A locked pump refuses control commands.
         */
        const val LOCKED = 13

        /** Daily dose limit switch. Frame offset 19. */
        const val DAILY_LIMIT_ENABLED = 15

        /** Screen timeout in tenths of a second, uint16 little endian. Frame offset 20. */
        const val SCREEN_TIMEOUT = 16

        /** Daily dose limit in whole units, uint16 little endian. Frame offset 26. */
        const val DAILY_LIMIT_UNITS = 22

        /** Maximum basal rate, uint16 little endian, 0.025 U/h per raw unit. Frame offset 30. */
        const val MAX_BASAL = 26

        /** Maximum bolus, uint16 little endian, 0.025 U per raw unit. Frame offset 32. */
        const val MAX_BOLUS = 28

        /** Pump language, non zero for English. Frame offset 56. */
        const val LANGUAGE = 52
    }

    /**
     * The pump settings block, written whole by [ControlOpcode.WRITE_SETTINGS] as a 16 byte
     * payload.
     *
     * Status V1 mirrors every field but one, which is what lets the driver read the pump's own
     * settings before it changes any of them.
     *
     * ```
     * offset 0      switches, one bit each
     * offset 1      alarm signal type
     * offset 2      screen brightness level
     * offset 3      auto off delay, whole hours
     * offset 4      low insulin threshold, whole units
     * offset 5      low insulin threshold, half hours
     * offset 6      bit 0 extended bolus allowed, bit 1 blood glucose reminder
     * offset 7      alarm signal duration
     * offset 8..9   screen timeout, tenths of a second, uint16 little endian
     * offset 10..11 daily dose limit, whole units, uint16 little endian
     * offset 12..13 maximum basal rate, uint16 little endian, 0.025 U/h per raw unit
     * offset 14..15 maximum bolus, uint16 little endian, 0.025 U per raw unit
     * ```
     */
    object Settings {

        const val PAYLOAD_LENGTH = 16

        const val SWITCHES = 0
        const val ALARM_SIGNAL_TYPE = 1
        const val BRIGHTNESS = 2
        const val AUTO_OFF_HOURS = 3
        const val LOW_INSULIN_UNITS = 4
        const val LOW_INSULIN_HALF_HOURS = 5
        const val BOLUS_TYPES = 6
        const val ALARM_DURATION = 7
        const val SCREEN_TIMEOUT = 8
        const val DAILY_LIMIT_UNITS = 10
        const val MAX_BASAL = 12
        const val MAX_BOLUS = 14

        /** Set for the low bolus speed, clear for the normal one. */
        const val BIT_LOW_BOLUS_SPEED = 0x01

        const val BIT_KEYPAD_LOCK = 0x02
        const val BIT_AUTO_OFF = 0x04
        const val BIT_BASAL_PATTERNS = 0x08
        const val BIT_DAILY_LIMIT = 0x20

        /** Set for English, clear for Russian. */
        const val BIT_ENGLISH = 0x40

        const val BOLUS_TYPE_EXTENDED = 0x01
        const val BOLUS_TYPE_BG_REMINDER = 0x02

        /**
         * How far Status V1 shifts the bolus type bits up: payload `01` reads back as `02` and
         * payload `02` as `04`.
         */
        const val BOLUS_TYPES_STATUS_SHIFT = 1

        /** Screen brightness percentages, in the order the level byte indexes them. */
        val BRIGHTNESS_PERCENTS = intArrayOf(10, 30, 50, 60, 80, 100)

        /**
         * What each editable field will accept.
         *
         * These are the bounds the pump's own screens offer, so that a value entered here is one
         * the pump would have let the user enter on it.
         */
        const val MAX_BOLUS_CEILING = 30.0
        const val MAX_BASAL_FALLBACK_CEILING = 25.0
        const val LOW_INSULIN_UNITS_MAX = 100
        const val LOW_INSULIN_HOURS_MAX = 24.0
        const val DAILY_LIMIT_UNITS_MAX = 500
        const val SCREEN_TIMEOUT_SECONDS_MIN = 5
        const val SCREEN_TIMEOUT_SECONDS_MAX = 300
        const val SCREEN_TIMEOUT_SECONDS_STEP = 5

        /** Auto off delay range, whole hours. */
        const val AUTO_OFF_HOURS_MIN = 1
        const val AUTO_OFF_HOURS_MAX = 7

        /** One raw unit of the screen timeout, in seconds. */
        const val SCREEN_TIMEOUT_SCALE = 0.1

        /** One raw unit of the low insulin time threshold, in hours. */
        const val LOW_INSULIN_HOUR_SCALE = 0.5

        /** Alarm signal type values. */
        const val ALARM_TYPE_SOUND = 0
        const val ALARM_TYPE_VIBRATION = 1
        const val ALARM_TYPE_BOTH = 2

        /** Alarm signal duration values. */
        const val ALARM_DURATION_LONG = 0
        const val ALARM_DURATION_NORMAL = 1
        const val ALARM_DURATION_SHORT = 2
    }

    /** Response data offsets inside a Status V2 frame. */
    object StatusV2 {

        /**
         * Active insulin (insulin on board), uint16 little endian, [DOSE_SCALE] per raw unit.
         * Frame offset 6.
         *
         * The pump's own figure: it rises after each bolus and decays by one raw unit every few
         * minutes, the same value the pump shows on its status screen.
         */
        const val ACTIVE_INSULIN = 2

        /** Battery voltage, hundredths of a volt. Frame offset 9. */
        const val BATTERY_VOLTAGE = 5
    }

    /** Response data offsets inside the handshake answer, object [ReadOpcode.VERSION]. */
    object Version {

        /** Two bytes that are not decoded, `00 00`. Frame offsets 6..7. */
        const val UNKNOWN = 2

        /**
         * Firmware version, [FIRMWARE_PARTS] bytes, least significant part first. Frame offsets
         * 8..11: `00 01 01 01` is firmware `1.1.1.0`.
         */
        const val FIRMWARE = 4

        /** How many parts the firmware version has. */
        const val FIRMWARE_PARTS = 4

        /**
         * Protocol version, major then minor. Frame offsets 12..13.
         *
         * `04 0c`, 4.12, is the protocol this driver implements.
         */
        const val PROTOCOL = 8
    }

    /**
     * The firmware from which the pump has a Bluetooth password at all.
     *
     * Older firmware has no password menu and no authorisation service, so its link cannot be
     * protected by any means the driver has; the only cure is a firmware update.
     */
    val PASSWORD_FIRMWARE = listOf(1, 1, 1, 0)

    /**
     * The oldest firmware this driver will work with. Older firmware has no Bluetooth password at
     * all: anything within radio range can command the pump, and nothing the driver does can change
     * that. Such a pump is refused, and the user is told to have the firmware updated.
     */
    val MINIMUM_FIRMWARE = listOf(1, 1, 1)

    /** One raw unit of the battery reading, in volts. */
    const val BATTERY_VOLTAGE_SCALE = 0.01

    /**
     * Voltage at which the battery charge is shown as empty.
     *
     * The pump sends volts and nothing else, so the percentage is worked out here: a straight
     * line of 3.5 % per hundredth of a volt, zero at this voltage and a hundred at about 1.52 V,
     * clamped at both ends.
     */
    const val BATTERY_EMPTY_VOLTS = 1.2305

    /** How many percent the charge scale moves per volt. */
    const val BATTERY_PERCENT_PER_VOLT = 350.0

    /**
     * Response data offsets inside a running temporary basal, object `0x0A`.
     *
     * Counted from the start of the payload, which begins one byte after the response data
     * base, these are two less than here.
     */
    object ActiveTbr {

        /** When the temporary basal started, six binary clock bytes. Payload offset 0. */
        const val START_CLOCK = 2

        /**
         * The same six clock bytes a second time. Payload offset 7.
         *
         * Normally identical to [START_CLOCK], except that the pump sometimes leaves the first
         * copy empty and fills only this one, which makes it the only start time there is. See
         * [app.aaps.pump.atc3.comm.Atc3TbrStatus.decode].
         */
        const val START_CLOCK_REPEAT = 9

        /**
         * Duration, uint16 little endian, in units of [TbrPayload.DURATION_UNIT_MINUTES].
         * Payload offset 14.
         *
         * The same encoding the start command uses: a 30 minute temporary basal reads back as 2.
         */
        const val DURATION = 16

        /**
         * Mode, [TbrPayload.MODE_ABSOLUTE] or [TbrPayload.MODE_PERCENT]. Payload offset 13.
         *
         * It has to be read before [RATE], because it says what that field means.
         */
        const val MODE = 15

        /**
         * Rate or percentage, uint16 little endian. Payload offset 16.
         *
         * [DOSE_SCALE] per raw unit under [TbrPayload.MODE_ABSOLUTE], but whole percent under
         * [TbrPayload.MODE_PERCENT]: 111 % reads `6F 00`, which taken for a rate would be
         * 2.775 U/h. See [MODE].
         */
        const val RATE = 18

        /** Amount delivered so far, uint16 little endian, [DOSE_SCALE] per raw unit. Payload offset 18. */
        const val DELIVERED = 20
    }

    /**
     * How the last temporary basal ended, object `0x0B`.
     *
     * Status V1 says that a temporary basal is over, but not when it ended nor which of the three
     * ways ended it. Both are here, and the end clock is the moment it actually ended rather than
     * the moment the driver next looked.
     */
    object FinishedTbr {

        /** The result byte. Payload offset 0. */
        const val RESULT = 2

        /** It ran for the whole duration it was started for. */
        const val RESULT_COMPLETED = 0x01

        /** Stopped on the pump's own keypad. */
        const val RESULT_CANCELLED_ON_PUMP = 0x02

        /** Stopped by a command over the link, which is what this driver's cancel sends. */
        const val RESULT_CANCELLED_BY_COMMAND = 0x03

        /** When it started, six binary clock bytes. Payload offset 1. */
        const val START_CLOCK = 3

        /**
         * The same six clock bytes a second time. Payload offset 7.
         *
         * Read for the same reason as [ActiveTbr.START_CLOCK_REPEAT].
         */
        const val START_CLOCK_REPEAT = 9

        /** Mode, [TbrPayload.MODE_ABSOLUTE] or [TbrPayload.MODE_PERCENT]. Payload offset 13. */
        const val MODE = 15

        /** Duration, uint16, in units of [TbrPayload.DURATION_UNIT_MINUTES]. Payload offset 14. */
        const val DURATION = 16

        /**
         * Rate or percentage, uint16. Payload offset 16.
         *
         * [DOSE_SCALE] per raw unit under [TbrPayload.MODE_ABSOLUTE], whole percent otherwise,
         * exactly as in [ActiveTbr.RATE] and [TbrRecord.RATE].
         */
        const val RATE = 18

        /** When it ended, six binary clock bytes. Payload offset 18. */
        const val END_CLOCK = 20

        /** What it delivered altogether, uint16, [DOSE_SCALE] per raw unit. Payload offset 24. */
        const val DELIVERED = 26
    }

    /**
     * Response data offsets inside a temporary basal history record, object `0x27`, 22 bytes.
     *
     * One record per temporary basal that has finished. Unlike [FinishedTbr], which holds only the
     * most recent one, this is the journal: it answers what rates actually ran between two polls,
     * which the reservoir arithmetic cannot answer on its own.
     *
     * The field that matters is [DELIVERED]: what the temporary basal actually put in, so a gap in
     * the driver's own accounting is closed from the pump's figure rather than reconstructed from
     * rates and durations.
     */
    object TbrRecord {

        /** When it started, six binary clock bytes. Payload offset 0. */
        const val START_CLOCK = 2

        /** Mode, [TbrPayload.MODE_ABSOLUTE] or [TbrPayload.MODE_PERCENT]. Payload offset 6. */
        const val MODE = 8

        /** Duration, uint16, in units of [TbrPayload.DURATION_UNIT_MINUTES]. Payload offset 8. */
        const val DURATION = 10

        /**
         * Rate or percentage, uint16. Payload offset 10.
         *
         * [DOSE_SCALE] per raw unit under [TbrPayload.MODE_ABSOLUTE], whole percent otherwise.
         */
        const val RATE = 12

        /** What it delivered, uint16, [DOSE_SCALE] per raw unit. Payload offset 12. */
        const val DELIVERED = 14
    }

    /**
     * Response data offsets inside a basal change history record, object `0x02`, 110 bytes.
     *
     * One record per moment the effective basal schedule changed, carrying the whole schedule as
     * it stood right after the change. Not one per day: a single day can carry several.
     *
     * This is the only place a change to the rates of the *active* profile leaves a trace. Status
     * V1 says which profile is active, and object `0x08` says what the profiles hold now, but
     * neither says that the one in use was edited.
     */
    object BasalChange {

        /** When the schedule changed, six binary clock bytes, to the minute. Payload offset 0. */
        const val CHANGE_CLOCK = 2

        /** First of the 48 half hour rates, uint16 each, [DOSE_SCALE] per raw unit. Payload offset 6. */
        const val FIRST_RATE = 8
    }

    /**
     * Response data offsets inside a reservoir refill history record, object `0x04`, 18 bytes.
     *
     * The only place the date a reservoir was started is reported, and the only trace a catheter
     * prime leaves: the insulin a prime uses shows up in neither the bolus history nor the bolus
     * field of the daily statistics, though it does leave the reservoir. Delivery added up from
     * the bolus history alone is therefore short by every prime.
     *
     * It is also what tells a real refill from a reservoir level that merely went up, which the
     * level alone cannot.
     */
    object Refill {

        /** When it was refilled, six binary clock bytes. Payload offset 0. */
        const val REFILL_CLOCK = 2

        /**
         * How much went in, uint16, [DOSE_SCALE] per raw unit. Payload offset 6.
         *
         * Zero is a valid manual refill of nothing rather than an anomaly.
         */
        const val AMOUNT = 8

        /** [TYPE_PRIME] or [TYPE_MANUAL], uint16. Payload offset 8. */
        const val TYPE = 10

        /** A catheter prime, recorded here and nowhere else. */
        const val TYPE_PRIME = 0

        /** A manual reservoir refill. */
        const val TYPE_MANUAL = 1
    }

    /**
     * Response data offsets inside the short form of the last temporary basal command, `0x09`.
     *
     * @see ReadOpcode.TBR_SHORT for what separates it from object `0x0A`.
     */
    object TbrShort {

        /**
         * Rate, uint16, [DOSE_SCALE] per raw unit. Payload offset 0.
         *
         * Always an absolute rate, percentage mode included, where it holds the rate the
         * percentage worked out to. Object `0x0A` carries the percentage itself instead.
         */
        const val RATE = 2

        /** Mode, uint16, [TbrPayload.MODE_ABSOLUTE] or [TbrPayload.MODE_PERCENT]. Payload offset 2. */
        const val MODE = 4

        /** Duration, uint16, in units of [TbrPayload.DURATION_UNIT_MINUTES]. Payload offset 4. */
        const val DURATION = 6

        /** Whole minutes since it started, uint16. Payload offset 6. */
        const val ELAPSED = 8
    }

    /**
     * Response data offsets inside the bolus calculator settings, object `0x07`, 254 bytes.
     *
     * Six tables of twelve slots, three quantities in two unit systems each. Two rules decide
     * whether the numbers read out of them mean anything:
     *
     * **A slot whose start is [PARKED_START] is not in use.** Its value is left over from an
     * earlier edit. The slots in use come first and the parked ones follow.
     *
     * **The members of a pair are independent settings, not conversions of one another.** A pump
     * can hold 4.0 mmol/l in one and 50 mg/dl in the other at the same moment, and those are
     * different sensitivities. The unit flags say which one is in force; nothing may convert one
     * into the other.
     */
    object BolusCalculator {

        /** Whether the calculator is switched on. Payload offset 0. */
        const val ENABLED = 2

        /** [FLAG_BREAD_UNITS] and [FLAG_MGDL]. Payload offset 1. */
        const val UNIT_FLAGS = 3

        /** Set: carbohydrates in bread units. Clear: grams. */
        const val FLAG_BREAD_UNITS = 0x01

        /** Set: glucose in mg/dl. Clear: mmol/l. */
        const val FLAG_MGDL = 0x10

        /** How long insulin goes on working, uint16, minutes. Payload offset 2. */
        const val ACTIVE_INSULIN_MINUTES = 4

        /** Carb ratio in whole grams per unit, twelve uint16 pairs. Payload offset 4. */
        const val TABLE_CARBS_GRAMS = 6

        /** Carb ratio in units per bread unit, twelve uint16 pairs. Payload offset 52. */
        const val TABLE_CARBS_BREAD_UNITS = 54

        /** Sensitivity in mmol/l, twelve uint16 pairs. Payload offset 100. */
        const val TABLE_SENSITIVITY_MMOL = 102

        /** Sensitivity in whole mg/dl, twelve uint16 pairs. Payload offset 148. */
        const val TABLE_SENSITIVITY_MGDL = 150

        /** Target glucose in mmol/l, twelve single byte pairs. Payload offset 196. */
        const val TABLE_TARGET_MMOL = 198

        /** Target glucose in whole mg/dl, twelve single byte pairs. Payload offset 220. */
        const val TABLE_TARGET_MGDL = 222

        /**
         * CRC-16/Modbus of payload 0..243, little endian. Payload offset 244.
         *
         * The object's own checksum, inside the frame's.
         */
        const val PAYLOAD_CRC = 246

        /** Slots in a table, in use or parked. */
        const val SLOTS = 12

        /** A start of 48 half hours, that is 24:00, marks a slot that is not in use. */
        const val PARKED_START = 48

        /** One raw unit of table 2, units of insulin per bread unit. */
        const val BREAD_UNIT_SCALE = 0.05

        /** One raw unit of tables 3 and 5, mmol/l. */
        const val MMOL_SCALE = 0.1
    }

    /** Response data offsets inside a daily statistics object 0x06. */
    object DailyStats {

        const val BOLUS = 2
        const val BASAL = 4
        const val TBR = 6
        const val DATE = 8
    }

    /** One raw unit of dose or rate, in U or U/h. */
    const val DOSE_SCALE = 0.025

    /** One raw unit of the reservoir reading, in U. */
    const val RESERVOIR_SCALE = 0.001

    /** Number of half hour slots in a basal profile. */
    const val BASAL_SLOTS = 48

    /** Seconds covered by one basal slot. */
    const val BASAL_SLOT_SECONDS = 1800

    /** Number of basal profiles stored in the pump. */
    const val PROFILE_COUNT = 8

    /**
     * The pump profile slot the driver keeps AAPS's basal schedule in.
     *
     * AAPS owns the schedule, the pump is only storage for it. Switching between profiles stored
     * in the pump is not offered: an AAPS profile carries a percentage and a time shift that a
     * fixed pump slot cannot express, and the loop has to know exactly which rates are running.
     *
     * A basal write always lands in whichever profile is active, so the driver makes this slot
     * active before writing.
     */
    const val DRIVER_PROFILE_INDEX = 0

    /**
     * How many times a refused Bluetooth password is retried before the driver stops asking.
     *
     * A refusal is a definite answer, not a transient failure: the six digits the driver holds are
     * not the ones on the pump's screen, and retrying does not change that. Ten is enough to ride
     * out anything that only looks like a refusal, and few enough that a wrong password costs a
     * couple of minutes of radio rather than the rest of the day.
     */
    const val AUTH_MAX_ATTEMPTS = 10

    /**
     * How long to wait for the pump to answer a request, milliseconds.
     *
     * Covers the slowest answers, bursts of history records that take up to about three seconds,
     * three times over. A request left unanswered holds the queue for this long.
     */
    const val COMMAND_TIMEOUT_MS = 9_000L

    /**
     * How long to wait between checks that a control command has taken effect, milliseconds.
     *
     * The pump acknowledges within about a third of a second but its status reflects the change
     * only a couple of seconds later. Reading the status immediately would report the old value
     * and make a successful command look like a failure.
     */
    const val EFFECT_POLL_INTERVAL_MS = 1_500L

    /**
     * How long a command may go on trying before it gives the queue back, milliseconds.
     *
     * A decision about how long the queue may be held rather than a property of the pump. The
     * queue is serial, so everything else AAPS wants to do waits behind whatever is running, and
     * the loop decides every five minutes; thirty seconds is a tenth of that. A temporary basal
     * that takes on the first attempt is done in about two seconds, so the budget is there for the
     * command that does not take.
     */
    const val COMMAND_BUDGET_MS = 30_000L

    /** How many times a command may be sent before the driver gives up on it. */
    const val COMMAND_SEND_ATTEMPTS = 3

    /**
     * How many times the pump's state is read after one send before sending again.
     *
     * A command that took normally shows in the status within one read. Three reads, spaced
     * [EFFECT_POLL_INTERVAL_MS] apart and each taking up to a couple of seconds of its own, cover
     * some nine seconds.
     */
    const val COMMAND_CHECK_ATTEMPTS = 3

    /** How many times to re-read the status while waiting for a control command to take effect. */
    const val EFFECT_POLL_ATTEMPTS = 8

    // Reconciliation, see app.aaps.pump.atc3.history


    /**
     * How far apart two of the driver's own boluses must start, milliseconds.
     *
     * **Not a therapy limit.** It exists so that the pump's two records can be told apart. A bolus
     * is matched to the pump's record on the dose asked for and on the minute the bolus started,
     * which is the minute the pump stamps the record with. A minute from the start of one bolus to
     * the start of the next puts them, and so their records, in different minutes.
     *
     * The loop's own microboluses are already spaced by AAPS. A bolus a person enters by hand is
     * not, which is the case this is here for. A bolus given on the pump's own keypad or by
     * another client is not spaced by anything the driver does, and this cannot help there.
     */
    const val BOLUS_SPACING_MS = 60_000L

    /**
     * How long the periodic cycle may go without reading the bolus history, milliseconds.
     *
     * The floor under [app.aaps.pump.atc3.history.Atc3BolusHistoryGate]. Twenty minutes is three or
     * four status cycles: long enough to take most of the reads away, and short enough that a bolus
     * given on the keypad, or a record the pump withheld through an alarm, is still found while it
     * matters.
     */
    const val BOLUS_HISTORY_FLOOR_MS = 20 * 60 * 1000L

    /**
     * How stale a Status V1 snapshot may be, milliseconds.
     *
     * The pump answers with a snapshot rather than a reading, up to a minute old, see
     * [app.aaps.pump.atc3.comm.Atc3StatusV1]. Two of them can therefore be a minute of delivery out
     * of step with one another, which is what the reservoir comparison has to allow for.
     */
    const val STATUS_SNAPSHOT_AGE_MS = 60 * 1000L

    /**
     * How far the books may be out before a bolus without the history being read first, units.
     *
     * The read in front of a bolus is what refuses to stack a microbolus on insulin the pump was
     * given without the loop's knowledge, so it is only skipped while the reservoir agrees with what
     * the driver expected to within this. A tenth of a unit is four pump steps: wide enough that the
     * pulse timing of a running basal cannot trip it, narrow enough that a bolus worth refusing over
     * cannot hide under it.
     */
    const val BOLUS_BASELINE_TOLERANCE = 0.1

    /** How far back a record may be and still be imported, milliseconds. */
    const val RECONCILE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** How many records the ledger remembers. The pump returns at most ten per answer. */
    const val SEEN_CAPACITY = 40

    /**
     * How long a temporary basal the driver did not start is recorded for, milliseconds.
     *
     * Used while the driver does not read the duration the pump reports in object `0x0A`: the
     * record is written this far ahead, extended on every poll while the pump still reports it, and
     * closed as soon as the pump stops.
     *
     * It has to outlast the interval AAPS polls at, which is a quarter of an hour when the loop is
     * changing nothing (`KeepAliveWorker.STATUS_UPDATE_FREQUENCY`). A shorter record would lapse
     * between two polls, and AAPS would go back to counting the profile basal while the pump was
     * still running something else.
     */
    const val TBR_HORIZON_MS = 20 * 60 * 1000L

    /**
     * How long a pump suspension is recorded for, milliseconds.
     *
     * A stopped pump reaches AAPS as a temporary basal of zero, and that record must not expire by
     * itself: if it did, AAPS would go back to counting the profile basal as delivered while the
     * pump is still delivering nothing. So it is written long and closed explicitly when the pump
     * reports that it is running again.
     *
     * A day is far past the point where AAPS would have raised a pump unreachable alarm, so it
     * cannot lapse unnoticed, and while polling continues each poll pushes it further out anyway.
     */
    const val SUSPEND_HORIZON_MS = 24 * 60 * 60 * 1000L

    /**
     * How many history reads in a row may put our own boluses a minute off before the pump's clock
     * is written.
     *
     * The pump stamps a bolus record with the minute the bolus started, at second 59, and the driver
     * writes the pump's clock with seconds, so right after a write every record of ours sits in the
     * minute it started in. A read whose records of our boluses sit a minute early or late counts
     * one miss, a read that matches exactly clears the count, and at this many the clock is written.
     *
     * See `history/Atc3ClockWatch.kt`.
     */
    const val CLOCK_JOURNAL_MISSES_TO_SET = 2

    /**
     * The three bands of the pump's clock against the phone's. The snapshot is not a clock and
     * nothing takes its time; it is only compared with the phone's at the moment it was read, and
     * it can be [STATUS_SNAPSHOT_AGE_MS] old, which each band allows for.
     *
     * Up to five minutes apart: the clock is put right quietly -- when AAPS starts, when the
     * loop's running mode changes, every [CLOCK_SYNC_EVERY_MS], and on the trigger the bolus
     * history gives, two reads in a row that find our boluses a minute off, see
     * [CLOCK_JOURNAL_MISSES_TO_SET]. Never further than that without the user being told: a phone
     * switched on in another timezone, or one whose time was changed, must not move the pump's
     * clock in silence.
     */
    const val CLOCK_QUIET_CORRECTION_MS = 5 * 60 * 1000L + STATUS_SNAPSHOT_AGE_MS

    /** How often the pump's clock is put on the phone's while the two are close, milliseconds. */
    const val CLOCK_SYNC_EVERY_MS = 8 * 60 * 60 * 1000L

    /** The least between two clock writes asked for by the loop's mode changing, milliseconds. */
    const val CLOCK_SYNC_MIN_GAP_MS = 5 * 60 * 1000L

    /**
     * Between five minutes and this apart: the clock is put right at once, and the user is told.
     * From fifty five minutes on the driver writes nothing, raises an alarm and stops the loop.
     * The phone's own change of a whole hour, daylight saving or a timezone, therefore stops the
     * loop too, and the pump's clock is set by hand.
     */
    const val CLOCK_MAX_CORRECTION_MS = 55 * 60 * 1000L

    /**
     * How old the driver's knowledge of the pump's boluses may be before a command re-reads it.
     *
     * AAPS cannot deliver insulin without connecting to the pump: the command queue finishes
     * connecting before it takes the first command. Reading the history at the start of every
     * command that changes delivery therefore means nothing is ever delivered on knowledge older
     * than this.
     *
     * Four minutes sits just under the loop's five minute cycle, so a command in one cycle does not
     * reuse what was read in the previous one, while several commands in the same cycle still cost
     * a single read.
     */
    const val HISTORY_FRESH_MS = 4 * 60 * 1000L

    /**
     * How stale the driver's picture of what the pump is *delivering* may be, milliseconds.
     *
     * Separate from [HISTORY_FRESH_MS] because the two are learned by different reads: a command
     * reads the bolus history on its way past, which makes the history fresh without anything
     * looking at the temporary basal or at whether the pump is stopped.
     *
     * Four minutes sits just under the loop's five minute cycle, so the state is re-read once per
     * cycle, while a burst of glucose values arriving together still costs one read.
     */
    const val STATUS_FRESH_MS = 4 * 60 * 1000L

    /**
     * How stale the state may get before glucose alone is reason enough to read it, milliseconds.
     *
     * The state is normally re-read when the loop has finished deciding, so that the read travels
     * in the same connection as whatever the loop then asks for. That only works while the loop
     * produces a decision; this is the fallback for glucose values that produce none. Longer than
     * [STATUS_FRESH_MS], so it does not pre-empt the read that would have shared a connection, and
     * far shorter than the quarter of an hour AAPS's own keepalive waits. A status is one exchange
     * of about 200 ms, and five minutes is the cadence the whole cycle is designed around.
     */
    const val STATUS_STALE_MS = 5 * 60 * 1000L

    /**
     * How often the battery voltage is worth an exchange of its own, milliseconds.
     *
     * Status V2 carries nothing else the driver uses, and a battery moves on the scale of days.
     */
    const val BATTERY_READ_INTERVAL_MS = 60 * 60 * 1000L

    /**
     * How often the alarm history is worth an exchange of its own, milliseconds.
     *
     * The pump announces nothing when an alarm fires, so an alarm is only ever found by asking.
     * Status V1 already says what is being raised right now and costs nothing extra, and the
     * history is read whenever it says something is up. This interval is for the other case: an
     * alarm that came and went between two polls, which the slots never showed. Half an hour
     * bounds how long such an alarm can stay out of the AAPS history without spending an exchange
     * per cycle on a list that usually has not changed.
     */
    const val ALARM_READ_INTERVAL_MS = 30 * 60 * 1000L

    /**
     * How long after the link comes up the pump ignores the first request, milliseconds.
     *
     * For about the first second and a half after the GATT link comes up the pump is not
     * listening yet: a request written then is acknowledged by the Bluetooth stack all the same,
     * so nothing below this layer notices, but the pump never answers it. The first request of a
     * connection is therefore held back until this long after the link came up.
     *
     * The driver connects once per loop cycle, because that is what the AAPS command queue does
     * with every pump, so it meets this window on every connection.
     *
     * Three seconds leaves a margin over the edge. Being wrong on the low side costs a request that
     * sits out its whole [COMMAND_TIMEOUT_MS] and has to be sent again; this costs at most a second
     * and a half added to a connection, and nothing on a connection whose setup already ran past
     * the window.
     */
    const val FIRST_REQUEST_SETTLE_MS = 3_000L

    /** How many times to re-read the history looking for the record of the bolus just delivered. */
    const val BOLUS_RECORD_POLL_ATTEMPTS = 4

    /**
     * Parameter byte carried at request offset 5 by the opcodes that take one, the latest bolus
     * and the daily record among them.
     */
    const val PARAMETER_LATEST: Byte = 0x01
}
