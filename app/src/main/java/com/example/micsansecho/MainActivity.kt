package com.example.micsansecho

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity(), AudioEngine.Listener {

    private lateinit var engine: AudioEngine
    private lateinit var toggle: Button
    private lateinit var meterIn: ProgressBar
    private lateinit var meterOut: ProgressBar
    private lateinit var feedback: TextView
    private lateinit var report: TextView
    private lateinit var hint: TextView
    private lateinit var swAec: Switch
    private lateinit var swNs: Switch
    private lateinit var swAgc: Switch
    private lateinit var swGate: Switch
    private lateinit var swFeedback: Switch
    private lateinit var swSpeaker: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        engine = AudioEngine(applicationContext)

        toggle = view(R.id.toggle)
        meterIn = view(R.id.meterIn)
        meterOut = view(R.id.meterOut)
        feedback = view(R.id.feedback)
        report = view(R.id.report)
        hint = view(R.id.hint)
        swAec = view(R.id.swAec)
        swNs = view(R.id.swNs)
        swAgc = view(R.id.swAgc)
        swGate = view(R.id.swGate)
        swFeedback = view(R.id.swFeedback)
        swSpeaker = view(R.id.swSpeaker)

        val gainLabel = view<TextView>(R.id.gainLabel)
        val gain = view<SeekBar>(R.id.gain)
        val gateLabel = view<TextView>(R.id.gateLabel)
        val gate = view<SeekBar>(R.id.gate)

        // Gain : -20 dB .. +20 dB
        fun applyGain(p: Int) {
            val db = p - 20
            engine.gain = 10f.pow(db / 20f)
            gainLabel.text = "Volume : ${if (db >= 0) "+" else ""}$db dB"
        }
        // Seuil porte : -70 dB .. -30 dB
        fun applyGate(p: Int) {
            val db = -70 + p
            engine.gateThresholdDb = db.toFloat()
            gateLabel.text = "Seuil de la porte de bruit : $db dB"
        }
        applyGain(gain.progress)
        applyGate(gate.progress)
        gain.setOnSeekBarChangeListener(SimpleSeek { applyGain(it) })
        gate.setOnSeekBarChangeListener(SimpleSeek { applyGate(it) })

        toggle.setOnClickListener {
            if (engine.isRunning) stopEngine() else ensurePermissionAndStart()
        }
    }

    override fun onResume() {
        super.onResume()
        updateHint()
    }

    override fun onStop() {
        super.onStop()
        // On coupe quand l'app passe en arrière-plan pour ne pas laisser le micro ouvert.
        if (engine.isRunning) stopEngine()
    }

    private fun updateHint() {
        hint.text = if (headphonesConnected())
            "🎧 Casque détecté : aucun risque d'écho, le son va directement dans vos oreilles."
        else
            "Astuce : avec un casque ou des écouteurs, l'écho disparaît totalement. " +
                "Sur haut-parleur, l'annulation d'écho et l'anti-larsen font de leur mieux : " +
                "éloignez le téléphone de la source et baissez le volume si ça siffle."
    }

    private fun headphonesConnected(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun ensurePermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startEngine()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startEngine()
            else Toast.makeText(this, "L'accès au micro est nécessaire.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startEngine() {
        val settings = AudioEngine.Settings(
            echoCanceler = swAec.isChecked,
            noiseSuppressor = swNs.isChecked,
            autoGain = swAgc.isChecked,
            speaker = swSpeaker.isChecked && !headphonesConnected(),
            noiseGate = swGate.isChecked,
            antiFeedback = swFeedback.isChecked,
        )
        engine.start(settings, this)
        if (engine.isRunning) {
            toggle.text = "Arrêter"
            setOptionsEnabled(false)
            report.text = "Effets du téléphone :\n" + engine.effectsReport
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun stopEngine() {
        engine.stop()
        toggle.text = "Démarrer"
        setOptionsEnabled(true)
        meterIn.progress = 0
        meterOut.progress = 0
        feedback.text = ""
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setOptionsEnabled(enabled: Boolean) {
        listOf(swAec, swNs, swAgc, swGate, swFeedback, swSpeaker).forEach { it.isEnabled = enabled }
    }

    override fun onLevels(input: Float, output: Float, feedbackReduction: Float) {
        runOnUiThread {
            meterIn.progress = (input * 1000).roundToInt()
            meterOut.progress = (output * 1000).roundToInt()
            feedback.text = if (feedbackReduction > 0.05f)
                "Anti-larsen actif : volume réduit de ${(feedbackReduction * 100).roundToInt()} %"
            else ""
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            stopEngine()
        }
    }

    private fun <T : View> view(id: Int): T = findViewById<T>(id)!!

    private class SimpleSeek(val onChange: (Int) -> Unit) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    companion object {
        private const val REQ_MIC = 1
    }
}
