package dev.deepseek.mobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Play the two bundled tap sounds through SoundPool. */
internal object WhaleSounds {
    private var pool: SoundPool? = null
    private var audio: AudioManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val loaded = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private var pressId = 0
    private var releaseId = 0
    private var pressStream = 0
    private var releaseStream = 0
    private var pressAt = 0L
    private var pressDuration = 180L
    val isReady
        get() = pressId != 0 && releaseId != 0 && pressId in loaded && releaseId in loaded

    private val releaseAction = Runnable {
        if (allowed() && isReady) releaseStream = pool?.play(releaseId, .45f, .45f, 1, 0, 1f) ?: 0
    }

    fun initialize(context: Context) {
        if (pool != null) return
        audio = context.applicationContext.getSystemService(AudioManager::class.java)
        val player =
            SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
        pool = player
        player.setOnLoadCompleteListener { _, id, status ->
            if (pool === player && status == 0) loaded.add(id)
        }
        pressId = player.load(context, R.raw.pet_press, 1)
        releaseId = player.load(context, R.raw.pet_release, 1)
        try {
            context.resources.openRawResourceFd(R.raw.pet_press).use { fd ->
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    pressDuration =
                        (reader
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull() ?: 180L)
                            .coerceIn(20L, 1500L)
                } finally {
                    reader.release()
                }
            }
        } catch (_: Exception) {
            pressDuration = 180L
        }
    }

    private fun allowed() =
        AssistantState.petSoundEnabled && audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL

    fun press(): Int {
        stop()
        if (!allowed() || !isReady) return 0
        pressAt = SystemClock.uptimeMillis()
        pressStream = pool?.play(pressId, .45f, .45f, 1, 0, 1f) ?: 0
        return pressStream
    }

    fun releaseTap() {
        if (pressStream == 0 || !allowed()) return
        handler.removeCallbacks(releaseAction)
        handler.postDelayed(
            releaseAction,
            (pressDuration - (SystemClock.uptimeMillis() - pressAt)).coerceAtLeast(0L),
        )
    }

    fun stop() {
        handler.removeCallbacks(releaseAction)
        if (pressStream != 0) pool?.stop(pressStream)
        if (releaseStream != 0) pool?.stop(releaseStream)
        pressStream = 0
        releaseStream = 0
    }

    fun dispose() {
        stop()
        pool?.release()
        pool = null
        loaded.clear()
        pressId = 0
        releaseId = 0
        audio = null
    }
}
