package io.github.besliky.airplaytv.lg

import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.SERVICE
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

    /**
     * What can be found out without anyone looking at this stick's screen, run whenever a session with the TV opens: the TV's hardware
     * addresses when they are not known, and, when the input is not known, the one connected input that the TV names like an Amazon
     * stick. Nothing that is known is overwritten. A TV that names its inputs otherwise is learned from the other way, by pressing
     * "The Stick's TV Input" while it shows this stick. Blocking: run it off the main thread.
     */
    fun learnQuietly(session: LgSession, settings: Settings) {
        if (settings.lgMacs.isEmpty()) {
            val macs = session.request("ssap://com.webos.service.connectionmanager/getinfo", JSONObject())?.let { LgFacts.macAddresses(it) }.orEmpty()
            if (macs.isNotEmpty()) {
                settings.lgMacs = macs.joinToString(",")
                Log.i(SERVICE, "LG learned the TV's hardware address")
            }
        }
        if (settings.lgInput.isEmpty()) {
            val inputs = session.request("ssap://tv/getExternalInputList")?.let { LgFacts.inputs(it) }.orEmpty()
            if (inputs.isEmpty()) return
            Log.i(
                SERVICE,
                "LG inputs: " + inputs.joinToString("; ") {
                    "${it.appId.substringAfterLast('.')} \"${it.label}\"${if (it.connected) "" else " (nothing connected)"}"
                },
            )
            LgFacts.stickInput(inputs)?.let {
                settings.lgInput = it
                Log.i(SERVICE, "LG learned this stick's input: $it")
            }
        }
    }

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
