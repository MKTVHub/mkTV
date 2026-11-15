package com.mktvhub.mktv

/** The "WIFI:" text phone cameras read as "join this network" (Android 10+, iOS 11+). */
object WifiQr {
    /** [password] blank means an open network. */
    fun text(ssid: String, password: String, hidden: Boolean = false): String {
        val open = password.isEmpty()
        return buildString {
            append("WIFI:T:").append(if (open) "nopass" else "WPA").append(';')
            append("S:").append(escape(ssid)).append(';')
            if (!open) append("P:").append(escape(password)).append(';')
            if (hidden) append("H:true;")
            append(';')
        }
    }

    /** Backslash, ; , : and " are special in the format. */
    fun escape(s: String) = s.replace(Regex("""[\\;,:"]""")) { "\\" + it.value }

    /** WifiManager reports quoted names, or a placeholder when it isn't allowed to say. */
    fun cleanSsid(raw: String?): String? {
        val s = raw?.trim()?.removeSurrounding("\"") ?: return null
        return s.takeIf { it.isNotEmpty() && it != "<unknown ssid>" && it != "0x" }
    }

    /** WPA needs 8–63 characters; an open network has none. */
    fun passwordProblem(password: String): String? = when {
        password.isEmpty() -> null
        password.length < 8 -> "Wi-Fi passwords are at least 8 characters"
        password.length > 63 -> "Wi-Fi passwords are at most 63 characters"
        else -> null
    }
}
