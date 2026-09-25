package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const

/**
 * Decoded handshake answer, object `0x31`.
 *
 * Eight bytes of data, of which six are decoded:
 *
 * ```
 * header | 2 bytes, not decoded | firmware, 4 bytes | protocol, 2 bytes | crc
 * ```
 *
 * The firmware is four bytes, least significant part first, so `00 01 01 01` reads 1.1.1.0, as the
 * pump's own screen shows it. The protocol pair is major then minor, 4.12 for the protocol this
 * driver implements.
 */
data class Atc3Version(
    /** Firmware version, most significant part first, four parts as the pump displays it. */
    val firmware: List<Int>,
    /** Protocol major version, 4 for protocol 4.12. */
    val protocolMajor: Int,
    /** Protocol minor version, 12 for protocol 4.12. */
    val protocolMinor: Int,
    /** The two undecoded bytes in front of the firmware, kept for the log. */
    val unknown: List<Int>
) {

    /** Firmware as the pump shows it, `1.1.1.0`. */
    val firmwareText: String get() = firmware.joinToString(".")

    /** Protocol as major.minor, `4.12`. */
    val protocolText: String get() = "$protocolMajor.$protocolMinor"

    /**
     * Whether this firmware is [other] or newer, comparing part by part.
     *
     * A missing part counts as zero, so a shorter version compares as if padded with zeroes.
     */
    fun isAtLeast(other: List<Int>): Boolean {
        for (i in 0 until maxOf(firmware.size, other.size)) {
            val mine = firmware.getOrElse(i) { 0 }
            val theirs = other.getOrElse(i) { 0 }
            if (mine != theirs) return mine > theirs
        }
        return true
    }

    companion object {

        fun decode(frame: Atc3ResponseFrame): Atc3Version? {
            if (frame.objectType != Atc3Const.ReadOpcode.VERSION) return null
            if (!frame.has(Atc3Const.Version.PROTOCOL, 2)) return null
            return Atc3Version(
                firmware = (Atc3Const.Version.FIRMWARE_PARTS - 1 downTo 0)
                    .map { frame.byteAt(Atc3Const.Version.FIRMWARE + it) },
                protocolMajor = frame.byteAt(Atc3Const.Version.PROTOCOL),
                protocolMinor = frame.byteAt(Atc3Const.Version.PROTOCOL + 1),
                unknown = (0 until Atc3Const.Version.FIRMWARE - Atc3Const.Version.UNKNOWN)
                    .map { frame.byteAt(Atc3Const.Version.UNKNOWN + it) }
            )
        }
    }
}
