package com.spotify.aalyrics.screen

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.spotify.aalyrics.R
import com.spotify.aalyrics.listener.SpotifyTrack
import com.spotify.aalyrics.repository.LyricsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A Car Screen that safely displays the lyrics of the currently playing Spotify track.
 * Adheres strictly to Android Auto Car App Library rules:
 * - Asynchronous background fetching (avoids NetworkOnMainThreadException)
 * - Maximum 4 rows per PaneTemplate
 * - Pagination controls via ActionStrip
 */
class LyricsScreen(
    private val carContext: CarContext,
    initialTrack: SpotifyTrack? = null
) : Screen(carContext) {

    private var currentTrack: SpotifyTrack? = initialTrack
    private var lyricsResult: LyricsRepository.LyricsResult? = null
    private var isLoading: Boolean = false
    private var fetchJob: Job? = null
    private var currentPage: Int = 0

    // Car App Library strictly enforces maximum 4 rows per Pane
    private val pageSize = 4

    init {
        initialTrack?.let {
            fetchLyrics(it)
        }
    }

    override fun onGetTemplate(): Template {
        val track = currentTrack

        // 1. No track currently playing
        if (track == null) {
            val pane = Pane.Builder()
                .addRow(
                    Row.Builder()
                        .setTitle(carContext.getString(R.string.no_track_playing))
                        .addText("Buka Spotify di ponsel dan putar musik.")
                        .build()
                )
                .build()

            return PaneTemplate.Builder(pane)
                .setHeaderAction(Action.APP_ICON)
                .setTitle(carContext.getString(R.string.app_name))
                .build()
        }

        // 2. Currently loading lyrics from LRCLIB
        if (isLoading) {
            val loadingPane = Pane.Builder()
                .setLoading(true)
                .build()

            return PaneTemplate.Builder(loadingPane)
                .setHeaderAction(Action.APP_ICON)
                .setTitle("${track.title} - ${track.artist}")
                .build()
        }

        val lyrics = lyricsResult
        val lines = lyrics?.lines ?: emptyList()

        // 3. Lyrics not found
        if (lines.isEmpty()) {
            val pane = Pane.Builder()
                .addRow(
                    Row.Builder()
                        .setTitle(carContext.getString(R.string.lyrics_not_found))
                        .addText("Tidak dapat menemukan lirik di LRCLIB.")
                        .build()
                )
                .build()

            val actionStrip = ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle("Coba Lagi")
                        .setOnClickListener {
                            fetchLyrics(track)
                        }
                        .build()
                )
                .build()

            return PaneTemplate.Builder(pane)
                .setHeaderAction(Action.APP_ICON)
                .setTitle("${track.title} - ${track.artist}")
                .setActionStrip(actionStrip)
                .build()
        }

        // 4. Render lyrics with pagination (max 4 rows per screen)
        val totalPages = maxOf(1, (lines.size + pageSize - 1) / pageSize)
        if (currentPage >= totalPages) currentPage = totalPages - 1
        if (currentPage < 0) currentPage = 0

        val startIndex = currentPage * pageSize
        val endIndex = minOf(startIndex + pageSize, lines.size)
        val pageLines = lines.subList(startIndex, endIndex)

        val paneBuilder = Pane.Builder()
        if (pageLines.isEmpty()) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.lyrics_not_found))
                    .build()
            )
        } else {
            for (line in pageLines) {
                paneBuilder.addRow(
                    Row.Builder()
                        .setTitle(line)
                        .build()
                )
            }
        }

        val templateBuilder = PaneTemplate.Builder(paneBuilder.build())
            .setHeaderAction(Action.APP_ICON)
            .setTitle(
                if (totalPages > 1) {
                    "${track.title} (${currentPage + 1}/$totalPages)"
                } else {
                    "${track.title} - ${track.artist}"
                }
            )

        // Add Next/Previous page buttons if there are multiple pages
        if (totalPages > 1) {
            val actionStripBuilder = ActionStrip.Builder()

            if (currentPage > 0) {
                actionStripBuilder.addAction(
                    Action.Builder()
                        .setTitle("◀ Prev")
                        .setOnClickListener {
                            if (currentPage > 0) {
                                currentPage--
                                invalidate()
                            }
                        }
                        .build()
                )
            }

            if (currentPage < totalPages - 1) {
                actionStripBuilder.addAction(
                    Action.Builder()
                        .setTitle("Next ▶")
                        .setOnClickListener {
                            if (currentPage < totalPages - 1) {
                                currentPage++
                                invalidate()
                            }
                        }
                        .build()
                )
            }

            try {
                templateBuilder.setActionStrip(actionStripBuilder.build())
            } catch (_: Exception) {
                // If actionStrip has no actions, omit it safely
            }
        }

        return templateBuilder.build()
    }

    fun setTrack(track: SpotifyTrack?) {
        if (currentTrack?.title == track?.title && currentTrack?.artist == track?.artist) {
            return
        }
        currentTrack = track
        currentPage = 0
        lyricsResult = null

        if (track != null) {
            fetchLyrics(track)
        } else {
            isLoading = false
            fetchJob?.cancel()
            invalidate()
        }
    }

    private fun fetchLyrics(track: SpotifyTrack) {
        fetchJob?.cancel()
        isLoading = true
        invalidate()

        fetchJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                LyricsRepository.fetchLyrics(track.title, track.artist)
            }

            // Verify track hasn't changed while network call was in flight
            if (currentTrack?.title == track.title && currentTrack?.artist == track.artist) {
                lyricsResult = result
                isLoading = false
                currentPage = 0
                invalidate()
            }
        }
    }
}
