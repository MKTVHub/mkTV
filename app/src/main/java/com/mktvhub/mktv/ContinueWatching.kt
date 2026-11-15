package com.mktvhub.mktv

import org.json.JSONArray
import org.json.JSONObject

/**
 * Continue watching (normal mode only): where long videos were left, newest first. Pure, no Android deps.
 * Positions and durations are in seconds; [Entry.time] is when it was last watched (epoch ms).
 */
object ContinueWatching {
    data class Entry(val url: String, val title: String, val host: String, val position: Double, val duration: Double, val time: Long) {
        val remaining get() = (duration - position).coerceAtLeast(0.0)
        val progress get() = if (duration > 0) (position / duration).coerceIn(0.0, 1.0) else 0.0
    }

    const val MAX = 20
    /** Shorter videos (clips, ads, previews) are not worth remembering. */
    const val MIN_DURATION = 180.0
    /** Before this, the user has barely started: nothing to resume. */
    const val MIN_POSITION = 30.0
    /** Closer than this to the end (or past [END_FRACTION]) counts as finished: the credits are rolling. */
    const val END_SECONDS = 60.0
    const val END_FRACTION = 0.95

    /** Same page as History and Favourites: trailing slash ignored. The fragment is kept (YouTube's TV interface routes by #/watch?v=). */
    fun key(url: String) = url.trimEnd('/')

    fun find(list: List<Entry>, url: String?): Entry? = url?.let { u -> list.firstOrNull { key(it.url) == key(u) } }

    fun remove(list: List<Entry>, url: String): List<Entry> = list.filterNot { key(it.url) == key(url) }

    fun isFinished(position: Double, duration: Double) =
        duration - position < END_SECONDS || position > duration * END_FRACTION

    /**
     * Applies one playback sample for [url]: too short or live (duration 0) and too early change nothing,
     * finished removes the entry, anything else moves it to the top with the new position.
     */
    fun record(list: List<Entry>, url: String, title: String, host: String, position: Double, duration: Double, now: Long, max: Int = MAX): List<Entry> {
        if (!duration.isFinite() || !position.isFinite() || duration < MIN_DURATION) return list
        if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) return list
        if (isFinished(position, duration)) return remove(list, url).let { if (it.size == list.size) list else it }
        if (position < MIN_POSITION) return list
        val e = Entry(url, title.ifBlank { host }, host, position, duration, now)
        return (listOf(e) + remove(list, url)).take(max)
    }

    /** Offer "Resume from …" only for the same video (about the same length) and when the page didn't resume by itself. */
    fun shouldOffer(saved: Entry, position: Double, duration: Double): Boolean =
        sameVideo(saved, duration) && saved.position - position > 15

    /** A video of about the saved length; a pre-roll ad or an unknown (0, live) duration isn't. */
    fun sameVideo(saved: Entry, duration: Double): Boolean =
        duration.isFinite() && duration > 0 && kotlin.math.abs(duration - saved.duration) <= maxOf(10.0, saved.duration * 0.02)

    /** 75 → "1:15", 3723 → "1:02:03". */
    fun clock(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0).toLong()
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "12 min left", "1 h 5 min left", "Less than a minute left". */
    fun leftLabel(e: Entry): String {
        val min = (e.remaining / 60).toLong()
        return when {
            min < 1 -> "Less than a minute left"
            min < 60 -> "$min min left"
            min % 60 == 0L -> "${min / 60} h left"
            else -> "${min / 60} h ${min % 60} min left"
        }
    }

    fun toJson(list: List<Entry>): String = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().put("url", e.url).put("title", e.title).put("host", e.host)
                .put("pos", e.position).put("dur", e.duration).put("time", e.time))
        }
    }.toString()

    fun fromJson(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Entry(url, o.optString("title"), o.optString("host"), o.optDouble("pos", 0.0), o.optDouble("dur", 0.0), o.optLong("time"))
            }.sortedByDescending { it.time }.take(MAX)
        }.getOrDefault(emptyList())
    }
}

/** Home screen section order. Pure, so the migration of older saved orders is testable. */
object HomeSections {
    /** Sections added in later versions that belong at the top when an older saved order doesn't know them. */
    private val NEW_ON_TOP = setOf("cw")

    /** Saved order (unknown ids dropped) with missing ids added: new ones on top, the rest at the end. */
    fun merge(saved: List<String>, all: List<String>): List<String> {
        val known = saved.filter { it in all }.distinct()
        if (known.isEmpty()) return all
        val missing = all.filter { it !in known }
        return missing.filter { it in NEW_ON_TOP } + known + missing.filterNot { it in NEW_ON_TOP }
    }
}
