package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const

/**
 * The pump's Bluetooth password, as it travels and as it is changed.
 *
 * The pump shows the current value on its own status screen, on the line labelled `B/P`, and never
 * sends it over the air. A client that does not present it is refused: the pump answers the write
 * that enables notifications with `Write Not Permitted`, and refuses writes to the ATC3 write
 * characteristic the same way, so nothing at all can be done without it.
 */
object Atc3BtPassword {

    /** How many digits the pump shows and expects. */
    const val DIGITS = 6

    /** The password a pump without one expects. */
    const val NONE = "000000"

    /** Answer byte on the authorisation characteristic when the password was accepted. */
    const val ACCEPTED: Byte = 0x00

    /** Answer byte when it was refused. */
    const val REJECTED: Byte = 0x01

    /**
     * What the change command adds to the password before sending it: the payload `05 05 03 01 04 09`
     * sets the password `487613`, the payload digits less 65536.
     *
     * A change is not trusted to have set what it asked for, see [changePayload].
     */
    const val CHANGE_OFFSET = 65_536

    /** Lowest password the change command can produce. */
    const val MIN_SETTABLE = 0

    /**
     * Highest password the change command can produce.
     *
     * The payload carries six decimal digits, so the value it encodes cannot exceed 999999, and the
     * password is that value less [CHANGE_OFFSET]. Anything above this simply has no payload that
     * would ask for it.
     */
    const val MAX_SETTABLE = 999_999 - CHANGE_OFFSET

    /** Whether [text] is a password the pump could be showing. */
    fun isValid(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.length == DIGITS && trimmed.all { it.isDigit() }
    }

    /** Whether the change command can set [value] at all, see [MAX_SETTABLE]. */
    fun isSettable(value: Int): Boolean = value in MIN_SETTABLE..MAX_SETTABLE

    /**
     * The value written to the authorisation characteristic: the six digits, twice, in ASCII.
     *
     * `000000000000` when the pump has no password, and the six shown digits repeated when it has
     * one.
     *
     * @throws IllegalArgumentException if [password] is not six digits
     */
    fun authValue(password: String): ByteArray {
        val trimmed = password.trim()
        require(isValid(trimmed)) { "ATC3 Bluetooth password must be $DIGITS digits" }
        return (trimmed + trimmed).toByteArray(Charsets.US_ASCII)
    }

    /**
     * Payload for [Atc3Const.ControlOpcode.SET_BT_PASSWORD]: one byte per decimal digit of
     * `value + CHANGE_OFFSET`.
     *
     * The pump does not always end up with [value]: it can take the payload read literally instead,
     * so that a change asking for 123456 leaves the pump holding 188992. The driver therefore does
     * not assume: it keeps [candidatesFor] and lets the pump pick.
     *
     * @throws IllegalArgumentException if [value] is outside [MIN_SETTABLE]..[MAX_SETTABLE]
     */
    fun changePayload(value: Int): ByteArray {
        require(isSettable(value)) { "ATC3 Bluetooth password must be $MIN_SETTABLE..$MAX_SETTABLE" }
        val digits = (value + CHANGE_OFFSET).toString().padStart(DIGITS, '0')
        return ByteArray(DIGITS) { (digits[it] - '0').toByte() }
    }

    /**
     * The passwords the pump may be holding after a change that asked for [value], likeliest first.
     *
     * The first is the value asked for. The second is the payload read literally, which the pump
     * can end up holding instead. Presenting them in turn costs one refused connection in the rare
     * case and saves a link that would otherwise stay down until somebody read the number off the
     * pump.
     */
    fun candidatesFor(value: Int): List<String> = listOf(format(value), format(value + CHANGE_OFFSET))

    /** [value] as the pump will show it, zero padded to [DIGITS]. */
    fun format(value: Int): String = value.toString().padStart(DIGITS, '0')
}
