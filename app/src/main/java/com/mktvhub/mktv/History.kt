package com.mktvhub.mktv

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Full browsing history (normal mode only): list operations, search and day grouping. No Android deps. */
object History {
    data class Entry(val title: String, val url: String, val time: Long)

    const val MAX = 500

    private fun key(url: String) = url.trimEnd('/')

    /** Newest first; a revisited address moves to the top instead of appearing twice. */
    fun add(list: List<Entry>, e: Entry, max: Int = MAX): List<Entry> =
        (listOf(e) + list.filterNot { key(it.url) == key(e.url) }).take(max)

    fun remove(list: List<Entry>, url: String): List<Entry> = list.filterNot { key(it.url) == key(url) }

    /** Pages often set their real title after loading; update the entry for [url] if it has one. */
    fun retitle(list: List<Entry>, url: String, title: String): List<Entry> {
        if (title.isBlank()) return list
        val i = list.indexOfFirst { key(it.url) == key(url) }
        if (i < 0 || list[i].title == title) return list
        return list.toMutableList().also { it[i] = it[i].copy(title = title) }
    }

    /** Every word of [query] must appear in the title or the address. */
    fun search(list: List<Entry>, query: String): List<Entry> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return list
        return list.filter { e ->
            val hay = (e.title + " " + WebSearch.displayUrl(e.url)).lowercase()
            words.all { it in hay }
        }
    }

    /** "Today", "Yesterday", "Monday 3 November", or with the year if it isn't this year. */
    fun dayLabel(time: Long, now: Long, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
        val day = Calendar.getInstance(zone, locale).apply { timeInMillis = time }
        val today = Calendar.getInstance(zone, locale).apply { timeInMillis = now }
        fun startOfDay(c: Calendar) = (c.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val days = Math.round((startOfDay(today) - startOfDay(day)) / 86_400_000.0)
        return when {
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            else -> {
                val pattern = if (day.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "EEEE d MMMM" else "EEEE d MMMM yyyy"
                java.text.SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(day.time)
            }
        }
    }

    fun toJson(list: List<Entry>): String {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("t", it.title).put("u", it.url).put("at", it.time)) }
        return arr.toString()
    }

    fun fromJson(json: String?): List<Entry> = runCatching {
        val arr = org.json.JSONArray(json ?: return emptyList())
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Entry(o.optString("t"), o.getString("u"), o.optLong("at")) } }
    }.getOrDefault(emptyList())
}
