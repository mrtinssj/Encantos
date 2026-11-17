package com.ifpr.androidapptemplatear

import android.os.Bundle
import android.media.AudioManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import kotlin.math.sin
import kotlin.math.PI
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import com.ifpr.androidapptemplatear.ui.SharedModelViewModel
import com.ifpr.androidapptemplatear.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var audio: GameAudio

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        audio = GameAudio(this)
        val game = ViewModelProvider(this)[SharedModelViewModel::class.java]
        var previousScore = game.collected.value ?: 0
        game.collected.observe(this) { score ->
            if (score > previousScore) audio.collect()
            previousScore = score
        }
        game.cleaning.observe(this) {
            if (game.isGameOver) audio.stop()
            else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) audio.start()
        }
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        binding.navView.setupWithNavController(navHost.navController)
        navHost.navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.navView.visibility = if (destination.id == R.id.navigation_cleaning) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        if (!ViewModelProvider(this)[SharedModelViewModel::class.java].isGameOver) audio.start()
    }

    override fun onStop() {
        audio.stop()
        super.onStop()
    }
}

/** Ambient audio is synthesized in memory; the collection uses the existing blin.mp3. */
private class GameAudio(private val context: Context) {
    private var ambient: AudioTrack? = null
    private var effect: MediaPlayer? = null
    private val sampleRate = 22050
    private val samples by lazy {
        ShortArray(sampleRate) { index ->
            val time = index.toDouble() / sampleRate
            ((sin(2 * PI * 220 * time) + sin(2 * PI * 277 * time) +
                sin(2 * PI * 330 * time)) * 1400).toInt().toShort()
        }
    }

    fun start() {
        if (ambient != null) return
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC).build()
            ambient = track
            track.write(samples, 0, samples.size)
            track.setLoopPoints(0, samples.size, -1)
            track.setVolume(0.3f)
            track.play()
        } catch (_: Exception) {
            ambient?.release()
            ambient = null
        }
    }

    fun collect() {
        effect?.release()
        effect = MediaPlayer.create(context, R.raw.blin)?.apply {
            setOnCompletionListener { player ->
                player.release()
                if (effect === player) effect = null
            }
            start()
        }
    }

    fun stop() {
        ambient?.release()
        ambient = null
        effect?.release()
        effect = null
    }
}
