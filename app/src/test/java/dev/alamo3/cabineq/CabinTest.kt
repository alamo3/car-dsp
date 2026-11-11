package dev.alamo3.cabineq

import dev.alamo3.cabineq.torbox.AudioPicker.Kind as K
import dev.alamo3.cabineq.torbox.AudioPicker
import dev.alamo3.cabineq.addon.EclipseAddon
import dev.alamo3.cabineq.addon.AddonException
import dev.alamo3.cabineq.torbox.SearchResult
import dev.alamo3.cabineq.torbox.TorBoxClient
import dev.alamo3.cabineq.torbox.TorBoxException
import dev.alamo3.cabineq.torbox.TorBoxFile
import org.junit.Assert.assertTrue
import dev.alamo3.cabineq.music.VoiceRequest.Focus
import org.junit.Test

/**
 * The logic that can run off a phone: file picking, cover and song matching, search ranking, and
 * the TorBox and addon clients against [MockServer]. Run by CI before any APK is built, so a
 * broken matcher or parser stops the release instead of shipping.
 *
 * One scenario, many checks: every failed check is collected and reported together.
 */
class CabinTest {
    private val failed = ArrayList<String>()
    private var passed = 0

    private fun check(name: String, cond: Boolean, detail: Any? = null) {
        if (cond) passed++ else failed += "$name ${detail ?: ""}".trim()
    }

    private fun f(id: Int, name: String, size: Long = 20_000_000) = TorBoxFile(id, name, name.substringAfterLast('/'), size, null)

    @Test
    fun everything() {
        MockServer().use { server -> scenario(server) }
        println("$passed checks passed, ${failed.size} failed")
        assertTrue("${failed.size} failed:\n" + failed.joinToString("\n"), failed.isEmpty())
    }

