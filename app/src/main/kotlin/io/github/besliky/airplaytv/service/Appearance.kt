package io.github.besliky.airplaytv.service

/**
 * How the receiver presents itself to an iPhone's AirPlay list. The icon there is chosen by iOS from what the receiver
 * says it is. As an Apple TV (the default, and the only thing that is known to work with everything) the receiver
 * publishes both services and iOS draws the Apple TV tile. As a speaker it publishes only the audio service, under a
 * model iOS does not know, which is how a speaker is drawn (a receiver such as shairport-sync does the same); then only
 * music works, since screen mirroring and photos need the other service. A non-Apple model on the full service made iOS
 * connect again and again without ever starting a session, so this is a trial that undoes itself, see [Settings].
 */
object Appearance {

    /** The model the audio service claims in speaker mode. */
    const val SPEAKER_MODEL = "FireTV1,1"

    /**
     * The records to publish for [airplay] (the _airplay._tcp service) and [raop] (the audio service). An empty map means
     * "do not publish that service".
     */
    fun records(airplay: Map<String, String>, raop: Map<String, String>, speaker: Boolean): Pair<Map<String, String>, Map<String, String>> {
        if (!speaker) return airplay to raop
        val audio = LinkedHashMap(raop)
        if (audio.isNotEmpty()) audio["am"] = SPEAKER_MODEL
        return emptyMap<String, String>() to audio
    }
}
