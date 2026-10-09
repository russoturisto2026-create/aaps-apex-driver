package app.aaps.pump.atc3.link

import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.verify

/** Disconnecting a pump forgets it whole and drops the link. */
class Atc3PairingTest : TestBaseWithProfile() {

    @Mock lateinit var pumpSync: PumpSync
    @Mock lateinit var atc3HistorySync: Atc3HistorySync
    @Mock lateinit var atc3Connection: Atc3Connection
    @Mock lateinit var commandQueue: CommandQueue

    private lateinit var pairing: Atc3Pairing

    @BeforeEach
    fun setup() {
        pairing = Atc3Pairing(
            aapsLogger, preferences, commandQueue, pumpSync, atc3HistorySync, Atc3PumpState(),
            { atc3Connection }, pumpEnactResultProvider
        )
    }

    @Test
    fun `disconnecting forgets the serial and the password, everything kept of the pump, and the link`() {
        pairing.disconnect()

        verify(preferences).put(Atc3StringKey.Atc3SerialNumber, "")
        verify(preferences).put(Atc3StringKey.Atc3BtPassword, "")
        verify(preferences).put(Atc3StringKey.Atc3Address, "")
        verify(atc3Connection).disconnect(any())
        verify(atc3HistorySync).forgetPump("")
    }
}