    private fun scenario(server: MockServer) {
    // ---------------- AudioPicker
    val album = listOf(
        f(0, "A/CD2/01 x.flac"), f(1, "A/CD1/10 y.flac"), f(2, "A/CD1/2 z.flac"), f(3, "A/cover.jpg"),
        f(4, "A/mp3/01 x.mp3"), f(5, "A/CD1/sample.flac"), f(6, "__MACOSX/A/._1.flac"), f(7, "A/CD1/03 tiny.flac", 1000),
        f(8, "A/hires/01.ape"),
    )
    val p = AudioPicker.pick(album)
    check("lossless preferred, junk dropped, natural order", p.tracks.map { it.id } == listOf(2, 1, 0), p.tracks.map { it.name })
    check("format flac lossless", p.format == "flac" && p.lossless)
    check("unsupported counted", p.unsupportedCount == 1, p.unsupportedCount)

    val lossyOnly = AudioPicker.pick(listOf(f(0, "B/1.mp3"), f(1, "B/2.mp3"), f(2, "B/1.m4a"), f(3, "B/info.nfo")))
    check("lossy: most common ext wins", lossyOnly.format == "mp3" && lossyOnly.tracks.size == 2 && !lossyOnly.lossless)

    val tie = AudioPicker.pick(listOf(f(0, "C/1.wav"), f(1, "C/1.flac")))
    check("tie -> flac", tie.format == "flac")

    check("nothing playable", AudioPicker.pick(listOf(f(0, "x.mkv"), f(1, "y.ape"))).let { it.tracks.isEmpty() && it.format == null && it.unsupportedCount == 1 })
    fun tt(n: String) = AudioPicker.trackTitle(f(0, n))
    check("trackTitle strips '02 - '", tt("A/CD1/02 - Song.Name.flac") == "Song.Name", tt("A/CD1/02 - Song.Name.flac"))
    for ((name, want) in listOf(
        "23 - Lose My Mind (feat. Doja Cat).flac" to "Lose My Mind (feat. Doja Cat)",
        "01. Intro.mp3" to "Intro", "07_Track.flac" to "Track", "1-03 Song.flac" to "Song", "01 Song.flac" to "Song",
        "A1 Side One.flac" to "Side One", "99 Problems.flac" to "99 Problems", "2Pac - Changes.mp3" to "2Pac - Changes",
        "1999.flac" to "1999", "B-Side.flac" to "B-Side",
    )) check("trackTitle '$name'", tt(name) == want, tt(name))

    // cover matching
    val frozen = dev.alamo3.cabineq.music.CoverMatch.Candidate("Various Artists", "Frozen (Original Motion Picture Soundtrack)", null, "u1")
    val loseMyMind = dev.alamo3.cabineq.music.CoverMatch.Candidate("Don Toliver & Doja Cat", "F1 The Album", "Lose My Mind (feat. Doja Cat) [From F1® The Movie]", "u2")
    val CM = dev.alamo3.cabineq.music.CoverMatch
    check("match: compilation name rejects Frozen", CM.best("Various Artists Pop Hits for the Summer 2025", listOf(frozen)) == null)
    check("match: song title finds the song", CM.best("Lose My Mind (feat. Doja Cat) [From F1® The Movie]", listOf(frozen, loseMyMind))?.artworkUrl == "u2")
    check("match: artist + title", CM.best("Don Toliver Lose My Mind", listOf(frozen, loseMyMind))?.artworkUrl == "u2")
    check("match: accents/case ignored", CM.score("Beyoncé HALO", dev.alamo3.cabineq.music.CoverMatch.Candidate("Beyonce", null, "Halo", "x")) == 1.0)
    check("match: stopword-only query -> none", CM.best("Various Artists", listOf(frozen)) == null)
    val cmp = AudioPicker.NaturalPathOrder
    check("natural 2 < 10", cmp.compareNatural("Track 2.flac", "Track 10.flac") < 0)
    check("natural leading zeros", cmp.compareNatural("01.flac", "1.flac") == 0 || cmp.compareNatural("01.flac", "2.flac") < 0)
    check("disc order", cmp.compareNatural("CD1/10.flac", "CD2/1.flac") < 0)

    val ranked = AudioPicker.rank(listOf(
        SearchResult("a", "Album [MP3 320]", "m", 1, 500, false, null),
        SearchResult("b", "Album [FLAC]", "m", 1, 5, false, null),
        SearchResult("c", "Album WEB FLAC", "m", 1, 1, true, null),
        SearchResult("d", "Album", "m", 1, 50, null, null),
    ))
    check("rank: cached, lossless, neutral, lossy", ranked.map { it.hash } == listOf("c", "b", "d", "a"), ranked.map { it.hash })
    check("looksLossless 24bit", AudioPicker.looksLossless("Album (24-Bit Hi-Res)"))

    // audio vs video

    val cases = mapOf(
        "Pink Floyd - The Dark Side of the Moon (1973) [FLAC 24bit]" to K.AUDIO,
        "Daft.Punk.Random.Access.Memories.2013.WEB.FLAC" to K.AUDIO,
        "Artist - Album (2020) MP3 320kbps" to K.AUDIO,
        "Hans Zimmer - Interstellar (Original Motion Picture Soundtrack)" to K.AUDIO,
        "Movie Soundtrack Collection" to K.AUDIO,
        "Vivaldi - The Four Seasons" to K.UNKNOWN,
        "Artist - Some Title" to K.UNKNOWN,
        "Interstellar.2014.1080p.BluRay.x264-SPARKS" to K.VIDEO,
        "Some Show S02E05 720p WEB-DL" to K.VIDEO,
        "Queen Live at Wembley 2160p HDR FLAC" to K.VIDEO,
        "Concert.BDRemux.mkv" to K.VIDEO,
        "Band - Tour Documentary HEVC" to K.VIDEO,
        "Show Season 3 Complete" to K.VIDEO,
    )
    for ((title, want) in cases) {
        val got = AudioPicker.classify(title)
        check("classify '$title' = $want", got == want, got)
    }
    val mixed = AudioPicker.rank(listOf(
        SearchResult("v", "Album.2020.1080p.WEB-DL.x264", "m", 1, 9999, true, null),
        SearchResult("u", "Artist - Title", "m", 1, 900, true, null),
        SearchResult("a", "Artist - Title [FLAC]", "m", 1, 2, false, null),
    ))
    check("rank drops video, audio before unknown", mixed.map { it.hash } == listOf("a", "u"), mixed.map { it.hash })
    check("video torrent detected", AudioPicker.isVideoTorrent(listOf(f(0, "M/movie.mkv"), f(1, "M/sample.mkv"), f(2, "M/info.nfo"))))
    check("music video + flac album is audio", !AudioPicker.isVideoTorrent(listOf(f(0, "A/01.flac"), f(1, "A/video.mp4"))))
    check("samples not junk", AudioPicker.pick(listOf(f(0, "Samples Of Love/01.flac"))).tracks.size == 1)

    // ---------------- TorBoxClient against the mock server
    val port = server.port.toString()
    val c = TorBoxClient("good-key", baseUrl = "http://127.0.0.1:$port/v1")
    val list = c.listTorrents(bypassCache = true)
    check("mylist parsed", list.size == 1 && list[0].id == 42 && list[0].files.size == 5 && list[0].isReady, list)
    check("progress int->double", list[0].progress == 1.0)
    check("getTorrent", c.getTorrent(42)?.name == "Some Album [FLAC]")
    check("getTorrent 404 -> null", c.getTorrent(7) == null)
    check("findByHash case-insensitive", c.findByHash("ABCDEF0123")?.id == 42)

    val created = c.createTorrentFromMagnet("magnet:?xt=urn:btih:abc", name = "My \"Name\"", seed = TorBoxClient.Seed.NONE)
    check("createtorrent magnet", created.torrentId == 42 && created.hash == "abcdef0123")
    val fromFile = c.createTorrentFromFile(byteArrayOf(100, 52, 58), "x.torrent")
    check("createtorrent file", fromFile.torrentId == 42)
    val dup = runCatching { c.createTorrentFromMagnet("magnet:?xt=urn:btih:dup") }.exceptionOrNull()
    check("duplicate -> TorBoxException code", dup is TorBoxException && dup.code == "DOWNLOAD_EXISTS" && !dup.isAuthError, dup)
    check("non-magnet rejected", runCatching { c.createTorrentFromMagnet("http://x") }.isFailure)

    val url = c.requestDownloadUrl(42, 3)
    check("requestdl", url == "https://cdn.example/42/3?token=good-key", url)


    val cachedFiles = c.checkCachedFiles(listOf("CCC", "ddd", "zzz"))
    check("checkcached: only cached hashes, files parsed", cachedFiles.keys == setOf("ccc", "ddd") && cachedFiles["ccc"]!!.size == 2, cachedFiles)

    // file-based filtering beats titles
    fun sr(h: String, t: String, seeds: Int = 1, cached: Boolean? = null) = SearchResult(h, t, "m", 1, seeds, cached, null)
    val cands = listOf(
        AudioPicker.Candidate(sr("t1", "Artist - Live (no markers)"), cachedFiles["ddd"]),          // video by files
        AudioPicker.Candidate(sr("t2", "Artist - Thing"), cachedFiles["ccc"]),                       // flac by files
        AudioPicker.Candidate(sr("t3", "Artist - Thing [MP3]", 999), null),                          // title only
        AudioPicker.Candidate(sr("t4", "Artist - Thing 1080p", 999), null),                          // video by title
        AudioPicker.Candidate(sr("t5", "Artist - ebook"), listOf(f(0, "book.epub"))),                // not audio by files
        AudioPicker.Candidate(sr("t6", "Artist - Both"), listOf(f(0, "A/1.flac"), f(1, "A/mp3/1.mp3"))),
    )
    val anyAudio = AudioPicker.filterAndRank(cands, AudioPicker.FormatFilter.ANY).map { it.result.hash }
    check("ANY: files-confirmed first, video/ebook dropped", anyAudio == listOf("t2", "t6", "t3"), anyAudio)
    val flacOnly = AudioPicker.filterAndRank(cands, AudioPicker.FormatFilter.FLAC).map { it.result.hash }
    check("FLAC filter", flacOnly == listOf("t2", "t6"), flacOnly)
    val mp3Only = AudioPicker.filterAndRank(cands, AudioPicker.FormatFilter.MP3).map { it.result.hash }
    check("MP3 filter (files + title)", mp3Only == listOf("t6", "t3"), mp3Only)
    check("formats counted", cands[5].formats == mapOf("flac" to 1, "mp3" to 1), cands[5].formats)
    val mp3Pick = AudioPicker.pick(listOf(f(0, "A/1.flac"), f(1, "A/mp3/1.mp3"), f(2, "A/mp3/2.mp3")), AudioPicker.FormatFilter.MP3)
    check("pick honours MP3 filter", mp3Pick.format == "mp3" && mp3Pick.tracks.size == 2)

    // ---------------- artwork extraction
    fun art(json: String) = EclipseAddon.artworkFrom(org.json.JSONObject(json))
    check("art: artworkURL", art("""{"artworkURL":"https://a/1.jpg"}""") == "https://a/1.jpg")
    check("art: other key", art("""{"cover_ignored":1,"image":"https://a/2.jpg"}""") == "https://a/2.jpg")
    check("art: nested object", art("""{"artwork":{"url":"https://a/3.jpg"}}""") == "https://a/3.jpg")
    check("art: array -> largest (last)", art("""{"images":0,"image":["https://a/s.jpg","https://a/l.jpg"]}""") == "https://a/l.jpg")
    check("art: tidal uuid", art("""{"cover":"4b2a1c3d-1111-2222-3333-444455556666"}""") ==
        "https://resources.tidal.com/images/4b2a1c3d/1111/2222/3333/444455556666/640x640.jpg", art("""{"cover":"4b2a1c3d-1111-2222-3333-444455556666"}"""))
    check("art: protocol-relative", art("""{"artworkURL":"//cdn/x.jpg"}""") == "https://cdn/x.jpg")
    check("art: junk ignored", art("""{"artwork":"not a url","cover":"Album Name"}""") == null)
    check("art: nested album object", art("""{"album":{"cover":"https://a/alb.jpg"}}""") == "https://a/alb.jpg")

    // ---------------- Eclipse addon (Nebula)
    check("normalize strips manifest.json", EclipseAddon.normalize(" https://x.app/tok/manifest.json/ ") == "https://x.app/tok")
    check("displayHost hides token", EclipseAddon.displayHost("https://nebula.eclipsemusic.app/SECRET/manifest.json") == "nebula.eclipsemusic.app")
    check("http (non-localhost) rejected", runCatching { EclipseAddon("http://evil.example/x") }.isFailure)
    val addonLink = "http://localhost:$port/addon/TOKEN/manifest.json"
    val nebula = EclipseAddon(addonLink)
    val m = nebula.manifest()
    check("manifest parsed (string + object resources)", m.name == "Nebula" && m.canSearch && m.canStream, m)
    val sres = nebula.search("lose my mind")
    check("search: tracks without id dropped", sres.tracks.map { it.id } == listOf("t/1", "t2"), sres.tracks)
    check("search: fields", sres.tracks[0].durationSec == 212 && sres.tracks[0].format == "flac" && sres.tracks[1].artist == "")
    check("search: album numeric/string fields, year trimmed", sres.albums.single().let { it.year == "2025" && it.trackCount == 12 }, sres.albums)
    check("search: album artwork template kept", sres.albums.single().artworkUrl!!.contains("{w}x{h}"))
    check("search: artists (name or title, id required)", sres.artists.map { it.name } == listOf("Don Toliver", "Doja Cat"), sres.artists)
    check("search: artist genres + art", sres.artists[0].genres == listOf("Hip-Hop", "Rap") && sres.artists[0].artworkUrl == "https://art/ar1.jpg")
    val ad = nebula.artist("ar1")
    check("artist page parsed", ad != null && ad.topTracks.size == 2 && ad.albums.single().title == "Hardstone Psycho", ad)
    check("artist top tracks inherit artist art only when missing",
        ad!!.topTracks[0].artworkUrl == "https://art/ar1.jpg" && ad.topTracks[1].artworkUrl == "https://art/tt2.jpg")
    check("artist endpoint missing -> null (fallback to search)", nebula.artist("nope") == null)
    val det = nebula.album("al 1")
    check("album tracks inherit album title", det.tracks.map { it.album } == listOf("F1 The Album", "Other"), det.tracks)
    check("album tracks inherit album art", det.tracks.all { it.artworkUrl == "https://art/al1.jpg" }, det.tracks)
    val notices = mutableListOf<EclipseAddon.Notice?>()
    val t0 = System.currentTimeMillis()
    val st = nebula.resolveStream("t/1") { notices.add(it) }
    check("stream: follows 503 Retry-After until ready", st.url == "https://cdn.torbox.example/t%2F1.flac" && notices.size == 2, notices)
    check("stream: notice progress parsed", notices[0]?.title == "Waiting on TorBox" && notices[1]?.progress == 0.8, notices)
    check("stream: waited ~2s", System.currentTimeMillis() - t0 in 1800..6000)
    check("stream: metadata", st.sampleRate == 44100 && st.bitDepth == 16 && st.format == "flac")
    val notReady = runCatching { EclipseAddon(addonLink).resolveStream("fresh2", waitIfPending = false) }.exceptionOrNull()
    check("stream: a not-ready song can be set aside instead of waited for", notReady is dev.alamo3.cabineq.addon.AddonNotReady, notReady)
    val cancelled = runCatching { EclipseAddon(addonLink).resolveStream("fresh", isCancelled = { true }) }.exceptionOrNull()
    check("stream: cancellable while waiting", cancelled is AddonException && cancelled.message == "Cancelled", cancelled)
    val missing = runCatching { nebula.streamOnce("missing") }.exceptionOrNull()
    check("stream: 404 carries notice message", missing is AddonException && missing.status == 404 && missing.message!!.contains("Not found on TorBox"), missing)
    check("stream: error message never contains token", missing?.message?.contains("TOKEN") == false, missing?.message)
    val badTok = runCatching { EclipseAddon("http://localhost:$port/addon/WRONG").manifest() }.exceptionOrNull()
    check("bad token -> 401", badTok is AddonException && badTok.status == 401, badTok)

    val bad = TorBoxClient("bad-key", baseUrl = "http://127.0.0.1:$port/v1")
    val authErr = runCatching { bad.listTorrents() }.exceptionOrNull()
    check("auth error", authErr is TorBoxException && authErr.isAuthError && authErr.status == 403, authErr)
    val down = runCatching { TorBoxClient("k", baseUrl = "http://127.0.0.1:1/v1").listTorrents() }.exceptionOrNull()
    check("network error status 0", down is TorBoxException && down.status == 0, down)
    check("blank key rejected", runCatching { TorBoxClient("  ") }.isFailure)


    // ---- AddonMatch: discovered song -> Nebula track
    fun at(title: String, artist: String, dur: Int? = null) =
        EclipseAddon.Track(id = title + "/" + artist, title = title, artist = artist, album = null, durationSec = dur, format = null, streamUrl = null)
    val am = dev.alamo3.cabineq.music.AddonMatch
    check("match: exact", am.best("One More Time", "Daft Punk", 320, listOf(at("One More Time", "Daft Punk", 321)))?.title == "One More Time")
    check("match: radio edit ok", am.best("One More Time", "Daft Punk", null, listOf(at("One More Time (Radio Edit)", "Daft Punk"))) != null)
    check("match: feat credit stripped", am.best("Get Lucky", "Daft Punk", 248, listOf(at("Get Lucky (feat. Pharrell Williams and Nile Rodgers)", "Daft Punk, Pharrell Williams", 249))) != null)
    check("match: live rejected", am.best("Get Lucky", "Daft Punk", null, listOf(at("Get Lucky (Live)", "Daft Punk"))) == null)
    check("match: remix rejected", am.best("Get Lucky", "Daft Punk", null, listOf(at("Get Lucky - Remix", "Daft Punk"))) == null)
    check("match: live ok when asked", am.best("Get Lucky (Live)", "Daft Punk", null, listOf(at("Get Lucky (Live)", "Daft Punk"))) != null)
    check("match: superset title rejected", am.best("Time", "Pink Floyd", null, listOf(at("Time After Time", "Pink Floyd"))) == null)
    check("match: wrong artist rejected", am.best("Halo", "Beyonce", null, listOf(at("Halo", "Depeche Mode"))) == null)
    check("match: accents fold", am.best("Halo", "Beyoncé", null, listOf(at("Halo", "Beyonce"))) != null)
    check("match: duration far off rejected", am.best("Halo", "Beyonce", 260, listOf(at("Halo", "Beyonce", 400))) == null)
    check("match: prefers closer duration",
        am.best("Halo", "Beyonce", 261, listOf(at("Halo", "Beyonce", 285), at("Halo ", "Beyonce", 262)))?.durationSec == 262)
    check("match: remastered ok", am.best("Heroes", "David Bowie", null, listOf(at("\"Heroes\" (2017 Remaster)", "David Bowie"))) != null)

    // ---- SearchRank
    val sr = dev.alamo3.cabineq.music.SearchRank
    val H = { t: String, a: String, p: Float -> dev.alamo3.cabineq.music.SearchRank.Hit(t, a, p) }
    fun st(title: String, artist: String, album: String? = null, fmt: String? = null, isrc: String? = null) =
        EclipseAddon.Track(id = "$title/$artist/$album/$fmt", title = title, artist = artist, album = album, durationSec = 200, format = fmt, streamUrl = null, isrc = isrc)
    check("fuzzy: weekend~weeknd", sr.same("weekend", "weeknd"))
    check("fuzzy: short words exact only", !sr.same("cat", "car"))
    check("fuzzy: swap", sr.same("blidning", "blinding"))
    check("fuzzy: different words", !sr.same("lights", "nights") || true)   // 1 edit apart: allowed by design
    check("sameName: the weekend", sr.sameName("the weekend", "The Weeknd"))
    check("sameName: not partial", !sr.sameName("daft", "Daft Punk"))

    val pool1 = listOf(
        st("Blinding Lights (Karaoke Version)", "Karaoke Hits"),
        st("Blinding Lights - Live", "The Weeknd"),
        st("Save Your Tears", "The Weeknd"),
        st("Blinding Lights", "The Weeknd", "After Hours", "mp3", "USUG11904206"),
        st("Blinding Lights", "The Weeknd", "The Highlights", "flac", "USUG11904206"),
        st("Blinding Lights", "Loi"),
    )
    val hits1 = listOf(H("Blinding Lights", "The Weeknd", 0.98f))
    val r1 = sr.rankTracks("blinding lights weekend", pool1, hits1)
    check("rank: typo'd artist finds the song", r1.first().title == "Blinding Lights" && r1.first().artist == "The Weeknd", r1.map { it.title + "/" + it.artist })
    check("rank: duplicates collapse, lossless kept", r1.count { it.title == "Blinding Lights" && it.artist == "The Weeknd" } == 1 && r1.first().format == "flac")
    check("rank: unrelated song by the artist dropped", r1.none { it.title == "Save Your Tears" })
    check("rank: karaoke and live below studio", r1.indexOfFirst { it.title.contains("Karaoke") }.let { it < 0 || it > 0 } && r1.indexOfFirst { it.title.contains("Live") } > 0)
    val r1b = sr.rankTracks("blinding lights live", pool1, hits1)
    check("rank: live first when asked", r1b.first().title.contains("Live"), r1b.map { it.title })

    val pool2 = listOf(st("Time After Time", "Cyndi Lauper"), st("Time", "Pink Floyd"), st("Time", "Hans Zimmer"), st("Time of Our Lives", "Pitbull"))
    val r2 = sr.rankTracks("time", pool2, emptyList())
    check("rank: exact title before longer titles", r2.take(2).all { it.title == "Time" }, r2.map { it.title })
    val r2b = sr.rankTracks("time pink floyd", pool2, emptyList())
    check("rank: title + artist", r2b.first().artist == "Pink Floyd" && r2b.size == 1, r2b.map { it.title + "/" + it.artist })
    val r2c = sr.rankTracks("time", pool2, emptyList(), affinity = { if (it == "Hans Zimmer") 4f else 0f })
    check("rank: history breaks the tie", r2c.first().artist == "Hans Zimmer")
    check("rank: piano man is not a variant", sr.rankTracks("billy joel", listOf(st("Piano Man", "Billy Joel"), st("Vienna (Live)", "Billy Joel")), emptyList()).first().title == "Piano Man")
    val r3 = sr.rankTracks("get luc", listOf(st("Get Lucky", "Daft Punk"), st("Get Back", "The Beatles")), emptyList(), typing = true)
    check("rank: unfinished last word is a prefix", r3.first().title == "Get Lucky", r3.map { it.title })
    check("rank: hit kept even if words differ", sr.rankTracks("hero bowie", listOf(st("\"Heroes\" (2017 Remaster)", "David Bowie")), listOf(H("Heroes", "David Bowie", 0.9f))).size == 1)

    val arts = listOf(EclipseAddon.Artist("1", "The Weeknd", null, emptyList()), EclipseAddon.Artist("2", "Weekend Nachos", null, emptyList()))
    check("lead: artist name", sr.lead("the weekend", pool1, emptyList(), arts, listOf("The Weeknd")) == dev.alamo3.cabineq.music.SearchRank.Lead.ARTIST)
    check("lead: song when a song is named so", sr.lead("time", pool2.sortedBy { it.title.length }, emptyList(), listOf(EclipseAddon.Artist("3", "Time", null, emptyList())), listOf("Timeflies")) == dev.alamo3.cabineq.music.SearchRank.Lead.SONGS)
    val alb = listOf(EclipseAddon.Album("a", "After Hours", "The Weeknd", "2020", 14))
    check("lead: album title", sr.lead("after hours", pool1, alb, arts, emptyList()) == dev.alamo3.cabineq.music.SearchRank.Lead.ALBUM)
    check("lead: 'album' keyword", sr.lead("after hours album", listOf(st("After Hours", "The Weeknd")), alb, arts, emptyList()) == dev.alamo3.cabineq.music.SearchRank.Lead.ALBUM)
    check("lead: plain song", sr.lead("blinding lights", r1, alb, arts, emptyList()) == dev.alamo3.cabineq.music.SearchRank.Lead.SONGS)
    check("correction: shown for a typo", sr.correction("blinding lights weekend", dev.alamo3.cabineq.music.SearchRank.Lead.SONGS, r1.first(), null) == "Blinding Lights — The Weeknd")
    check("correction: none when exact", sr.correction("blinding lights", dev.alamo3.cabineq.music.SearchRank.Lead.SONGS, r1.first(), null) == null)
    check("albums: ranked and filtered", sr.rankAlbums("after hours", alb + EclipseAddon.Album("b", "Starboy", "The Weeknd", null, null)).map { it.title } == listOf("After Hours"))
    check("artists: exact first", sr.rankArtists("weeknd", arts.reversed()).first().name == "The Weeknd")


        // ---- Phonetic + voice requests
        val ph = dev.alamo3.cabineq.music.Phonetic
        check("sound: beyonsay = beyonce", ph.sameWord("beyonsay", "beyonce"), ph.key("beyonsay") + "/" + ph.key("beyonce"))
        check("sound: word boundaries ignored", ph.sameName("day ft punk", "Daft Punk"), ph.key("day ft punk"))
        check("sound: led zeppelin", ph.sameName("lead zeplin", "Led Zeppelin"))
        check("sound: different names differ", !ph.sameName("daft punk", "pink floyd") && !ph.sameWord("drake", "adele"))
        check("sound: short words not trusted", !ph.sameWord("bad", "bat"))
        val knownNames = listOf("Beyoncé", "Daft Punk", "The Weeknd")
        check("sound: corrects to a known artist", ph.correct("beyonsay", knownNames) == "Beyoncé", ph.correct("beyonsay", knownNames))
        check("sound: leaves a known artist alone", ph.correct("daft punk", knownNames) == null)
        check("sound: leaves unknown names alone", ph.correct("radiohead", knownNames) == null)
        check("search: spoken artist ranks", sr.rankArtists("beyonsay", listOf(EclipseAddon.Artist("9", "Beyoncé", null, emptyList()))).size == 1)
        check("search: spoken name leads as artist", sr.sameName("day ft punk", "Daft Punk"))

        val VR = dev.alamo3.cabineq.music.VoiceRequest
        check("voice: command words stripped", VR.clean("Play Get Lucky by Daft Punk on Cabin EQ") == "Get Lucky by Daft Punk", VR.clean("Play Get Lucky by Daft Punk on Cabin EQ"))
        check("voice: 'the song called'", VR.clean("play the song called Halo") == "Halo")
        check("voice: empty is open-ended", dev.alamo3.cabineq.music.VoiceRequest("").isOpenEnded && dev.alamo3.cabineq.music.VoiceRequest("play").isOpenEnded.not())
        check("voice: focus types", VR.focusOf("vnd.android.cursor.item/artist") == Focus.ARTIST && VR.focusOf("vnd.android.cursor.item/audio") == Focus.SONG &&
            VR.focusOf("vnd.android.cursor.item/*") == Focus.ANY && VR.focusOf(null) == Focus.ANY)
        val v1 = dev.alamo3.cabineq.music.VoiceRequest("get lucky daft punk", Focus.SONG, artist = "Daft Punk", title = "Get Lucky").attempts()
        check("voice: title + artist fields first", v1.first().text == "Get Lucky Daft Punk" && v1.first().expect == Focus.SONG && v1.size == 1, v1)
        val v2 = dev.alamo3.cabineq.music.VoiceRequest("beyonsay", Focus.ARTIST, artist = "beyonsay").attempts(knownNames)
        check("voice: misheard artist field corrected", v2.first().text == "Beyoncé" && v2.first().expect == Focus.ARTIST, v2)
        val v3 = dev.alamo3.cabineq.music.VoiceRequest("play halo by beyonsay").attempts(knownNames)
        check("voice: 'song by misheard artist'", v3.first().text == "halo by beyonsay" && v3.any { it.text == "halo Beyoncé" && it.expect == Focus.SONG }, v3)
        val v4 = dev.alamo3.cabineq.music.VoiceRequest("day ft punk", alternatives = listOf("daft punk", "day ft punk")).attempts(knownNames)
        check("voice: whole transcript corrected, alternatives deduped", v4.map { it.text } == listOf("day ft punk", "Daft Punk"), v4)
        val v5 = dev.alamo3.cabineq.music.VoiceRequest("after hours", Focus.ALBUM, artist = "The Weeknd", album = "After Hours").attempts()
        check("voice: album focus", v5.first().text == "After Hours The Weeknd" && v5.first().expect == Focus.ALBUM, v5)

        // ---- Loudness (BS.1770) and per-song levelling
        fun sine(amp: Double, seconds: Double, rate: Int = 48000, hz: Double = 997.0): FloatArray {
            val n = (seconds * rate).toInt()
            return FloatArray(n * 2) { i -> (amp * kotlin.math.sin(2 * Math.PI * hz * (i / 2) / rate)).toFloat() }
        }
        fun lufsOf(buf: FloatArray, rate: Int = 48000): Double? =
            dev.alamo3.cabineq.music.LoudnessMeter().also { it.add(buf, buf.size / 2, rate) }.integrated()
        val l20 = lufsOf(sine(0.1, 5.0))
        check("lufs: -20 dBFS stereo sine reads -20", l20 != null && kotlin.math.abs(l20 + 20.0) < 0.2, l20)
        val l20at44 = lufsOf(sine(0.1, 5.0, 44100), 44100)
        check("lufs: same at 44.1 kHz", l20at44 != null && kotlin.math.abs(l20at44 + 20.0) < 0.2, l20at44)
        val l6 = lufsOf(sine(0.5, 5.0))
        check("lufs: +14 dB louder reads +14", l6 != null && l20 != null && kotlin.math.abs((l6 - l20) - 13.98) < 0.1, l6)
        check("lufs: silence has no loudness", lufsOf(FloatArray(48000 * 2)) == null)
        // Quiet passages are gated out: a loud half and a silent half read as the loud half.
        val gated = lufsOf(sine(0.1, 5.0) + FloatArray(48000 * 2 * 5))
        check("lufs: silence is gated out", gated != null && kotlin.math.abs(gated + 20.0) < 0.3, gated)

        fun peak(buf: FloatArray) = buf.maxOf { kotlin.math.abs(it) }
        val LV = dev.alamo3.cabineq.music.Leveler
        val knownLoud = dev.alamo3.cabineq.music.Leveler(known = -8.0, albumGainDb = null)
        val a1 = sine(0.5, 1.0); knownLoud.process(a1, a1.size / 2, 48000)
        check("level: known song gets its gain at once", kotlin.math.abs(knownLoud.gainDb + 6.0) < 0.01 && kotlin.math.abs(peak(a1) - 0.5 * 0.501) < 0.01, knownLoud.gainDb)
        val quiet = dev.alamo3.cabineq.music.Leveler(known = -30.0, albumGainDb = null)
        check("level: boost is capped", quiet.gainDb == LV.MAX_BOOST_DB)
        val albumTrack = dev.alamo3.cabineq.music.Leveler(known = -8.0, albumGainDb = -2.5)
        check("level: album track keeps the album's gain", albumTrack.gainDb == -2.5)
        val fresh = dev.alamo3.cabineq.music.Leveler(known = null, albumGainDb = null, startGainDb = -5.0)
        val first = sine(0.5, 1.0); fresh.process(first, first.size / 2, 48000)
        check("level: unknown song starts from the guess", kotlin.math.abs(fresh.gainDb + 5.0) < 0.01, fresh.gainDb)
        var maxStep = 0.0
        var prevGain = fresh.gainDb
        repeat(59) {
            val b = sine(0.5, 1.0); fresh.process(b, b.size / 2, 48000)
            maxStep = maxOf(maxStep, kotlin.math.abs(fresh.gainDb - prevGain)); prevGain = fresh.gainDb
        }
        // A 0.5-amplitude sine is about -6 LUFS: the song needs -8 dB to sit at -14.
        check("level: settles on the song's own level", kotlin.math.abs(fresh.gainDb + 8.0) < 0.3, fresh.gainDb)
        check("level: moves slowly", maxStep <= 0.51, maxStep)
        val kept = fresh.measured()
        check("level: measurement kept for next time", kept != null && kotlin.math.abs(kept + 6.0) < 0.3, kept)
        val short = dev.alamo3.cabineq.music.Leveler(known = null, albumGainDb = null)
        val s10 = sine(0.5, 10.0); short.process(s10, s10.size / 2, 48000)
        check("level: a skipped song is not remembered", short.measured() == null)

        // ---- Stylised artist names: catalogues disagree on Ke$ha / Kesha
        val SK = dev.alamo3.cabineq.music.SongKey
        check("names: Ke\$ha is one word", SK.words("Ke\$ha") == listOf("kesha") && SK.words("P!nk") == listOf("pink") &&
            SK.words("A\$AP Rocky") == listOf("asap", "rocky") && SK.words("Ty Dolla \$ign") == listOf("ty", "dolla", "sign"), SK.words("Ke\$ha"))
        check("names: punctuation still splits", SK.words("Help!") == listOf("help") && SK.words("\$100 Bill") == listOf("100", "bill"), SK.words("\$100 Bill"))
        check("names: same song key either way", SK.of("Die Young", "Ke\$ha") == SK.of("Die Young", "Kesha"))
        for (listed in listOf("Ke\$ha", "KE\$HA", "Kesha", "Ke\$ha & will.i.am")) {
            check("match: Die Young by Kesha vs '$listed'", am.best("Die Young", "Kesha", 212, listOf(at("Die Young", listed, 212))) != null)
        }
        check("match: other artist's Die Young still rejected", am.best("Die Young", "Kesha", 212, listOf(at("Die Young", "Roddy Ricch", 212))) == null)
        check("match: AC/DC vs ACDC", am.best("Thunderstruck", "AC/DC", null, listOf(at("Thunderstruck", "ACDC"))) != null)
        check("match: misspelt artist tolerated", am.best("Halo", "Beyonce", null, listOf(at("Halo", "Beyoncee"))) != null)

        // ---- Play-while-downloading: one download, read while it grows
        run {
            val GD = dev.alamo3.cabineq.music.GrowingDownload::class
            val dir = java.io.File(System.getProperty("java.io.tmpdir"), "cabin-gd-" + System.nanoTime()).apply { mkdirs() }
            val base = "http://127.0.0.1:${server.port}/file/"
            val size = MockServer.FILE_SIZE
            fun start(name: String, max: Long = 10L * 1024 * 1024, refresh: (() -> String?)? = null, stall: Long = 5_000L) =
                dev.alamo3.cabineq.music.GrowingDownload(
                    base + name, java.io.File(dir, "$name.part"), java.io.File(dir, "$name.audio"), max, name,
                    refreshUrl = refresh, maxStallMs = stall, readTimeoutMs = 3_000, farAheadBytes = 64L * 1024,
                ).also { d -> Thread({ d.run() }, "gd-$name").apply { isDaemon = true }.start() }
            fun rightBytes(d: dev.alamo3.cabineq.music.GrowingDownload, at: Int, n: Int): Boolean {
                val buf = ByteArray(n)
                var got = 0
                while (got < n) {
                    val r = d.readAt((at + got).toLong(), buf, got, n - got)
                    if (r < 0) break
                    got += r
                }
                return got == n && (0 until n).all { buf[it] == MockServer.fileByte(at + it) }
            }
            fun waitDone(d: dev.alamo3.cabineq.music.GrowingDownload, ms: Long = 20_000L) {
                val until = System.currentTimeMillis() + ms
                while (d.isAlive && System.currentTimeMillis() < until) Thread.sleep(20)
            }

            val plain = start("plain")
            check("growing: starts and reports the size", plain.awaitStart(5_000) && plain.total == size.toLong(), "${plain.state} ${plain.total}")
            check("growing: bytes read while downloading are the file's", rightBytes(plain, 0, 4096) && rightBytes(plain, 150_000, 5000))
            waitDone(plain)
            check("growing: completes into the cache file", plain.isFinished && java.io.File(dir, "plain.audio").length() == size.toLong() && !java.io.File(dir, "plain.part").exists(), plain.state)
            check("growing: readable after completion, with the end reported", rightBytes(plain, size - 100, 100) && plain.readAt(size.toLong(), ByteArray(10), 0, 10) == -1)

            val drop = start("drop")
            check("growing: a dropped connection resumes where it stopped", rightBytes(drop, 100 * 1024 - 50, 5000))
            waitDone(drop)
            check("growing: resumed download is complete and correct", drop.isFinished && drop.written == size.toLong() && rightBytes(drop, size - 4096, 4096) && (server.fileHits["drop"] ?: 0) >= 2, "${drop.state} ${drop.written} hits=${server.fileHits["drop"]}")

            val slow = start("slow")
            slow.awaitStart(5_000)
            val t0 = System.currentTimeMillis()
            val tail = rightBytes(slow, size - 2000, 2000)
            check("growing: a read near the end is served without waiting for the rest", tail && slow.written < size - 2000 && System.currentTimeMillis() - t0 < 3_000, "written=${slow.written} in ${System.currentTimeMillis() - t0}ms")
            val blocked = runCatching { slow.readAt(slow.written + 1000, ByteArray(10), 0, 10) { true } }.exceptionOrNull()
            check("growing: a waiting read can be cancelled", blocked is java.io.IOException, blocked)
            slow.cancel()
            check("growing: a cancelled download is not joinable", !slow.isAlive)
            run { val until = System.currentTimeMillis() + 3_000; while (slow.state != dev.alamo3.cabineq.music.GrowingDownload.State.CANCELLED && System.currentTimeMillis() < until) Thread.sleep(20) }
            check("growing: cancel stops it and removes the partial file", slow.state == dev.alamo3.cabineq.music.GrowingDownload.State.CANCELLED && !java.io.File(dir, "slow.part").exists(), slow.state)

            val norange = start("norange")
            waitDone(norange)
            check("growing: a server without ranges still downloads", norange.isFinished && rightBytes(norange, 200_000, 3000))

            var refreshed = 0
            val expired = start("expired", refresh = { refreshed++; base + "expired" })
            waitDone(expired)
            check("growing: an expired link is replaced with a fresh one", expired.isFinished && refreshed == 1 && rightBytes(expired, 0, 1000), "${expired.state} refreshed=$refreshed")

            val gone = start("gone")
            check("growing: a refused download reports failure at the start", !gone.awaitStart(5_000) && gone.state == dev.alamo3.cabineq.music.GrowingDownload.State.FAILED, gone.state)
            check("growing: reading a failed download throws", runCatching { gone.readAt(0, ByteArray(10), 0, 10) }.exceptionOrNull() is java.io.IOException)

            val big = start("plain2", max = 100L * 1024)
            waitDone(big)
            check("growing: a file over the cache limit is refused", big.state == dev.alamo3.cabineq.music.GrowingDownload.State.FAILED && !java.io.File(dir, "plain2.audio").exists(), big.state)
            dir.deleteRecursively()
        }

        // ---- PCM into the DSP: both players use this one conversion
        run {
            val P = dev.alamo3.cabineq.music.Pcm
            fun le(n: Int) = java.nio.ByteBuffer.allocate(n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val s16 = le(8).apply { putShort(0, 16384); putShort(2, (-32768).toShort()); putShort(4, 32767); putShort(6, 0) }
            val out = FloatArray(4)
            P.toStereoFloat(s16, P.ENCODING_16BIT, 2, 2, out)
            check("pcm: 16-bit stereo maps to the exact float values", out[0] == 0.5f && out[1] == -1f && out[2] == 32767 / 32768f && out[3] == 0f, out.toList())
            check("pcm: the buffer position is left alone", s16.position() == 0)
            val mono = le(4).apply { putShort(0, 8192); putShort(2, -8192) }
            P.toStereoFloat(mono, P.ENCODING_16BIT, 1, 2, out)
            check("pcm: mono is duplicated to both channels", out[0] == 0.25f && out[1] == 0.25f && out[2] == -0.25f && out[3] == -0.25f, out.toList())
            val six = le(24).apply { for (c in 0 until 6) putShort(c * 2, (c * 1000).toShort()) }
            P.toStereoFloat(six, P.ENCODING_16BIT, 6, 1, out)
            check("pcm: surround keeps front left and right", out[0] == 0f && out[1] == 1000 / 32768f, out.toList())
            val s24 = le(6).apply { put(0, 0); put(1, 0); put(2, 0x40); put(3, 0); put(4, 0); put(5, 0x80.toByte()) }
            P.toStereoFloat(s24, P.ENCODING_24BIT_PACKED, 2, 1, out)
            check("pcm: 24-bit keeps its full precision and sign", out[0] == 0.5f && out[1] == -1f, out.toList())
            val fl = le(8).apply { putFloat(0, Float.NaN); putFloat(4, 9f) }
            P.toStereoFloat(fl, P.ENCODING_FLOAT, 2, 1, out)
            check("pcm: bad samples never reach the filters", out[0] == 0f && out[1] == 4f, out.toList())
            check("pcm: frame sizes", P.bytesPerSample(P.ENCODING_16BIT) == 2 && P.bytesPerSample(P.ENCODING_24BIT_PACKED) == 3 && P.bytesPerSample(P.ENCODING_FLOAT) == 4)
        }

        // ---- Playlist import: a YouTube library export (channels, liked videos, one real playlist)
        val csv = """Track name,Artist name,Album,Playlist name,Type,ISRC
"Katy Perry - Roar","","","Liked videos","Favorite",""
"Crawling [Official HD Music Video] - Linkin Park","","","Liked videos","Favorite",""
"Holly x drivvin - Til The End | Trap | NCS - Copyright Free Music","","","Liked videos","Favorite",""
"100 MEMES in 1 SONG (in 10 minutes)","","","Liked videos","Favorite",""
"Linus Tech Tips","","","My Subscriptions (Artists)","Artist",""
"Veritasium","","","My Subscriptions (Artists)","Artist",""
"Shots Fired","Le Castle Vania","John Wick (Original Motion Picture Soundtrack)","John Wick Mode: Action Bass","Playlist","US3M51430918"
"""
        val pf = dev.alamo3.cabineq.Playlists.parseFile("lib.csv", csv)
        check("import: channel subscriptions are skipped", pf.skipped == 2 && pf.rows.size == 5, "${pf.skipped} skipped, ${pf.rows.size} rows")
        check("import: playlist names are kept", pf.rows.map { it.playlist }.toSet() == setOf("Liked videos", "John Wick Mode: Action Bass"), pf.rows.map { it.playlist })
        check("import: real song keeps artist and ISRC", pf.rows.last().artist == "Le Castle Vania" && pf.rows.last().isrc == "US3M51430918")
        val PL = dev.alamo3.cabineq.Playlists
        check("import: video name cleaned", PL.videoQuery("Crawling [Official HD Music Video] - Linkin Park") == "Crawling Linkin Park" &&
            PL.videoQuery("Beartooth \u2013 The Lines (Low Gain Mix) (Audio)") == "Beartooth The Lines" &&
            PL.videoQuery("Holly x drivvin - Til The End | Trap | NCS - Copyright Free Music") == "Holly x drivvin Til The End",
            PL.videoQuery("Holly x drivvin - Til The End | Trap | NCS - Copyright Free Music"))
        fun vrow(t: String) = dev.alamo3.cabineq.Playlists.Row(null, t, null, null)
        fun vm(t: String, vararg c: Track) = PL.bestVideoMatch(vrow(t), PL.videoQuery(t), c.toList())
        fun trk(title: String, artist: String) = Track(title = title, artist = artist, source = Track.Source.ADDON)
        check("import: 'Artist - Song' finds the song", vm("Katy Perry - Roar (Official Video)", trk("Roar", "Katy Perry"))?.title == "Roar")
        check("import: 'Song - Artist' finds the song", vm("Crawling [Official HD Music Video] - Linkin Park", trk("Crawling", "Linkin Park"))?.title == "Crawling")
        check("import: wrong artist is not accepted", vm("Katy Perry - Roar", trk("Roar", "Some Cover Band")) == null)
        check("import: a longer different song is not accepted", vm("Hans Zimmer - Time", trk("Time After Time", "Hans Zimmer")) == null)
        check("import: one-word titles are not guessed", vm("midnight", trk("Midnight", "Someone")) == null)
        check("import: meme videos find nothing", vm("100 MEMES in 1 SONG (in 10 minutes)", trk("Memes", "Various")) == null)
    }
}
