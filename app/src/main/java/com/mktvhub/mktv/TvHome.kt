package com.mktvhub.mktv

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android TV home screen: Continue watching as "Play Next" (Watch Next) cards, plus a "mkTV" channel with
 * Continue watching and Favourites. Everything runs on one background thread, is skipped where there is
 * no TV provider (phones, Android 7, some launchers) and swallows provider errors: the launcher can
 * revoke access or reject rows at any time. Google TV may not show third-party channels; Watch Next is
 * the dependable part.
 */
object TvHome {
    /** Set on launcher card intents so BrowserActivity knows where the page came from. */
    const val EXTRA_FROM_TV_HOME = "com.mktvhub.mktv.FROM_TV_HOME"
    private const val MAX_CHANNEL_CW = 8
    private const val MAX_CHANNEL_FAVS = 10

    private val executor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    @Volatile private var lastSig: String? = null

    private fun supported(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 26 && runCatching {
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            ctx.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null
    }.getOrDefault(false)

    /**
     * Brings the launcher rows in line with Prefs. [foreground]: mkTV is on screen, so the channel may be
     * created now (the system must not be asked to show a new channel from the background).
     * Cheap when nothing changed since the last call.
     */
    fun sync(context: Context, foreground: Boolean) {
        val app = context.applicationContext
        executor.execute { runCatching { if (supported(app)) doSync(app, foreground) } }
    }

    private fun doSync(ctx: Context, foreground: Boolean) {
        val prefs = Prefs(ctx)
        val on = prefs.rememberWatching
        val entries = if (on) prefs.continueWatching else emptyList()
        val favs = if (on) prefs.shortcuts.filter { it.second.startsWith("http") } else emptyList()
        // Positions in 30 s steps: a few seconds more of the same video isn't worth a provider write.
        val sig = buildString {
            append(on).append(prefs.tvChannelId >= 0 || foreground)
            entries.forEach { append('|').append(it.url).append('@').append((it.position / 30).toLong()).append(it.title) }
            favs.forEach { append('|').append(it.second).append(it.first) }
        }
        if (sig == lastSig) return
        val published = runCatching { JSONObject(prefs.tvPublished ?: "{}") }.getOrDefault(JSONObject())
        val gone = syncWatchNext(ctx, entries, published)
        if (gone.isNotEmpty()) {
            // Removed from Play Next on the TV home screen: forget it here too, and don't put it back.
            prefs.continueWatching = prefs.continueWatching.filterNot { ContinueWatching.key(it.url) in gone }
        }
        val shown = entries.filterNot { ContinueWatching.key(it.url) in gone }
        syncChannel(ctx, prefs, shown, favs, published, foreground, on)
        prefs.tvPublished = published.toString()
        lastSig = sig
    }

    private fun cardIntent(ctx: Context, url: String): Uri = Uri.parse(
        Intent(ctx, BrowserActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))
            .putExtra(EXTRA_FROM_TV_HOME, true).toUri(Intent.URI_INTENT_SCHEME),
    )

    /** A raster drawable: launchers load poster art by URI and often can't draw vector XML. Site icons live in mkTV's private files. */
    private fun poster(ctx: Context) = Uri.parse("android.resource://${ctx.packageName}/drawable/banner")

    /** Returns the keys of cards the user removed from Play Next. */
    private fun syncWatchNext(ctx: Context, entries: List<ContinueWatching.Entry>, published: JSONObject): Set<String> {
        val cr = ctx.contentResolver
        val map = published.optJSONObject("wn") ?: JSONObject()
        // No WHERE clauses allowed: read all our rows (the provider only returns this app's).
        val rows = HashMap<Long, WatchNextProgram>()
        cr.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null, null, null)?.use { c ->
            while (c.moveToNext()) WatchNextProgram.fromCursor(c).let { rows[it.id] = it }
        }
        val gone = HashSet<String>()
        val wanted = entries.associateBy { ContinueWatching.key(it.url) }
        for (key in map.keys().asSequence().toList()) {
            val id = map.optLong(key, -1)
            val row = rows[id]
            when {
                row == null -> { if (key in wanted) gone += key; map.remove(key) }
                !row.isBrowsable -> { if (key in wanted) gone += key; delete(ctx, TvContractCompat.buildWatchNextProgramUri(id)); map.remove(key) }
                key !in wanted -> { delete(ctx, TvContractCompat.buildWatchNextProgramUri(id)); map.remove(key) }
            }
        }
        // Rows of ours that no mapping knows (e.g. app data cleared): remove them.
        val known = map.keys().asSequence().map { map.optLong(it) }.toSet()
        rows.keys.filter { it !in known }.forEach { delete(ctx, TvContractCompat.buildWatchNextProgramUri(it)) }

