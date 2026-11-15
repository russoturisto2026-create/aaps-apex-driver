package app.aaps.pump.apex.command

import app.aaps.core.interfaces.queue.CustomCommand

/** Give the pump a new random Bluetooth password, in its turn on the queue: the link the pump drops afterwards takes nothing else with it. */
class ApexSetBtPassword : CustomCommand {

    override val statusDescription: String = "Apex BLUETOOTH PASSWORD"
}
