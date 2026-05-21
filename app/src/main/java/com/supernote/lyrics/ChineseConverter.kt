package com.supernote.lyrics

import android.content.Context

/**
 * Traditional Chinese → Simplified Chinese, character-level conversion.
 * Data sourced from OpenCC's TSCharacters.txt (Apache-2.0). Loaded once
 * from the bundled `assets/t2s.tsv` on first use.
 */
object ChineseConverter {

    @Volatile
    private var map: Map<Char, Char>? = null

    fun load(context: Context) {
        if (map != null) return
        synchronized(this) {
            if (map != null) return
            val m = HashMap<Char, Char>(3500)
            try {
                context.applicationContext.assets.open("t2s.tsv")
                    .bufferedReader(Charsets.UTF_8)
                    .useLines { lines ->
                        for (line in lines) {
                            // Each line: <trad>\t<simp>
                            if (line.length >= 3 && line[1] == '\t') {
                                m[line[0]] = line[2]
                            }
                        }
                    }
            } catch (_: Exception) {
                // Asset missing — leave map empty; convert() will be a no-op
            }
            map = m
        }
    }

    fun convert(text: String): String {
        val m = map ?: return text
        if (m.isEmpty() || text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var changed = false
        for (c in text) {
            val mapped = m[c]
            if (mapped != null) {
                sb.append(mapped)
                changed = true
            } else {
                sb.append(c)
            }
        }
        return if (changed) sb.toString() else text
    }
}