        wanted.forEach { (key, e) ->
            if (key in gone) return@forEach
            val values = WatchNextProgram.Builder()
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setType(TvContractCompat.WatchNextPrograms.TYPE_CLIP)
                .setTitle(e.title)
                .setDescription(e.host)
                .setLastEngagementTimeUtcMillis(e.time)
                .setLastPlaybackPositionMillis((e.position * 1000).toInt())
                .setDurationMillis((e.duration * 1000).toInt())
                .setPosterArtUri(poster(ctx))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(cardIntent(ctx, e.url))
                .setInternalProviderId(key)
                .build().toContentValues()
            val id = map.optLong(key, -1)
            if (id >= 0) runCatching { cr.update(TvContractCompat.buildWatchNextProgramUri(id), values, null, null) }
            else runCatching { cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values) }.getOrNull()
                ?.let { map.put(key, ContentUris.parseId(it)) }
        }
        published.put("wn", map)
        return gone
    }

    private fun syncChannel(ctx: Context, prefs: Prefs, entries: List<ContinueWatching.Entry>, favs: List<Pair<String, String>>,
                            published: JSONObject, foreground: Boolean, on: Boolean) {
        val cr = ctx.contentResolver
        var channelId = prefs.tvChannelId
        val exists = channelId >= 0 && runCatching {
            cr.query(TvContractCompat.buildChannelUri(channelId), null, null, null, null)?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
        if (!exists) {
            // Only made once there is something to show, and only while mkTV is on screen.
            if (!on || !foreground || (entries.isEmpty() && favs.isEmpty())) return
            val channel = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName("mkTV")
                .setAppLinkIntentUri(Uri.parse(Intent(ctx, BrowserActivity::class.java).toUri(Intent.URI_INTENT_SCHEME)))
                .setInternalProviderId("mktv")
                .build()
            val uri = cr.insert(TvContractCompat.Channels.CONTENT_URI, channel.toContentValues()) ?: return
            channelId = ContentUris.parseId(uri)
            prefs.tvChannelId = channelId
            logo(ctx)?.let { ChannelLogoUtils.storeChannelLogo(ctx, channelId, it) }
            // The first channel is the app's default channel: shown without asking the user.
            runCatching { TvContractCompat.requestChannelBrowsable(ctx, channelId) }
        }
        // Programs the user removed ("not interested") must not come back.
        val dismissed = (published.optJSONArray("ppx") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) }.toMutableSet() }
        val map = published.optJSONObject("pp") ?: JSONObject()
        val rows = HashMap<Long, PreviewProgram>()
        cr.query(TvContractCompat.PreviewPrograms.CONTENT_URI, null, null, null, null)?.use { c ->
            while (c.moveToNext()) PreviewProgram.fromCursor(c).let { if (it.channelId == channelId) rows[it.id] = it }
        }
        for (key in map.keys().asSequence().toList()) {
            val row = rows[map.optLong(key, -1)]
            if (row == null || !row.isBrowsable) dismissed += key
        }
        rows.keys.forEach { delete(ctx, TvContractCompat.buildPreviewProgramUri(it)) }
        val newMap = JSONObject()
        val cwKeys = entries.map { ContinueWatching.key(it.url) }.toSet()
        val items = entries.take(MAX_CHANNEL_CW).map { Triple(it.url, it.title, it) } +
            favs.filter { ContinueWatching.key(it.second) !in cwKeys }.take(MAX_CHANNEL_FAVS).map { Triple(it.second, it.first, null) }
        items.forEachIndexed { i, (url, title, e) ->
            val key = ContinueWatching.key(url)
            if (key in dismissed) return@forEachIndexed
            val b = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                .setTitle(title)
                .setDescription(e?.let { "${it.host} · ${ContinueWatching.leftLabel(it)}" } ?: Uri.parse(url).host?.removePrefix("www.").orEmpty())
                .setPosterArtUri(poster(ctx))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(cardIntent(ctx, url))
                .setInternalProviderId(key)
                .setWeight(items.size - i)
            if (e != null) b.setLastPlaybackPositionMillis((e.position * 1000).toInt()).setDurationMillis((e.duration * 1000).toInt())
            runCatching { cr.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, b.build().toContentValues()) }.getOrNull()
                ?.let { newMap.put(key, ContentUris.parseId(it)) }
        }
        published.put("pp", newMap)
        published.put("ppx", JSONArray(dismissed.toList().takeLast(50)))
    }

    private fun delete(ctx: Context, uri: Uri) { runCatching { ctx.contentResolver.delete(uri, null, null) } }

    /** Opaque square logo (the launcher masks it to a circle), drawn here on the background thread. */
    private fun logo(ctx: Context): android.graphics.Bitmap? = runCatching {
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher) ?: return null
        val size = 160
        android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888).also { bmp ->
            val c = android.graphics.Canvas(bmp)
            c.drawColor(0xFF101217.toInt())
            d.setBounds(0, 0, size, size); d.draw(c)
        }
    }.getOrNull()
}
