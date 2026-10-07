package app.aaps.pump.atc3.command

import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.pump.atc3.manager.Atc3Manager

/** Ask a pump that went quiet on a held link whether it is still there, in its turn on the queue. See [Atc3Manager.probeQuietLink]. */
class Atc3ProbeLink : CustomCommand {

    override val statusDescription: String = "Apex LINK CHECK"
}
