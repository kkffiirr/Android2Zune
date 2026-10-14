package io.github.kkffiirr.zuneorganizer

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** One suggestion: a recording as it appears on one release. */
class Candidate(val artist: String, val album: String, val year: String, val track: String, val kinds: String,
                val lengthMatches: Boolean, val score: Int, val recordingId: String, val groupId: String, val releaseId: String = "")

/**
 * Tag suggestions from MusicBrainz (free, no key; asks for ~1 request/second and a descriptive User-Agent).
 * Sends only what the song already carries: title, plus artist, album and duration when known.
 * The result is advice for the user to pick from; nothing here writes to the Zune.
 */
object MusicBrainz {
    private const val UA = "ZuneOrganizer/0.12 ( https://github.com/kkffiirr/Android2Zune )"
    private const val LENGTH_SLACK_MS = 3000   // the duration filter and the "length matches" tick

    private fun esc(s: String) = s.replace("\\", " ").replace("\"", " ").trim()

    /** MusicBrainz answers 503/429 when it is asked too fast; back off and retry instead of failing the whole batch. */
    private fun fetch(query: String): JSONObject = getJson("https://musicbrainz.org/ws/2/recording?fmt=json&limit=25&query=" + URLEncoder.encode(query, "UTF-8"))

    private fun getJson(address: String): JSONObject {
        val url = URL(address)
        var last = "no answer"
        for (attempt in 1..4) {
            try {
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000; c.setRequestProperty("User-Agent", UA)
                val code = c.responseCode
                if (code == 200) return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                last = "MusicBrainz answered HTTP $code"
                if (code != 503 && code != 429) error(last)
            } catch (e: java.io.IOException) { last = e.message ?: e.toString() }
            Thread.sleep(2000L * attempt)
        }
        error(last)
    }

    /** Blocking. Tries the most specific query first and relaxes it until something comes back. */
    fun suggest(title: String, artist: String?, album: String, durMs: Int): List<Candidate> {
        val t = "recording:\"${esc(title)}\""
        val a = if (artist.isNullOrBlank()) null else "artist:\"${esc(artist)}\""
        val d = if (durMs > 0) "dur:[${maxOf(0, durMs - LENGTH_SLACK_MS)} TO ${durMs + LENGTH_SLACK_MS}]" else null
        val queries = listOfNotNull(
            listOfNotNull(t, a, d), listOfNotNull(t, d), listOfNotNull(t, a), listOf(t)
        ).map { it.joinToString(" AND ") }.distinct()
        var recordings = org.json.JSONArray()
        for ((i, q) in queries.withIndex()) {
            if (i > 0) Thread.sleep(1100)   // be polite: one request per second
            recordings = fetch(q).optJSONArray("recordings") ?: org.json.JSONArray()
            if (recordings.length() > 0) break
        }
        val wantAlbum = album.lowercase()
        val out = LinkedHashMap<String, Candidate>()
        for (i in 0 until recordings.length()) {
            val r = recordings.getJSONObject(i)
            val credit = r.optJSONArray("artist-credit")
            val who = buildString {
                if (credit != null) for (k in 0 until credit.length()) {
                    val ac = credit.getJSONObject(k)
                    append(ac.optString("name", ac.optJSONObject("artist")?.optString("name").orEmpty())); append(ac.optString("joinphrase"))
                }
            }.ifBlank { "(unknown artist)" }
            val len = r.optInt("length", 0)
            val lenOk = durMs > 0 && len > 0 && abs(len - durMs) <= LENGTH_SLACK_MS / 2
            val rels = r.optJSONArray("releases") ?: continue
            for (j in 0 until rels.length()) {
                val rel = rels.getJSONObject(j)
                val title0 = rel.optString("title"); val low = title0.lowercase()
                val rg = rel.optJSONObject("release-group")
                val kinds = buildList {
                    rg?.optString("primary-type")?.takeIf { it.isNotEmpty() && it != "null" }?.let { add(it) }
                    rg?.optJSONArray("secondary-types")?.let { s -> for (k in 0 until s.length()) add(s.getString(k)) }
                }.joinToString(", ")
                var number = ""
                rel.optJSONArray("media")?.let { m -> for (k in 0 until m.length()) m.getJSONObject(k).optJSONArray("track")?.let { tr ->
                    if (tr.length() > 0 && number.isEmpty()) number = tr.getJSONObject(0).optString("number") } }
                val trackNo = Regex("(\\d+)").find(number)?.value.orEmpty()   // vinyl sides like "A4" become 4
                var score = r.optInt("score", 0) + (if (lenOk) 30 else 0)
                if (wantAlbum.isNotEmpty() && (low.contains(wantAlbum.take(20)) || wantAlbum.contains(low.take(20)))) score += 15
                val c = Candidate(who, title0, rel.optString("date").take(4), trackNo, kinds, lenOk, score, r.optString("id"), rg?.optString("id").orEmpty(), rel.optString("id"))
                val key = "$who|$low|${c.year}|$trackNo"
                if (out[key]?.let { it.score >= score } != true) out[key] = c
            }
        }
        return out.values.sortedByDescending { it.score }.take(15)
    }

