package app.aaps.pump.atc3.command

import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.pump.atc3.protocol.Atc3Settings

/** Write the pump's settings block, collected on the driver's screen and sent whole, on the queue. */
class Atc3WriteSettings(val settings: Atc3Settings) : CustomCommand {

    override val statusDescription: String = "Apex SETTINGS"
}
