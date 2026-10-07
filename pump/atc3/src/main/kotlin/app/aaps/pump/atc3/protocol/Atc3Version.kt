package app.aaps.pump.atc3.protocol

/** The pump's firmware and protocol versions. */
data class Atc3Version(
    /** Most significant part first, as the pump displays it. */
    val firmware: List<Int>,
    val protocolMajor: Int,
    val protocolMinor: Int,
    /** Two bytes not decoded, kept for the log. */
    val unknown: List<Int>
) {

    val firmwareText: String get() = firmware.joinToString(".")

    val protocolText: String get() = "$protocolMajor.$protocolMinor"

    /** Whether this firmware is [other] or newer; a missing part counts as zero. */
    fun isAtLeast(other: List<Int>): Boolean {
        for (i in 0 until maxOf(firmware.size, other.size)) {
            val mine = firmware.getOrElse(i) { 0 }
            val theirs = other.getOrElse(i) { 0 }
            if (mine != theirs) return mine > theirs
        }
        return true
    }

    internal object Offset {

        const val UNKNOWN = 2
        const val FIRMWARE = 4
        const val FIRMWARE_PARTS = 4
        const val PROTOCOL = 8
    }

    companion object {

        fun decode(frame: Atc3ResponseFrame): Atc3Version? {
            if (frame.objectType != Atc3Protocol.ReadOpcode.VERSION) return null
            if (!frame.has(Offset.PROTOCOL, 2)) return null
            return Atc3Version(
                firmware = (Offset.FIRMWARE_PARTS - 1 downTo 0)
                    .map { frame.byteAt(Offset.FIRMWARE + it) },
                protocolMajor = frame.byteAt(Offset.PROTOCOL),
                protocolMinor = frame.byteAt(Offset.PROTOCOL + 1),
                unknown = (0 until Offset.FIRMWARE - Offset.UNKNOWN)
                    .map { frame.byteAt(Offset.UNKNOWN + it) }
            )
        }
    }
}
