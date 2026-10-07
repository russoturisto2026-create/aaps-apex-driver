package app.aaps.pump.atc3.link

/** The pump's Bluetooth password: presented on every link, and changed by a command. The pump shows it and never sends it. */
object Atc3BtPassword {

    /** How many digits the pump shows and expects. */
    const val DIGITS = 6

    /** What a pump without a password expects. */
    const val NONE = "000000"

    /** The pump's answer to an accepted password. */
    const val ACCEPTED: Byte = 0x00

    /** The pump's answer to a refused one. */
    const val REJECTED: Byte = 0x01

    /** What the change command adds to the password it carries. */
    const val CHANGE_OFFSET = 65_536
    const val MIN_SETTABLE = 0

    /** The highest password the change command can carry. */
    const val MAX_SETTABLE = 999_999 - CHANGE_OFFSET

    /** Whether [text] is a password the pump could be showing. */
    fun isValid(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.length == DIGITS && trimmed.all { it.isDigit() }
    }

    /** Whether the change command can set [value]. */
    fun isSettable(value: Int): Boolean = value in MIN_SETTABLE..MAX_SETTABLE

    /**
     * What is written to present [password].
     *
     * @throws IllegalArgumentException if [password] is not six digits
     */
    fun authValue(password: String): ByteArray {
        val trimmed = password.trim()
        require(isValid(trimmed)) { "ATC3 Bluetooth password must be $DIGITS digits" }
        return (trimmed + trimmed).toByteArray(Charsets.US_ASCII)
    }

    /**
     * The payload of the change command for [value]. The pump may end up holding the payload read
     * literally instead, see [candidatesFor].
     *
     * @throws IllegalArgumentException if [value] is outside [MIN_SETTABLE]..[MAX_SETTABLE]
     */
    fun changePayload(value: Int): ByteArray {
        require(isSettable(value)) { "ATC3 Bluetooth password must be $MIN_SETTABLE..$MAX_SETTABLE" }
        val digits = (value + CHANGE_OFFSET).toString().padStart(DIGITS, '0')
        return ByteArray(DIGITS) { (digits[it] - '0').toByte() }
    }

    /** The passwords the pump may hold after a change asked for [value], likeliest first; presented in turn. */
    fun candidatesFor(value: Int): List<String> = listOf(format(value), format(value + CHANGE_OFFSET))

    /** [value] as the pump shows it. */
    fun format(value: Int): String = value.toString().padStart(DIGITS, '0')
}
