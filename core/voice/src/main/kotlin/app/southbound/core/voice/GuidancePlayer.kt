package app.southbound.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer

/**
 * Plays one rendered utterance with the user's output, volume and
 * other-audio settings, holding transient audio focus while it speaks.
 */
internal class GuidancePlayer(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile
    private var current: AudioTrack? = null

    /** Blocks until [pcm] has played or [stop] is called. */
    fun play(pcm: Pcm, settings: VoiceSettings) {
        if (pcm.samples.isEmpty()) return
        val callChannel = settings.output == AudioOutput.BLUETOOTH_CALL
        val attributes = AudioAttributes.Builder()
            .setUsage(
                if (callChannel) AudioAttributes.USAGE_VOICE_COMMUNICATION
                else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(
            if (settings.otherAudio == OtherAudio.LOWER) {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            } else {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            },
        ).setAudioAttributes(attributes).build()

        // The track first: if it can't be made, no other app is left ducked and no call device held.
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.samples.size * 2)
            .build()
        audioManager.requestAudioFocus(focus)
        var bluetoothCall: AudioDeviceInfo? = null
        var enhancer: LoudnessEnhancer? = null
        try {
            if (callChannel) {
                bluetoothCall = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    ?.also { audioManager.setCommunicationDevice(it) }
            }
            track.write(pcm.samples, 0, pcm.samples.size)
            track.setVolume(settings.volume.gain)
            if (settings.output == AudioOutput.PHONE_SPEAKER) {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { track.setPreferredDevice(it) }
            }
            if (settings.volume.boostMillibels > 0) {
                enhancer = runCatching {
                    LoudnessEnhancer(track.audioSessionId).apply {
                        setTargetGain(settings.volume.boostMillibels)
                        enabled = true
                    }
                }.getOrNull()
            }
            current = track
            track.play()
            val endMs = System.currentTimeMillis() + pcm.samples.size * 1000L / pcm.sampleRate + TAIL_MS
            while (current === track && track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                track.playbackHeadPosition < pcm.samples.size && System.currentTimeMillis() < endMs
            ) {
                Thread.sleep(POLL_MS)
            }
        } finally {
            current = null
            enhancer?.release()
            track.release()
            if (bluetoothCall != null) audioManager.clearCommunicationDevice()
            audioManager.abandonAudioFocusRequest(focus)
        }
    }

    /** Cuts off whatever is playing. */
    fun stop() {
        current?.let { runCatching { it.pause(); it.flush() } }
        current = null
    }

    private companion object {
        const val POLL_MS = 20L
        const val TAIL_MS = 400L
    }
}
