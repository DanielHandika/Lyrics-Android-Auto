package com.spotify.aalyrics

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.spotify.aalyrics.listener.SpotifyTrack
import com.spotify.aalyrics.listener.SpotifyTrackListener
import com.spotify.aalyrics.repository.LyricsRepository
import com.spotify.aalyrics.service.LyricsMediaBrowserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusBroadcastText: TextView
    private lateinit var liveTrackText: TextView
    private lateinit var liveLyricsText: TextView
    private var lyricsFetchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpotifyTrackListener.init(applicationContext)
        setContentView(createLayout())

        observeBroadcastState()
    }

    private fun observeBroadcastState() {
        lifecycleScope.launch {
            combine(
                SpotifyTrackListener.hasReceivedBroadcast,
                SpotifyTrackListener.currentTrackFlow
            ) { hasBroadcast, track ->
                hasBroadcast to (track ?: LyricsMediaBrowserService.lastTrack)
            }.collectLatest { (hasBroadcast, track) ->
                updateBroadcastStatus(hasBroadcast)
                updateTrackUI(track)
            }
        }
    }

    private fun updateBroadcastStatus(hasReceivedBroadcast: Boolean) {
        if (hasReceivedBroadcast) {
            statusBroadcastText.text = "✅ Terhubung dengan Broadcast Spotify\n(Tanpa izin sensitif • Berjalan normal)"
            statusBroadcastText.setTextColor(0xFF2E7D32.toInt())
        } else {
            statusBroadcastText.text = "ℹ️ Mode: Spotify Device Broadcast Status\n(Tanpa izin sensitif • Menunggu sinyal dari Spotify)"
            statusBroadcastText.setTextColor(0xFF1565C0.toInt())
        }
    }

    private fun updateTrackUI(track: SpotifyTrack?) {
        lyricsFetchJob?.cancel()

        if (track == null) {
            liveTrackText.text = "🎵 Menunggu Spotify...\n(Putar lagu di Spotify setelah mengaktifkan Device Broadcast Status)"
            liveLyricsText.text = ""
            return
        }

        liveTrackText.text = "🎶 Sedang Diputar:\n${track.title}\nOleh: ${track.artist}"
        liveLyricsText.text = "Memuat lirik dari LRCLIB..."

        lyricsFetchJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                LyricsRepository.fetchLyrics(track.title, track.artist)
            }

            if (result != null && result.lines.isNotEmpty()) {
                val preview = result.lines.take(8).joinToString("\n")
                val more = if (result.lines.size > 8) "\n... (${result.lines.size} baris lirik tersedia)" else ""
                liveLyricsText.text = "📄 Preview Lirik:\n$preview$more"
            } else {
                liveLyricsText.text = "❌ Lirik tidak ditemukan di LRCLIB untuk lagu ini."
            }
        }
    }

    private fun createLayout(): ScrollView {
        val scrollView = ScrollView(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "Spotify AA Lyrics"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        statusBroadcastText = TextView(this).apply {
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }

        val spotifyGuideCard = TextView(this).apply {
            text = "🎧 AKTIFKAN DEVICE BROADCAST STATUS DI SPOTIFY:\n" +
                    "(Aplikasi tidak memerlukan izin Akses Notifikasi)\n\n" +
                    "1. Buka aplikasi Spotify -> ketuk foto profil di pojok kiri atas\n" +
                    "2. Pilih 'Settings and privacy' (Pengaturan dan privasi)\n" +
                    "3. Scroll ke bagian 'Apps and devices' / 'Devices'\n" +
                    "4. Aktifkan toggle 'Device Broadcast Status' (Status Siaran Perangkat)\n" +
                    "5. Putar lagu di Spotify (jika lagu sudah berjalan, jeda lalu putar ulang sekali)"
            textSize = 13f
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFFE3F2FD.toInt())
            setTextColor(0xFF1A237E.toInt())
        }

        val btnOpenSpotify = Button(this).apply {
            text = "1. Buka Spotify (Aktifkan Device Broadcast Status)"
            setOnClickListener {
                val spotifyIntent = packageManager.getLaunchIntentForPackage("com.spotify.music")
                if (spotifyIntent != null) {
                    startActivity(spotifyIntent)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Aplikasi Spotify belum terpasang di perangkat ini.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        val aaGuideCard = TextView(this).apply {
            text = "📱 SYARAT ANDROID AUTO AGAR TERDETEKSI DI MOBIL:\n\n" +
                    "1. Buka Pengaturan HP -> Cari 'Android Auto'\n" +
                    "2. Scroll ke paling bawah ke bagian 'Version'\n" +
                    "3. KETUK 'Version' 10 KALI sampai muncul pop-up Developer Mode\n" +
                    "4. Tekan Titik Tiga (⋮) di pojok kanan atas -> 'Developer settings'\n" +
                    "5. CENTANG 'Unknown Sources' (Sumber tidak dikenal)\n\n" +
                    "Setelah langkah di atas, aplikasi akan langsung muncul di layar head unit mobil."
            textSize = 13f
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFFF0F4F8.toInt())
            setTextColor(0xFF333333.toInt())
        }

        val btnAA = Button(this).apply {
            text = "2. Buka Pengaturan Android Auto"
            setOnClickListener {
                openAndroidAutoSettings()
            }
        }

        val testBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFFE8F5E9.toInt())
        }

        liveTrackText = TextView(this).apply {
            text = "🎵 Menunggu Spotify..."
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF1B5E20.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        liveLyricsText = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(0xFF2E7D32.toInt())
            gravity = Gravity.CENTER
        }

        testBox.addView(liveTrackText)
        testBox.addView(liveLyricsText)

        layout.addView(title)
        layout.addView(statusBroadcastText)
        layout.addView(spotifyGuideCard)
        layout.addView(TextView(this).apply { height = 16 })
        layout.addView(btnOpenSpotify)
        layout.addView(TextView(this).apply { height = 24 })
        layout.addView(aaGuideCard)
        layout.addView(TextView(this).apply { height = 16 })
        layout.addView(btnAA)
        layout.addView(TextView(this).apply { height = 32 })
        layout.addView(testBox)

        scrollView.addView(layout)
        return scrollView
    }

    /**
     * Opens Android Auto's settings screen directly without falling back to phone Developer Options.
     */
    private fun openAndroidAutoSettings() {
        val directIntents = listOfNotNull(
            Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(ANDROID_AUTO_PACKAGE),
            Intent().setClassName(ANDROID_AUTO_PACKAGE, ANDROID_AUTO_SETTINGS_ACTIVITY),
            packageManager.getLaunchIntentForPackage(ANDROID_AUTO_PACKAGE)
        )
        for (intent in directIntents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // Activity missing or not exported on this Android version/ROM; try next option.
            }
        }

        if (isPackageInstalled(ANDROID_AUTO_PACKAGE)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", ANDROID_AUTO_PACKAGE, null)
                    )
                )
                Toast.makeText(
                    this,
                    "Pilih 'Pengaturan tambahan di aplikasi' untuk membuka pengaturan Android Auto.",
                    Toast.LENGTH_LONG
                ).show()
                return
            } catch (_: Exception) {
                // Fall back to main system settings below.
            }
        }

        startActivity(Intent(Settings.ACTION_SETTINGS))
        Toast.makeText(
            this,
            "Buka Pengaturan HP lalu cari 'Android Auto'.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
        private const val ANDROID_AUTO_SETTINGS_ACTIVITY =
            "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
    }
}
