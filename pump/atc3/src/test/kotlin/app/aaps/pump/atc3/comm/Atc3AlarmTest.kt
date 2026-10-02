package app.aaps.pump.atc3.comm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Alarm decoding, against alarm history and Status V1 frames.
 */
class Atc3AlarmTest {

    private fun frame(hex: String) = Atc3ResponseFrame(
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }
    )

    @Test
    fun `decodes the occlusion record of 2026-09-04 14 10`() {
        val record = Atc3AlarmRecord.decode(frame("AA100CA303001A09040E0A000800A64C"))

        assertThat(record).isNotNull()
        assertThat(record!!.code).isEqualTo(8)
        assertThat(record.alarm).isEqualTo(Atc3Alarm.NO_DELIVERY)
        assertThat(record.index).isEqualTo(0)
    }

    @Test
    fun `names every code the pump raises`() {
        assertThat(Atc3Alarm.ofCode(1)).isEqualTo(Atc3Alarm.LOW_BATTERY)
        assertThat(Atc3Alarm.ofCode(2)).isEqualTo(Atc3Alarm.BLOOD_GLUCOSE_REMINDER)
        assertThat(Atc3Alarm.ofCode(3)).isEqualTo(Atc3Alarm.BUTTON_ERROR)
        assertThat(Atc3Alarm.ofCode(8)).isEqualTo(Atc3Alarm.NO_DELIVERY)
        assertThat(Atc3Alarm.ofCode(13)).isEqualTo(Atc3Alarm.RESERVOIR_EMPTY)
    }

    @Test
    fun `keeps a code it has no name for rather than dropping the record`() {
        assertThat(Atc3Alarm.ofCode(99)).isNull()

        // The same frame with code 99 in place of 8, so the record still decodes and carries it.
        val record = Atc3AlarmRecord.decode(frame("AA100CA303001A09040E0A006300A64C"))

        assertThat(record).isNotNull()
        assertThat(record!!.code).isEqualTo(99)
        assertThat(record.alarm).isNull()
    }

    @Test
    fun `refuses the heartbeat, which carries the same object number`() {
        // AA 06 00 A5 03 00 80 C2 — object 0x03 like an alarm record, frame id 0xA5 unlike one.
        assertThat(Atc3AlarmRecord.decode(frame("AA0600A5030080C2"))).isNull()
    }

    @Test
    fun `refuses a frame of another object`() {
        // A bolus record, object 0x01.
        assertThat(Atc3AlarmRecord.decode(frame("AA1680A301011A09040E093B5000340000000000FC8A"))).isNull()
    }

    @Test
    fun `decodes a whole answer of thirteen records`() {
        val answer = listOf(
            "AA100DA303001A09040E0D0008005AFB", "AA100DA303011A09040E0A000800561F",
            "AA100DA303021A09030D16000D000398", "AA100DA303031A09030D15000D000E4C",
            "AA100DA303041A09030D0E0003002AF8", "AA100DA303051A09030C2F0002001104",
            "AA100DA303061A08181331000D0080C5", "AA100DA303071A08180631000D008096",
            "AA100DA303081A08180630000D00C09A", "AA100DA303091A08180436000100B142",
            "AA100DA3030A1A08180124000D0069CA", "AA100DA3030B1A0817130C000800D656",
            "AA100DA3030C1A08171135000300820A"
        ).mapNotNull { Atc3AlarmRecord.decode(frame(it)) }

        assertThat(answer).hasSize(13)
        assertThat(answer.map { it.code }).containsExactly(
            8, 8, 13, 13, 3, 2, 13, 13, 13, 1, 13, 8, 3
        ).inOrder()
        // Records come newest first, so the timestamps fall as the list runs.
        assertThat(answer.map { it.pumpClockUtcSeconds }).isInStrictOrder(reverseOrder<Long>())
    }
}
