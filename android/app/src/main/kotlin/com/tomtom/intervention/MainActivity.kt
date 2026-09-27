package com.tomtom.intervention

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.tomtom.intervention/notify"

        // L'Activity peut rester chargée sans être affichée.
        // Le service de notification peut alors communiquer directement
        // avec Flutter sans remettre l'interface au premier plan.
        private var instance: MainActivity? = null

        fun dispatchNotification(
            message: String,
            packageName: String
        ): Boolean {
            val activity = instance ?: return false
            return activity.sendNotificationToFlutter(message, packageName)
        }
    }

    private var flutterChannel: MethodChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val isNotificationLaunch =
            intent?.hasExtra("notification_msg") == true

        super.onCreate(savedInstanceState)

        instance = this

        // Une notification reçue en arrière-plan ne doit jamais afficher
        // l'interface Intervention.
        if (isNotificationLaunch) {
            setVisible(false)
        } else {
            setVisible(true)

            // Le service est déjà lancé par le système / BootReceiver.
            // On ne le relance ici que lorsque l'utilisateur ouvre réellement
            // l'application.
            ContextCompat.startForegroundService(
                this,
                Intent(this, InterventionService::class.java)
            )

            requestIgnoreBatteryOptimizations()
        }
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        flutterChannel = null
        super.onDestroy()
    }

    // Sans cette exemption, certains appareils peuvent tuer le service
    // après une période d'inactivité.
    // IMPORTANT : cette demande ne doit être faite que lorsque l'utilisateur
    // ouvre volontairement l'application, jamais lors d'une alerte reçue.
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        flutterChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        )

        flutterChannel?.setMethodCallHandler { call, result ->
            if (call.method == "forceMaxVolume") {
                try {
                    val audioManager =
                        getSystemService(Context.AUDIO_SERVICE) as AudioManager

                    // Volume multimédia au maximum.
                    val maxVol =
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

                    audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        maxVol,
                        0
                    )

                    // Focus audio comme une instruction de navigation.
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val audioAttributes = AudioAttributes.Builder()
                            .setUsage(
                                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                            )
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SPEECH
                            )
                            .build()

                        val focusRequest = AudioFocusRequest.Builder(
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                        )
                            .setAudioAttributes(audioAttributes)
                            .setAcceptsDelayedFocusGain(false)
                            .setWillPauseWhenDucked(false)
                            .build()

                        audioManager.requestAudioFocus(focusRequest)
                    }

                    result.success(true)
                } catch (e: Exception) {
                    result.error(
                        "VOL_ERR",
                        "Impossible de monter le volume",
                        e.message
                    )
                }
            } else {
                result.notImplemented()
            }
        }

        // Si l'Activity a été créée uniquement pour une notification,
        // on transmet le message à Flutter sans afficher l'interface.
        dispatchCurrentNotification()
    }

    private fun dispatchCurrentNotification() {
        val currentIntent = intent ?: return
        val message = currentIntent.getStringExtra("notification_msg")
            ?: return
        val packageName =
            currentIntent.getStringExtra("notification_package") ?: ""

        sendNotificationToFlutter(message, packageName)
    }

    private fun sendNotificationToFlutter(
        message: String,
        packageName: String
    ): Boolean {
        val channel = flutterChannel ?: return false

        channel.invokeMethod(
            "onNotificationReceived",
            mapOf(
                "message" to message,
                "packageName" to packageName
            )
        )
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val msg = intent.getStringExtra("notification_msg")

        if (msg != null) {
            // Une notification reçue alors que l'Activity existe déjà
            // reste totalement invisible.
            setVisible(false)

            val pkg =
                intent.getStringExtra("notification_package") ?: ""

            sendNotificationToFlutter(msg, pkg)
        } else {
            // L'utilisateur vient réellement d'ouvrir Intervention.
            setVisible(true)
        }
    }
}