    private fun lookup(path: String): JSONObject {
        val c = URL("https://musicbrainz.org/ws/2/$path?inc=genres&fmt=json").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000; c.setRequestProperty("User-Agent", UA)
        if (c.responseCode != 200) error("MusicBrainz answered HTTP ${c.responseCode}")
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    }

    /** Blocking, up to two requests. Genres of the recording first, then of the album, most-voted first; empty if none known. */
    fun genres(c: Candidate): List<String> {
        val found = LinkedHashMap<String, Int>()
        fun add(o: JSONObject) {
            val g = o.optJSONArray("genres") ?: return
            val list = (0 until g.length()).map { g.getJSONObject(it) }.sortedByDescending { it.optInt("count") }
            for (x in list) found.putIfAbsent(x.optString("name"), x.optInt("count"))
        }
        runCatching { if (c.recordingId.isNotEmpty()) add(lookup("recording/${c.recordingId}")) }
        if (c.groupId.isNotEmpty()) { Thread.sleep(1100); runCatching { add(lookup("release-group/${c.groupId}")) } }
        return found.keys.filter { it.isNotBlank() }.map { n -> n.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } }.distinct().take(8)
    }

    class RTrack(val title: String, val lengthMs: Int, val number: String, val artist: String)
    class ReleaseInfo(val id: String, val title: String, val year: String, val kinds: String, val tracks: List<RTrack>)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun sameArtist(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
    }

    /** Blocking, one request: the full track list of a release, so many songs of one album can be matched without further requests. */
    fun release(id: String): ReleaseInfo {
        val r = getJson("https://musicbrainz.org/ws/2/release/$id?inc=recordings+artist-credits&fmt=json")
        val rg = r.optJSONObject("release-group")
        val kinds = buildList {
            rg?.optString("primary-type")?.takeIf { it.isNotEmpty() && it != "null" }?.let { add(it) }
            rg?.optJSONArray("secondary-types")?.let { a -> for (k in 0 until a.length()) add(a.getString(k)) }
        }.joinToString(", ")
        val tracks = ArrayList<RTrack>()
        val media = r.optJSONArray("media")
        if (media != null) for (m in 0 until media.length()) {
            val ts = media.getJSONObject(m).optJSONArray("tracks") ?: continue
            for (k in 0 until ts.length()) {
                val t = ts.getJSONObject(k); val ac = t.optJSONArray("artist-credit")
                val who = buildString { if (ac != null) for (q in 0 until ac.length()) { val c = ac.getJSONObject(q); append(c.optString("name")); append(c.optString("joinphrase")) } }
                tracks += RTrack(t.optString("title"), t.optInt("length", 0), t.optString("number"), who)
            }
        }
        return ReleaseInfo(id, r.optString("title"), r.optString("date").take(4), kinds, tracks)
    }

    /**
     * Matches one song against a downloaded release: same length (within 1.5 s) AND a title that equals or contains the
     * other (MusicBrainz often prefixes episode names), and the same artist when the song has one. Null if not on this release.
     */
    fun matchLocal(rel: ReleaseInfo, title: String, durMs: Int, knownArtist: String?): Candidate? {
        val n = norm(title)
        val pick = rel.tracks.filter { it.lengthMs > 0 && abs(it.lengthMs - durMs) <= LENGTH_SLACK_MS / 2 }
            .filter { val t = norm(it.title); t == n || t.contains(n) || n.contains(t) }
            .minByOrNull { abs(it.lengthMs - durMs) } ?: return null
        if (knownArtist != null && pick.artist.isNotEmpty() && !sameArtist(pick.artist, knownArtist)) return null
        return Candidate(pick.artist.ifEmpty { knownArtist.orEmpty() }, rel.title, rel.year, Regex("(\\d+)").find(pick.number)?.value.orEmpty(),
            rel.kinds, true, 100, "", "", rel.id)
    }
}
