package dev.alamo3.cabineq

import android.content.Context
import android.util.Log
import dev.alamo3.cabineq.addon.EclipseAddon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The listener's own collection: liked songs, playlists they made (or imported) and albums they
 * saved. Everything is kept with full metadata in `filesDir/library.json`, so the library lists
 * and plays without asking the catalogue for anything.
 *
 * Likes also feed the taste engine ([Taste]); this is the part of a like you can look at.
 */
object Library {
    private const val TAG = "CabinEQ/Library"
    private const val FILE = "library.json"
    private const val MAX_LIKED = 5000
    private const val MAX_PLAYLIST = 2000

    class Playlist(val id: String, val name: String, val tracks: List<Track>)
    class SavedAlbum(val id: String, val title: String, val artist: String, val year: String?, val artworkUrl: String?) {
        fun toAddon() = EclipseAddon.Album(id, title, artist, year, null, artworkUrl)
    }

    private class State(
        val liked: MutableList<Track> = ArrayList(),
        val playlists: MutableList<Playlist> = ArrayList(),
        val albums: MutableList<SavedAlbum> = ArrayList(),
    )

    private val lock = Any()
    private var state: State? = null

    /** Bumped on every change, so screens know to redraw. */
    @Volatile var version = 0
        private set

    // ------------------------------------------------------------------ liked songs

    /** Newest like first. */
    fun liked(context: Context): List<Track> = synchronized(lock) { ArrayList(load(context).liked) }

    fun isLiked(context: Context, track: Track): Boolean {
        val keys = Taste.keys(track).toSet()
        return synchronized(lock) { load(context).liked.any { t -> Taste.keys(t).any { it in keys } } }
    }

    /** Called by [Taste] whenever a song is liked, unliked or disliked, from any surface. */
    fun setLiked(context: Context, track: Track, liked: Boolean) {
        if (track.title.isBlank()) return
        val keys = Taste.keys(track).toSet()
        synchronized(lock) {
            val s = load(context)
            val had = s.liked.removeAll { t -> Taste.keys(t).any { it in keys } }
            if (liked) {
                // Stream URLs expire; the id is what plays it again later.
                s.liked.add(0, if (track.sourceId != null) track.copy(streamUrl = null) else track)
                while (s.liked.size > MAX_LIKED) s.liked.removeAt(s.liked.lastIndex)
            } else if (!had) return
            save(context, s)
        }
        version++
    }

    // ------------------------------------------------------------------ playlists

    fun playlists(context: Context): List<Playlist> = synchronized(lock) { ArrayList(load(context).playlists) }

    fun playlist(context: Context, id: String): Playlist? = playlists(context).firstOrNull { it.id == id }

    /** Creates a playlist (or returns the existing one of that name) and adds [tracks] to it. */
    fun createPlaylist(context: Context, name: String, tracks: List<Track> = emptyList()): Playlist? {
        val clean = name.trim().take(80)
        if (clean.isEmpty()) return null
        val created = synchronized(lock) {
            val s = load(context)
            val i = s.playlists.indexOfFirst { it.name.equals(clean, ignoreCase = true) }
            val base = if (i >= 0) s.playlists[i] else Playlist("p" + java.lang.Long.toHexString(System.currentTimeMillis()), clean, emptyList())
            val merged = Playlist(base.id, base.name, merge(base.tracks, tracks))
            if (i >= 0) s.playlists[i] = merged else s.playlists.add(0, merged)
            save(context, s)
            merged
        }
        version++
        return created
    }

    /** Returns false when the song was already in the playlist. */
    fun addToPlaylist(context: Context, id: String, track: Track): Boolean {
        val added = synchronized(lock) {
            val s = load(context)
            val i = s.playlists.indexOfFirst { it.id == id }
            if (i < 0) return false
            val before = s.playlists[i]
            val merged = merge(before.tracks, listOf(track))
            if (merged.size == before.tracks.size) return false
            s.playlists[i] = Playlist(before.id, before.name, merged)
            save(context, s)
            true
        }
        version++
        return added
    }

