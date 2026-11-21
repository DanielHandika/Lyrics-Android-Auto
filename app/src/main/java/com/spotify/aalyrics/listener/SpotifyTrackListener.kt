package com.spotify.aalyrics.listener

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.spotify.aalyrics.SpotifyLyricsApp
import com.spotify.aalyrics.service.LyricsMediaBrowserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 * NotificationListenerService that watches Spotify's "Now Playing" notification
 * to extract currently playing track metadata (title, artist, album art, duration).
 */
class SpotifyTrackListener : NotificationListenerService() {

    private val logTag = "SpotifyTrackListener"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Track currently observed by this listener instance
    private var currentTrack: SpotifyTrack? = null

    companion object {
        private const val TAG = "SpotifyTrackListener"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"

        // Observable StateFlow so MainActivity and other components can observe track changes
        private val _currentTrackFlow = MutableStateFlow<SpotifyTrack?>(null)
        val currentTrackFlow: StateFlow<SpotifyTrack?> = _currentTrackFlow.asStateFlow()

        var isServiceConnected: Boolean = false
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceConnected = true
        Log.d(TAG, "NotificationListener connected")
        SpotifyLyricsApp.notificationPermissionGranted = true

        // Try to read existing active notifications to catch Spotify if it's already playing
        scope.launch {
            try {
                val activeNotifications = activeNotifications ?: return@launch
                for (sbn in activeNotifications) {
                    if (sbn.packageName == SPOTIFY_PACKAGE) {
                        val track = extractTrack(sbn.notification)
                        if (track != null) {
                            Log.d(TAG, "Discovered active Spotify track on connect: ${track.title} - ${track.artist}")
                            currentTrack = track
                            _currentTrackFlow.value = track
                            LyricsMediaBrowserService.updateTrack(track)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking active notifications on connect", e)
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isServiceConnected = false
        Log.d(TAG, "NotificationListener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != SPOTIFY_PACKAGE) {
            return
        }

        scope.launch {
            val notification = sbn.notification ?: return@launch
            val track = extractTrack(notification)

            if (track != null) {
                if (currentTrack == null ||
                    currentTrack?.title != track.title ||
                    currentTrack?.artist != track.artist
                ) {
                    Log.d(logTag, "Track changed: ${track.title} - ${track.artist}")
                    currentTrack = track
                    _currentTrackFlow.value = track
                    SpotifyLyricsApp.notificationPermissionGranted = hasNotificationPermission()
                    LyricsMediaBrowserService.updateTrack(track)
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != SPOTIFY_PACKAGE) {
            return
        }

        val notification = sbn.notification
        val isMediaNotification = notification?.category == Notification.CATEGORY_TRANSPORT ||
            notification?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        if (!isMediaNotification) {
            return
        }

        scope.launch {
            currentTrack = null
            _currentTrackFlow.value = null
            LyricsMediaBrowserService.clearTrack()
        }
    }

    /**
     * Extract track info from Spotify's now-playing notification.
     * Robust extraction that never fails if album art is missing or non-Bitmap.
     */
    private fun extractTrack(notification: Notification): SpotifyTrack? {
        val extras = notification.extras ?: return null

        // In Android MediaStyle notifications:
        // EXTRA_TITLE = Track Title
        // EXTRA_TEXT = Artist Name
        // EXTRA_SUB_TEXT = Album Name
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()

        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence("android.summaryText")?.toString()?.trim()

        // If no title could be determined, this is not a valid track notification
        if (title.isNullOrBlank()) {
            return null
        }

        // Extract artwork safely without throwing or returning null if unavailable
        val artwork: Bitmap? = extractArtwork(extras)

        val duration = extras.getLong("android.duration", -1)

        return SpotifyTrack(
            title = title,
            artist = if (!artist.isNullOrBlank() && artist != title) artist else "Spotify",
            albumArt = artwork,
            durationMs = if (duration > 0) duration else null
        )
    }

    @Suppress("DEPRECATION")
    private fun extractArtwork(extras: android.os.Bundle): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable("android.largeIcon", Bitmap::class.java)
            } else {
                extras.getParcelable("android.largeIcon")
            }
        } catch (e: Exception) {
            try {
                // If largeIcon is stored as an Icon object
                val icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    extras.getParcelable("android.largeIcon.big", Icon::class.java)
                } else {
                    extras.getParcelable("android.largeIcon.big")
                }
                val drawable = icon?.loadDrawable(this)
                if (drawable is BitmapDrawable) {
                    drawable.bitmap
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
