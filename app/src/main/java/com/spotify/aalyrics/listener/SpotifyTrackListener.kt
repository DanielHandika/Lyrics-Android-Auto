package com.spotify.aalyrics.listener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.util.Log
import androidx.core.content.ContextCompat
import com.spotify.aalyrics.service.LyricsMediaBrowserService
import com.spotify.aalyrics.service.SpotifyLyricsCarAppService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Data class representing a currently playing Spotify track.
 */
data class SpotifyTrack(
    val title: String,
    val artist: String,
    val albumArt: Bitmap? = null,
    val durationMs: Long? = null
)

/**
 * BroadcastReceiver that listens to Spotify's built-in "Device Broadcast Status"
 * (metadatachanged, playbackstatechanged, queuechanged) without requiring any
 * sensitive permissions such as NotificationListenerService.
 */
class SpotifyTrackListener : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            ACTION_METADATA_CHANGED -> {
                _hasReceivedBroadcast.value = true
                val track = parseTrackFromIntent(intent)
                if (track != null) {
                    Log.d(TAG, "Metadata broadcast: ${track.title} - ${track.artist}")
                    handleTrackUpdate(context, track)
                }
            }
            ACTION_PLAYBACK_STATE_CHANGED -> {
                _hasReceivedBroadcast.value = true
                val isPlaying = intent.getBooleanExtra("playing", false)
                _isPlayingFlow.value = isPlaying
                Log.d(TAG, "Playback state broadcast: playing=$isPlaying")

                // Some Spotify versions bundle track metadata with playback state broadcasts
                val track = parseTrackFromIntent(intent)
                if (track != null) {
                    handleTrackUpdate(context, track)
                }
                // Keep last track and lyrics visible even when playback is paused
            }
            ACTION_QUEUE_CHANGED -> {
                _hasReceivedBroadcast.value = true
                Log.d(TAG, "Queue changed broadcast received")
            }
        }
    }

    companion object {
        private const val TAG = "SpotifyTrackListener"

        const val ACTION_METADATA_CHANGED = "com.spotify.music.metadatachanged"
        const val ACTION_PLAYBACK_STATE_CHANGED = "com.spotify.music.playbackstatechanged"
        const val ACTION_QUEUE_CHANGED = "com.spotify.music.queuechanged"

        private const val PREFS_NAME = "spotify_broadcast_prefs"
        private const val KEY_TITLE = "last_track_title"
        private const val KEY_ARTIST = "last_track_artist"
        private const val KEY_DURATION_MS = "last_track_duration_ms"

        private val _currentTrackFlow = MutableStateFlow<SpotifyTrack?>(null)
        val currentTrackFlow: StateFlow<SpotifyTrack?> = _currentTrackFlow.asStateFlow()

        private val _isPlayingFlow = MutableStateFlow(false)
        val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow.asStateFlow()

        private val _hasReceivedBroadcast = MutableStateFlow(false)
        val hasReceivedBroadcast: StateFlow<Boolean> = _hasReceivedBroadcast.asStateFlow()

        @Volatile
        private var isDynamicReceiverRegistered = false
        private var dynamicReceiver: SpotifyTrackListener? = null

        /**
         * Idempotently restore the last saved track and register a dynamic receiver
         * with RECEIVER_EXPORTED so implicit Spotify broadcasts work on Android 8.0–14+.
         */
        @Synchronized
        fun init(context: Context) {
            val appContext = context.applicationContext

            if (_currentTrackFlow.value == null) {
                val restored = restoreLastTrack(appContext)
                if (restored != null) {
                    _currentTrackFlow.value = restored
                    LyricsMediaBrowserService.updateTrack(restored)
                    SpotifyLyricsCarAppService.updateTrack(restored)
                    Log.d(TAG, "Restored last track from prefs: ${restored.title} - ${restored.artist}")
                }
            }

            if (!isDynamicReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(ACTION_METADATA_CHANGED)
                    addAction(ACTION_PLAYBACK_STATE_CHANGED)
                    addAction(ACTION_QUEUE_CHANGED)
                }
                val receiver = SpotifyTrackListener()
                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                dynamicReceiver = receiver
                isDynamicReceiverRegistered = true
                Log.d(TAG, "Dynamic Spotify broadcast receiver registered")
            }
        }

        /**
         * Extract Spotify track metadata from a Spotify broadcast Intent.
         */
        fun parseTrackFromIntent(intent: Intent): SpotifyTrack? {
            val title = intent.getStringExtra("track")?.trim()
            if (title.isNullOrBlank()) {
                return null
            }

            val rawArtist = intent.getStringExtra("artist")?.trim()
            val artist = if (!rawArtist.isNullOrBlank()) rawArtist else "Spotify"

            var lengthMs = intent.getIntExtra("length", -1).toLong()
            if (lengthMs <= 0L) {
                lengthMs = intent.getLongExtra("length", -1L)
            }

            return SpotifyTrack(
                title = title,
                artist = artist,
                albumArt = null,
                durationMs = if (lengthMs > 0L) lengthMs else null
            )
        }

        private fun handleTrackUpdate(context: Context, track: SpotifyTrack) {
            val current = _currentTrackFlow.value
            val isSameTrack = current != null &&
                current.title == track.title &&
                current.artist == track.artist
            val hasNewDuration = current?.durationMs == null && track.durationMs != null

            if (isSameTrack && !hasNewDuration) {
                return
            }

            _currentTrackFlow.value = track
            saveLastTrack(context.applicationContext, track)
            LyricsMediaBrowserService.updateTrack(track)
            SpotifyLyricsCarAppService.updateTrack(track)
        }

        private fun saveLastTrack(context: Context, track: SpotifyTrack) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TITLE, track.title)
                .putString(KEY_ARTIST, track.artist)
                .putLong(KEY_DURATION_MS, track.durationMs ?: -1L)
                .apply()
        }

        private fun restoreLastTrack(context: Context): SpotifyTrack? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val title = prefs.getString(KEY_TITLE, null)?.trim()
            if (title.isNullOrBlank()) {
                return null
            }
            val artist = prefs.getString(KEY_ARTIST, null)?.trim()
            val durationMs = prefs.getLong(KEY_DURATION_MS, -1L)
            return SpotifyTrack(
                title = title,
                artist = if (!artist.isNullOrBlank()) artist else "Spotify",
                albumArt = null,
                durationMs = if (durationMs > 0L) durationMs else null
            )
        }
    }
}
