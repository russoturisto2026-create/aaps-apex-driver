package app.aaps.pump.apex.bolus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JournalOverlayTest {

    /** A record identity standing for a bolus of [dose] in minute [minute]; the same pair twice is the same identity. */
    private fun id(minute: Int, dose: Int): ByteArray = byteArrayOf(minute.toByte(), dose.toByte())

    @Test
    fun `the records above the overlap are new, and only they`() {
        // The copy, oldest first: six records. The answer, newest first: three new ones over the top five of the copy.
        val kept = listOf(id(1, 5), id(2, 5), id(3, 11), id(4, 5), id(5, 10), id(6, 11))
        val answer = listOf(id(9, 11), id(8, 5), id(7, 11), id(6, 11), id(5, 10), id(4, 5), id(3, 11), id(2, 5))
        assertEquals(3, JournalOverlay.newOnTop(answer, kept))
    }

    @Test
    fun `a record that repeats an older one by stamp and dose is still new by its place`() {
        // The clock was put back an hour: the newest record has the same minute and dose as one an hour older.
        val kept = listOf(id(60, 11), id(64, 11), id(68, 11), id(112, 5), id(115, 10), id(118, 5), id(122, 11), id(125, 5))
        val answer = listOf(id(68, 11), id(125, 5), id(122, 11), id(118, 5), id(115, 10), id(112, 5), id(68, 11), id(64, 11), id(60, 11))
        assertEquals(1, JournalOverlay.newOnTop(answer, kept))
    }

    @Test
    fun `nothing new when the answer repeats the top of the copy`() {
        val kept = listOf(id(1, 5), id(2, 5), id(3, 11), id(4, 5), id(5, 10), id(6, 11))
        val answer = listOf(id(6, 11), id(5, 10), id(4, 5), id(3, 11), id(2, 5), id(1, 5))
        assertEquals(0, JournalOverlay.newOnTop(answer, kept))
    }

    @Test
    fun `an empty journal kept - every record of the answer is new`() {
        val answer = listOf(id(2, 5), id(1, 5))
        assertEquals(2, JournalOverlay.newOnTop(answer, emptyList()))
    }

    @Test
    fun `an answer that does not show the newest record kept is not laid over the copy`() {
        // Ten new records since the last read: the answer of ten repeats nothing.
        val kept = listOf(id(1, 5), id(2, 5), id(3, 11), id(4, 5), id(5, 10), id(6, 11))
        val answer = (16 downTo 7).map { id(it, 5) }
        assertNull(JournalOverlay.newOnTop(answer, kept))
    }

    @Test
    fun `the newest record kept at the bottom of the answer is enough`() {
        val kept = listOf(id(1, 5), id(2, 5), id(3, 11), id(4, 5), id(5, 10), id(6, 11))
        // Nine new records over the newest one kept.
        val answer = (15 downTo 7).map { id(it, 5) } + listOf(id(6, 11))
        assertEquals(9, JournalOverlay.newOnTop(answer, kept))
    }

    @Test
    fun `a record below the top that differs from the copy lays nothing over it`() {
        val kept = listOf(id(1, 5), id(2, 5), id(3, 11), id(4, 5), id(5, 10), id(6, 11))
        // The newest record kept is there, the one under it is not what the copy holds: a bad answer.
        val answer = listOf(id(7, 5), id(6, 11), id(5, 99), id(4, 5))
        assertNull(JournalOverlay.newOnTop(answer, kept))
    }

    @Test
    fun `a short copy is repeated whole`() {
        // A pump with two records so far: the answer repeats both.
        val kept = listOf(id(1, 5), id(2, 5))
        val answer = listOf(id(3, 11), id(2, 5), id(1, 5))
        assertEquals(1, JournalOverlay.newOnTop(answer, kept))
    }
}
