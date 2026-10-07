package app.aaps.pump.atc3.command

import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.pump.atc3.ui.Atc3ScanActivity

/**
 * The first connection to a pump being paired: a status read through the queue, since the pump
 * accepts a link before it has looked at the password. See [app.aaps.pump.atc3.ui.Atc3ScanActivity].
 */
class Atc3Pair : CustomCommand {

    override val statusDescription: String = "Apex PAIR"
}
