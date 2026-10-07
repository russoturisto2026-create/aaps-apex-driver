package app.aaps.pump.atc3.link

import app.aaps.pump.atc3.Atc3Const

/**
 * How well the last link was protected, as the pump answered the password presented while it came
 * up: the password is all that stands between the pump and anybody in radio range, since the pump
 * accepts a link rather than a client.
 */
enum class Atc3LinkProtection {

    /** No link has come up yet. */
    UNKNOWN,

    /** The pump took a password of the user's own. */
    PROTECTED,

    /** The pump took `000000`: no password is set on it. */
    UNPROTECTED,

    /** The pump has no password feature: firmware older than [app.aaps.pump.atc3.Atc3Const.PASSWORD_FIRMWARE]. */
    UNSUPPORTED
}
