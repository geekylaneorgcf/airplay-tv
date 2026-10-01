package io.github.besliky.airplaytv.service

/** Lyrics of one track: time-stamped lines when [synced], otherwise plain text lines. */
class Lyrics(val lines: List<Line>, val synced: Boolean, val instrumental: Boolean = false) {

    class Line(val timeMs: Long, val text: String)

    /** The index of the line being sung at [positionMs], or -1 before the first line. */
    fun indexAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    companion object {
        val INSTRUMENTAL = Lyrics(emptyList(), synced = false, instrumental = true)

        private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

        /**
         * Parses LRC text: `[mm:ss.xx] words`, several time tags per line, and an optional
         * `[offset:+ms]` tag (positive = lines appear earlier). Lines without words are dropped.
         */
        fun parseLrc(text: String): List<Line> {
            var offsetMs = 0L
            val out = ArrayList<Line>()
            for (raw in text.lineSequence()) {
                var rest = raw.trim()
                if (rest.isEmpty()) continue
                if (rest.startsWith("[offset:", ignoreCase = true)) {
                    offsetMs = rest.substringAfter(':').substringBefore(']').trim().toLongOrNull() ?: 0L
                    continue
                }
                val times = ArrayList<Long>(1)
                while (true) {
                    val match = TIME_TAG.find(rest)
                    if (match == null || match.range.first != 0) break
                    val minutes = match.groupValues[1].toLong()
                    val seconds = match.groupValues[2].toLong()
                    val fraction = match.groupValues[3]
                    val fractionMs = when (fraction.length) {
                        0 -> 0L
                        1 -> fraction.toLong() * 100
                        2 -> fraction.toLong() * 10
                        else -> fraction.take(3).toLong()
                    }
                    times += minutes * 60_000 + seconds * 1000 + fractionMs
                    rest = rest.substring(match.range.last + 1)
                }
                val words = rest.trim()
                if (times.isEmpty() || words.isEmpty()) continue
                for (t in times) out += Line((t - offsetMs).coerceAtLeast(0L), words)
            }
            out.sortBy { it.timeMs }
            return out
        }

        /** Plain lyrics: one line per text line, blank lines kept as paragraph breaks. */
        fun parsePlain(text: String): List<Line> {
            val lines = text.lines().map { it.trim() }
            val first = lines.indexOfFirst { it.isNotEmpty() }
            val last = lines.indexOfLast { it.isNotEmpty() }
            if (first < 0) return emptyList()
            val out = ArrayList<Line>()
            var previousBlank = false
            for (line in lines.subList(first, last + 1)) {
                if (line.isEmpty()) {
                    if (!previousBlank) out += Line(-1, "")
                    previousBlank = true
                } else {
                    out += Line(-1, line)
                    previousBlank = false
                }
            }
            return out
        }
    }
}
