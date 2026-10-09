package app.aaps.pump.atc3.link

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.command.Atc3Pair
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.ui.Atc3ScanActivity
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Connecting a pump to AAPS and disconnecting it: its address, serial and password. A pump is
 * connected only if it answers: the three are stored for a first status read on the queue and taken
 * out again when it fails. The screen is [app.aaps.pump.atc3.ui.Atc3ScanActivity].
 */
@Singleton
class Atc3Pairing @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val commandQueue: CommandQueue,
    private val pumpSync: PumpSync,
    private val atc3HistorySync: Atc3HistorySync,
    private val pumpState: Atc3PumpState,
    // Lazy: the connection asks this class whether a pump is on trial.
    private val atc3Connection: Lazy<Atc3Connection>,
    private val pumpEnactResultProvider: Provider<PumpEnactResult>
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
            preferences.get(Atc3StringKey.Atc3Address).isNotBlank() &&
            preferences.get(Atc3StringKey.Atc3SerialNumber).isNotBlank() &&
            preferences.get(Atc3StringKey.Atc3BtPassword).isNotBlank()

    /** The pump on trial refused the password: the queue is emptied to end the attempt now. */
    fun noteRefused() {
        refused = true
        commandQueue.clear()
    }

    /** Connect the pump with [serial] at [address] under [password]; [onResult] is called on the queue's thread once what is stored is settled. */
    fun connect(address: String, serial: String, password: String, onResult: (Outcome, String) -> Unit) {
        // Another pump than the last has a history of its own: AAPS is told, and the ledger goes with the
        // old one, or the new pump's records would be taken as already counted.
        if (serial != atc3HistorySync.ledgerSerial()) {
            aapsLogger.debug(LTag.PUMP, "ATC3: connecting a pump other than the last one, resetting history state")
            pumpSync.connectNewPump()
            atc3HistorySync.forgetPump(serial)
            pumpState.reset()
        }
        aapsLogger.debug(LTag.PUMP, "ATC3: connecting pump $serial at $address")
        refused = false
        inProgress = true
        // Stored for the attempt: the queue brings the link up with what is stored.
        preferences.put(Atc3StringKey.Atc3Address, address)
        preferences.put(Atc3StringKey.Atc3SerialNumber, serial)
        preferences.put(Atc3StringKey.Atc3BtPassword, password)
        preferences.put(Atc3StringKey.Atc3BtPasswordAlternate, "")
        val callback = object : Callback() {
            override fun run() = finish(serial, result.success, result.comment, onResult)
        }
        if (!commandQueue.customCommand(Atc3Pair(), callback)) callback.result(pumpEnactResultProvider.get().success(false)).run()
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
            aapsLogger.debug(LTag.PUMP, "ATC3: pump $serial answered, serial and password kept")
        } else {
            aapsLogger.debug(LTag.PUMP, "ATC3: pump $serial did not connect ($outcome, $comment), nothing kept")
            forgetCredentials()
            atc3Connection.get().disconnect(PAIRING_FAILED_REASON)
        }
        onResult(outcome, comment)
    }

    /** Whether a bolus is queued or under way: the pump is not disconnected then. */
    val bolusUnderWay: Boolean
        get() = commandQueue.bolusInQueue() ||
            commandQueue.isRunning(Command.CommandType.BOLUS) ||
            commandQueue.isRunning(Command.CommandType.SMB_BOLUS)

    /** Forget the pump whole and drop the link; the window of the basal account goes with the rest, and begins anew with the next pump. */
    fun disconnect() {
        aapsLogger.debug(LTag.PUMP, "ATC3: user disconnected the pump ${preferences.get(Atc3StringKey.Atc3SerialNumber)}")
        forgetCredentials()
        atc3Connection.get().disconnect(UNPAIRED_REASON)
        atc3HistorySync.forgetPump("")
        pumpState.reset()
    }

    private fun forgetCredentials() {
        preferences.put(Atc3StringKey.Atc3Address, "")
        preferences.put(Atc3StringKey.Atc3SerialNumber, "")
        preferences.put(Atc3StringKey.Atc3BtPassword, "")
        preferences.put(Atc3StringKey.Atc3BtPasswordAlternate, "")
    }

    private companion object {

        const val UNPAIRED_REASON = "pump disconnected by the user"
        const val PAIRING_FAILED_REASON = "pairing failed"
    }
}
