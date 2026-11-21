package com.spotify.aalyrics

import android.app.Application
import android.util.Log

/**
 * Application class for the Spotify AA Lyrics app.
 */
class SpotifyLyricsApp : Application() {

    companion object {
        private const val TAG = "SpotifyLyricsApp"
        var instance: SpotifyLyricsApp? = null
        var notificationPermissionGranted: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "App created")
    }

    fun requestNotificationPermission(): String {
        return "android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS"
    }

    override fun onTerminate() {
        super.onTerminate()
        instance = null
    }
}