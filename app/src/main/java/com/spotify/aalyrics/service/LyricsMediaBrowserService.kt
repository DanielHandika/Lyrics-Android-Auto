package com.spotify.aalyrics.service

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import com.spotify.aalyrics.R
import com.spotify.aalyrics.listener.SpotifyTrack
import com.spotify.aalyrics.repository.LyricsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android Auto MediaBrowserServiceCompat that presents Spotify lyrics
 * in a Media Browse List without activating audio focus or interrupting playback.
 */
class LyricsMediaBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentTrack: SpotifyTrack? = null
    private var lyricsResult: LyricsRepository.LyricsResult? = null
    private var isLoading: Boolean = false
    private var fetchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "LyricsMediaBrowserService created")

        val idleState = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_NONE, 0L, 0f)
            .build()

        mediaSession = MediaSessionCompat(baseContext, TAG).apply {
            setPlaybackState(idleState)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    // Only allow manual retry/reload on the refresh/header item; keep Spotify uninterrupted.
                    if (mediaId == ITEM_REFRESH_ID) {
                        currentTrack?.let { onTrackChanged(it, forceRefresh = true) }
                    }
                }
            })
        }

        setSessionToken(mediaSession.sessionToken)

        lastTrack?.let { onTrackChanged(it) }
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        val extras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        when (parentId) {
            ROOT_ID -> {
                val subtitle = currentTrack?.let { "${it.title} - ${it.artist}" }
                    ?: getString(R.string.no_track_playing)
                val rootItem = buildMediaItem(
                    mediaId = CURRENT_LYRICS_ID,
                    title = "Lirik",
                    subtitle = subtitle,
                    flags = MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                )
                result.sendResult(mutableListOf(rootItem))
            }
            CURRENT_LYRICS_ID -> result.sendResult(buildLyricsItems())
            else -> result.sendResult(mutableListOf())
        }
    }

    private fun buildLyricsItems(): MutableList<MediaBrowserCompat.MediaItem> {
        val track = currentTrack
        if (track == null) {
            return mutableListOf(
                buildMediaItem(
                    mediaId = "info_no_track",
                    title = getString(R.string.no_track_playing),
                    subtitle = "Putar lagu di Spotify",
                    flags = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
            )
        }

        if (isLoading) {
            return mutableListOf(
                buildMediaItem(
                    mediaId = "info_loading",
                    title = getString(R.string.lyrics_loading),
                    subtitle = "${track.title} - ${track.artist}",
                    flags = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
            )
        }

        val lines = lyricsResult?.lines.orEmpty()
        if (lines.isEmpty()) {
            return mutableListOf(
                buildMediaItem(
                    mediaId = ITEM_REFRESH_ID,
                    title = getString(R.string.lyrics_not_found),
                    subtitle = "Ketuk untuk coba lagi • ${track.title} - ${track.artist}",
                    flags = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
            )
        }

        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        items.add(
            buildMediaItem(
                mediaId = ITEM_REFRESH_ID,
                title = "🎵 ${track.title}",
                subtitle = "${track.artist} (ketuk untuk muat ulang)",
                flags = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        )

        for (i in lines.indices step 2) {
            items.add(
                buildMediaItem(
                    mediaId = "lyric_$i",
                    title = lines[i],
                    subtitle = lines.getOrNull(i + 1),
                    flags = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
            )
        }

        return items
    }

    private fun buildMediaItem(
        mediaId: String,
        title: String,
        subtitle: String?,
        flags: Int
    ): MediaBrowserCompat.MediaItem {
        val descriptionBuilder = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
        if (subtitle != null) {
            descriptionBuilder.setSubtitle(subtitle)
        }
        return MediaBrowserCompat.MediaItem(descriptionBuilder.build(), flags)
    }

    private fun onTrackChanged(track: SpotifyTrack?, forceRefresh: Boolean = false) {
        serviceScope.launch {
            if (!forceRefresh &&
                currentTrack?.title == track?.title &&
                currentTrack?.artist == track?.artist &&
                (isLoading || lyricsResult != null)
            ) {
                return@launch
            }

            fetchJob?.cancel()
            currentTrack = track
            lyricsResult = null

            if (track == null) {
                isLoading = false
                notifyChildrenChanged(ROOT_ID)
                notifyChildrenChanged(CURRENT_LYRICS_ID)
                return@launch
            }

            isLoading = true
            notifyChildrenChanged(ROOT_ID)
            notifyChildrenChanged(CURRENT_LYRICS_ID)

            fetchJob = serviceScope.launch {
                val result = withContext(Dispatchers.IO) {
                    LyricsRepository.fetchLyrics(track.title, track.artist)
                }
                if (currentTrack?.title == track.title && currentTrack?.artist == track.artist) {
                    lyricsResult = result
                    isLoading = false
                    notifyChildrenChanged(CURRENT_LYRICS_ID)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fetchJob?.cancel()
        serviceScope.cancel()
        mediaSession.release()
        if (instance === this) {
            instance = null
        }
        Log.d(TAG, "LyricsMediaBrowserService destroyed")
    }

    companion object {
        private const val TAG = "LyricsMediaBrowserSvc"
        private const val ROOT_ID = "ROOT"
        private const val CURRENT_LYRICS_ID = "CURRENT_LYRICS"
        private const val ITEM_REFRESH_ID = "REFRESH_LYRICS"
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"

        @Volatile
        private var instance: LyricsMediaBrowserService? = null

        @Volatile
        var lastTrack: SpotifyTrack? = null
            private set

        fun updateTrack(track: SpotifyTrack?) {
            lastTrack = track
            instance?.onTrackChanged(track)
        }

        fun clearTrack() {
            lastTrack = null
            instance?.onTrackChanged(null)
        }
    }
}
