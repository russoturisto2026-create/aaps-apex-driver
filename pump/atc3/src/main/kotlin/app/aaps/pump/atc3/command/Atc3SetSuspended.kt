package app.aaps.pump.atc3.command

import app.aaps.core.interfaces.queue.CustomCommand

/** Stop or resume the pump. On the queue, which connects and keeps it out of a running bolus. */
class Atc3SetSuspended(val suspended: Boolean) : CustomCommand {

    override val statusDescription: String = if (suspended) "Apex STOP" else "Apex RESUME"
}
