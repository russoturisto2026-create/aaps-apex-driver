package app.aaps.pump.atc3.command

import app.aaps.core.interfaces.queue.CustomCommand

/** Replace the pump's Bluetooth password. On the queue, so that the link the pump drops afterwards takes nothing else with it. */
class Atc3SetBtPassword(val password: Int) : CustomCommand {

    override val statusDescription: String = "Apex BLUETOOTH PASSWORD"
}
