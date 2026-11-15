package app.aaps.pump.apex.command

import app.aaps.core.interfaces.queue.CustomCommand

/**
 * Set the pump up for AAPS, in its turn on the queue: extended boluses off, screen brightness at the
 * lowest, alarms by vibration, the screen off after the shortest time. Everything else stays as the
 * pump has it. See `ApexPumpPlugin.setupForAaps`.
 */
class ApexSetupForAaps : CustomCommand {
    override val statusDescription: String = "Apex SETUP FOR AAPS"
}
