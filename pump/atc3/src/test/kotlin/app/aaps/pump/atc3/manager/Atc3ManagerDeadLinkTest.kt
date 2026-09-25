package app.aaps.pump.atc3.manager


import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.atc3.Atc3Pump
import app.aaps.pump.atc3.ble.Atc3BLE
import app.aaps.pump.atc3.history.Atc3ClockWatch
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * That a link nothing can be sent on is let go of, without waiting to be told.
 *
 * When the phone's Bluetooth goes away briefly, the stack can deliver no disconnection callback
 * at all, and the driver would go on believing it is connected until the pump's heartbeat had been
 * missing long enough to be questioned, minutes later. Meanwhile every write is refused by the
 * stack within milliseconds and written down as `not_sent`, and a run of those ends the link.
 *
 * The counting is what these tests pin, because both directions are wrong in their own way: too
 * eager and a good link is thrown away for a momentary refusal, costing a full setup to rebuild;
 * too patient and the pump stays unreachable for minutes with the evidence already in hand.
 */
class Atc3ManagerDeadLinkTest : TestBaseWithProfile() {

    @Mock lateinit var atc3BLE: Atc3BLE
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var trace: Atc3Trace

    private lateinit var manager: Atc3Manager

    @BeforeEach
    fun setup() {
        // No serial, so nothing can be built or sent and every exchange fails at the first step -
        // which is exactly the "reached nobody" outcome under test.
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("")
        whenever(atc3BLE.write(any())).thenReturn(false)
        whenever(atc3BLE.isConnected).thenReturn(true)
        manager = Atc3Manager(
            aapsLogger, rxBus, preferences, dateUtil, atc3BLE, Atc3Pump(),
            trace, uiInteraction, Atc3ClockWatch(), rh
        )
    }

    @Test
    fun `one exchange that reached nobody is not enough to give up a link`() {
        manager.readStatus()

        verify(atc3BLE, never()).disconnect()
    }

    @Test
    fun `two are still not enough`() {
        manager.readStatus()
        manager.readStatus()

        verify(atc3BLE, never()).disconnect()
    }

    @Test
    fun `three in a row end the link`() {
        manager.readStatus()
        manager.readStatus()
        manager.readStatus()

        verify(atc3BLE).disconnect()
    }

    /**
     * There is nothing to give up on. Calling disconnect on a link that is already down would
     * announce an ending that never happened, and [Atc3Manager.onDisconnected] releases every
     * waiting caller and tells AAPS the pump has gone.
     */
    @Test
    fun `a link that is already down is not dropped again`() {
        whenever(atc3BLE.isConnected).thenReturn(false)

        manager.readStatus()
        manager.readStatus()
        manager.readStatus()

        verify(atc3BLE, never()).disconnect()
    }

    /**
     * The number in the record is the number that happened.
     *
     * This line was written once with the counter already reset, so it always read "no answer to 0
     * exchanges" - a record that asserted something untrue about the one event it exists to
     * describe. Nothing caught it but a reading of the code, which is why it is pinned here.
     */
    @Test
    fun `the ending says how many exchanges reached nothing`() {
        repeat(3) { manager.readStatus() }

        verify(trace).event(
            eq(Atc3TraceCat.BLE),
            eq("link_dead"),
            eq("after" to 3),
            any()
        )
    }

    /**
     * A command on a link that is gone fails at once rather than spending its budget on sends
     * that reach nothing and reads that say nothing.
     */
    @Test
    fun `a command on a link that is down fails at once, sending nothing`() {
        whenever(atc3BLE.isConnected).thenReturn(false)

        val result = manager.setTempBasal(1.0, 30)

        verify(atc3BLE, never()).write(any())
        assert(result.failure == "the link is down") { result.failure.toString() }
        assert(manager.cancelTempBasal() == "the link is down")
    }

    @Test
    fun `the count starts again after the link has been given up on`() {
        repeat(3) { manager.readStatus() }
        verify(atc3BLE).disconnect()

        // Two more must not drop a second time: the run was consumed by the first.
        manager.readStatus()
        manager.readStatus()

        verify(atc3BLE).disconnect()
    }
}