    fun removeFromPlaylist(context: Context, id: String, track: Track) {
        synchronized(lock) {
            val s = load(context)
            val i = s.playlists.indexOfFirst { it.id == id }
            if (i < 0) return
            val p = s.playlists[i]
            s.playlists[i] = Playlist(p.id, p.name, p.tracks.filterNot { it.mediaId == track.mediaId })
            save(context, s)
        }
        version++
    }

    fun deletePlaylist(context: Context, id: String) {
        synchronized(lock) {
            val s = load(context)
            if (!s.playlists.removeAll { it.id == id }) return
            save(context, s)
        }
        version++
    }

    private fun merge(have: List<Track>, more: List<Track>): List<Track> {
        val seen = have.flatMap { Taste.keys(it) }.toHashSet()
        val out = ArrayList(have)
        for (t in more) {
            if (t.title.isBlank() || Taste.keys(t).any { it in seen }) continue
            seen.addAll(Taste.keys(t))
            out += if (t.sourceId != null) t.copy(streamUrl = null) else t
            if (out.size >= MAX_PLAYLIST) break
        }
        return out
    }

    // ------------------------------------------------------------------ albums

    fun albums(context: Context): List<SavedAlbum> = synchronized(lock) { ArrayList(load(context).albums) }

    fun isAlbumSaved(context: Context, id: String): Boolean =
        synchronized(lock) { load(context).albums.any { it.id == id } }

    fun setAlbumSaved(context: Context, album: EclipseAddon.Album, saved: Boolean) {
        synchronized(lock) {
            val s = load(context)
            s.albums.removeAll { it.id == album.id }
            if (saved) s.albums.add(0, SavedAlbum(album.id, album.title, album.artist, album.year, album.artworkUrl))
            save(context, s)
        }
        version++
    }

    // ------------------------------------------------------------------ storage

    private fun load(context: Context): State {
        state?.let { return it }
        val s = State()
        val f = File(context.applicationContext.filesDir, FILE)
        if (f.exists()) {
            runCatching {
                val o = JSONObject(f.readText())
                s.liked += tracks(o.optJSONArray("liked"))
                o.optJSONArray("playlists")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        val name = p.optString("name")
                        if (name.isBlank()) continue
                        s.playlists += Playlist(p.optString("id").ifBlank { "p$i" }, name, tracks(p.optJSONArray("tracks")))
                    }
                }
                o.optJSONArray("albums")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val a = arr.optJSONObject(i) ?: continue
                        val id = a.optString("id")
                        val title = a.optString("title")
                        if (id.isBlank() || title.isBlank()) continue
                        s.albums += SavedAlbum(
                            id, title, a.optString("artist"),
                            a.optString("year").takeIf { it.isNotBlank() },
                            a.optString("art").takeIf { it.startsWith("http") },
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "Couldn't read the library: ${it.message}") }
        } else {
            // First run after an update: likes used to be remembered only as taste. Recover the
            // songs themselves from the play history, which still has their details.
            runCatching {
                val history = Settings(context).playHistory()
                s.liked += history.filter { Taste.isLiked(context, it) }.distinctBy { Taste.key(it) }
                if (s.liked.isNotEmpty()) save(context, s)
            }
        }
        state = s
        return s
    }

    private fun tracks(arr: JSONArray?): List<Track> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Track::fromJson) }

    private fun save(context: Context, s: State) {
        val o = JSONObject()
            .put("liked", JSONArray().apply { s.liked.forEach { put(it.toJson()) } })
            .put("playlists", JSONArray().apply {
                s.playlists.forEach { p ->
                    put(JSONObject().put("id", p.id).put("name", p.name)
                        .put("tracks", JSONArray().apply { p.tracks.forEach { put(it.toJson()) } }))
                }
            })
            .put("albums", JSONArray().apply {
                s.albums.forEach { a ->
                    put(JSONObject().put("id", a.id).put("title", a.title).put("artist", a.artist)
                        .putOpt("year", a.year).putOpt("art", a.artworkUrl))
                }
            })
        val dir = context.applicationContext.filesDir
        val tmp = File(dir, "$FILE.tmp")
        val f = File(dir, FILE)
        runCatching {
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        }.onFailure { Log.w(TAG, "Couldn't save the library: ${it.message}") }
    }
}
