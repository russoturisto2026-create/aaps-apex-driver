package app.aaps.pump.apex.command

import app.aaps.core.interfaces.queue.CustomCommand

/**
 * The first connection to a pump being paired: a status read through the queue, since the pump
 * accepts a link before it has looked at the password.
 */
class ApexPair : CustomCommand {

    override val statusDescription: String = "Apex PAIR"
}
