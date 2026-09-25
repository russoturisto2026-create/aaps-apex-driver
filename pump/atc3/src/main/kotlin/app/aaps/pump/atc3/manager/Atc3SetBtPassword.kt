package app.aaps.pump.atc3.manager

import app.aaps.core.interfaces.queue.CustomCommand

/**
 * Replace the pump's Bluetooth password.
 *
 * Goes through the command queue like every other pump operation: the queue is what connects the
 * pump if the link is down, and what keeps this from cutting into a bolus. It matters more here
 * than elsewhere, because the pump drops the link once it has taken the new password, and a change
 * landing in the middle of something else would take that something else down with it.
 */
class Atc3SetBtPassword(val password: Int) : CustomCommand {

    override val statusDescription: String = "Apex BLUETOOTH PASSWORD"
}
