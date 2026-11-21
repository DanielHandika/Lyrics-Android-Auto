package com.spotify.aalyrics.repository

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Repository to fetch synchronized lyrics from LRCLIB.net (free, community-driven, no API key).
 */
object LyricsRepository {
    private const val TAG = "LyricsRepository"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // In-memory cache for recent tracks to avoid repeated network calls
    private val lyricsCache = ConcurrentHashMap<String, LyricsResult>()

    data class LyricsResult(
        val lines: List<String>,
        val timestamps: List<Long> // in milliseconds
    )

    /**
     * Clean track title from common Spotify suffixes (e.g., "- Remastered", "(feat. X)")
     * to dramatically improve search match rates on LRCLIB.
     */
    fun cleanTrackTitle(title: String): String {
        return title
            .replace(Regex("""(?i)\s*-\s*(remaster(ed)?(\s*\d+)?|live(\s*at\s*.*)?|radio edit|bonus track|single version|deluxe).*"""), "")
            .replace(Regex("""(?i)\s*[\(\[](feat\.|featuring|with|ft\.).*?[\)\]]"""), "")
            .trim()
    }

    /**
     * Clean artist name in case of multiple artists.
     */
    fun cleanArtistName(artist: String): String {
        return artist.split(",", ";", " feat.", " ft.", " & ").firstOrNull()?.trim() ?: artist
    }

    /**
     * Fetch lyrics (must be executed in a background thread / coroutine).
     */
    fun fetchLyrics(title: String, artist: String): LyricsResult? {
        val cacheKey = "${title.trim().lowercase()}_${artist.trim().lowercase()}"
        lyricsCache[cacheKey]?.let { return it }

        val cleanTitle = cleanTrackTitle(title)
        val cleanArtist = cleanArtistName(artist)

        // Try queries in order of precision:
        // 1. Cleaned title + Cleaned artist
        // 2. Original title + Original artist
        // 3. Cleaned title only
        val queries = mutableListOf<String>()
        queries.add("$cleanTitle $cleanArtist")
        if (cleanTitle != title || cleanArtist != artist) {
            queries.add("$title $artist")
        }
        if (cleanTitle.isNotBlank() && cleanTitle != queries[0]) {
            queries.add(cleanTitle)
        }

        for (query in queries) {
            val result = queryLrcLib(query)
            if (result != null && result.lines.isNotEmpty()) {
                lyricsCache[cacheKey] = result
                return result
            }
        }

        return null
    }

    private fun queryLrcLib(query: String): LyricsResult? {
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://lrclib.net/api/search?q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "SpotifyAALyrics/1.0.0 (personal use Android Auto)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to fetch lyrics for '$query': HTTP ${response.code}")
                    return null
                }

                val bodyStr = response.body?.string() ?: return null
                val jsonArray = org.json.JSONArray(bodyStr)

                if (jsonArray.length() == 0) {
                    return null
                }

                // Choose the best match (first match with syncedLyrics, or first with plainLyrics)
                var bestSynced: String? = null
                var bestPlain: String? = null

                for (i in 0 until minOf(jsonArray.length(), 5)) {
                    val item = jsonArray.getJSONObject(i)
                    val synced = item.optString("syncedLyrics", "").trim()
                    val plain = item.optString("plainLyrics", "").trim()

                    if (synced.isNotBlank() && bestSynced == null) {
                        bestSynced = synced
                        break
                    }
                    if (plain.isNotBlank() && bestPlain == null) {
                        bestPlain = plain
                    }
                }

                return when {
                    !bestSynced.isNullOrBlank() -> parseSyncedLyrics(bestSynced)
                    !bestPlain.isNullOrBlank() -> {
                        val lines = bestPlain.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        LyricsResult(lines = lines, timestamps = emptyList())
                    }
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception querying lyrics for '$query'", e)
            return null
        }
    }

    /**
     * Parse LRC format: [01:23.45] Lyric line here
     */
    private fun parseSyncedLyrics(lrc: String): LyricsResult {
        val lines = mutableListOf<String>()
        val timestamps = mutableListOf<Long>()

        val regex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})](.*)""")
        for (rawLine in lrc.lines()) {
            val trimmed = rawLine.trim()
            val match = regex.find(trimmed)
            if (match != null) {
                val (min, sec, millis, text) = match.destructured
                val timeMs = (min.toLong() * 60 * 1000) +
                        (sec.toLong() * 1000) +
                        if (millis.length == 2) millis.toLong() * 10 else millis.toLong()

                val lineText = text.trim()
                if (lineText.isNotEmpty()) {
                    timestamps.add(timeMs)
                    lines.add(lineText)
                }
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("[")) {
                lines.add(trimmed)
            }
        }

        return LyricsResult(lines, timestamps)
    }

    fun clearCache() {
        lyricsCache.clear()
    }
}
