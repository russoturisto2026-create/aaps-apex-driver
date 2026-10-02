package app.aaps.pump.atc3.ui

import app.aaps.pump.atc3.comm.Atc3Frame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Atc3ScanActivityTest {

    @Test
    fun `the advertised name is the identity block as text`() {
        assertEquals("${Atc3Frame.IDENTITY_PREFIX}12345678", Atc3ScanActivity.advertisedNameFor("12345678"))
    }

    @Test
    fun `the pump with the entered serial matches`() {
        assertTrue(Atc3ScanActivity.matchesSerial("${Atc3Frame.IDENTITY_PREFIX}12345678", "12345678"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertTrue(Atc3ScanActivity.matchesSerial("  ${Atc3Frame.IDENTITY_PREFIX}12345678 ", "12345678"))
    }

    /** The whole point of the screen: another pump in range must not be selectable. */
    @Test
    fun `another pump does not match`() {
        assertFalse(Atc3ScanActivity.matchesSerial("${Atc3Frame.IDENTITY_PREFIX}12345679", "12345678"))
        assertFalse(Atc3ScanActivity.matchesSerial("${Atc3Frame.IDENTITY_PREFIX}87654321", "12345678"))
    }

    @Test
    fun `devices that are not this pump do not match`() {
        assertFalse(Atc3ScanActivity.matchesSerial("My Headphones", "12345678"))
        assertFalse(Atc3ScanActivity.matchesSerial("${Atc3Frame.IDENTITY_PREFIX}1234567", "12345678"))     // too short
        assertFalse(Atc3ScanActivity.matchesSerial("${Atc3Frame.IDENTITY_PREFIX}123456789", "12345678"))   // too long
        assertFalse(Atc3ScanActivity.matchesSerial("atc312345678", "12345678"))    // wrong case
        assertFalse(Atc3ScanActivity.matchesSerial("", "12345678"))
    }

    /** Nothing is listed until the serial is complete, so a partial serial matches nothing. */
    @Test
    fun `an incomplete serial matches nothing`() {
        assertFalse(Atc3ScanActivity.matchesSerial("${Atc3Frame.IDENTITY_PREFIX}1234567", "1234567"))
        assertFalse(Atc3ScanActivity.matchesSerial("${Atc3Frame.IDENTITY_PREFIX}", ""))
        assertFalse(Atc3ScanActivity.matchesSerial("${Atc3Frame.IDENTITY_PREFIX}1234567x", "1234567x"))
    }
}
