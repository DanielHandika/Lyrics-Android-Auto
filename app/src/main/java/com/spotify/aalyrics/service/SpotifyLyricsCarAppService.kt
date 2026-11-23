package com.spotify.aalyrics.service

import android.content.Intent
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.spotify.aalyrics.listener.SpotifyTrack
import com.spotify.aalyrics.listener.SpotifyTrackListener
import com.spotify.aalyrics.screen.LyricsScreen

/**
 * Session for the Spotify AA Lyrics app in Android Auto.
 */
class SpotifyLyricsSession : Session() {

    private var lyricsScreen: LyricsScreen? = null

    override fun onCreateScreen(intent: Intent): Screen {
        Log.d("SpotifyLyricsSession", "onCreateScreen with lastTrack: ${SpotifyLyricsCarAppService.lastTrack?.title}")
        val screen = LyricsScreen(carContext, SpotifyLyricsCarAppService.lastTrack)
        lyricsScreen = screen
        return screen
    }

    fun updateTrack(track: SpotifyTrack?) {
        lyricsScreen?.setTrack(track)
    }

    fun clearTrack() {
        lyricsScreen?.setTrack(null)
    }
}

/**
 * Main CarAppService for Android Auto.
 */
class SpotifyLyricsCarAppService : CarAppService() {

    private var currentSession: SpotifyLyricsSession? = null

    override fun createHostValidator(): HostValidator {
        // Allows connection from any Android Auto host (essential for development & sideloaded apps)
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        Log.d("SpotifyLyricsCarAppService", "onCreateSession")
        val session = SpotifyLyricsSession()
        currentSession = session
        return session
    }

    companion object {
        private const val TAG = "SpotifyLyricsService"
        private var instance: SpotifyLyricsCarAppService? = null
        var lastTrack: SpotifyTrack? = null
            private set

        fun updateTrack(track: SpotifyTrack?) {
            lastTrack = track
            instance?.currentSession?.updateTrack(track)
        }

        fun clearTrack() {
            lastTrack = null
            instance?.currentSession?.clearTrack()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        SpotifyTrackListener.init(applicationContext)
        if (lastTrack == null) {
            lastTrack = SpotifyTrackListener.currentTrackFlow.value
        }
        Log.d(TAG, "SpotifyLyricsCarAppService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "SpotifyLyricsCarAppService destroyed")
    }
}
