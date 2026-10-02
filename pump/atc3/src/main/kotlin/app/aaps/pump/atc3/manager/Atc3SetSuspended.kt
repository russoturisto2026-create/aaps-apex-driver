package app.aaps.pump.atc3.manager

import app.aaps.core.interfaces.queue.CustomCommand

/**
 * Stop or resume the pump's delivery.
 *
 * `Pump` has no method for this, so it travels as a custom command, the same way a settings write
 * does. Going through the queue is what matters: the queue connects the pump if the link is down,
 * and it keeps this from cutting into a bolus — which is not a nicety, because stopping the pump
 * stops a running bolus too.
 */
class Atc3SetSuspended(val suspended: Boolean) : CustomCommand {

    override val statusDescription: String = if (suspended) "Apex STOP" else "Apex RESUME"
}
