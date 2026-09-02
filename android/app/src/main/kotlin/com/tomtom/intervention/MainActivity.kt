package com.tomtom.intervention

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.tomtom.intervention/notify"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Filet de sécurité en plus de onListenerConnected() côté service :
        // force le passage en foreground dès que l'app est ouverte.
        ContextCompat.startForegroundService(this, Intent(this, InterventionService::class.java))
        requestIgnoreBatteryOptimizations()
    }

    // Sans cette exemption, le fabricant (Samsung/Xiaomi/etc.) ou Android Doze
    // peut tuer le service en tâche de fond après un moment d'inactivité,
    // même s'il est en foreground.
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) { }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "forceMaxVolume") {
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    // Définit le volume du flux Multimédia (STREAM_MUSIC) au maximum
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
                    result.success(true)
                } catch (e: Exception) {
                    result.error("VOL_ERR", "Impossible de monter le volume", e.message)
                }
            } else {
                result.notImplemented()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val msg = intent.getStringExtra("notification_msg")
        if (msg != null) {
            MethodChannel(flutterEngine?.dartExecutor?.binaryMessenger!!, CHANNEL)
                .invokeMethod("onNotificationReceived", msg)
        }
    }
}