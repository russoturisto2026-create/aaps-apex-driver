package app.aaps.pump.atc3.protocol

/** The payloads of the control commands, built from the amounts the driver wants. */
object Atc3Payload {

    /** A bolus of [units]. */
    fun bolus(units: Double): ByteArray = uint16(raw(units)) + 0x00

    /** An absolute temporary basal of [rate] U/h for [quarterHours] quarter hours. */
    fun absoluteTbr(rate: Double, quarterHours: Int): ByteArray =
        byteArrayOf(Atc3Protocol.TbrPayload.MODE_ABSOLUTE, quarterHours.toByte()) + uint16(raw(rate))

    /** The half hour rates of a basal profile, in order. */
    fun basalProfile(rates: DoubleArray): ByteArray =
        rates.fold(ByteArray(0)) { payload, rate -> payload + uint16(raw(rate)) }

    /** Stop the pump, or resume it. */
    fun suspend(suspended: Boolean): ByteArray = byteArrayOf(if (suspended) 1 else 0)

    private fun raw(amount: Double): Int = Math.round(amount / Atc3Protocol.DOSE_SCALE).toInt()

    private fun uint16(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
}
