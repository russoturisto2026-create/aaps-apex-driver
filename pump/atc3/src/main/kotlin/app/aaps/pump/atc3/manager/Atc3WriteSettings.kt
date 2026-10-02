package app.aaps.pump.atc3.manager

import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.pump.atc3.comm.Atc3Settings

/**
 * Write a complete settings block to the pump.
 *
 * The ATC3 screens collect edits into one draft and send it whole when it is saved. It reaches the
 * pump through the command queue like every other operation: the queue is what connects the pump if
 * the link is down and what keeps this from cutting into a bolus or a profile write.
 */
class Atc3WriteSettings(val settings: Atc3Settings) : CustomCommand {

    override val statusDescription: String = "Apex SETTINGS"
}
