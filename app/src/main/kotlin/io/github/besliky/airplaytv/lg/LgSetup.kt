package io.github.besliky.airplaytv.lg

import io.github.besliky.airplaytv.Settings
import org.json.JSONObject

/**
 * Learns what the receiver needs to know about a paired TV, and keeps it: which input of the TV this stick is on, and the TV's
 * hardware addresses (to wake it over the network). It has to run while the TV shows this stick's picture, which it does at the end
 * of pairing (the owner is looking at this stick's screen then) and when "The Stick's TV Input" is pressed.
 */
object LgSetup {

    /** What was found: [input] is the TV's app for the input in front (`com.webos.app.hdmi1`) or null when it is not an input. */
    data class Result(val input: String?, val macs: List<String>)

    /** Blocking: run it off the main thread. */
    fun learn(settings: Settings): Result {
        if (!TvSettings.paired(settings)) return Result(null, emptyList())
        val tv = LgTv(TvSettings.connector(settings, 4000))
        val session = (tv.openSession(settings.lgHost, settings.lgClientKey.ifEmpty { null }) as? LgTv.SessionResult.Ready)?.session
            ?: return Result(null, emptyList())
        return session.use { s ->
            val app = s.request("ssap://com.webos.applicationManager/getForegroundAppInfo")?.let { LgFacts.foregroundApp(it) }
            val input = app?.takeIf { LgFacts.inputIdOf(it) != null }
            if (input != null) settings.lgInput = input
            val fromTv = s.request("ssap://com.webos.service.connectionmanager/getinfo", JSONObject())?.let { LgFacts.macAddresses(it) }.orEmpty()
            val macs = fromTv.ifEmpty {
                listOfNotNull(ArpTable.macFor(settings.lgHost, ArpTable.read()))
            }
            if (macs.isNotEmpty()) settings.lgMacs = macs.joinToString(",")
            Result(input, macs)
        }
    }
}
