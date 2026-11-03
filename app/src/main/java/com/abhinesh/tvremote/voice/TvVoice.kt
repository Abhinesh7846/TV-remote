package com.abhinesh.tvremote.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.abhinesh.tvremote.RemoteRepository
import com.abhinesh.tvremote.core.RemoteSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.abs

sealed interface TvVoiceUi {
    data object Idle : TvVoiceUi
    data object Starting : TvVoiceUi
    data class Streaming(val seconds: Float, val level: Float) : TvVoiceUi
    data class Done(val seconds: Float) : TvVoiceUi
    data class Error(val message: String) : TvVoiceUi
}

/**
 * Mode 2, "TV voice search": streams the phone microphone to the TV over the remote protocol
 * (KEYCODE_SEARCH -> remote_voice_begin -> 16-bit 8 kHz mono PCM chunks -> remote_voice_end).
 * The TV's own Assistant does the recognition.
 */
class TvVoice(private val repo: RemoteRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<TvVoiceUi>(TvVoiceUi.Idle)
    val state: StateFlow<TvVoiceUi> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false

    @SuppressLint("MissingPermission") // The UI only calls start() after RECORD_AUDIO is granted.
    fun start() {
        if (job?.isActive == true) return
        stopRequested = false
        cancelled = false
        _state.value = TvVoiceUi.Starting
        job = scope.launch {
            val session = repo.session ?: run { _state.value = TvVoiceUi.Error("Not connected to the TV."); return@launch }
            val id = try {
                session.startVoice()
            } catch (e: Exception) {
                _state.value = TvVoiceUi.Error(e.message ?: "The TV didn't start a voice session.")
                return@launch
            }
            if (stopRequested) { session.endVoice(id); _state.value = TvVoiceUi.Idle; return@launch }
            val rec = openRecorder() ?: run {
                session.endVoice(id)
                _state.value = TvVoiceUi.Error("The microphone couldn't be opened.")
                return@launch
            }
            val (record, inputRate) = rec
            val pending = ByteArrayOutputStream()
            val frame = ShortArray(inputRate / 20) // 50 ms
            var sentBytes = 0
            try {
                record.startRecording()
                val started = System.currentTimeMillis()
                while (isActive && !stopRequested && System.currentTimeMillis() - started < MAX_MS) {
                    val n = record.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    val pcm8k = toPcm8k(frame, n, inputRate)
                    pending.write(pcm8k)
                    if (pending.size() >= RemoteSession.VOICE_CHUNK_MIN) {
                        if (!session.sendVoiceAudio(id, pending.toByteArray())) break
                        sentBytes += pending.size()
                        pending.reset()
                    }
                    _state.value = TvVoiceUi.Streaming((System.currentTimeMillis() - started) / 1000f, level(frame, n))
                }
            } finally {
                runCatching { record.stop() }
                record.release() // microphone off immediately
            }
            if (!cancelled && pending.size() > 0) { session.sendVoiceAudio(id, pending.toByteArray()); sentBytes += pending.size() }
            session.endVoice(id)
            _state.value = if (cancelled) TvVoiceUi.Idle else TvVoiceUi.Done(sentBytes / 16000f)
        }
    }

    fun stop() { stopRequested = true }

    fun cancel() { cancelled = true; stopRequested = true }

    fun clear() { if (job?.isActive != true) _state.value = TvVoiceUi.Idle }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): Pair<AudioRecord, Int>? {
        for (rate in intArrayOf(8000, 16000, 48000)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val r = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 2))
            }.getOrNull() ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) return r to rate
            r.release()
        }
        return null
    }

    /** Averages blocks of samples down to 8 kHz and returns little-endian 16-bit PCM. */
    private fun toPcm8k(src: ShortArray, n: Int, rate: Int): ByteArray {
        val factor = rate / 8000
        val outCount = n / factor
        val out = ByteArray(outCount * 2)
        for (i in 0 until outCount) {
            var sum = 0
            for (j in 0 until factor) sum += src[i * factor + j]
            val v = sum / factor
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun level(frame: ShortArray, n: Int): Float {
        var peak = 0
        for (i in 0 until n) peak = maxOf(peak, abs(frame[i].toInt()))
        return (peak / 12000f).coerceIn(0f, 1f)
    }

    private companion object {
        const val MAX_MS = 15_000L
    }
}
