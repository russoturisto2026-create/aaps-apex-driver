package app.aaps.pump.apex.link

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.command.ApexPair
import app.aaps.pump.apex.keys.ApexStringKey
import app.aaps.pump.apex.state.ApexPumpState
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Connecting a pump to AAPS and disconnecting it: its address, serial and password. A pump is
 * connected only if it answers: the three are stored for a first status read on the queue and taken
 * out again when it fails.
 */
@SingleIn(AppScope::class)
@Inject
class ApexPairing(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val commandQueue: CommandQueue,
    private val pumpSync: PumpSync,
    private val pumpState: ApexPumpState,
    private val store: ApexStore,
    // Lazy: the connection asks this class whether a pump is on trial.
    private val apexConnection: Lazy<ApexConnection>
) {

    enum class Outcome { CONNECTED, REFUSED, NO_ANSWER }

    /** A pump is on trial: what is stored stays only if this first connection works. */
    @Volatile var inProgress: Boolean = false
        private set

    /** The pump on trial refused the password: one refusal is the answer for a value never seen working. */
    @Volatile var refused: Boolean = false
        private set

    /** Whether a pump is connected: an address, a serial and a password. */
    val isPaired: Boolean
        get() = !inProgress &&
            preferences.get(ApexStringKey.ApexAddress).isNotBlank() &&
            preferences.get(ApexStringKey.ApexSerialNumber).isNotBlank() &&
            preferences.get(ApexStringKey.ApexBtPassword).isNotBlank()

    /** The pump on trial refused the password: the queue is emptied to end the attempt now. */
    fun noteRefused() {
        refused = true
        commandQueue.clear()
    }

    /** Connect the pump with [serial] at [address] under [password]; [onResult] is called on the queue's thread once what is stored is settled. */
    suspend fun connect(address: String, serial: String, password: String, onResult: (Outcome, String) -> Unit) {
        // Another pump than the last: AAPS is told, so that its records are kept apart by serial.
        if (serial != preferences.get(ApexStringKey.ApexSerialNumber)) {
            aapsLogger.debug(LTag.PUMP, "Apex: connecting a pump other than the last one")
            pumpSync.connectNewPump()
            pumpState.reset()
        }
        aapsLogger.debug(LTag.PUMP, "Apex: connecting pump $serial at $address")
        refused = false
        inProgress = true
        // Stored for the attempt: the queue brings the link up with what is stored.
        preferences.put(ApexStringKey.ApexAddress, address)
        preferences.put(ApexStringKey.ApexSerialNumber, serial)
        preferences.put(ApexStringKey.ApexBtPassword, password)
        preferences.put(ApexStringKey.ApexBtPasswordAlternate, "")
        // Waits for the queue to carry the first read out, or to turn it down.
        val result = commandQueue.customCommand(ApexPair())
        finish(serial, result.success, result.comment, onResult)
    }

    private fun finish(serial: String, success: Boolean, comment: String, onResult: (Outcome, String) -> Unit) {
        val outcome = when {
            success -> Outcome.CONNECTED
            refused -> Outcome.REFUSED
            else    -> Outcome.NO_ANSWER
        }
        inProgress = false
        refused = false
        if (outcome == Outcome.CONNECTED) {
            aapsLogger.debug(LTag.PUMP, "Apex: pump $serial answered, serial and password kept")
        } else {
            aapsLogger.debug(LTag.PUMP, "Apex: pump $serial did not connect ($outcome, $comment), nothing kept")
            forgetCredentials()
            apexConnection.value.disconnect(PAIRING_FAILED_REASON)
        }
        onResult(outcome, comment)
    }

    /** Whether a bolus is queued or under way: the pump is not disconnected then. */
    val bolusUnderWay: Boolean
        get() = commandQueue.bolusInQueue() ||
            commandQueue.isRunning(Command.CommandType.BOLUS) ||
            commandQueue.isRunning(Command.CommandType.SMB_BOLUS)

    /**
     * Forget the pump whole: its address, serial and password, the link, and all the driver kept of it
     * (the journals, the day of the statistics, the orders, the clock). Connected again later, the pump
     * is a new one: its first journal is the past, and nothing is counted across the time it was away.
     */
    suspend fun disconnect() {
        val serial = preferences.get(ApexStringKey.ApexSerialNumber)
        aapsLogger.debug(LTag.PUMP, "Apex: user disconnected the pump $serial, all kept of it is dropped")
        forgetCredentials()
        apexConnection.value.disconnect(UNPAIRED_REASON)
        pumpState.reset()
        if (serial.isNotEmpty()) withContext(Dispatchers.IO) { store.forgetPump(serial) }
    }

    private fun forgetCredentials() {
        preferences.put(ApexStringKey.ApexAddress, "")
        preferences.put(ApexStringKey.ApexSerialNumber, "")
        preferences.put(ApexStringKey.ApexBtPassword, "")
        preferences.put(ApexStringKey.ApexBtPasswordAlternate, "")
    }

    private companion object {

        const val UNPAIRED_REASON = "pump disconnected by the user"
        const val PAIRING_FAILED_REASON = "pairing failed"
    }
}
