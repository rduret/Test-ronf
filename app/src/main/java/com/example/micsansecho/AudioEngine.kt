package com.example.micsansecho

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Boucle micro -> haut-parleur en temps réel, avec suppression d'écho.
 *
 * Couches anti-écho, de la plus efficace à la plus simple :
 *  1. Mode "communication" d'Android (MODE_IN_COMMUNICATION + source VOICE_COMMUNICATION) :
 *     active le traitement d'écho matériel/logiciel du téléphone, le même que pour les appels.
 *  2. Effets audio Android : AcousticEchoCanceler, NoiseSuppressor, AutomaticGainControl.
 *  3. Traitement maison dans la boucle :
 *     - porte de bruit (coupe les résidus d'écho et le souffle quand personne ne parle),
 *     - anti-larsen (baisse automatiquement le gain si un sifflement soutenu apparaît),
 *     - limiteur (évite la saturation).
 *  4. Latence minimale (tampons de 10 ms) pour que le son ne soit pas perçu comme "décalé".
 */
class AudioEngine(private val context: Context) {

    data class Settings(
        val echoCanceler: Boolean = true,
        val noiseSuppressor: Boolean = true,
        val autoGain: Boolean = false,
        val speaker: Boolean = true,
        val noiseGate: Boolean = true,
        val antiFeedback: Boolean = true,
    )

    interface Listener {
        /** Niveaux en 0..1 (entrée micro, sortie), appelé depuis le thread audio. */
        fun onLevels(input: Float, output: Float, feedbackReduction: Float)
        fun onError(message: String)
    }

    @Volatile var gain: Float = 1.0f          // gain manuel (linéaire)
    @Volatile var gateThresholdDb: Float = -50f

    @Volatile private var running = false
    private var thread: Thread? = null

    var effectsReport: String = ""
        private set

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode = AudioManager.MODE_NORMAL

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(settings: Settings, listener: Listener) {
        if (running) return

        val sampleRate = pickSampleRate()
        val frame = sampleRate / 100 // 10 ms
        val channelIn = AudioFormat.CHANNEL_IN_MONO
        val channelOut = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minRec = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
        val minPlay = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
        if (minRec <= 0 || minPlay <= 0) {
            listener.onError("Format audio non supporté par cet appareil.")
            return
        }

        // 1. Mode communication : c'est lui qui active l'AEC du téléphone sur la plupart des appareils.
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routeOutput(settings.speaker)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate, channelIn, encoding,
                max(minRec, frame * 2 * 4)
            )
        } catch (e: Exception) {
            restoreAudio()
            listener.onError("Impossible d'ouvrir le micro : ${e.message}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            restoreAudio()
            listener.onError("Micro indisponible (déjà utilisé par une autre app ?).")
            return
        }

        // 2. Effets Android attachés à la session du micro.
        val session = record.audioSessionId
        val report = StringBuilder()
        val aec = if (settings.echoCanceler) enableEffect("Annulation d'écho", report) {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session) else null
        } else null
        val ns = if (settings.noiseSuppressor) enableEffect("Réduction de bruit", report) {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session) else null
        } else null
        val agc = if (settings.autoGain) enableEffect("Gain auto", report) {
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session) else null
        } else null
        effectsReport = report.toString().trim()

        val trackBuilder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelOut)
                    .build()
            )
            .setBufferSizeInBytes(max(minPlay, frame * 2 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(session)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val track = try {
            trackBuilder.build()
        } catch (e: Exception) {
            aec?.release(); ns?.release(); agc?.release()
            record.release()
            restoreAudio()
            listener.onError("Impossible d'ouvrir la sortie audio : ${e.message}")
            return
        }

        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                loop(record, track, frame, sampleRate, settings, listener)
            } catch (e: Exception) {
                listener.onError("Erreur audio : ${e.message}")
            } finally {
                try { record.stop() } catch (_: Exception) {}
                try { track.stop() } catch (_: Exception) {}
                aec?.release(); ns?.release(); agc?.release()
                record.release()
                track.release()
                restoreAudio()
                running = false
            }
        }, "audio-loop").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop(
        record: AudioRecord,
        track: AudioTrack,
        frame: Int,
        sampleRate: Int,
        s: Settings,
        listener: Listener,
    ) {
        val buf = ShortArray(frame)
        val framesPerSecond = sampleRate.toFloat() / frame

        // État de la porte de bruit
        var gateGain = 0f
        val gateAttack = 0.5f     // ouverture rapide
        val gateRelease = 0.05f   // fermeture douce
        var holdFrames = 0
        val holdMax = (0.15f * framesPerSecond).toInt() // 150 ms

        // État de l'anti-larsen
        var feedbackGain = 1f
        var loudFrames = 0
        val loudLimit = (0.25f * framesPerSecond).toInt() // son fort continu > 250 ms = larsen probable
        val loudThreshold = 0.35f // RMS de sortie (≈ -9 dBFS)
        val recoverPerFrame = 10f.pow(1.5f / 20f / framesPerSecond) // remonte de ~1,5 dB/s

        var meterIn = 0f
        var meterOut = 0f
        var meterCounter = 0

        record.startRecording()
        track.play()

        while (running) {
            val n = record.read(buf, 0, frame)
            if (n <= 0) {
                if (n < 0) throw IllegalStateException("lecture micro ($n)")
                continue
            }

            // RMS d'entrée
            var sum = 0.0
            for (i in 0 until n) { val v = buf[i] / 32768f; sum += v * v }
            val rmsIn = sqrt(sum / n).toFloat()

            // Porte de bruit : coupe les résidus d'écho / souffle sous le seuil.
            var target = 1f
            if (s.noiseGate) {
                val threshold = 10f.pow(gateThresholdDb / 20f)
                if (rmsIn >= threshold) {
                    holdFrames = holdMax
                } else if (holdFrames > 0) {
                    holdFrames--
                }
                target = if (holdFrames > 0) 1f else 0f
            }

            val g0 = gain * feedbackGain
            var sumOut = 0.0
            for (i in 0 until n) {
                gateGain += (target - gateGain) * (if (target > gateGain) gateAttack else gateRelease) / 16f
                var v = buf[i] / 32768f * g0 * gateGain
                // Limiteur doux (tanh approché) pour éviter la saturation.
                v = softClip(v)
                sumOut += v * v
                buf[i] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            val rmsOut = sqrt(sumOut / n).toFloat()

            // Anti-larsen : un larsen = son fort, continu, qui ne retombe pas.
            if (s.antiFeedback) {
                if (rmsOut > loudThreshold) loudFrames++ else loudFrames = max(0, loudFrames - 2)
                if (loudFrames > loudLimit) {
                    feedbackGain = max(0.05f, feedbackGain * 0.5f) // -6 dB d'un coup
                    loudFrames = 0
                } else {
                    feedbackGain = min(1f, feedbackGain * recoverPerFrame)
                }
            } else {
                feedbackGain = 1f
            }

            track.write(buf, 0, n)

            meterIn = max(meterIn * 0.8f, rmsIn)
            meterOut = max(meterOut * 0.8f, rmsOut)
            if (++meterCounter >= 5) { // ~20 rafraîchissements/s
                meterCounter = 0
                listener.onLevels(toMeter(meterIn), toMeter(meterOut), 1f - feedbackGain)
            }
        }
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.7f) return x
        // Compression progressive au-dessus de 0.7, plafonnée à 1.
        val y = 0.7f + (1f - 0.7f) * (1f - 1f / (1f + (a - 0.7f) * 3f))
        return if (x < 0) -y else y
    }

    /** Convertit un RMS linéaire en 0..1 sur une échelle de -60..0 dB. */
    private fun toMeter(rms: Float): Float {
        if (rms <= 1e-6f) return 0f
        val db = 20f * kotlin.math.log10(rms)
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    private inline fun <T : android.media.audiofx.AudioEffect> enableEffect(
        name: String,
        report: StringBuilder,
        create: () -> T?,
    ): T? {
        val fx = try { create() } catch (_: Exception) { null }
        if (fx == null) {
            report.append("• $name : non disponible sur ce téléphone\n")
            return null
        }
        return try {
            fx.enabled = true
            report.append("• $name : activé\n")
            fx
        } catch (_: Exception) {
            fx.release()
            report.append("• $name : échec d'activation\n")
            null
        }
    }

    private fun pickSampleRate(): Int {
        for (rate in intArrayOf(48000, 16000, 44100)) {
            val r = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (r > 0 && t > 0) return rate
        }
        return 16000
    }

    @Suppress("DEPRECATION")
    private fun routeOutput(speaker: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (speaker) {
                val dev = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (dev != null) audioManager.setCommunicationDevice(dev)
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            audioManager.isSpeakerphoneOn = speaker
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.isSpeakerphoneOn = false
        }
        audioManager.mode = previousMode
    }
}
