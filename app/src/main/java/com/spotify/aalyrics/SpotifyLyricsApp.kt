package com.spotify.aalyrics

import android.app.Application
import android.util.Log
import com.spotify.aalyrics.listener.SpotifyTrackListener

/**
 * Application class for the Spotify AA Lyrics app.
 */
class SpotifyLyricsApp : Application() {

    companion object {
        private const val TAG = "SpotifyLyricsApp"
        var instance: SpotifyLyricsApp? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        SpotifyTrackListener.init(this)
        Log.d(TAG, "App created and Spotify Device Broadcast Status listener initialized")
    }

    override fun onTerminate() {
        super.onTerminate()
        instance = null
    }
}
