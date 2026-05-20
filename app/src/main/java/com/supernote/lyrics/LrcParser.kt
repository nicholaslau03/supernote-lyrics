package com.supernote.lyrics

data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {
    private val TIMESTAMP_RE = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(synced: String?): List<LrcLine> {
        if (synced.isNullOrBlank()) return emptyList()
        val out = mutableListOf<LrcLine>()
        for (raw in synced.lineSequence()) {
            val matches = TIMESTAMP_RE.findAll(raw).toList()
            if (matches.isEmpty()) continue
            val lastEnd = matches.last().range.last + 1
            val text = raw.substring(lastEnd).trim()
            for (m in matches) {
                val mm = m.groupValues[1].toLong()
                val ss = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val fracMs = if (fracStr.isEmpty()) 0L else {
                    val v = fracStr.toLong()
                    when (fracStr.length) {
                        1 -> v * 100L
                        2 -> v * 10L
                        else -> v
                    }
                }
                out.add(LrcLine(mm * 60_000L + ss * 1_000L + fracMs, text))
            }
        }
        out.sortBy { it.timeMs }
        return out
    }
}
