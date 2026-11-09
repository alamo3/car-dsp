package dev.alamo3.cabineq

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.alamo3.cabineq.music.Artwork

/**
 * Serves album art to other processes as small files, so the car (Android Auto) can load icons.
 *
 * A browse item's icon can only be a URI the car's own process can open, and it has no way to
 * authenticate to Apple/TorBox or to read our cache. So the browse tree points here, and we turn
 * the request into a cache file (fetching it first if needed) and hand back a read-only fd.
 *
 * `content://dev.alamo3.cabineq.art/art?u=<artwork url>&q=<search fallback>&s=<size px>`
 *
 * The provider is exported read-only because the car app has a different uid. It only ever
 * serves images: [Artwork.fileFor] validates the bytes and https-only URLs are accepted, so a
 * caller can't use it to read arbitrary files.
 */
class ArtworkProvider : ContentProvider() {

    /** Downloads run here, never on the caller's (binder) thread. */
    private val warm = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "CabinEQ-ArtWarm").apply { isDaemon = true }
    }
    private val warming = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun warmArtwork(ctx: android.content.Context, url: String?, query: String?, size: Int) {
        val key = "$url|$query|$size"
        if (!warming.add(key)) return
        warm.execute {
            try {
                Artwork.fileFor(ctx, url, query, size)
            } catch (e: Exception) {
                // Best effort: the next request will try again.
            } finally {
                warming.remove(key)
            }
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r" || uri.pathSegments.firstOrNull() != "art") return null
        val ctx = context ?: return null
        // A jam QR: generated here, so the car's host can fetch it at its own resolution.
        uri.getQueryParameter("qr")?.takeIf { it.isNotBlank() }?.let { text ->
            val size = uri.getQueryParameter("s")?.toIntOrNull()?.coerceIn(128, 1024) ?: 640
            val file = dev.alamo3.cabineq.jam.QrCodes.fileFor(ctx, text, size) ?: return null
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val url = uri.getQueryParameter("u")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val query = uri.getQueryParameter("q")
        if (url == null && query.isNullOrBlank()) return null
        val size = uri.getQueryParameter("s")?.toIntOrNull()?.coerceIn(64, 1200) ?: 512
        // Cached cover: serve it. Otherwise hand over a placeholder now and fetch in the
        // background — the car opens icons synchronously, and a 15 s network wait paints a
        // blank tile (or stalls the row) while it works.
        val cached = Artwork.cachedFileFor(ctx, url, query, size)
        if (cached != null) return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
        warmArtwork(ctx, url, query, size)
        val placeholder = Artwork.placeholderFile(ctx, size) ?: return null
        return ParcelFileDescriptor.open(placeholder, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        if (uri.getQueryParameter("qr") != null) return "image/png"
        // The cache has no extensions: report what the bytes actually are. Not cached yet means
        // openFile() will hand out the placeholder, which is a PNG.
        val ctx = context ?: return "image/png"
        val url = uri.getQueryParameter("u")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val query = uri.getQueryParameter("q")
        val size = uri.getQueryParameter("s")?.toIntOrNull()?.coerceIn(64, 1200) ?: 512
        return Artwork.cachedMimeType(ctx, url, query, size) ?: "image/png"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /**
         * Declared in the manifest as `${applicationId}.art`, so a test build installed next to a
         * release build gets its own authority instead of fighting over one.
         */
        fun authority(): String = BuildConfig.APPLICATION_ID + ".art"

        /**
         * A content:// URI the car (another process) can open. Either an artwork URL or a text
         * query for the iTunes fallback — both are resolved and cached by the provider.
         */
        /** A QR image of [text] (the jam URL), sized by the requester. */
        fun qrUri(text: String, sizePx: Int = 640): Uri = Uri.Builder()
            .scheme("content")
            .authority(authority())
            .appendPath("art")
            .appendQueryParameter("qr", text)
            .appendQueryParameter("s", sizePx.toString())
            .build()

        fun uriFor(url: String? = null, query: String? = null, sizePx: Int = 512): Uri? {
            val hasUrl = !url.isNullOrBlank()
            val hasQuery = !query.isNullOrBlank()
            if (!hasUrl && !hasQuery) return null
            return Uri.Builder()
                .scheme("content")
                .authority(authority())
                .appendPath("art")
                .apply {
                    if (hasUrl) appendQueryParameter("u", url)
                    if (hasQuery) appendQueryParameter("q", query)
                    appendQueryParameter("s", sizePx.toString())
                }
                .build()
        }
    }
}
